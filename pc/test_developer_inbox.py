import base64
import datetime as dt
import json
from pathlib import Path
import subprocess
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import brain
import developer_inbox
from food_library import FoodLibrary
from meal_jobs import MealJobs
from meal_thread import MealThreads


class Inbox(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.cwd = Path(directory.name)
        self.now = dt.datetime(2026, 11, 27, 23, 59, 30, tzinfo=dt.timezone.utc)
        self.runner = Mock(return_value=SimpleNamespace(returncode=0))

    def forward(self, **kwargs):
        return developer_inbox.forward(self.cwd, "исправь бота $(secret)", "мой голос", [], ["wake", "captain"],
                                       runner=self.runner, now=self.now, **kwargs)

    def test_original_content_and_attachments_are_stored_before_wake(self):
        images = [{"media_type": media, "data": base64.b64encode(data).decode()}
                  for media, data in [("image/png", b"png"), ("image/jpeg", b"jpeg"), ("image/webp", b"webp")]]

        def wake(argv, **kwargs):
            self.assertEqual(len(list((self.cwd / "dev-inbox").glob("*.json"))), 1)
            return SimpleNamespace(returncode=0)

        result = developer_inbox.forward(self.cwd, "точный текст", "точный голос", images, ["wake"], wake, self.now)
        self.assertEqual(result, "sent")
        path, = (self.cwd / "dev-inbox").glob("*.json")
        self.assertRegex(path.name, r"^20261127-235930-[a-f0-9]{8}\.json$")
        value = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(value, {"ts": self.now.isoformat(), "typed": "точный текст", "voice": "точный голос",
                                 "images": [path.stem + "-1.png", path.stem + "-2.jpg", path.stem + "-3.webp"]})
        self.assertEqual([(path.parent / name).read_bytes() for name in value["images"]], [b"png", b"jpeg", b"webp"])
        self.assertEqual(list(path.parent.glob("*.tmp")), [])

    def test_wake_argv_has_only_fixed_message_and_path(self):
        self.assertEqual(self.forward(), "sent")
        argv, = self.runner.call_args.args
        path, = (self.cwd / "dev-inbox").glob("*.json")
        self.assertEqual(argv, ["wake", "captain", "2026-11-28 00:01",
                                "Request from Oleg in the bot chat: read " + developer_inbox.wsl_path(path)])
        self.assertFalse(self.runner.call_args.kwargs["shell"])
        self.assertEqual(self.runner.call_args.kwargs["timeout"], 30)
        self.assertEqual(self.runner.call_args.kwargs["creationflags"], getattr(subprocess, "CREATE_NO_WINDOW", 0))

    def test_wsl_path_conversion(self):
        self.assertEqual(developer_inbox.wsl_path(r"C:\x\y"), "/mnt/c/x/y")
        self.assertEqual(developer_inbox.wsl_path("D:/x/y"), "/mnt/d/x/y")
        self.assertEqual(developer_inbox.wsl_path("/home/oleg/x"), "/home/oleg/x")

    def test_failed_wake_keeps_the_request(self):
        for error in (None, OSError("unavailable"), subprocess.TimeoutExpired("wake", 30)):
            with self.subTest(error=error):
                self.runner.side_effect = error
                self.runner.return_value = SimpleNamespace(returncode=1)
                self.assertEqual(self.forward(), "saved")
        self.assertEqual(len(list((self.cwd / "dev-inbox").glob("*.json"))), 3)

    def test_storage_failure_never_wakes(self):
        (self.cwd / "dev-inbox").write_text("blocked", encoding="utf-8")
        self.assertEqual(self.forward(), "failed")
        self.runner.assert_not_called()


class DeveloperRequest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.server = server = brain.BrainServer.__new__(brain.BrainServer)
        server.cwd, server.cli = Path(directory.name), "unused"
        server.busy = threading.Lock()
        server.config = {"developer_wake": ["wake"]}
        server.threads = MealThreads(server.cwd / "t.sqlite", server.cwd / "images")
        server.library = FoodLibrary(server.cwd / "library.sqlite")
        server.jobs = MealJobs(server.cwd / "jobs.sqlite", lambda payload: {"status": "complete"})
        self.addCleanup(server.jobs.close)
        self.payload = {"_execution_id": "fixture", "text": "передай капитану: исправь бота",
                        "date": "2026-11-27", "target": "phone"}

    def test_feature_off_leaves_schema_and_system_unchanged(self):
        for catalog in (False, True):
            self.assertEqual(brain.build_schema(catalog), brain.build_schema(catalog, False))
            self.assertNotIn("developer", brain.build_schema(catalog)["properties"])
            self.assertEqual(brain.build_system(catalog), brain.THREAD_SYSTEM +
                             (brain.CATALOG_RULES if catalog else brain.NO_CATALOG_RULES))
        for config in ({}, {"developer_wake": []}):
            self.server.config = config
            with patch.object(brain, "parse_meal", return_value={"kind": "chat", "reply": "ответ", "developer": True}) as model, \
                    patch.object(developer_inbox, "forward") as forward:
                self.server.process_custom_meal(self.payload)
                self.assertEqual(model.call_args.kwargs["schema"], brain.build_schema(False))
                self.assertEqual(model.call_args.kwargs["system"], brain.build_system(False))
                forward.assert_not_called()

    def test_ack_and_metadata_for_each_status_with_only_the_new_message(self):
        thread = self.server.threads.open_thread()
        self.server.threads.add_turn(thread, "user", "old private context")
        for status, ack in (("sent", "Передал твоё сообщение разработчику."),
                            ("saved", "Сохранил сообщение для разработчика, но разбудить его не удалось: увидит при следующем запуске."),
                            ("failed", "Не смог сохранить сообщение для разработчика.")):
            with self.subTest(status=status), \
                    patch.object(brain, "parse_meal", return_value={"kind": "chat", "reply": "Принял.", "meal": None, "developer": True}) as model, \
                    patch.object(developer_inbox, "forward", return_value=status) as forward, patch.object(brain, "tele") as tele:
                result = self.server.process_custom_meal(self.payload)
                self.assertEqual(result["message"], "Принял.\n" + ack)
                forward.assert_called_once_with(self.server.cwd, self.payload["text"], "", [], ["wake"])
                tele.assert_any_call("developer_request", ok=status == "sent", flags={"status": status})
                schema = model.call_args.kwargs["schema"]
                self.assertEqual(schema["properties"]["developer"], {"type": "boolean"})
                self.assertNotIn("developer", schema["required"])
                self.assertIn(brain.DEVELOPER_RULES, model.call_args.kwargs["system"])

    def test_developer_flag_is_independent_of_meal_kind_and_ack_reaches_draft(self):
        meal = {"date": "2026-11-27", "meal": "lunch", "notes": "оценка", "items": [
            {"query": "Рис", "source": "user", "grams": "100", "grams_source": "estimate",
             "nutrition": {"basis_grams": "100", "calories": "100", "protein": "2", "fat": "0", "carbs": "23"}}]}
        with patch.object(brain, "parse_meal", return_value={"kind": "meal", "meal": meal, "developer": True}), \
                patch.object(developer_inbox, "forward", return_value="sent"):
            result = self.server.process_custom_meal(self.payload)
        self.assertTrue(result["confirm"])
        self.assertIn("Передал твоё сообщение разработчику.", result["message"])
        self.assertIn("Передал твоё сообщение разработчику.", self.server.threads.draft(self.server.threads.open_thread())["notes"])

    def test_transcript_and_new_images_are_forwarded_without_the_placeholder(self):
        image = {"media_type": "image/png", "data": base64.b64encode(b"png").decode()}
        voice = {"data": base64.b64encode(b"OggSfixture").decode()}
        payload = {**self.payload, "text": brain.VOICE_PLACEHOLDER, "images": [image], "voice": voice}
        with patch.object(brain, "parse_meal", return_value={"kind": "chat", "developer": True}), \
                patch.object(brain, "transcribe", return_value="исправь это") as transcribe, \
                patch.object(developer_inbox, "forward", return_value="sent") as forward:
            result = self.server.process_custom_meal(payload)
        transcribe.assert_called_once()
        forward.assert_called_once_with(self.server.cwd, "", "исправь это", [{"type": "base64", **image}], ["wake"])
        self.assertIn("Передал твоё сообщение разработчику.", result["message"])

    def test_only_true_with_new_content_forwards(self):
        for flag, text in ((False, "bug"), (1, "bug"), ("true", "bug"), (True, "")):
            with self.subTest(flag=flag, text=text), \
                    patch.object(brain, "parse_meal", return_value={"kind": "chat", "developer": flag}), \
                    patch.object(developer_inbox, "forward") as forward:
                self.server.process_custom_meal({**self.payload, "text": text})
                forward.assert_not_called()


if __name__ == "__main__":
    unittest.main()
