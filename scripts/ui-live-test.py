"""Read-only real-app acceptance on the dedicated emulator; never clicks SAVE."""
import argparse
from pathlib import Path
import subprocess
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--pc-config", type=Path)
    parser.add_argument("--russian", action="store_true")
    parser.add_argument("--commands", action="store_true")
    parser.add_argument("--metadata", action="store_true")
    parser.add_argument("--photo", type=Path)
    parser.add_argument("--voice", type=Path)
    args = parser.parse_args()
    if sum((bool(args.pc_config), args.russian, args.commands, args.metadata)) > 1:
        parser.error("Choose one test mode")
    if (args.photo and args.voice) or ((args.photo or args.voice) and not args.pc_config):
        parser.error("Choose one media fixture with --pc-config")
    media = args.photo or args.voice
    if media and (not media.is_file() or media.stat().st_size > 4_000_000):
        parser.error("Media fixture missing or above 4MB")
    base = [str(args.adb), "-s", "emulator-5556"]

    def adb(*arguments):
        return subprocess.run(base + list(arguments), check=True, capture_output=True, text=True, encoding="utf-8").stdout.strip()

    if adb("shell", "getprop", "ro.kernel.qemu") != "1" or adb("shell", "getprop", "ro.build.version.sdk") != "30":
        raise SystemExit("Dedicated API 30 emulator required")
    component = "com.oleg.androidagent/.FatAccessibilityService"
    original = adb("shell", "settings", "get", "secure", "enabled_accessibility_services")
    previous = [] if original == "null" else original.split(":")
    others = [item for item in previous if "com.oleg.androidagent/" not in item]

    def connect():
        adb("shell", "settings", "put", "secure", "enabled_accessibility_services", ":".join(others) or "null")
        time.sleep(0.5)
        adb("shell", "settings", "put", "secure", "enabled_accessibility_services", ":".join(others + [component]))
        adb("shell", "settings", "put", "secure", "accessibility_enabled", "1")

    if args.pc_config:
        adb("shell", "run-as", "com.oleg.androidagent", "mkdir", "-p", "files")
        adb("push", str(args.pc_config), "/data/local/tmp/android-agent-pc-test.json")
        try:
            adb("shell", "run-as", "com.oleg.androidagent", "cp", "/data/local/tmp/android-agent-pc-test.json", "files/test-config.json")
        finally:
            adb("shell", "rm", "/data/local/tmp/android-agent-pc-test.json")
    if media:
        adb("push", str(media), "/data/local/tmp/android-agent-media-test.bin")
        try:
            adb("shell", "run-as", "com.oleg.androidagent", "cp", "/data/local/tmp/android-agent-media-test.bin", "files/test-media.bin")
        finally:
            adb("shell", "rm", "/data/local/tmp/android-agent-media-test.bin")
    phase = "pc-live-photo" if args.photo else "pc-live-voice" if args.voice else "pc-live-draft" if args.pc_config else "ui-metadata" if args.metadata else "ui-command-contract" if args.commands else "ui-live-ru-read" if args.russian else "ui-live-read"
    command = base + ["shell", "am", "instrument", "-r", "-w", "-e", "phase", phase,
                      "com.oleg.androidagent.test/com.oleg.androidagent.DeviceSmoke"]
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8")
    try:
        # Instrumentation force-stops the target process. Rebind only our service;
        # FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES is set by this test phase.
        time.sleep(2)
        connect()
        stdout, stderr = process.communicate(timeout=150)
        print(stdout)
        if process.returncode or "INSTRUMENTATION_CODE: -1" not in stdout or "PASS:" not in stdout:
            raise SystemExit("Real-app dry-run failed; diary SAVE was never requested")
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=10)
        adb("shell", "run-as", "com.oleg.androidagent", "rm", "-f", "files/test-config.json")
        adb("shell", "run-as", "com.oleg.androidagent", "rm", "-f", "files/test-media.bin")
        connect()


if __name__ == "__main__":
    main()
