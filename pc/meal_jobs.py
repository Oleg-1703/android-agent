"""Durable job receipts and fixed emulator execution, with no uncertain write replay."""
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from decimal import Decimal, InvalidOperation
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import subprocess
import threading
import time
import uuid

from barcodes import lookup


def validate_plan(plan):
    if plan.get("question"):
        raise ValueError(str(plan["question"])[:500])
    if plan.get("meal") not in {"breakfast", "lunch", "dinner", "other"}:
        raise ValueError("Не определён приём пищи. Ничего не записано.")
    items = plan.get("items")
    if not isinstance(items, list) or not 1 <= len(items) <= 10:
        raise ValueError("Еду не удалось определить. Ничего не записано.")
    for item in items:
        if item.get("barcode"):
            item.update(lookup(item["barcode"]))
        if not isinstance(item.get("query"), str) or not 2 <= len(item["query"].strip()) <= 150:
            raise ValueError("Не определено название еды. Ничего не записано.")
        try:
            grams = Decimal(item["grams"])
            if item.get("source") == "existing":
                if not grams.is_finite() or not 0 < grams <= 5000 or item.get("barcode") or item.get("nutrition"):
                    raise ValueError()
                continue
            n = item["nutrition"]
            values = {key: Decimal(n[key]) for key in ("basis_grams", "calories", "protein", "fat", "carbs")}
            if not grams.is_finite() or not 0 < grams <= 5000 or not 0 < values["basis_grams"] <= 5000:
                raise ValueError()
            if any(not x.is_finite() or not 0 <= x <= 10000 for x in values.values()):
                raise ValueError()
        except (KeyError, TypeError, InvalidOperation, ValueError):
            raise ValueError("Нет корректных КБЖУ и веса своей еды. Ничего не записано.") from None
    return plan


class Emulator:
    def __init__(self, tools, serial="emulator-5556"):
        self.adb = Path(tools) / "sdk/platform-tools/adb.exe"
        self.serial = serial

    def run(self, *args, data=None, timeout=30):
        result = subprocess.run([str(self.adb), "-s", self.serial, *args], input=data,
            capture_output=True, timeout=timeout, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        if result.returncode:
            raise RuntimeError("Не удалось связаться с эмулятором FatSecret.")
        return result.stdout

    def log(self, plan):
        if self.run("shell", "getprop", "ro.kernel.qemu").strip() != b"1":
            raise ValueError("Исполнитель настроен только на эмулятор.")
        execution_id = plan.setdefault("_execution_id", uuid.uuid4().hex)
        self.run("shell", "run-as", "com.oleg.androidagent", "sh", "-c", "'mkdir -p files && cat > files/custom-request.json'",
                 data=json.dumps(plan, ensure_ascii=False).encode())
        self.instrument("custom-meal")
        output = self.run("exec-out", "run-as", "com.oleg.androidagent", "cat", "files/custom-result.json")
        try:
            result = json.loads(output)
        except ValueError:
            raise RuntimeError("Исполнитель не вернул подтверждение. Проверь дневник; повторять запрос автоматически нельзя.") from None
        if result.get("execution_id") != execution_id:
            raise RuntimeError("Нельзя подтвердить результат текущего запроса. Проверь дневник; повтор запрещён.")
        if result.get("status") == "uncertain" and result.get("can_reconcile"):
            self.instrument("custom-reconcile")
            checked = json.loads(self.run("exec-out", "run-as", "com.oleg.androidagent", "cat", "files/custom-result.json"))
            if checked.get("execution_id") == execution_id and checked.get("status") == "complete":
                return checked
        return result

    def instrument(self, phase):
        if phase not in {"custom-meal", "custom-reconcile", "custom-dry-run", "owner-search", "existing-preview"}:
            raise ValueError("Unsupported fixed executor phase")
        current = self.run("shell", "settings", "get", "secure", "enabled_accessibility_services").decode().strip()
        others = [s for s in current.split(":") if s and s != "null" and not s.startswith("com.oleg.androidagent/")]
        component = "com.oleg.androidagent/.FatAccessibilityService"
        process = subprocess.Popen([str(self.adb), "-s", self.serial, "shell", "am", "instrument", "-r", "-w", "-e", "phase", phase,
            "com.oleg.androidagent.test/com.oleg.androidagent.DeviceSmoke"], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        try:
            time.sleep(2)
            self.run("shell", "settings", "put", "secure", "enabled_accessibility_services", ":".join(others) or "null")
            time.sleep(.4)
            self.run("shell", "settings", "put", "secure", "enabled_accessibility_services", ":".join(others + [component]))
            self.run("shell", "settings", "put", "secure", "accessibility_enabled", "1")
            stdout, _ = process.communicate(timeout=420)
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=10)
        return stdout


class MealJobs:
    def __init__(self, path, processor):
        self.path = Path(path)
        self.processor = processor
        self.lock = threading.Lock()
        self.pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="custom-meal")
        with self.connection() as db:
            db.execute("CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, state TEXT NOT NULL, result TEXT NOT NULL)")
            db.execute("UPDATE jobs SET state='uncertain', result=? WHERE state IN ('queued','running')",
                       (json.dumps({"message": "ПК перезапустился во время обработки. Проверь дневник; автоматического повтора не будет."}),))

    @contextmanager
    def connection(self):
        db = sqlite3.connect(self.path, timeout=10)
        try:
            with db:
                yield db
        finally:
            db.close()

    def get(self, key):
        with self.connection() as db:
            row = db.execute("SELECT state,result FROM jobs WHERE id=?", (key,)).fetchone()
        return None if row is None else {"status": row[0], **json.loads(row[1])}

    def submit(self, key, payload):
        if not isinstance(key, str) or not re.fullmatch(r"[a-f0-9]{32,64}", key):
            raise ValueError("invalid_request_id")
        fingerprint = hashlib.sha256(json.dumps(payload, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
        with self.lock, self.connection() as db:
            old = db.execute("SELECT fingerprint FROM jobs WHERE id=?", (key,)).fetchone()
            if old:
                if old[0] != fingerprint:
                    raise ValueError("request_id_conflict")
                return self.get(key)
            if db.execute("SELECT count(*) FROM jobs WHERE state IN ('queued','running')").fetchone()[0] >= 8:
                raise ValueError("queue_full")
            db.execute("INSERT INTO jobs VALUES (?,?,?,?)", (key, fingerprint, "queued", "{}"))
        self.pool.submit(self.work, key, payload)
        return {"status": "queued"}

    def work(self, key, payload):
        with self.connection() as db:
            db.execute("UPDATE jobs SET state='running' WHERE id=?", (key,))
        try:
            result = self.processor({**payload, "_execution_id": key})
            state = result.get("status", "complete")
            if state not in {"complete", "failed", "uncertain"}:
                raise RuntimeError("Неизвестное состояние записи. Проверь дневник.")
        except ValueError as error:
            state, result = "failed", {"message": str(error)[:700]}
        except Exception as error:
            # The executor may already have clicked Save. Never infer that retry is safe.
            state, result = "uncertain", {"message": str(error)[:700] or "Проверь дневник. Автоматического повтора нет."}
        with self.connection() as db:
            db.execute("UPDATE jobs SET state=?,result=? WHERE id=?", (state, json.dumps(result, ensure_ascii=False), key))

    def close(self):
        self.pool.shutdown(wait=True)
