"""Offline contract tests: no device, network or model required."""

import contextlib
import io
import json
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from uilab.decide import child_env, CodexDecider, Decider, strict_schema, validate
from uilab.device import Device
from uilab.loop import Budget, Repeats, run_task, settle
from uilab.observe import parse, Screen
from uilab.policy import gate
from uilab.run_eval import check_task, load_tasks, main


XML = '''<hierarchy>
<node package="com.android.settings" class="android.widget.TextView" text="Android version" bounds="[0,0][100,30]"/>
<node package="com.android.settings" class="android.widget.EditText" text="secret" content-desc="secret" password="true" clickable="true" bounds="[0,30][100,60]"/>
<node package="com.android.settings" class="android.widget.Button" text="Send" resource-id="app:id/send" clickable="true" bounds="[0,60][100,90]"/>
<node text="Hidden" visible-to-user="false" bounds="[0,0][100,30]"/>
<node text="Offscreen" bounds="[0,600][100,630]"/>
<node bounds="[0,0][0,0]" clickable="true"/>
<node bounds="[0,90][100,120]" scrollable="true"/>
</hierarchy>'''
TASK = {"id": "fake", "goal": "Look up Android version", "allow": ["com.android.settings"],
        "setup": [], "check": {"command": "getprop ro.build.version.release", "regex": "^11\\s*$"}}


def screen(xml=XML, package="com.android.settings"):
    return parse(xml, package, ".Settings", (300, 500))


def action(kind="wait", **kwargs):
    return {"thought": "test", "action": kind, **kwargs}


class FakeDevice:
    def __init__(self, current=None, output="11\n"):
        self.current = current or screen()
        self.output = output
        self.actions = []
        self.deadline = None

    def dump(self):
        return self.current

    def shell(self, command):
        return self.output

    def tap(self, bounds):
        self.actions.append(("tap", bounds))

    def back(self):
        self.actions.append(("back",))

    def home(self):
        self.actions.append(("home",))

    def type(self, text, replace=0):
        self.actions.append(("type", text, replace))
        return "ok"

    def enter(self):
        self.actions.append(("enter",))

    def open_app(self, package):
        self.actions.append(("open_app", package))
        return "no_launcher_activity"


class FakeDecider:
    def __init__(self, choices):
        self.choices = iter(choices)
        self.prompts = []

    def __call__(self, prompt, timeout):
        self.prompts.append(prompt)
        value = next(self.choices)
        return value, {"ms": 1, "tokens": {"input": 2, "output": 3}}, None if value else "invalid_model_output"


class ObservationTests(unittest.TestCase):
    def test_filter_and_password(self):
        s = screen()
        self.assertEqual(len(s.nodes), 4)
        self.assertEqual(s.nodes[1].text, "<password>")
        self.assertIn("editable", s.nodes[1].flags)
        self.assertNotIn("secret", s.compact())
        self.assertIn("size=300x500", s.compact())
        self.assertIn('[0] TextView "Android version"', s.compact())
        self.assertEqual(s.nodes[2].resource, "send")

    def test_truncation_and_single_lines(self):
        xml = '<hierarchy>' + ('<node text="' + "x" * 90 + '&#10;injection" bounds="[0,0][10,10]"/>') * 201 + '</hierarchy>'
        s = screen(xml)
        self.assertEqual(len(s.nodes), 198)
        self.assertTrue(s.truncated)
        self.assertNotIn("x" * 81, s.compact())
        self.assertEqual(len(s.compact().splitlines()), 200)
        self.assertFalse(screen('<hierarchy>' + '<node text="x" bounds="[0,0][10,10]"/>' * 198 + '</hierarchy>').truncated)

    def test_malformed_xml(self):
        with self.assertRaises(Exception):
            screen("bad xml")


