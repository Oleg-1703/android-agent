"""weekly_report statistics, alerts and dedupe on a synthetic workspace. No model, no Telegram."""
import contextlib
import io
import json
import sqlite3
import tempfile
import time
import unittest
from pathlib import Path

import telemetry
import weekly_report as wr

NOW = time.time()
H = 3600


def ev(type, ago_h, **fields):
    return {"ts": NOW - ago_h * H, "src": fields.pop("src", "pc"), "type": type, **fields}


def synthetic(workspace: Path):
    events = []
    for i in range(10):
        req = telemetry.request_hash("r%d" % i)
        events.append(ev("request_received", 100 - i * 8, req=req, n={"text": 1, "voice": 0, "photo": i % 3}))
        events.append(ev("request_outcome", 100 - i * 8, req=req, ok=True, ms=8000 + i * 1000, flags={"outcome": "prepared" if i % 2 else "draft"}))
    events.append(ev("request_outcome", 30, req="deadbeef", ok=False, ms=500, err_class="plan_invalid", flags={"outcome": "failed"}))
    events += [ev("model_call", 20, ok=False, ms=900, err_class="quota", flags={"purpose": "parse"}),
               ev("model_call", 19, ok=True, ms=12000, flags={"purpose": "parse"}),
               ev("tunnel_restart", 40), ev("tunnel_url_change", 40, ok=True),
               ev("a11y_off", 12, src="phone"), ev("a11y_on", 10, src="phone"), ev("a11y_off", 2, src="phone")]
    for i in range(4):
        events.append(ev("diary_scan", 5 - i, src="phone", ok=False, err_class="DiaryReadFailure", n={"rows": 20}, flags={"step": "scan2"}))
    events.append(ev("diary_scan", 6, src="phone", ok=True, n={"rows": 18}, flags={"step": "done"}))
    events += [ev("watchdog_tick", 30), ev("watchdog_tick", 29.9), ev("watchdog_tick", 20), ev("watchdog_tick", 19.9)]
    events.append(ev("weird_old", 24 * 20, err_class="OldKnown"))
    events.append(ev("weird_new", 3, err_class="BrandNew", ok=False))
    telemetry.Telemetry(workspace / "events.jsonl").path.write_text("\n".join(json.dumps(e) for e in events) + "\n", encoding="utf-8")
    db = sqlite3.connect(workspace / "meal-jobs.sqlite")
    db.execute("CREATE TABLE jobs (id TEXT PRIMARY KEY, fingerprint TEXT, state TEXT, result TEXT)")
    for i in range(10):
        db.execute("INSERT INTO jobs VALUES (?,?,?,?)", ("r%d" % i, "f", "complete" if i < 7 else "failed" if i < 9 else "uncertain", "{}"))
    db.commit()
    db.close()
    db = sqlite3.connect(workspace / "meal-thread.sqlite")
    db.execute("CREATE TABLE threads (id INTEGER PRIMARY KEY AUTOINCREMENT, opened REAL, closed REAL, state TEXT, draft TEXT, job TEXT, meal TEXT)")
    db.execute("CREATE TABLE turns (id INTEGER PRIMARY KEY AUTOINCREMENT, thread INTEGER, role TEXT, ts REAL, text TEXT, voice TEXT, images TEXT)")
    for t in range(5):
        db.execute("INSERT INTO threads (opened, closed, state, meal) VALUES (?,?,?,?)",
                   (NOW - (t + 2) * 86400, NOW - (t + 2) * 86400 + 60, "prepared", json.dumps({"meal": "lunch", "items": [{"query": "Творог 5%", "grams": "200"}, {"query": "Хлеб %d" % t}]})))
        for k in range(1 + t % 3):
            db.execute("INSERT INTO turns (thread, role, ts, text, voice, images) VALUES (?,?,?,?,?,?)", (t + 1, "user", NOW, "SECRET TEXT", "", "[]"))
    db.commit()
    db.close()


class ReportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.ws = Path(self.temp.name)
        synthetic(self.ws)

    def tearDown(self):
        self.temp.cleanup()

    def test_weekly_statistics(self):
        stats = wr.collect(self.ws, 7, NOW)
        self.assertEqual(stats["requests"]["total"], 10 - sum(1 for i in range(10) if NOW - (100 - i * 8) * H < NOW - 7 * 86400))
        self.assertEqual(stats["diary_scan"]["total"], 5)
        self.assertEqual(stats["diary_scan"]["failed"], 4)
        self.assertEqual(stats["diary_scan"]["top_step"], ("scan2", 4))
        self.assertEqual(stats["quota_hits"], 1)
        self.assertEqual(stats["tunnel"], {"restarts": 1, "url_changes": 1, "publish_failures": 0})
        self.assertEqual(stats["a11y"]["outages"], 2)
        self.assertTrue(stats["a11y"]["open_now"])
        self.assertEqual(stats["a11y"]["total_minutes"], 120)
        self.assertEqual([g["minutes"] for g in stats["pc_unreachable"]["watchdog_gaps"]], [594])  # ticks at 29.9 h and 20 h ago
        self.assertEqual(len(stats["pc_unreachable"]["watchdog_gaps"]), 1)
        self.assertEqual(stats["final_state"].get("complete", 0) + stats["final_state"].get("failed", 0) + stats["final_state"].get("uncertain", 0), stats["requests"]["total"])
        self.assertIn("BrandNew", stats["new_err_classes"])
        self.assertNotIn("OldKnown", stats["new_err_classes"])
        self.assertEqual(stats["corrections"]["meals"], 5)
        self.assertEqual(stats["corrections"]["total"], 0 + 1 + 2 + 0 + 1)  # user turns per thread: 1,2,3,1,2 minus the first
        self.assertEqual(stats["library_candidates"], [{"food": "Творог 5%", "times": 5}])
        self.assertIsNotNone(stats["median_seconds_to_prepared_or_draft"])

    def test_dry_run_weekly_prints_report_without_model_or_files(self):
        def forbidden(*_):
            raise AssertionError("model or Telegram used in dry-run")
        text = wr.run_weekly(self.ws, NOW, True, send=forbidden, model=forbidden)
        self.assertIn("Недельный отчёт", text)
        self.assertIn("Чтение дневника на телефоне: 4 из 5", text)
        self.assertIn("Творог 5% (5)", text)
        self.assertIn("модель не вызывалась", text)
        self.assertNotIn("SECRET TEXT", text)
        self.assertFalse((self.ws / "reports").exists())

    def test_weekly_with_model_sends_and_saves(self):
        sent = []
        answer = {"ideas": [{"title": "Исправить шаг scan2", "evidence": "4 из 5 сканов", "mark": "checked", "action": "разобрать отпечаток"}],
                  "problems": [{"title": "scan2", "evidence": "x4"}]}
        seen = []
        text = wr.run_weekly(self.ws, NOW, False, send=sent.append, model=lambda block: seen.append(block) or answer)
        self.assertIn("Исправить шаг scan2 [checked]", text)
        self.assertEqual(sent, [text])
        self.assertNotIn("SECRET TEXT", seen[0])
        [report] = (self.ws / "reports").glob("*.md")
        self.assertEqual(report.name, time.strftime("%Y-%m-%d", time.localtime(NOW)) + ".md")

    def test_weekly_model_failure_still_sends_statistics(self):
        sent = []
        def broken(_):
            raise RuntimeError("subscription_unavailable")
        text = wr.run_weekly(self.ws, NOW, False, send=sent.append, model=broken)
        self.assertIn("Идеи не получены", text)
        self.assertIn("Чтение дневника", sent[0])

    def test_daily_alert_sent_once_per_24h(self):
        sent = []
        first = wr.run_daily(self.ws, NOW, False, send=sent.append)
        self.assertTrue(first.startswith("sent"))
        self.assertEqual(len(sent), 1)
        self.assertIn("чтение дневника сорвалось 4", sent[0])
        self.assertIn("BrandNew", sent[0])
        self.assertIn("лимит подписки", sent[0])
        self.assertIn("специальных возможностей", sent[0])
        self.assertEqual(sent[0].count("\n- "), 5)  # one short message, one line per alert
        again = wr.run_daily(self.ws, NOW + 3 * H, False, send=sent.append)
        self.assertTrue(again.startswith("no new alerts"))
        self.assertEqual(len(sent), 1)
        state = json.loads((self.ws / "reports" / "alerts-state.json").read_text())
        state["quota"] = NOW - 25 * H  # only an alert older than 24 h may repeat
        (self.ws / "reports" / "alerts-state.json").write_text(json.dumps(state))
        wr.run_daily(self.ws, NOW + 3 * H, False, send=sent.append)
        self.assertEqual(len(sent), 2)
        self.assertEqual(sent[1].count("\n- "), 1)
        self.assertIn("лимит", sent[1])

    def test_daily_dry_run_sends_nothing_and_keeps_no_state(self):
        text = wr.run_daily(self.ws, NOW, True, send=lambda _: self.fail("sent"))
        self.assertIn("[dry-run] would send", text)
        self.assertFalse((self.ws / "reports").exists())

    def test_daily_quiet_when_below_thresholds(self):
        quiet = Path(tempfile.mkdtemp())
        (quiet / "events.jsonl").write_text(json.dumps(ev("diary_scan", 1, ok=False, flags={"step": "scan"})) + "\n")
        self.assertTrue(wr.run_daily(quiet, NOW, False, send=lambda _: self.fail("sent")).startswith("no new alerts"))

    def test_empty_workspace_does_not_crash(self):
        empty = Path(tempfile.mkdtemp())
        self.assertIn("Статистика за 7 дн.", wr.run_weekly(empty, NOW, True))

    def test_cli_dry_run_prints(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = wr.main(["--workspace", str(self.ws), "--weekly", "--daily", "--dry-run"])
        self.assertEqual(code, 0)
        self.assertIn("Недельный отчёт", out.getvalue())
        self.assertIn("[dry-run] would send", out.getvalue())


if __name__ == "__main__":
    unittest.main()
