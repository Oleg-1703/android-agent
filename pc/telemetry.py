"""Append-only event log of the PC brain and, via /v1/events, of the phone: events.jsonl next to meal-jobs.sqlite.

An event holds only metadata: {ts, src, type, req, thread, ok, ms, err_class, err, n, flags, ids}. Never food text, photos,
transcripts, macros or tokens: the sanitizer keeps a fixed set of fields and drops everything else. A telemetry failure
never raises into the caller (one stderr note per minute at most).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import threading
import time
from pathlib import Path

FILE_NAME = "events.jsonl"
MAX_BYTES = 20 * 1024 * 1024
KEEP_DAYS = 30
FIELDS = ("ts", "src", "type", "req", "thread", "ok", "ms", "err_class", "err", "n", "flags", "ids")
TYPE_RE = re.compile(r"[a-z][a-z0-9_]{1,39}")
CLASS_RE = re.compile(r"[A-Za-z][A-Za-z0-9_.-]{0,59}")
KEY_RE = re.compile(r"[a-z][a-z0-9_]{0,31}")
FLAG_TEXT_RE = re.compile(r"[A-Za-z0-9_.+-]{1,32}")
ID_RE = re.compile(r"[A-Za-z0-9_.:/$-]{1,120}")
REQ_RE = re.compile(r"[a-f0-9]{8,16}")
EID_RE = re.compile(r"[A-Za-z0-9]{6,32}")
SECRET_RE = re.compile(r"\d{6,}:[A-Za-z0-9_-]{20,}|[A-Za-z0-9_+/=-]{24,}|https?://\S+")


def events_path(workspace) -> Path:
    return Path(workspace) / FILE_NAME


def request_hash(value) -> str | None:
    """Short stable hash of a request id; the same function on the phone gives the same value."""
    return hashlib.sha256(str(value).encode()).hexdigest()[:8] if value else None


def clean_text(value, limit: int = 200) -> str:
    """One line, secrets and URLs masked, at most `limit` characters."""
    text = SECRET_RE.sub("<x>", " ".join(str(value).split()))
    return text[:limit]


def sanitize(raw: dict, src: str | None = None, now: float | None = None) -> dict | None:
    """The event reduced to the allowed fields and shapes; None when it has no valid type."""
    if not isinstance(raw, dict) or not isinstance(raw.get("type"), str) or not TYPE_RE.fullmatch(raw["type"]):
        return None
    now = time.time() if now is None else now
    ts = raw.get("ts")
    if not isinstance(ts, (int, float)) or isinstance(ts, bool) or not now - 7 * 86400 <= ts <= now + 86400:
        ts = now
    event: dict = {"ts": round(float(ts), 3), "src": src or ("phone" if raw.get("src") == "phone" else "pc"), "type": raw["type"]}
    req = raw.get("req")
    if isinstance(req, str) and REQ_RE.fullmatch(req):
        event["req"] = req
    thread = raw.get("thread")
    if isinstance(thread, int) and not isinstance(thread, bool) and 0 <= thread < 2**31:
        event["thread"] = thread
    if isinstance(raw.get("ok"), bool):
        event["ok"] = raw["ok"]
    ms = raw.get("ms")
    if isinstance(ms, (int, float)) and not isinstance(ms, bool) and 0 <= ms <= 86_400_000:
        event["ms"] = int(ms)
    if isinstance(raw.get("err_class"), str) and CLASS_RE.fullmatch(raw["err_class"]):
        event["err_class"] = raw["err_class"]
    if raw.get("err"):
        event["err"] = clean_text(raw["err"])
    counts = raw.get("n")
    if isinstance(counts, dict):
        kept = {k: int(v) for k, v in counts.items() if isinstance(k, str) and KEY_RE.fullmatch(k)
                and isinstance(v, (int, float)) and not isinstance(v, bool) and -10**9 <= v <= 10**9}
        if kept:
            event["n"] = dict(list(kept.items())[:12])
    flags = raw.get("flags")
    if isinstance(flags, dict):
        kept = {k: v for k, v in flags.items() if isinstance(k, str) and KEY_RE.fullmatch(k) and
                (isinstance(v, bool) or (isinstance(v, int) and abs(v) < 10**9) or (isinstance(v, str) and FLAG_TEXT_RE.fullmatch(v)))}
        if kept:
            event["flags"] = dict(list(kept.items())[:12])
    ids = raw.get("ids")
    if isinstance(ids, list):
        kept_ids = [v for v in ids if isinstance(v, str) and ID_RE.fullmatch(v)][:60]
        if kept_ids:
            event["ids"] = kept_ids
    return event


class Telemetry:
    def __init__(self, path, max_bytes: int = MAX_BYTES, keep_days: int = KEEP_DAYS, clock=time.time):
        self.path, self.max_bytes, self.keep_days, self.clock = Path(path), max_bytes, keep_days, clock
        self.lock = threading.Lock()
        self.last_note = 0.0
        self.size = None  # tracked after the first write to avoid a stat per event

    def _note(self, error):
        if isinstance(error, FileNotFoundError):
            return  # the workspace is gone (tests, removed folder): nothing to report
        now = time.monotonic()
        if now - self.last_note >= 60:
            self.last_note = now
            try:
                print("telemetry: " + type(error).__name__, file=sys.stderr, flush=True)
            except Exception:  # a closed stderr must not turn the note into a failure
                pass

    def emit(self, type: str, src: str = "pc", req=None, thread=None, ok=None, ms=None, err_class=None, err=None,
             n=None, flags=None, ids=None, raw_req: bool = True) -> None:
        """req is a raw request id (hashed here); raw_req=False passes an already hashed value."""
        try:
            request = (request_hash(req) if raw_req else req)
            self.write(sanitize({"type": type, "req": request, "thread": thread, "ok": ok, "ms": ms,
                                 "err_class": err_class, "err": err, "n": n, "flags": flags, "ids": ids}, src, self.clock()))
        except Exception as error:  # telemetry must never break a request
            self._note(error)

    def write(self, event: dict | None) -> None:
        if event is None:
            return
        line = json.dumps(event, ensure_ascii=False, separators=(",", ":")) + "\n"
        with self.lock:
            with self.path.open("a", encoding="utf-8", newline="\n") as stream:
                stream.write(line)
            self.size = (self.size if self.size is not None else self.path.stat().st_size - len(line.encode())) + len(line.encode())
            if self.size > self.max_bytes:
                self._compact()

    def maintain(self) -> None:
        """At start: prune events older than keep_days (the size cap alone would keep them for months)."""
        try:
            with self.lock:
                if self.path.exists():
                    with self.path.open(encoding="utf-8", errors="replace") as stream:
                        first = stream.readline()
                    if first and json.loads(first).get("ts", 0) < self.clock() - self.keep_days * 86400:
                        self._compact()
        except (OSError, ValueError) as error:
            self._note(error)

    def _compact(self) -> None:
        """Drop events older than keep_days; when still above the cap, the oldest ones until 75% of the cap is left."""
        try:
            lines = self.path.read_text(encoding="utf-8", errors="replace").splitlines(keepends=True)
            horizon = self.clock() - self.keep_days * 86400
            kept = []
            for line in lines:
                try:
                    if json.loads(line).get("ts", 0) >= horizon:
                        kept.append(line)
                except ValueError:
                    continue
            total = sum(len(line.encode()) for line in kept)
            while kept and total > self.max_bytes * 0.75:
                total -= len(kept.pop(0).encode())
            temporary = self.path.with_name(self.path.name + ".tmp")
            temporary.write_text("".join(kept), encoding="utf-8", newline="\n")
            os.replace(temporary, self.path)
            self.size = total
        except OSError as error:  # a reader may hold the file on Windows; try again at the next write
            self._note(error)


_current: Telemetry | None = None
_instances: dict[str, Telemetry] = {}


def configure(workspace, **options) -> Telemetry:
    """The one Telemetry of this workspace (shared by the brain events and /v1/events) becomes the module-level target."""
    global _current
    path = str(events_path(workspace))
    if path not in _instances:
        _instances[path] = Telemetry(path, **options)
        _instances[path].maintain()
    _current = _instances[path]
    return _current


def emit(type: str, **fields) -> None:
    """Module-level shortcut: a no-op until configure() was called."""
    if _current is not None:
        _current.emit(type, **fields)


def exc_class(error: BaseException) -> str:
    return type(error).__name__


class RateLimit:
    """Sliding window for /v1/events: at most `requests` calls and `events` accepted events per window."""
    def __init__(self, requests: int = 30, events: int = 1500, window: float = 3600, clock=time.monotonic):
        self.requests, self.events, self.window, self.clock = requests, events, window, clock
        self.calls: list[tuple[float, int]] = []
        self.lock = threading.Lock()

    def allow(self, count: int) -> bool:
        with self.lock:
            now = self.clock()
            self.calls = [(t, c) for t, c in self.calls if now - t < self.window]
            if len(self.calls) >= self.requests or sum(c for _, c in self.calls) + count > self.events:
                return False
            self.calls.append((now, count))
            return True


class PhoneEvents:
    """Validation and storage of POST /v1/events batches."""
    MAX_BATCH = 100
    MAX_BODY = 120_000

    def __init__(self, telemetry: Telemetry, limit: RateLimit | None = None):
        self.telemetry, self.limit = telemetry, limit or RateLimit()
        self.seen: dict[str, None] = {}

    def accept(self, body) -> dict:
        """Raises ValueError for a malformed batch, PermissionError when rate limited."""
        events = body.get("events") if isinstance(body, dict) else None
        if not isinstance(events, list) or not 1 <= len(events) <= self.MAX_BATCH:
            raise ValueError("invalid_batch")
        if not self.limit.allow(len(events)):
            raise PermissionError("rate_limited")
        stored = dropped = 0
        now = self.telemetry.clock()
        for raw in events:
            eid = raw.get("eid") if isinstance(raw, dict) else None
            if isinstance(eid, str) and EID_RE.fullmatch(eid):
                if eid in self.seen:
                    stored += 1  # a resent event after a lost acknowledgement: counted as accepted, not written twice
                    continue
            else:
                eid = None
            ts = raw.get("ts") if isinstance(raw, dict) else None
            if isinstance(ts, (int, float)) and not isinstance(ts, bool) and ts > 10**11:
                raw = {**raw, "ts": ts / 1000}  # the phone sends epoch milliseconds
            event = sanitize(raw, "phone", now)
            if event is None:
                dropped += 1
                continue
            self.telemetry.write(event)
            if eid:
                self.seen[eid] = None
                if len(self.seen) > 5000:
                    self.seen.pop(next(iter(self.seen)))
            stored += 1
        return {"accepted": stored, "dropped": dropped}


def main(argv=None) -> int:
    """`telemetry.py --workspace DIR emit TYPE [--ok 1] [--err-class X] [--flag k=v]`: used by the PowerShell watchdog."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--workspace", required=True)
    commands = parser.add_subparsers(dest="command", required=True)
    emit_parser = commands.add_parser("emit")
    emit_parser.add_argument("type")
    emit_parser.add_argument("--ok", choices=("0", "1"))
    emit_parser.add_argument("--err-class")
    emit_parser.add_argument("--flag", action="append", default=[])
    args = parser.parse_args(argv)
    flags = {}
    for item in args.flag:
        key, _, value = item.partition("=")
        flags[key] = int(value) if value.lstrip("-").isdigit() else value
    tele = Telemetry(events_path(args.workspace))
    tele.emit(args.type, ok=None if args.ok is None else args.ok == "1", err_class=args.err_class, flags=flags)
    return 0


if __name__ == "__main__":
    sys.exit(main())
