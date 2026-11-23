"""Local store of the OPEN meal thread: every turn since the last closed meal, with the user's images and the draft.

The thread is the model's whole context for the next message. Code owns its lifecycle: it closes when the meal is
handed to the executor (prepared), logged, uncertain, cancelled or idle for 3 hours. Nothing here is logged or sent
anywhere; images live only under the brain working directory and are deleted when the thread closes or after 24 h.
"""
from __future__ import annotations

from contextlib import contextmanager
import base64
import json
from pathlib import Path
import sqlite3
import threading
import time
import uuid

import telemetry

IDLE_SECONDS = 3 * 3600
IMAGE_SECONDS = 24 * 3600
MAX_IMAGES = 10
EXTENSION = {"image/jpeg": "jpg", "image/png": "png"}


class MealThreads:
    def __init__(self, path, image_dir):
        self.path, self.image_dir = Path(path), Path(image_dir)
        self.lock = threading.RLock()
        self.image_dir.mkdir(parents=True, exist_ok=True)
        with self.connection() as db:
            db.execute("CREATE TABLE IF NOT EXISTS threads (id INTEGER PRIMARY KEY AUTOINCREMENT, opened REAL NOT NULL, "
                       "closed REAL, state TEXT NOT NULL, draft TEXT, job TEXT, meal TEXT)")
            db.execute("CREATE TABLE IF NOT EXISTS turns (id INTEGER PRIMARY KEY AUTOINCREMENT, thread INTEGER NOT NULL, "
                       "role TEXT NOT NULL, ts REAL NOT NULL, text TEXT NOT NULL, voice TEXT NOT NULL, images TEXT NOT NULL)")

    @contextmanager
    def connection(self):
        db = sqlite3.connect(self.path, timeout=10)
        try:
            with db:
                yield db
        finally:
            db.close()

    # lifecycle
    def open_thread(self, now=None):
        """Id of the open thread, creating it when none is open. A thread idle for 3 h is closed first."""
        now = time.time() if now is None else now
        with self.lock, self.connection() as db:
            row = db.execute("SELECT id FROM threads WHERE state='open' ORDER BY id DESC LIMIT 1").fetchone()
            if row:
                last = db.execute("SELECT max(ts) FROM turns WHERE thread=?", (row[0],)).fetchone()[0]
                opened = db.execute("SELECT opened FROM threads WHERE id=?", (row[0],)).fetchone()[0]
                if now - (last or opened) <= IDLE_SECONDS:
                    return row[0]
                self._close(db, row[0], "expired", now)
            return db.execute("INSERT INTO threads (opened, state) VALUES (?, 'open')", (now,)).lastrowid

    def _close(self, db, thread, state, now, job=None, meal=None):
        for (raw,) in db.execute("SELECT images FROM turns WHERE thread=?", (thread,)).fetchall():
            for name in json.loads(raw):
                (self.image_dir / name).unlink(missing_ok=True)
        db.execute("UPDATE turns SET images='[]' WHERE thread=?", (thread,))
        user_turns = db.execute("SELECT count(*) FROM turns WHERE thread=? AND role='user'", (thread,)).fetchone()[0]
        telemetry.emit("thread_close", thread=thread, flags={"reason": state}, n={"user_turns": user_turns})
        db.execute("UPDATE threads SET state=?, closed=?, draft=NULL, job=?, meal=? WHERE id=?",
                   (state, now, job, json.dumps(meal, ensure_ascii=False) if meal else None, thread))

    def close(self, thread, state, now=None, job=None, meal=None):
        with self.lock, self.connection() as db:
            self._close(db, thread, state, time.time() if now is None else now, job, meal)

    def cancel(self, now=None):
        """Close the open thread (draft and images dropped). True when a draft was waiting."""
        with self.lock, self.connection() as db:
            row = db.execute("SELECT id, draft FROM threads WHERE state='open' ORDER BY id DESC LIMIT 1").fetchone()
            if not row:
                return False
            self._close(db, row[0], "cancelled", time.time() if now is None else now)
            return row[1] is not None

    # content
    def add_turn(self, thread, role, text="", voice="", images=(), now=None):
        """images: [{'media_type', 'data' (base64)}] of the user; saved under image_dir."""
        names = []
        for value in images:
            name = f"t{thread}-{uuid.uuid4().hex}.{EXTENSION.get(value.get('media_type'), 'jpg')}"
            (self.image_dir / name).write_bytes(base64.b64decode(value["data"]))
            names.append(name)
        with self.lock, self.connection() as db:
            db.execute("INSERT INTO turns (thread, role, ts, text, voice, images) VALUES (?,?,?,?,?,?)",
                       (thread, role, time.time() if now is None else now, text, voice, json.dumps(names)))

    def turns(self, thread):
        """Ordered turns: {role, ts, text, voice, images: [{'media_type', 'data'}]}."""
        with self.connection() as db:
            rows = db.execute("SELECT role, ts, text, voice, images FROM turns WHERE thread=? ORDER BY id", (thread,)).fetchall()
        out = []
        for role, ts, text, voice, raw in rows:
            images = []
            for name in json.loads(raw):
                path = self.image_dir / name
                if path.is_file():
                    images.append({"type": "base64", "media_type": "image/png" if name.endswith(".png") else "image/jpeg",
                                   "data": base64.b64encode(path.read_bytes()).decode()})
            out.append({"role": role, "ts": ts, "text": text, "voice": voice, "images": images})
        return out

    def draft(self, thread):
        with self.connection() as db:
            row = db.execute("SELECT draft FROM threads WHERE id=?", (thread,)).fetchone()
        return json.loads(row[0]) if row and row[0] else None

    def set_draft(self, thread, plan):
        with self.lock, self.connection() as db:
            db.execute("UPDATE threads SET draft=? WHERE id=?", (json.dumps(plan, ensure_ascii=False), thread))

    # closed meals
    def closed_meals(self, now=None, hours=6, job_state=None):
        """Meals handed to the executor in the last `hours`, newest first, with their state.

        job_state(job_key) -> 'complete'|'uncertain'|'failed'|other: resolves a prepared meal lazily. A failed job saved
        nothing, so it is not listed. state is 'written' | 'uncertain' | 'pending'."""
        now = time.time() if now is None else now
        with self.lock, self.connection() as db:
            rows = db.execute("SELECT id, closed, state, job, meal FROM threads WHERE state IN ('prepared','logged','uncertain') "
                              "AND closed>? ORDER BY closed DESC", (now - hours * 3600,)).fetchall()
            out = []
            for thread, closed, state, job, meal in rows:
                if state == "prepared" and job and job_state:
                    resolved = job_state(job)
                    if resolved == "complete":
                        state = "logged"
                    elif resolved == "uncertain":
                        state = "uncertain"
                    elif resolved == "failed":
                        db.execute("UPDATE threads SET state='failed' WHERE id=?", (thread,))
                        continue
                    if state != "prepared":
                        db.execute("UPDATE threads SET state=? WHERE id=?", (state, thread))
                data = json.loads(meal) if meal else {}
                out.append({"closed": closed, "meal": data.get("meal", "other"), "items": data.get("items", []),
                            "state": {"logged": "written", "uncertain": "uncertain"}.get(state, "pending")})
        return out

    def cleanup(self, now=None):
        """Drop images older than 24 h (turn or stray file); close an idle thread."""
        now = time.time() if now is None else now
        with self.lock, self.connection() as db:
            for turn, raw in db.execute("SELECT id, images FROM turns WHERE images!='[]' AND ts<?", (now - IMAGE_SECONDS,)).fetchall():
                for name in json.loads(raw):
                    (self.image_dir / name).unlink(missing_ok=True)
                db.execute("UPDATE turns SET images='[]' WHERE id=?", (turn,))
            row = db.execute("SELECT id FROM threads WHERE state='open' ORDER BY id DESC LIMIT 1").fetchone()
            if row:
                last = db.execute("SELECT max(ts) FROM turns WHERE thread=?", (row[0],)).fetchone()[0]
                opened = db.execute("SELECT opened FROM threads WHERE id=?", (row[0],)).fetchone()[0]
                if now - (last or opened) > IDLE_SECONDS:
                    self._close(db, row[0], "expired", now)
        for path in self.image_dir.glob("t*-*"):
            try:
                if now - path.stat().st_mtime > IMAGE_SECONDS:
                    path.unlink(missing_ok=True)
            except OSError:
                pass