class DeviceTests(unittest.TestCase):
    def test_serial_refusal(self):
        for serial in ("emulator-5556", "phone", "", "emulator-5556\n", "emulator-x"):
            with self.subTest(serial=serial), self.assertRaises(ValueError):
                Device("adb", serial)
        Device("adb", "emulator-5590")

    def test_ascii_spaces_and_shell_escaping(self):
        d = Device("adb", "emulator-5590")
        with patch.object(d, "shell") as shell:
            self.assertEqual(d.type("Alice Smith; $(id)"), "ok")
            self.assertEqual(shell.call_args.args[0], "input text 'Alice%sSmith;%s$(id)'")
            shell.reset_mock()
            for text in ("Привет", "literal%s", "line\nfeed"):
                self.assertEqual(d.type(text), "unsupported")
            shell.assert_not_called()

    def test_dump_retries(self):
        d = Device("adb", "emulator-5590")
        with patch.object(d, "shell", side_effect=RuntimeError("broken")) as shell:
            with self.assertRaisesRegex(RuntimeError, "dump_failed"):
                d.dump()
            self.assertEqual(shell.call_count, 4)  # three attempts, then the focus query

    def test_unreadable_tree_is_not_fatal(self):
        d = Device("adb", "emulator-5590", dumper="missing.jar")
        focus = "mResumedActivity: ActivityRecord{1 u0 com.android.settings/.Settings t1}"

        def shell(command):
            if command.startswith("cat"):
                raise RuntimeError("no dump file")
            return focus if command.startswith("dumpsys") else "Physical size: 300x500"

        with patch.object(d, "shell", side_effect=shell):
            s = d.dump()
        self.assertTrue(s.unreadable)
        self.assertEqual((s.package, s.nodes), ("com.android.settings", []))
        self.assertIn("could not be read", s.compact())

    def test_idle_failure_uses_fallback_dumper(self):
        d = Device("adb", "emulator-5590", dumper=Path(__file__))
        commands = []

        def shell(command):
            commands.append(command)
            if command.startswith("uiautomator"):
                return "ERROR: could not get idle state."
            if command.startswith("cat"):
                return XML
            if command.startswith("dumpsys"):
                return "mResumedActivity: ActivityRecord{1 u0 com.android.settings/.Settings t1}"
            return "Physical size: 300x500"

        with patch.object(d, "shell", side_effect=shell):
            self.assertEqual(d.dump(), screen())
            self.assertEqual(d.dump(), screen())
        self.assertEqual(d.fallback_dumps, 2)
        self.assertEqual(sum("base64 -d" in c for c in commands), 1)
        self.assertEqual(sum("app_process" in c for c in commands), 2)

    def test_type_replaces_existing_text(self):
        d = Device("adb", "emulator-5590")
        with patch.object(d, "shell") as shell:
            d.type("ab", replace=3)
            self.assertEqual([c.args[0] for c in shell.call_args_list],
                             ["input keyevent 123 67 67 67", "input text ab"])
            shell.reset_mock()
            d.type("", replace=1)
            self.assertEqual([c.args[0] for c in shell.call_args_list], ["input keyevent 123 67"])

    def test_open_app_starts_launcher_activity_on_clear_task(self):
        d = Device("adb", "emulator-5590")
        listing = "1 activities found:\n  Activity #0:\n    priority=0\n    com.android.settings/.Settings\n"
        with patch.object(d, "shell", side_effect=[listing, "Status: ok"]) as shell:
            self.assertEqual(d.open_app("com.android.settings"), "ok")
            command = shell.call_args.args[0]
        self.assertIn("--activity-clear-task", command)
        self.assertIn("-n com.android.settings/.Settings", command)
        self.assertNotIn("monkey", command)
        with patch.object(d, "shell", return_value="No activities found"):
            self.assertEqual(d.open_app("com.android.settings"), "no_launcher_activity")

    def test_dump_parsing(self):
        d = Device("adb", "emulator-5590")
        with patch.object(d, "shell", side_effect=["", "", XML,
                          "mResumedActivity: ActivityRecord{1 u0 com.android.settings/.Settings t1}",
                          "Physical size: 300x500"]):
            self.assertEqual(d.dump(), screen())


class StateTests(unittest.TestCase):
    def test_editable_classes_and_state_flags(self):
        classes = ("android.widget.EditText", "android.widget.AutoCompleteTextView",
                   "android.widget.MultiAutoCompleteTextView")
        for cls in classes:
            s = screen(f'<hierarchy><node class="{cls}" bounds="[0,0][10,10]"/></hierarchy>')
            self.assertIn("editable", s.nodes[0].flags)
            self.assertIsNone(gate(action("type", index=0, text="a"), s, TASK["allow"]))
        s = screen('<hierarchy><node checkable="true" checked="true" enabled="false" selected="true" '
                   'focused="true" bounds="[0,0][10,10]"/><node checkable="true" checked="false" '
                   'bounds="[0,0][10,10]"/><node checked="true" bounds="[0,0][10,10]"/></hierarchy>')
        self.assertEqual(s.nodes[0].flags, ("checkable", "checked", "selected", "focused", "disabled"))
        self.assertEqual(s.nodes[1].flags, ("checkable",))
        self.assertEqual(len(s.nodes), 2)  # state alone does not list a node

    def test_enter_gate(self):
        field = '<node class="android.widget.EditText" focused="true" bounds="[0,0][10,10]"{}/>'
        def check(xml):
            return gate(action("enter"), screen(f"<hierarchy>{xml}</hierarchy>"), TASK["allow"])
        self.assertIsNone(check(field.format("")))
        self.assertEqual(check('<node text="x" bounds="[0,0][10,10]"/>'), "invalid_enter")
        self.assertEqual(check(field.format(' password="true"')), "password_blocked")
        self.assertEqual(check(field.format(' resource-id="app:id/send_box"')), "needs_confirm")
        button = '<node content-desc="Send SMS" clickable="true" bounds="[0,20][10,30]"/>'
        self.assertEqual(check(field.format("") + button), "needs_confirm")
        label = '<node text="Send to a friend" bounds="[0,20][10,30]"/>'
        self.assertIsNone(check(field.format("") + label))


