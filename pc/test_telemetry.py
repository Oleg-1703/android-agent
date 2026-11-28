"""Event log: sanitizer, rotation, failure isolation, /v1/events validation, brain instrumentation."""
import http.client
import json
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

import brain
import telemetry

TOKEN = "fixture-token" * 4


def lines(path):
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines()]


class SanitizerTest(unittest.TestCase):
    def test_drops_text_fields_tokens_and_urls(self):
        event = telemetry.sanitize({
            "type": "request_outcome", "ok": False, "food": "борщ 300 г", "text": "съел борщ", "transcript": "x", "macros": {"kcal": 1},
            "err": "failed 123456789:AAAbbbCCCdddEEEfffGGGhhhIIIjjjKKK at https://api.telegram.org/bot123/getMe?x=1\nnext",
            "n": {"photo": 2, "food": "borscht", "bad key": 1}, "flags": {"target": "phone", "note": "съел борщ", "ok": True, "Bad": 1},
            "ids": ["food_journal_new_body", "borscht 300 g", "x" * 300],
        }, "pc", 1000.0)
        self.assertEqual(set(event) - {"ts"}, {"src", "type", "ok", "err", "n", "flags", "ids"})
        self.assertNotIn("123456789", event["err"])
        self.assertNotIn("telegram.org", event["err"])
        self.assertNotIn("\n", event["err"])
        self.assertEqual(event["n"], {"photo": 2})
        self.assertEqual(event["flags"], {"target": "phone", "ok": True})
        self.assertEqual(event["ids"], ["food_journal_new_body"])
        self.assertNotIn("борщ", json.dumps(event, ensure_ascii=False))

    def test_err_is_capped_and_type_validated(self):
        self.assertEqual(len(telemetry.sanitize({"type": "x_y", "err": "ab " * 400})["err"]), 200)
        for bad in ("", "Bad", "a b", "x" * 60, None, 5):
            self.assertIsNone(telemetry.sanitize({"type": bad}))

    def test_request_hash_is_short_and_stable(self):
        self.assertEqual(telemetry.request_hash("a" * 64), telemetry.request_hash("a" * 64))
        self.assertEqual(len(telemetry.request_hash("a" * 64)), 8)
        self.assertNotIn("a" * 64, telemetry.request_hash("a" * 64))


class FileTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "events.jsonl"

    def tearDown(self):
        self.temp.cleanup()

    def test_appends_json_lines_from_many_threads(self):
        tele = telemetry.Telemetry(self.path)
        workers = [threading.Thread(target=lambda i=i: [tele.emit("t_" + "x", req="r%d" % i, n={"i": j}) for j in range(50)]) for i in range(8)]
        [w.start() for w in workers]
        [w.join() for w in workers]
        self.assertEqual(len(lines(self.path)), 400)

    def test_rotation_drops_old_days_then_oldest_to_the_cap(self):
        clock = [10 * 86400 * 5.0]
        tele = telemetry.Telemetry(self.path, max_bytes=4000, keep_days=30, clock=lambda: clock[0])
        for i in range(30):
            tele.write({"ts": clock[0] - 40 * 86400, "src": "pc", "type": "old"})
        for i in range(200):
            clock[0] += 1
            tele.emit("fresh", n={"i": i})
        events = lines(self.path)
        self.assertFalse([e for e in events if e["type"] == "old"])
        self.assertLessEqual(self.path.stat().st_size, 4000)
        self.assertEqual(events[-1]["n"]["i"], 199)  # the newest survive

    def test_maintain_prunes_events_older_than_keep_days(self):
        clock = [50 * 86400.0]
        tele = telemetry.Telemetry(self.path, keep_days=30, clock=lambda: clock[0])
        tele.write({"ts": clock[0] - 31 * 86400, "src": "pc", "type": "old"})
        tele.write({"ts": clock[0] - 1 * 86400, "src": "pc", "type": "new"})
        telemetry.Telemetry(self.path, keep_days=30, clock=lambda: clock[0]).maintain()
        self.assertEqual([e["type"] for e in lines(self.path)], ["new"])

    def test_failure_never_raises(self):
        tele = telemetry.Telemetry(Path(self.temp.name) / "missing" / "events.jsonl")
        tele.emit("anything")  # folder missing
        tele.emit("anything", n=object(), flags=object(), ids=5)  # nonsense arguments
        directory = Path(self.temp.name) / "isdir"
        directory.mkdir()
        telemetry.Telemetry(directory).emit("anything")  # path is a directory
        telemetry.emit("no_target_configured")

    def test_stderr_note_at_most_once_a_minute(self):
        directory = Path(self.temp.name) / "isdir"
        directory.mkdir()
        tele = telemetry.Telemetry(directory)
        with patch("sys.stderr") as err:
            for _ in range(5):
                tele.emit("x_y")
        self.assertEqual(err.write.call_count, 2)  # one print = text + newline


class PhoneBatchTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.tele = telemetry.Telemetry(Path(self.temp.name) / "events.jsonl")
        self.phone = telemetry.PhoneEvents(self.tele)

    def tearDown(self):
        self.temp.cleanup()

    def test_unknown_fields_dropped_and_src_forced(self):
        now = time.time()
        result = self.phone.accept({"events": [{"eid": "abc123", "ts": int(now * 1000), "src": "pc", "type": "stage", "ok": True, "ms": 5,
                                                "text": "борщ", "photo": "AAAA", "flags": {"stage": "save"}}]})
        self.assertEqual(result, {"accepted": 1, "dropped": 0})
        [event] = lines(self.tele.path)
        self.assertEqual(event["src"], "phone")
        self.assertAlmostEqual(event["ts"], now, delta=2)
        self.assertEqual(set(event), {"ts", "src", "type", "ok", "ms", "flags"})

    def test_resent_event_is_written_once(self):
        batch = {"events": [{"eid": "same01", "type": "stage"}]}
        self.phone.accept(batch)
        self.assertEqual(self.phone.accept(batch)["accepted"], 1)
        self.assertEqual(len(lines(self.tele.path)), 1)

    def test_batch_shape_and_cap(self):
        for bad in (None, [], {}, {"events": []}, {"events": "x"}, {"events": [{"type": "x_y"}] * 101}):
            with self.assertRaises(ValueError):
                self.phone.accept(bad)
        self.assertEqual(self.phone.accept({"events": [{"type": "Bad"}, "x", {"type": "ok_one"}]}), {"accepted": 1, "dropped": 2})
        self.assertEqual(len(self.phone.accept({"events": [{"type": "x_y"}] * 100}) and lines(self.tele.path)), 101)

    def test_rate_limit(self):
        phone = telemetry.PhoneEvents(self.tele, telemetry.RateLimit(requests=2, events=1000))
        phone.accept({"events": [{"type": "x_y"}]})
        phone.accept({"events": [{"type": "x_y"}]})
        with self.assertRaises(PermissionError):
            phone.accept({"events": [{"type": "x_y"}]})
        phone = telemetry.PhoneEvents(self.tele, telemetry.RateLimit(requests=50, events=5))
        with self.assertRaises(PermissionError):
            phone.accept({"events": [{"type": "x_y"}] * 6})


class EndpointTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.server = brain.BrainServer(("127.0.0.1", 0), {"pairing_token": TOKEN}, "unused", Path(self.temp.name))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.temp.cleanup()

    def post(self, body, token=TOKEN, raw=None):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=3)
        data = raw if raw is not None else json.dumps(body)
        connection.request("POST", "/v1/events", data, {"Authorization": "Bearer " + token})
        response = connection.getresponse()
        status, value = response.status, json.loads(response.read())
        connection.close()
        return status, value

    def test_auth_required_and_nothing_written(self):
        self.assertEqual(self.post({"events": [{"type": "x_y"}]}, token="wrong")[0], 401)
        self.assertFalse((Path(self.temp.name) / "events.jsonl").exists() and [e for e in lines(Path(self.temp.name) / "events.jsonl") if e["src"] == "phone"])

    def test_valid_batch_is_stored_and_invalid_rejected(self):
        status, value = self.post({"events": [{"type": "diary_scan", "ok": False, "flags": {"step": "scan"}, "ids": ["food_journal_new_body"], "food": "x"}]})
        self.assertEqual((status, value), (200, {"accepted": 1, "dropped": 0}))
        event = [e for e in lines(Path(self.temp.name) / "events.jsonl") if e["src"] == "phone"][0]
        self.assertEqual(event["ids"], ["food_journal_new_body"])
        self.assertNotIn("food", event)
        self.assertEqual(self.post({"events": []})[0], 400)
        self.assertEqual(self.post(None, raw="not json")[0], 400)
        self.assertEqual(self.post({"events": [{"type": "x_y"}] * 101})[0], 400)
        self.assertEqual(self.post(None, raw=json.dumps({"events": [{"type": "x_y", "err": "a" * 200000}]}))[0], 400)  # body cap

    def test_rate_limited_batches_get_429(self):
        self.server.phone_events.limit = telemetry.RateLimit(requests=1, events=100)
        self.assertEqual(self.post({"events": [{"type": "x_y"}]})[0], 200)
        self.assertEqual(self.post({"events": [{"type": "x_y"}]})[0], 429)


class BrainInstrumentationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.server = brain.BrainServer(("127.0.0.1", 0), {"pairing_token": TOKEN}, "unused", Path(self.temp.name))
        self.path = Path(self.temp.name) / "events.jsonl"

    def tearDown(self):
        self.server.server_close()
        self.temp.cleanup()

    def events(self, kind):
        return [e for e in lines(self.path) if e["type"] == kind]

    def test_model_call_and_outcome_events_carry_no_text(self):
        answer = {"kind": "chat", "reply": "Жду фото.", "meal": None}
        with patch.object(brain, "_parse_meal", return_value=answer):
            result = self.server.process_custom_meal({"text": "борщ секретный 300 г", "date": time.strftime("%Y-%m-%d"), "_execution_id": "a" * 64})
        self.assertTrue(result["reply"])
        [call] = self.events("model_call")
        [outcome] = self.events("request_outcome")
        self.assertEqual((call["ok"], call["flags"]["purpose"]), (True, "parse"))
        self.assertEqual(outcome["flags"]["outcome"], "reply")
        self.assertEqual(outcome["req"], telemetry.request_hash("a" * 64))
        self.assertIn("thread", outcome)
        self.assertNotIn("секретный", self.path.read_text(encoding="utf-8"))
        self.assertNotIn("a" * 64, self.path.read_text(encoding="utf-8"))

    def test_quota_is_classified(self):
        with patch.object(brain, "_parse_meal", side_effect=brain.SubscriptionLimit("10:00")):
            with self.assertRaises(ValueError):
                self.server.process_custom_meal({"text": "x", "date": time.strftime("%Y-%m-%d"), "_execution_id": "b" * 64})
        self.assertEqual(self.events("model_call")[0]["err_class"], "quota")
        self.assertEqual(self.events("request_outcome")[0]["err_class"], "quota")

    def test_unexpected_error_is_uncertain_and_logged_by_class(self):
        with patch.object(brain, "_parse_meal", side_effect=OSError("disk gone")):
            with self.assertRaises(ValueError):
                self.server.process_custom_meal({"text": "x", "date": time.strftime("%Y-%m-%d"), "_execution_id": "c" * 64})
        self.assertEqual(self.events("model_call")[0]["err_class"], "OSError")
        self.assertEqual(self.events("request_outcome")[0]["err_class"], "OSError")

    def test_telemetry_failure_does_not_break_a_request(self):
        answer = {"kind": "chat", "reply": "ok", "meal": None}
        self.path.unlink(missing_ok=True)
        self.path.mkdir()  # the log path is a directory: every write fails
        with patch.object(brain, "_parse_meal", return_value=answer):
            result = self.server.process_custom_meal({"text": "x", "date": time.strftime("%Y-%m-%d"), "_execution_id": "d" * 64})
        self.assertTrue(result["reply"])


if __name__ == "__main__":
    unittest.main()
