"""Verify held-out ADB checkers without invoking a model or agent loop."""

import argparse
from pathlib import Path
import re
import subprocess
import sys
import time

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
    __package__ = "uilab"

from .run_eval import check_task, load_tasks


SERIAL = "emulator-5580"
WINDOWS_ADB = "C:/Users/Oleg/.local/share/android-agent-tools/sdk/platform-tools/adb.exe"
WSL_ADB = "/mnt/c/Users/Oleg/.local/share/android-agent-tools/sdk/platform-tools/adb.exe"
BLOCKED = ("com.phoneuse", "org.mozilla.firefox", "org.mozilla.fenix")


class VerificationDevice:
    def __init__(self, adb):
        if adb.replace("\\", "/") not in (WINDOWS_ADB, WSL_ADB):
            raise ValueError("Only the specified SDK adb executable is permitted")
        self.adb = adb
        self.checked_at = float('-inf')

    def _shell(self, command):
        result = subprocess.run([self.adb, "-s", SERIAL, "shell", command],
                                capture_output=True, text=True, encoding="utf-8", timeout=30)
        if result.returncode or result.stderr.strip():
            raise RuntimeError("ADB failed: " + result.stderr.strip())
        return result.stdout

    def shell(self, command):
        # This read-only probe is the only command allowed before ownership is known.
        # Seeding runs hundreds of commands: re-check ownership once a minute, not before each one.
        if time.monotonic() - self.checked_at > 60:
            # No activity is resumed for a moment while setup switches or clears apps.
            for attempt in range(10):
                focus = self._shell("dumpsys activity activities")
                resumed = re.findall(r"(?:ResumedActivity|topResumedActivity).*? ([\w.]+)/", focus)
                if resumed or attempt == 9:
                    break
                time.sleep(1)
            if not resumed or any(p == b or p.startswith(b + ".") for p in resumed for b in BLOCKED):
                raise RuntimeError("Emulator busy or foreground unknown; stopped without further commands")
            self.checked_at = time.monotonic()
        output = self._shell(command)
        if re.search(r"(?i)permission denial|securityexception|error:|exception|failed|failure", output):
            raise RuntimeError("ADB setup/action/query failed")
        return output


def verify(device, task):
    def setup():
        for command in task["setup"]:
            device.shell(command)

    setup()
    # An impossible task has the same device state before and after refusal.
    # The existing status predicate distinguishes an unattempted task from give_up.
    if check_task(device, task, "pending") != (False, None):
        raise RuntimeError("checker must be false immediately after setup")
    if task["check"]["status"] != "give_up":
        if check_task(device, task, "done") != (False, None):
            raise RuntimeError("checker accepts an unchanged device")
    for command in task["verification"]["correct"]:
        device.shell(command)
    if check_task(device, task, task["check"]["status"]) != (True, None):
        raise RuntimeError("checker rejects the exact correct state")
    setup()
    for command in task["verification"]["wrong"]:
        device.shell(command)
    if check_task(device, task, task["check"]["status"]) != (False, None):
        raise RuntimeError("checker accepts a plausible wrong state")
    setup()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=WINDOWS_ADB if sys.platform == "win32" else WSL_ADB)
    parser.add_argument("--task", help="verify only this task ID")
    args = parser.parse_args(argv)
    tasks = load_tasks(Path(__file__).with_name("tasks_heldout.json"))
    if args.task:
        tasks = [t for t in tasks if t["id"] == args.task]
        if not tasks:
            parser.error("unknown task")
    try:
        device = VerificationDevice(args.adb)
        if device.shell("getprop ro.build.version.sdk").strip() != "30":
            raise RuntimeError("Android 11/API 30 is required")
        for task in tasks:
            verify(device, task)
            print(task["id"] + ": False -> True -> False; seed restored", flush=True)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