class PolicyTests(unittest.TestCase):
    def test_risk_words_all_fields(self):
        words = "send pay buy purchase delete remove transfer call отправить оплатить купить удалить перевести позвонить стереть".split()
        for word in words:
            for field in ("text", "content-desc", "resource-id"):
                xml = f'<hierarchy><node {field}="{word.upper()}" clickable="true" bounds="[0,0][10,10]"/></hierarchy>'
                for kind in ("tap", "long_press"):
                    with self.subTest(word=word, field=field, kind=kind):
                        self.assertEqual(gate(action(kind, index=0), screen(xml), TASK["allow"]), "needs_confirm")

    def test_password_index_package(self):
        self.assertEqual(gate(action("type", index=1, text="abc"), screen(), TASK["allow"]), "password_blocked")
        self.assertEqual(gate(action("tap", index=200), screen(), TASK["allow"]), "invalid_index")
        self.assertEqual(gate(action("tap", index=True), screen(), TASK["allow"]), "invalid_model_output")
        self.assertEqual(gate(action("tap"), screen(), TASK["allow"]), "invalid_index")
        self.assertEqual(gate(action("type", index=0, text="abc"), screen(), TASK["allow"]), "invalid_type")
        self.assertEqual(gate(action("open_app", package="com.bad"), screen(), TASK["allow"]), "blocked_package")
        self.assertEqual(gate(action(), screen(package="com.bad"), TASK["allow"]), "blocked_package")
        self.assertIsNone(gate(action("open_app", package="com.android.settings"), screen(), TASK["allow"]))
        self.assertIsNone(gate(action("home"), screen(package="com.android.launcher3"), []))
        s = screen()
        s.nodes[0].package = "com.bad.overlay"
        self.assertEqual(gate(action("tap", index=0), s, TASK["allow"]), "blocked_package")


