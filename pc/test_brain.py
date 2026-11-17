"""Transport and isolation checks. No subscription calls are made by these tests."""
import http.client
import json
import os
from pathlib import Path
import tempfile
import threading
import datetime as dt
import unittest
from unittest.mock import patch

import brain


class BrainTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.server = brain.BrainServer(("127.0.0.1", 0), {"pairing_token": "fixture-token" * 4}, "unused", Path(self.temp.name))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.temp.cleanup()

    def post(self, body, token="fixture-token" * 4, path="/v1/meal"):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=3)
        connection.request("POST", path, json.dumps(body), {"Authorization": "Bearer " + token})
        response = connection.getresponse()
        status, value = response.status, json.loads(response.read())
        connection.close()
        return status, value

    @patch.object(brain, "parse_meal")
    def test_unauthorized_never_runs_model(self, model):
        self.assertEqual(self.post({"text": "x", "date": "2026-11-15"}, token="wrong")[0], 401)
        self.assertEqual(self.post({"text": "x", "date": "2026-11-15"}, token="é" * 32)[0], 401)
        model.assert_not_called()

    @patch.object(brain, "parse_meal")
    def test_invalid_requests_never_run_model(self, model):
        for body in ({}, {"text": "x" * 4001, "date": "2026-11-15"}, {"text": "x", "date": "yesterday"}):
            self.assertEqual(self.post(body)[0], 400)
        model.assert_not_called()

    @patch.object(brain, "parse_meal")
    def test_remote_urls_bad_base64_and_dual_media_never_run_model(self, model):
        invalid = [
            {"image": {"url": "https://example.invalid/private.jpg"}},
            {"image": {"data": "not base64", "media_type": "image/jpeg"}},
            {"voice": {"data": "AAAA"}},
            {"image": {"data": "AAAA"}, "voice": {"data": "AAAA"}},
        ]
        for media in invalid:
            self.assertEqual(self.post({"text": "food", "date": "2026-11-15", **media})[0], 400)
        model.assert_not_called()

    @patch.object(brain, "parse_meal")
    def test_concurrent_request_does_not_start_second_model(self, model):
        with self.server.busy:
            self.assertEqual(self.post({"text": "banana 100g lunch", "date": "2026-11-15"})[0], 429)
        model.assert_not_called()

    @patch.object(brain, "parse_meal", side_effect=RuntimeError("private provider error"))
    def test_provider_error_is_private_and_lock_is_released(self, model):
        status, result = self.post({"text": "banana 100g lunch", "date": "2026-11-15"})
        self.assertEqual(status, 503)
        self.assertNotIn("private", json.dumps(result))
        self.assertTrue(self.server.busy.acquire(timeout=1))
        self.server.busy.release()

    @patch.object(brain, "parse_meal")
    def test_clarification_reuses_food_then_erases_context(self, model):
        model.side_effect = [
            {"question": "Какой приём пищи?", "items": []},
            {"question": "", "items": [{"query": "banana", "grams": "100"}]},
        ]
        _, first = self.post({"text": "banana 100g", "date": "2026-11-15"})
        key = first["conversation"]
        status, second = self.post({"text": "lunch", "date": "2026-11-15", "conversation": key})
        self.assertEqual(status, 200)
        self.assertIn("banana 100g", model.call_args.args[0])
        self.assertIn("lunch", model.call_args.args[0])
        self.assertNotIn(key, self.server.drafts)

    @patch.object(brain, "parse_meal")
    def test_expired_clarification_does_not_guess_new_meal(self, model):
        key = "b8fac2e7-8546-46bc-b55a-600d45c8a461"
        status, _ = self.post({"text": "100g", "date": "2026-11-15", "conversation": key})
        self.assertEqual(status, 410)
        model.assert_not_called()
        self.assertTrue(self.server.busy.acquire(timeout=1))
        self.server.busy.release()

    @patch.object(brain, "parse_meal", return_value={"question": "Какой приём пищи?", "items": []})
    def test_clarification_cannot_silently_move_food_across_midnight(self, model):
        _, first = self.post({"text": "banana100g", "date": "2026-11-15"})
        key = first["conversation"]
        status, _ = self.post({"text": "lunch", "date": "2026-11-16", "conversation": key})
        self.assertEqual(status, 410)
        self.assertNotIn(key, self.server.drafts)
        self.assertEqual(model.call_count, 1)

    @patch.object(brain, "parse_meal", side_effect=brain.SubscriptionLimit("00:40"))
    def test_explicit_subscription_limit_is_cached_without_paid_fallback(self, model):
        body = {"text": "banana 100g lunch", "date": "2026-11-15"}
        for _ in range(2):
            status, response = self.post(body)
            self.assertEqual(status, 429)
            self.assertEqual(response["error"], "subscription_limit")
            self.assertEqual(response["reset"], "00:40")
        self.assertEqual(model.call_count, 1)

    def test_reset_boundary_does_not_create_a_five_hour_local_lock(self):
        just_after = dt.datetime(2026, 11, 16, 0, 40, 3)
        remaining = brain.quota_retry_delay("00:40", just_after)
        self.assertGreater(remaining, 0)
        self.assertLess(remaining, 120)
        self.assertEqual(brain.quota_retry_delay("00:40", dt.datetime(2026, 11, 15, 23, 55)), 2700)

    @patch.object(brain.subprocess, "run")
    def test_cli_has_no_tools_no_persistence_and_no_paid_auth(self, run):
        run.return_value.returncode = 0
        run.return_value.stdout = json.dumps({"structured_output": {"question": "Укажи вес"}})
        with patch.dict(os.environ, {"ANTHROPIC_API_KEY": "private", "OPENAI_API_KEY": "private"}):
            brain.parse_meal("banana", "2026-11-15", "claude", Path(self.temp.name))
        command = run.call_args.args[0]
        self.assertEqual(command[command.index("--tools") + 1], "")
        for flag in ("--safe-mode", "--restricted", "--strict-mcp-config", "--no-session-persistence"):
            self.assertIn(flag, command)
        self.assertNotIn("ANTHROPIC_API_KEY", run.call_args.kwargs["env"])
        self.assertNotIn("OPENAI_API_KEY", run.call_args.kwargs["env"])
        self.assertNotIn("shell", run.call_args.kwargs)


if __name__ == "__main__":
    unittest.main()
