import tempfile
import unittest
from pathlib import Path

import tunnel_publish as tp

LOG = """2026 INF Requesting new quick Tunnel on trycloudflare.com...
2026 INF +--------------------------------------------+
2026 INF |  https://old-name-one.trycloudflare.com  |
2026 INF https://api.trycloudflare.com/tunnel
2026 INF |  https://new-name-two.trycloudflare.com  |
"""


class Fake:
    def __init__(self, fail_pin=False):
        self.calls, self.fail_pin = [], fail_pin

    def __call__(self, method, payload):
        self.calls.append((method, payload))
        if method == "pinChatMessage" and self.fail_pin:
            raise RuntimeError("pin")
        return {"ok": True, "result": {"message_id": 77}}


class TunnelPublishTest(unittest.TestCase):
    def test_latest_url_skips_api_host_and_takes_last(self):
        self.assertEqual(tp.latest_url(LOG), "https://new-name-two.trycloudflare.com")
        self.assertIsNone(tp.latest_url("nothing here"))

    def test_compose_rejects_foreign_urls(self):
        self.assertEqual(tp.compose("https://a-b1.trycloudflare.com"), "AA-URL https://a-b1.trycloudflare.com")
        for bad in ("http://a.trycloudflare.com", "https://a.evil.com", "https://a.trycloudflare.com.evil.com",
                    "https://a.trycloudflare.com@evil.com", "https://a.trycloudflare.com/x"):
            with self.assertRaises(ValueError):
                tp.compose(bad)

    def test_publish_sends_silent_then_pins_and_skips_unchanged(self):
        with tempfile.TemporaryDirectory() as d:
            state, api = Path(d) / "u.txt", Fake()
            self.assertEqual(tp.publish("https://x1.trycloudflare.com", state, api, chat_id="5"), "published")
            send, pin = api.calls
            self.assertEqual(send[1]["text"], "AA-URL https://x1.trycloudflare.com")
            self.assertTrue(send[1]["disable_notification"])
            self.assertEqual((pin[0], pin[1]["message_id"], pin[1]["disable_notification"]), ("pinChatMessage", 77, True))
            self.assertEqual(tp.publish("https://x1.trycloudflare.com", state, api, chat_id="5"), "unchanged")
            self.assertEqual(len(api.calls), 2)
            self.assertEqual(tp.publish("https://x2.trycloudflare.com", state, api, chat_id="5"), "published")

    def test_failed_pin_does_not_record_state(self):
        with tempfile.TemporaryDirectory() as d:
            state = Path(d) / "u.txt"
            with self.assertRaises(RuntimeError):
                tp.publish("https://x1.trycloudflare.com", state, Fake(fail_pin=True), chat_id="5")
            self.assertFalse(state.exists())

    def test_dry_run_sends_nothing(self):
        with tempfile.TemporaryDirectory() as d:
            api = Fake()
            self.assertEqual(tp.publish("https://x1.trycloudflare.com", Path(d) / "u.txt", api, dry_run=True), "dry-run")
            self.assertEqual(api.calls, [])


if __name__ == "__main__":
    unittest.main()