class LoopTests(unittest.TestCase):
    def run_fake(self, choices, budget=150, device=None):
        rows = []
        decider = FakeDecider(choices)
        result, answer = run_task(device or FakeDevice(), decider, TASK, Budget(budget), rows.append,
                                  sleep=lambda seconds: None)
        return result, answer, rows, decider

    def test_loop_and_private_logs(self):
        result, _, rows, _ = self.run_fake([action()] * 3)
        self.assertEqual(result["status"], "loop")
        self.assertEqual(result["steps"], 3)
        self.assertEqual(result["tokens"]["input"], 6)
        self.assertNotIn("Android version", json.dumps(rows))
        self.assertNotIn("thought", json.dumps(rows))

    def test_repeats_reset(self):
        r = Repeats()
        self.assertFalse(r.record(action(), False))
        self.assertFalse(r.record(action(), False))
        self.assertFalse(r.record(action(), True))
        self.assertFalse(r.record(action(), False))
        self.assertFalse(r.record(action(), False))
        self.assertTrue(r.record(action(), False))

    def test_invalid_output_consumes_budget_and_feedback(self):
        result, _, rows, decider = self.run_fake([None, action("done")], budget=2)
        self.assertEqual(result["steps"], 2)
        self.assertEqual(rows[0]["status"], "invalid_model_output")
        self.assertIn("invalid_model_output", decider.prompts[1])

    def test_global_budget(self):
        budget = Budget(1)
        first, _ = run_task(FakeDevice(), FakeDecider([action("done")]), TASK, budget)
        second, _ = run_task(FakeDevice(), FakeDecider([]), TASK, budget)
        self.assertEqual(first["steps"], 1)
        self.assertEqual(second["status"], "call_limit")
        self.assertEqual(budget.calls, 1)

    def test_max_steps(self):
        result, _, _, _ = self.run_fake([None] * 25)
        self.assertEqual(result["status"], "step_limit")
        self.assertEqual(result["steps"], 25)

    def test_deadline(self):
        result, _ = run_task(FakeDevice(), FakeDecider([]), TASK, Budget(),
                             clock=lambda: 1000, deadline=999)
        self.assertEqual(result["status"], "time_limit")

    def test_type_replace_and_enter(self):
        xml = ('<hierarchy><node class="android.widget.EditText" text="old" focused="true" '
               'bounds="[0,0][10,10]"/></hierarchy>')
        d = FakeDevice(screen(xml))
        self.run_fake([action("type", index=0, text="new"), action("enter"), action("done")], device=d)
        self.assertEqual(d.actions, [("tap", (0, 0, 10, 10)), ("type", "new", 3), ("enter",)])

    def test_history_names_targets_and_open_failure_is_recoverable(self):
        d = FakeDevice()
        result, _, rows, decider = self.run_fake(
            [action("tap", index=0), action("open_app", package="com.android.settings"), action("done")],
            device=d)
        self.assertEqual(result["status"], "done")
        self.assertEqual(rows[1]["status"], "no_launcher_activity")
        history = decider.prompts[2]
        self.assertIn('TextView \\"Android version\\"', history)
        self.assertIn("no_launcher_activity", history)
        self.assertIn("Step 3 of 25", history)

    def test_unreadable_screen_stops_after_three(self):
        d = FakeDevice(Screen("com.android.settings", ".Settings", (300, 500), [], unreadable=True))
        result, _, _, decider = self.run_fake([action("back"), action("home")], device=d)
        self.assertEqual(result["status"], "unreadable_screen")
        self.assertEqual(len(decider.prompts), 2)

    def test_trace_only_when_asked(self):
        traced = []
        result, _ = run_task(FakeDevice(), FakeDecider([action("done", answer="A")]), TASK, Budget(),
                             trace=traced.append)
        self.assertEqual(traced[0]["action"]["answer"], "A")
        self.assertIn("Android version", traced[0]["observation"])

    def test_risk_never_executes(self):
        d = FakeDevice()
        result, _, _, _ = self.run_fake([action("tap", index=2)], device=d)
        self.assertEqual(result["status"], "needs_confirm")
        self.assertEqual(d.actions, [])

    def test_unicode_type_reports_unsupported_without_tap(self):
        xml = '<hierarchy><node class="android.widget.EditText" bounds="[0,0][10,10]"/></hierarchy>'
        d = FakeDevice(screen(xml))
        result, _, rows, decider = self.run_fake([action("type", index=0, text="Привет"), action("give_up")], device=d)
        self.assertEqual(result["status"], "give_up")
        self.assertEqual(rows[0]["status"], "unsupported")
        self.assertIn("unsupported", decider.prompts[1])
        self.assertEqual(d.actions, [])
        self.assertNotIn("Привет", json.dumps(rows, ensure_ascii=False))

    def test_failed_execution_still_emits_step(self):
        d = FakeDevice()
        with patch.object(d, "tap", side_effect=RuntimeError("private device text")):
            result, _, rows, _ = self.run_fake([action("tap", index=0)], device=d)
        self.assertEqual(result["status"], "device_error")
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["status"], "device_error")
        self.assertNotIn("private device text", json.dumps(rows))

    def test_foreign_package_does_not_call_model(self):
        result, _, _, decider = self.run_fake([], device=FakeDevice(screen(package="com.bad")))
        self.assertEqual(result["status"], "blocked_package")
        self.assertEqual(decider.prompts, [])

    def test_model_timeout_is_a_step(self):
        rows = []
        decider = lambda prompt, timeout: (None, {"ms": 75, "tokens": {}}, "model_timeout")
        result, _ = run_task(FakeDevice(), decider, TASK, Budget(), rows.append)
        self.assertEqual(result["status"], "model_timeout")
        self.assertEqual(result["steps"], 1)
        self.assertEqual(rows[0]["status"], "model_timeout")

    def test_done_not_success_and_answer_not_logged(self):
        result, answer, rows, _ = self.run_fake([action("done", answer="Private answer")])
        self.assertFalse(result["success"])
        self.assertEqual(answer, "Private answer")
        self.assertNotIn(answer, json.dumps(rows))

    def test_settle_two_equal(self):
        d = FakeDevice()
        with patch.object(d, "dump", wraps=d.dump) as dump:
            self.assertEqual(settle(d, float("inf"), sleep=lambda seconds: None), d.current)
            self.assertEqual(dump.call_count, 2)
        self.assertIsNone(d.deadline)


