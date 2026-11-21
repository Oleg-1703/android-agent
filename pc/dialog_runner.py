"""Runs recorded synthetic dialogs (pc/dialogs/*.json) through BrainServer.process_custom_meal.

The model is either scripted (turn["model"], unit test: checks that the CODE does the right thing with model outputs)
or the real subscription CLI (eval_dialogs.py). The web lookup always fails, so estimates stay estimates.
"""
from __future__ import annotations

import base64
import datetime as dt
import json
from pathlib import Path
import struct
import tempfile
import threading
import time
import zlib
from unittest.mock import patch

import brain
from meal_jobs import MealJobs

DIALOGS = Path(__file__).with_name("dialogs")
DATE = "2026-11-20"
START = dt.datetime(2026, 11, 20, 12, 0).timestamp()
FONT = {  # 3x5 digits
    "0": "111101101101111", "1": "010110010010111", "2": "111001111100111", "3": "111001111001111", "4": "101101111001001",
    "5": "111100111001111", "6": "111100111101111", "7": "111001001001001", "8": "111101111101111", "9": "111101111001111",
    " ": "000000000000000"}


def make_png(text="1", scale=14):
    """A small PNG (no Pillow): dark digits on a pale background. Only digits and spaces are drawn."""
    cells = [FONT.get(ch, FONT[" "]) for ch in text]
    width, height = (len(cells) * 4 + 1) * scale, 7 * scale
    rows = bytearray()
    for y in range(height):
        rows.append(0)
        for x in range(width):
            cell, inner = divmod(x // scale - 1, 4)
            on = 0 <= x // scale - 1 and inner < 3 and cell < len(cells) and 1 <= y // scale <= 5 and cells[cell][(y // scale - 1) * 3 + inner] == "1"
            rows += bytes((30, 30, 30) if on else (235, 235, 220))
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(rows))) + chunk(b"IEND", b""))


def load_dialogs():
    return [json.loads(path.read_text(encoding="utf-8")) for path in sorted(DIALOGS.glob("*.json"))]


def run_dialog(dialog, real=False, cli="claude"):
    """Returns a list of (turn number, [problems]) - empty problems = pass."""
    results = []
    with tempfile.TemporaryDirectory() as directory:
        server = brain.BrainServer.__new__(brain.BrainServer)
        server.cwd, server.cli = Path(directory), cli
        server.config = {"phone_catalog": bool(dialog.get("catalog", False))}
        server.busy = threading.Lock()
        server.jobs = MealJobs(Path(directory) / "jobs.sqlite", lambda payload: {"status": "complete"})
        server.threads = brain.MealThreads(Path(directory) / "thread.sqlite", Path(directory) / "thread-images")

        def web(payload, schema, system):
            raise RuntimeError("no web in dialog runs")
        server.web_search = web
        calls, state = [], {"turn": None}
        original = brain.parse_meal

        def model(prompt, date, cli_, cwd, **kwargs):
            calls.append({"prompt": prompt, "images": len(kwargs.get("images") or [])})
            if real:
                return original(prompt, date, cli_, cwd, **kwargs)
            return json.loads(json.dumps(state["turn"]["model"]))
        clock = {"now": START}
        with patch.object(brain, "parse_meal", side_effect=model), patch("time.time", side_effect=lambda: clock["now"]):
            try:
                for number, turn in enumerate(dialog["turns"], 1):
                    clock["now"] = START + turn.get("at", 0) * 60
                    state["turn"], before, problems = turn, len(calls), []
                    if turn.get("cancel"):
                        got = server.threads.cancel()
                        if got != turn.get("expect", {}).get("cancelled", True):
                            problems.append(f"cancel returned {got}")
                        results.append((number, problems))
                        continue
                    images = [{"media_type": "image/png", "data": base64.b64encode(make_png(dialog["images"][name])).decode()}
                              for name in turn.get("images", [])]
                    payload = {"text": turn.get("text") or brain.VOICE_PLACEHOLDER, "date": DATE, "target": "phone",
                               "_execution_id": f"{number:032x}", "images": images, "capabilities": turn.get("capabilities", [])}
                    if turn.get("voice"):
                        payload["voice"] = {"data": "x"}
                    try:
                        with patch.object(brain, "transcribe", return_value=turn.get("voice", "")), \
                             patch.object(brain, "attachment", return_value=b""):
                            result = server.process_custom_meal(payload)
                    except ValueError as error:
                        result = {"status": "failed", "message": str(error)}
                    with server.jobs.connection() as db:
                        job = "complete" if result["status"] == "prepared" else result["status"]
                        db.execute("INSERT OR REPLACE INTO jobs VALUES (?,?,?,?)", (payload["_execution_id"], "f", job, json.dumps(result, ensure_ascii=False)))
                    results.append((number, check(turn.get("expect", {}), result, calls[before:], server)))
            finally:
                server.jobs.close()
    return results


def outcome(result):
    if result["status"] == "prepared":
        return "prepared"
    if result.get("confirm"):
        return "draft"
    return "reply" if result.get("reply") else "error"


def check(expect, result, calls, server):
    problems = []
    got = outcome(result)
    if expect.get("outcome") and got != expect["outcome"]:
        problems.append(f"outcome {got}, expected {expect['outcome']} ({str(result.get('message', ''))[:80]})")
    if "model_called" in expect and bool(calls) != expect["model_called"]:
        problems.append(f"model_called {bool(calls)}")
    if calls and "model_images" in expect and calls[0]["images"] != expect["model_images"]:
        problems.append(f"model got {calls[0]['images']} images, expected {expect['model_images']}")
    for part in expect.get("prompt_contains", []):
        if not calls or part not in calls[0]["prompt"]:
            problems.append(f"prompt lacks {part!r}")
    for part in expect.get("prompt_lacks", []):
        if calls and part in calls[0]["prompt"]:
            problems.append(f"prompt has {part!r}")
    plan = result.get("plan") or (server.threads.draft(server.threads.open_thread()) if got == "draft" else None)
    names = " | ".join(str(item.get("query", "")).lower() for item in (plan or {}).get("items", []))
    for part in expect.get("items_include", []):
        if part.lower() not in names:
            problems.append(f"items lack {part!r}: {names}")
    for part in expect.get("items_exclude", []):
        if part.lower() in names:
            problems.append(f"items have {part!r}")
    if "duplicate" in expect and result.get("message", "").startswith("Похоже на уже записанный") != expect["duplicate"]:
        problems.append("duplicate flag mismatch")
    for part in expect.get("reply_contains", []):
        if part not in result.get("message", ""):
            problems.append(f"reply lacks {part!r}")
    return problems
