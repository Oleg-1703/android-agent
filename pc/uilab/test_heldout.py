"""Held-out checker contracts using synthetic content-provider outputs only."""

import contextlib
import io
import json
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from uilab.run_eval import check_task, load_tasks, main
from uilab.verify_heldout import VerificationDevice, WINDOWS_ADB, WSL_ADB, verify


ROOT = Path(__file__).parent
TASKS = load_tasks(ROOT / "tasks_heldout.json")
FIXTURES = json.loads((ROOT / "heldout_fixtures.json").read_text(encoding="utf-8"))


class OutputDevice:
    def __init__(self, output):
        self.output = output

    def shell(self, command):
        return self.output


class HeldoutTests(unittest.TestCase):
    def test_exact_states_and_wrong_records(self):
        for task in TASKS:
            with self.subTest(task=task["id"]):
                f = FIXTURES[task["id"]]
                status = task["check"]["status"]
                self.assertEqual(check_task(OutputDevice(f["before"]), task, "pending"), (False, None))
                if status != "give_up":
                    self.assertEqual(check_task(OutputDevice(f["before"]), task, status), (False, None))
                self.assertEqual(check_task(OutputDevice(f["correct"]), task, status), (True, None))
                self.assertEqual(check_task(OutputDevice(f["wrong"]), task, status), (False, None))
                self.assertEqual(check_task(OutputDevice(f["correct"]), task, "needs_confirm"), (False, None))

    def test_missing_extra_or_collaterally_edited_rows_fail(self):
        for task in TASKS:
            text = FIXTURES[task["id"]]["correct"]
            variants = ["\n".join(text.splitlines()[1:]) + "\n",
                        text + text.splitlines()[0] + "\n",
                        text.replace("Atlas 01", "Atlas WRONG"),
                        text.replace("data1=5550201", "data1=5559999"),
                        text.replace("_id=880001, starred=0", "_id=880001, starred=1"),
                        text.replace("data1=home@sokol.example", "data1=wrong@sokol.example")]
            for output in variants:
                with self.subTest(task=task["id"], output=output[-50:]):
                    self.assertEqual(check_task(OutputDevice(output), task, task["check"]["status"]),
                                     (False, None))

    def test_query_failure_never_passes(self):
        for task in TASKS:
            for output in ("", "No result found.\n", "Permission Denial: not exported\n",
                           "Error: unknown URI\n", FIXTURES[task["id"]]["correct"] + "Error: query failed\n"):
                self.assertFalse(check_task(OutputDevice(output), task, task["check"]["status"])[0])

    def test_russian_goals_and_seeded_search(self):
        self.assertEqual(len(TASKS), 6)
        for task in TASKS:
            self.assertRegex(task["goal"], "[А-Яа-я]")
            self.assertGreater(len(task["setup"]), 90)
            self.assertIn("Atlas 30", "\n".join(task["setup"]))
            self.assertNotIn("Vera North Logistics", "\n".join(task["setup"]))
            self.assertNotIn("com.phoneuse", "\n".join(task["setup"]))
            self.assertNotIn("firefox", "\n".join(task["setup"]))
        transfer = next(t for t in TASKS if t["id"] == "heldout_two_apps")
        self.assertEqual(set(transfer["allow"]), {"com.android.contacts", "com.android.settings", "com.android.settings.intelligence"})
        self.assertNotIn("5550187", transfer["goal"])

    def test_tasks_option_never_calls_model_in_dry_run(self):
        with patch("subprocess.run", side_effect=AssertionError("external call")), \
             contextlib.redirect_stdout(io.StringIO()) as output:
            result = main(["--tasks", str(ROOT / "tasks_heldout.json"), "--dry-run",
                           "--serial", "emulator-5580", "--adb", WINDOWS_ADB])
        self.assertEqual(result, 0)
        self.assertIn("heldout_missing_contact", output.getvalue())
        self.assertIn("6 tasks", output.getvalue())

    def test_verify_executes_both_variants_and_restores_seed(self):
        for task in TASKS:
            class ScriptedDevice:
                state = "before"
                commands = []

                def shell(self, command):
                    self.commands.append(command)
                    if command == task["check"]["command"]:
                        return FIXTURES[task["id"]][self.state]
                    if command == task["setup"][-1]:
                        self.state = "before"
                    elif command in task["verification"]["correct"]:
                        self.state = "correct"
                    elif command in task["verification"]["wrong"]:
                        self.state = "wrong"
                    return ""

            device = ScriptedDevice()
            verify(device, task)
            self.assertEqual(device.state, "before")
            for cmd in task["verification"]["correct"] + task["verification"]["wrong"]:
                self.assertIn(cmd, device.commands)

    def test_busy_device_stops_before_any_mutation(self):
        for package in ("com.phoneuse", "org.mozilla.firefox", "org.mozilla.fenix"):
            completed = subprocess.CompletedProcess([], 0,
                f"mResumedActivity: ActivityRecord{{abc u0 {package}/.Main t1}}", "")
            with patch("subprocess.run", return_value=completed) as run:
                with self.assertRaisesRegex(RuntimeError, "busy"):
                    VerificationDevice(WINDOWS_ADB).shell("settings put global device_name WRONG")
            self.assertEqual(run.call_count, 1)
            self.assertEqual(run.call_args.args[0],
                             [WINDOWS_ADB, "-s", "emulator-5580", "shell", "dumpsys activity activities"])

    def test_unknown_focus_and_failed_adb_stop(self):
        for stdout, stderr, code, calls in (("", "", 0, 10), ("", "denied", 1, 1)):
            with patch("subprocess.run", return_value=subprocess.CompletedProcess([], code, stdout, stderr)) as run:
                with patch("time.sleep"), self.assertRaises(RuntimeError):
                    VerificationDevice(WSL_ADB).shell("content delete --uri content://com.android.contacts/raw_contacts")
                self.assertEqual(run.call_count, calls)

    def test_fixed_adb_and_serial(self):
        with self.assertRaises(ValueError):
            VerificationDevice("adb")
        foreground = "mResumedActivity: ActivityRecord{abc u0 com.android.settings/.Settings t1}"
        with patch("subprocess.run", side_effect=[subprocess.CompletedProcess([], 0, foreground, ""),
                                                 subprocess.CompletedProcess([], 0, "30\n", "")]) as run:
            self.assertEqual(VerificationDevice(WSL_ADB).shell("getprop ro.build.version.sdk"), "30\n")
        self.assertTrue(all(c.args[0][1:3] == ["-s", "emulator-5580"] for c in run.call_args_list))


if __name__ == "__main__":
    unittest.main()