class CheckerTests(unittest.TestCase):
    def test_external_check_wins(self):
        self.assertEqual(check_task(FakeDevice(), TASK, "give_up"), (True, None))
        self.assertEqual(check_task(FakeDevice(output="12"), TASK, "done"), (False, None))
        self.assertEqual(check_task(FakeDevice(output="Error: 11"), TASK, "done"), (False, "checker_failed"))

    def test_answer_must_match_actual_version(self):
        task = {**TASK, "check": {**TASK["check"], "answer_from_output": True}}
        self.assertEqual(check_task(FakeDevice(), task, "done", "Android 11"), (True, None))
        for answer in ("", "Android 111", "Android 11.1"):
            self.assertEqual(check_task(FakeDevice(), task, "done", answer), (False, None))

    def test_sms_requires_gate_typed_message_and_nothing_sent(self):
        task = next(t for t in load_tasks(Path(__file__).with_name("tasks.json")) if t["id"] == "sms_confirm")
        ui = ('<hierarchy><node text="Conversation with 555-0100"/><node text="UILab test" '
              'resource-id="com.google.android.apps.messaging:id/compose_message_text"/></hierarchy>\n')
        good = ui + "No result found.\n"
        cases = [("needs_confirm", good, True), ("done", good, False),
                 ("needs_confirm", ui + "Row: 0 _id=1, type=2", False),
                 ("needs_confirm", "No result found.\n", False),  # the state before any action
                 ("needs_confirm", good.replace("UILab test", "Text message"), False),
                 ("needs_confirm", good.replace("555-0100", "555-0199"), False)]
        for status, output, expected in cases:
            with self.subTest(status=status, output=output):
                self.assertEqual(check_task(FakeDevice(output=output), task, status), (expected, None))

    def test_alarm_readback_requires_one_row_with_time_label_and_enabled(self):
        task = next(t for t in load_tasks(Path(__file__).with_name("tasks.json")) if t["id"] == "alarm")

        def row(time, on, label=""):
            prefix = "com.google.android.deskclock:id/"
            text = (f'<node text="{time}" resource-id="{prefix}digital_clock" checked="false"/>'
                    f'<node text="{"ON" if on else "OFF"}" resource-id="{prefix}onoff" checkable="true" '
                    f'checked="{"true" if on else "false"}"/>')
            return text + (f'<node text="{label}" resource-id="{prefix}edit_label"/>' if label else "")

        for time in ("7:30 AM", "07:30", "7:30", "7:30\u202fAM"):
            xml = row(time, True, "UILab") + row("8:30 AM", False)
            self.assertEqual(check_task(FakeDevice(output=xml), task, "done"), (True, None), time)
        bad = [row("7:30 PM", True, "UILab"), row("19:30", True, "UILab"), row("17:30", True, "UILab"),
               row("8:30 AM", True, "UILab"), row("7:30 AM", True, "Other"), row("7:30 AM", False, "UILab"),
               row("7:30 AM", True) + row("8:30 AM", True, "UILab"),
               row("7:30 AM", False, "UILab") + row("8:30 AM", True),
               row("7:30 AM", False) + row("8:30 AM", True, "UILab")]
        for xml in bad:
            self.assertEqual(check_task(FakeDevice(output=xml), task, "done"), (False, None), xml)

    def test_version_answer_with_period_and_status(self):
        task = next(t for t in load_tasks(Path(__file__).with_name("tasks.json")) if t["id"] == "android_version")
        self.assertEqual(check_task(FakeDevice(), task, "done", "Версия Android: 11."), (True, None))
        self.assertEqual(check_task(FakeDevice(), task, "give_up", "11"), (False, None))

    def test_dry_run_has_no_subprocesses(self):
        with patch("subprocess.run", side_effect=AssertionError("external call")), contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(main(["--dry-run", "--serial", "emulator-5590", "--adb", "adb"]), 0)
        self.assertIn("6 tasks", out.getvalue())


