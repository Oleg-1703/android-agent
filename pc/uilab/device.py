"""All device access lives here; no physical or personal emulator access."""

import base64
from pathlib import Path
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET

from .observe import parse, Screen


# Fallback for screens on which "uiautomator dump" never reaches idle (see dumper/UiDump.java).
DUMPER = Path(__file__).with_name("dumper") / "uidump.jar"
XML_PATH = "/data/local/tmp/uilab.xml"
JAR_PATH = "/data/local/tmp/uilab-dump.jar"


MAX_REPLACE = 300


def typeable(text):
    # Android "input text" replaces %s with spaces, so a literal %s is ambiguous.
    return text.isascii() and all(32 <= ord(c) <= 126 for c in text) and "%s" not in text


class Device:
    def __init__(self, adb_path, serial, dumper=DUMPER):
        if not serial.startswith("emulator-") or serial == "emulator-5556":
            raise ValueError("Only disposable emulator serials allowed; emulator-5556 is forbidden")
        if not re.fullmatch(r"emulator-\d+", serial):
            raise ValueError("Invalid emulator serial")
        self.adb_path, self.serial = adb_path, serial
        self.deadline = None
        self.dumper, self._installed, self.fallback_dumps = Path(dumper), False, 0

    def shell(self, command):
        # A dump that cannot reach idle gives up by itself after ~11 s; leave it room on a loaded host.
        timeout = 25.0 if command.startswith("uiautomator dump") else 15.0
        if self.deadline is not None:
            timeout = min(timeout, self.deadline - time.monotonic())
        if timeout <= 0:
            raise TimeoutError("device_deadline")
        result = subprocess.run([self.adb_path, "-s", self.serial, "shell", command],
                                capture_output=True, text=True, encoding="utf-8",
                                timeout=timeout, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        if result.returncode:
            raise RuntimeError("adb_shell_failed")
        return result.stdout

    def launcher(self):
        output = self.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN "
                            "-c android.intent.category.HOME")
        match = re.search(r"([\w.]+)/[\w.$]+", output)
        if not match:
            raise RuntimeError("launcher_unavailable")
        return match[1]

    def _xml(self):
        # Removing the previous dump prevents stale observations on dump failure.
        self.shell(f"rm -f {XML_PATH}")
        output = self.shell(f"uiautomator dump {XML_PATH} 2>&1")
        if "could not get idle state" in output and self.dumper.is_file():
            if not self._installed:
                code = base64.b64encode(self.dumper.read_bytes()).decode("ascii")
                self.shell(f"rm -f {JAR_PATH} && echo {code} | base64 -d > {JAR_PATH}")
                self._installed = True
            self.shell(f"CLASSPATH=/system/framework/uiautomator.jar:{JAR_PATH} "
                       f"app_process /system/bin UiDump {XML_PATH}")
            self.fallback_dumps += 1
        return self.shell(f"cat {XML_PATH}")

    def _focus(self):
        focus = self.shell("dumpsys activity activities")
        match = re.search(r"(?:ResumedActivity|topResumedActivity).*? ([\w.]+)/([\w.$]+)", focus)
        if not match:
            raise ValueError("no_foreground_activity")
        sizes = re.findall(r"(\d+)x(\d+)", self.shell("wm size"))
        if not sizes:
            raise ValueError("no_screen_size")
        return match[1], match[2], tuple(map(int, sizes[-1]))

    def dump(self):
        """The current screen. An unreadable tree is reported as such; only a dead device raises."""
        errors = (ET.ParseError, ValueError, RuntimeError, subprocess.TimeoutExpired)
        for _ in range(3):
            try:
                xml = self._xml()
                return parse(xml, *self._focus())
            except errors:
                pass
        try:
            return Screen(*self._focus(), nodes=[], unreadable=True)
        except errors:
            raise RuntimeError("dump_failed") from None

    def tap(self, bounds):
        x1, y1, x2, y2 = bounds
        self.shell(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")

    def long_press(self, bounds):
        x1, y1, x2, y2 = bounds
        x, y = (x1 + x2) // 2, (y1 + y2) // 2
        self.shell(f"input swipe {x} {y} {x} {y} 800")

    def type(self, text, replace=0):
        """Types into the focused field after deleting up to `replace` characters already in it."""
        if not typeable(text):
            return "unsupported"
        if replace:
            # 123 = MOVE_END, 67 = DEL. A hint reported as text just makes the extra DELs no-ops.
            self.shell("input keyevent 123" + " 67" * min(replace, MAX_REPLACE))
        if text:
            self.shell("input text " + shlex.quote(text.replace(" ", "%s")))
        return "ok"

    def enter(self):
        self.shell("input keyevent KEYCODE_ENTER")

    def scroll(self, size, down=True):
        w, h = size
        y1, y2 = (h * 3 // 4, h // 4) if down else (h // 4, h * 3 // 4)
        self.shell(f"input swipe {w // 2} {y1} {w // 2} {y2} 400")

    def back(self):
        self.shell("input keyevent KEYCODE_BACK")

    def home(self):
        self.shell("input keyevent KEYCODE_HOME")

    def open_app(self, package):
        """Starts the launcher activity on a cleared task, so the app shows its first screen.

        Not "monkey": it also picks MONKEY-category activities (arbitrary sub-pages) and
        resumes whatever page an existing task was left on.
        """
        if not re.fullmatch(r"[a-zA-Z][\w]*(?:\.[\w]+)+", package):
            raise ValueError("invalid_package")
        found = self.shell("cmd package query-activities --brief -a android.intent.action.MAIN "
                           "-c android.intent.category.LAUNCHER " + shlex.quote(package))
        match = re.search(r"^\s*(" + re.escape(package) + r"/[\w.$]+)\s*$", found, re.M)
        if not match:
            return "no_launcher_activity"
        output = self.shell("am start -W --activity-clear-task -a android.intent.action.MAIN "
                            "-c android.intent.category.LAUNCHER -n " + shlex.quote(match[1]))
        return "open_failed" if re.search(r"(?m)^Error", output) else "ok"

    def wake(self):
        self.shell("input keyevent KEYCODE_WAKEUP && wm dismiss-keyguard")
