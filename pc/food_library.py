"""Library of reusable foods (store products, batch-cooked dishes) with exact nutrition, in the brain working directory.

The model sees the whole library every turn and decides what to save or reuse; this module only validates, stores and
finds. An entry becomes its own custom diary card (separate from the merged meal card) whenever it is eaten.
"""
from __future__ import annotations

from contextlib import contextmanager
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
import json
from pathlib import Path
import sqlite3
import threading

MAX_ENTRIES = 200
FIELDS = ("basis_grams", "calories", "protein", "fat", "carbs")


def normalize(text):
    """Lowercase, letters and digits only, single spaces: '✅ Записать!' -> 'записать'."""
    return " ".join("".join(c if c.isalnum() else " " for c in str(text).lower()).split())


def _number(value, low, high):
    try:
        number = Decimal(str(value))
    except (InvalidOperation, ValueError):
        raise ValueError("bad number") from None
    if not number.is_finite() or not low <= number <= high:
        raise ValueError("bad number")
    return number


def clean_entry(entry):
    """Validated copy of an entry; ValueError (Russian message) when invalid. Nutrition is per basis_grams."""
    if not isinstance(entry, dict):
        raise ValueError("Некорректная запись библиотеки.")
    name = entry.get("name")
    if not isinstance(name, str) or not 2 <= len(name.strip()) <= 150 or not normalize(name):
        raise ValueError("Название в библиотеке: от 2 до 150 символов.")
    aliases = entry.get("aliases") or []
    if not isinstance(aliases, list) or len(aliases) > 5 or any(not isinstance(a, str) or not 2 <= len(a.strip()) <= 150 for a in aliases):
        raise ValueError("Не больше 5 синонимов по 2-150 символов.")
    nutrition = entry.get("nutrition")
    try:
        values = {key: _number(nutrition[key], Decimal(0), Decimal(10000)) for key in FIELDS}
        if not 0 < values["basis_grams"] <= 5000:
            raise ValueError("bad basis")
    except (KeyError, TypeError, ValueError):
        raise ValueError("Нет корректных КБЖУ для библиотеки.") from None
    out = {"name": name.strip(), "aliases": [a.strip() for a in aliases if normalize(a) != normalize(name)],
           "nutrition": {key: str(nutrition[key]) for key in FIELDS}}
    if entry.get("default_grams") not in (None, ""):
        try:
            _number(entry["default_grams"], Decimal("0.01"), Decimal(5000))
            out["default_grams"] = str(entry["default_grams"])
        except ValueError:
            raise ValueError("Вес по умолчанию: от 0 до 5000 г.") from None
    note = entry.get("note")
    if note:
        if not isinstance(note, str):
            raise ValueError("Заметка должна быть текстом.")
        out["note"] = note.strip()[:300]
    return out


class FoodLibrary:
    def __init__(self, path):
        self.path = Path(path)
        self.lock = threading.RLock()
        with self.connection() as db:
            db.execute("CREATE TABLE IF NOT EXISTS foods (key TEXT PRIMARY KEY, entry TEXT NOT NULL, updated TEXT NOT NULL)")

    @contextmanager
    def connection(self):
        db = sqlite3.connect(self.path, timeout=10)
        try:
            with db:
                yield db
        finally:
            db.close()

    def all(self):
        with self.connection() as db:
            rows = db.execute("SELECT entry, updated FROM foods ORDER BY key").fetchall()
        return [{**json.loads(entry), "updated": updated} for entry, updated in rows]

    def find(self, name):
        """Entry whose normalized name equals the normalized query, else one whose alias does; None when absent."""
        wanted = normalize(name)
        if not wanted:
            return None
        entries = self.all()
        for entry in entries:
            if normalize(entry["name"]) == wanted:
                return entry
        for entry in entries:
            if any(normalize(alias) == wanted for alias in entry.get("aliases", [])):
                return entry
        return None

    def save(self, entry):
        """Upsert by normalized name; returns (stored entry, created)."""
        clean = clean_entry(entry)
        key = normalize(clean["name"])
        with self.lock, self.connection() as db:
            exists = db.execute("SELECT 1 FROM foods WHERE key=?", (key,)).fetchone() is not None
            if not exists and db.execute("SELECT count(*) FROM foods").fetchone()[0] >= MAX_ENTRIES:
                raise ValueError(f"В библиотеке уже {MAX_ENTRIES} позиций. Удали ненужные.")
            updated = datetime.now(timezone.utc).strftime("%Y-%m-%d")
            db.execute("INSERT OR REPLACE INTO foods (key, entry, updated) VALUES (?,?,?)",
                       (key, json.dumps(clean, ensure_ascii=False), updated))
        return {**clean, "updated": updated}, not exists

    def delete(self, name):
        """Delete the entry found by name or alias; True when something was removed."""
        with self.lock:
            entry = self.find(name)
            if not entry:
                return False
            with self.connection() as db:
                db.execute("DELETE FROM foods WHERE key=?", (normalize(entry["name"]),))
            return True


def library_lines(entries):
    """Compact prompt lines: name (aliases) | nutrition per basis | default grams | note."""
    lines = []
    for entry in entries:
        n = entry["nutrition"]
        names = entry["name"] + (" (" + ", ".join(entry["aliases"]) + ")" if entry.get("aliases") else "")
        lines.append(f"- {names} | на {n['basis_grams']} г: {n['calories']} ккал, Б {n['protein']} / Ж {n['fat']} / У {n['carbs']}"
                     f" | вес по умолчанию: {entry.get('default_grams') or '-'} | {entry.get('note') or '-'}")
    return lines