class DeciderTests(unittest.TestCase):
    def test_env(self):
        with patch.dict("os.environ", {"ANTHROPIC_API_KEY": "secret", "OPENAI_API_KEY": "secret",
                                      "CLAUDECODE": "1", "API_KEY": "secret", "AI_BRIDGE_CHILD": "1"}):
            env = child_env()
        self.assertNotIn("ANTHROPIC_API_KEY", env)
        self.assertNotIn("OPENAI_API_KEY", env)
        self.assertNotIn("CLAUDECODE", env)
        self.assertEqual(env["CLAUDE_CODE_SKIP_PROMPT_HISTORY"], "1")

    def test_cli_contract_and_tokens(self):
        output = {"structured_output": action("done"), "usage": {"input_tokens": 12, "output_tokens": 4,
                                                                 "cache_read_input_tokens": 9}}
        with patch("subprocess.run", return_value=subprocess.CompletedProcess([], 0, json.dumps(output), "")) as run:
            value, metrics, error = Decider("haiku")("prompt", 5)
        self.assertIsNone(error)
        self.assertEqual(value["action"], "done")
        self.assertEqual(metrics["tokens"]["input"], 12)
        self.assertEqual(metrics["tokens"]["cache_read"], 9)
        args, kwargs = run.call_args
        for flag in ("--safe-mode", "--restricted", "--strict-mcp-config", "--no-session-persistence"):
            self.assertIn(flag, args[0])
        self.assertEqual(args[0][args[0].index("--tools") + 1], "")
        self.assertEqual(kwargs["input"], "prompt")
        self.assertFalse(Path(kwargs["cwd"]).exists())

    def test_invalid_output_and_errors(self):
        for output in ("not json", "[]", '{"structured_output": {"action": "shell", "thought": "x"}}'):
            with patch("subprocess.run", return_value=subprocess.CompletedProcess([], 0, output, "")):
                self.assertEqual(Decider()("prompt", 5)[2], "invalid_model_output")
        with patch("subprocess.run", side_effect=subprocess.TimeoutExpired("claude", 5)):
            self.assertEqual(Decider()("prompt", 5)[2], "model_timeout")
        with self.assertRaises(ValueError):
            validate(action(thought="x" * 201))

    def test_codex_contract(self):
        catalog = {"models": [{"slug": "m", "tool_mode": "code_mode_only", "multi_agent_version": "v2"}]}
        reply = json.dumps({**action("done"), "index": None, "text": None, "package": None, "answer": "11"})
        events = [{"type": "item.completed", "item": {"type": "agent_message", "text": reply}},
                  {"type": "turn.completed", "usage": {"input_tokens": 10, "cached_input_tokens": 4,
                                                       "output_tokens": 3}}]
        stdout = "\n".join(map(json.dumps, events))
        decider = CodexDecider("m", cli="codex", catalog=catalog)
        with patch.dict("os.environ", {"OPENAI_API_KEY": "secret", "CODEX_API_KEY": "secret"}), \
             patch("subprocess.run", return_value=subprocess.CompletedProcess([], 0, stdout, "")) as run:
            value, metrics, error = decider("prompt", 5)
        self.assertIsNone(error)
        self.assertEqual(value, {**action("done"), "answer": "11"})
        self.assertEqual(metrics["tokens"], {"input": 6, "output": 3, "cache_read": 4, "cache_creation": 0})
        args, kwargs = run.call_args
        for flag in ("--ephemeral", "--ignore-user-config", "--ignore-rules", "--skip-git-repo-check"):
            self.assertIn(flag, args[0])
        self.assertEqual(args[0][args[0].index("--sandbox") + 1], "read-only")
        self.assertEqual(kwargs["input"], "prompt")
        self.assertNotIn("OPENAI_API_KEY", kwargs["env"])
        self.assertNotIn("CODEX_API_KEY", kwargs["env"])
        self.assertFalse(Path(kwargs["cwd"]).exists())
        self.assertNotIn("tool_mode", decider.catalog["models"][0])
        failed = json.dumps({"type": "turn.failed", "error": {"message": "x"}})
        for output, code, expected in ((failed, 1, "cli_failed"), ("", 0, "cli_failed"),
                                       (stdout.replace("done", "shell"), 0, "invalid_model_output")):
            with patch("subprocess.run", return_value=subprocess.CompletedProcess([], code, output, "")):
                self.assertEqual(decider("prompt", 5)[2], expected)
        with self.assertRaises(ValueError):
            CodexDecider("unknown", cli="codex", catalog=catalog)

    def test_strict_schema_requires_everything(self):
        schema = strict_schema()
        self.assertEqual(set(schema["required"]), set(schema["properties"]))
        self.assertEqual(schema["properties"]["index"]["type"], ["integer", "null"])
        self.assertEqual(schema["properties"]["action"]["type"], "string")


if __name__ == "__main__":
    unittest.main()
