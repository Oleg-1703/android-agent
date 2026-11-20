from pathlib import Path
import json
import tempfile
import unittest
from unittest.mock import patch

import brain
from meal_jobs import MealJobs
from nutrition import enrich, exact_facts, table

SNICKERS = {"found": True, "exact_match": True, "product": "Snickers 50.5 g", "basis_grams": "100", "calories": "488",
            "protein": "9", "fat": "24", "carbs": "60", "source_url": "https://www.mars.com/snickers"}


def item(query, grams, source, nutrition=None):
    value = {"query": query, "grams": grams, "source": source}
    if nutrition:
        value["nutrition"] = dict(zip(("basis_grams", "calories", "protein", "fat", "carbs"), nutrition))
    return value


def plan(*items):
    return {"date": "2026-11-20", "meal": "lunch", "items": list(items), "question": "", "notes": "оценка"}


class ExactFacts(unittest.TestCase):
    def test_verified_page_is_accepted(self):
        self.assertEqual(exact_facts(SNICKERS)["nutrition"]["calories"], "488")

    def test_rejects_unverified_variant_or_missing_source(self):
        for change in ({"exact_match": False}, {"found": False}, {"source_url": ""}, {"source_url": "javascript:x"}, {"protein": "-1"}):
            self.assertIsNone(exact_facts({**SNICKERS, **change}))

    def test_rejects_inconsistent_numbers(self):
        self.assertIsNone(exact_facts({**SNICKERS, "calories": "100"}))
        self.assertIsNone(exact_facts({**SNICKERS, "protein": "50", "fat": "40", "carbs": "30"}))


class Enrich(unittest.TestCase):
    def test_only_estimates_are_replaced_and_failures_stay_estimates(self):
        calls = []

        def search(payload, schema, system):
            calls.append(payload["product"])
            if payload["product"] == "Сникерс":
                return SNICKERS
            raise RuntimeError("down")
        meal = plan(item("Сникерс", "26", "estimate", ("100", "1", "1", "1", "1")), item("Блюдо", "100", "estimate", ("100", "1", "1", "1", "1")),
                    item("Котлета", "335", "label", ("100", "200", "15", "10", "10")))
        enrich(meal, "обед сникерс 26 г", search)
        sources = [row["source"] for row in meal["items"]]
        self.assertEqual(sources, ["web", "estimate", "label"])
        self.assertEqual(sorted(calls), ["Блюдо", "Сникерс"])
        self.assertEqual(meal["items"][0]["source_url"], SNICKERS["source_url"])
        self.assertEqual(meal["items"][0]["nutrition"]["fat"], "24")


class Table(unittest.TestCase):
    def test_rows_and_total_use_eaten_grams_over_basis(self):
        meal = plan(item("Котлеты", "335", "label", ("100", "200", "15", "10", "10")),
                    item("Сникерс", "26", "web", ("100", "488", "9", "24", "60")))
        meal["items"][1]["source_url"] = "https://www.mars.com/snickers"
        text = table(meal)
        self.assertIn("1. Котлеты - 335 г: 670 ккал, Б 50.2 / Ж 33.5 / У 33.5 [этикетка]", text)
        self.assertIn("2. Сникерс - 26 г: 127 ккал, Б 2.3 / Ж 6.2 / У 15.6 [сайт www.mars.com]", text)
        self.assertIn("Итого 361 г: 797 ккал, Б 52.6 / Ж 39.7 / У 49.1", text)

    def test_unresolved_estimate_is_flagged_and_existing_is_excluded(self):
        meal = plan(item("Блюдо", "100", "estimate", ("100", "100", "5", "5", "10")),
                    {"query": "Ролл", "source": "existing", "count": "1", "unit": "pieces"})
        text = table(meal)
        self.assertIn("⚠ оценка", text)
        self.assertIn("значения возьму из карточки FatSecret", text)
        self.assertIn("без позиций из карточек FatSecret", text)


class Memory(unittest.TestCase):
    def jobs(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        jobs = MealJobs(Path(directory.name) / "jobs.sqlite", lambda payload: {"status": "complete"})
        self.addCleanup(jobs.close)
        return jobs

    def put(self, jobs, key, state, result, with_plan=True):
        jobs.remember(key, "обед", plan(item("Сникерс", "26", "web", ("100", "488", "9", "24", "60"))) if with_plan else None)
        with jobs.connection() as db:
            db.execute("INSERT INTO jobs VALUES (?,?,?,?)", (key, key, state, json.dumps(result)))

    def test_only_a_failed_job_that_saved_nothing_can_retry(self):
        jobs = self.jobs()
        self.put(jobs, "a" * 32, "failed", {"message": "дневник не прочитан"})
        self.put(jobs, "b" * 32, "failed", {"message": "partial", "receipts": [{"preview": "x"}]})
        self.put(jobs, "c" * 32, "uncertain", {"message": "?"})
        self.put(jobs, "d" * 32, "failed", {"message": "вопрос", "reply": True})
        self.put(jobs, "e" * 32, "failed", {"message": "без плана"}, with_plan=False)
        flags = {row["key"][0]: row["can_retry"] for row in jobs.recent("z" * 32)}
        self.assertEqual(flags, {"a": True, "b": False, "c": False, "d": False, "e": False})

    def test_reply_is_not_labelled_as_an_error(self):
        jobs = self.jobs()
        self.put(jobs, "a" * 32, "failed", {"message": "Что именно вы ели?", "reply": True})
        self.put(jobs, "b" * 32, "failed", {"message": "сбой"})
        self.assertEqual(jobs.get("a" * 32)["message"], "Что именно вы ели?")
        self.assertTrue(jobs.get("b" * 32)["message"].startswith("Последний запрос завершился ошибкой."))


class Retry(unittest.TestCase):
    def row(self, state="failed", can_retry=True):
        return {"key": "k" * 32, "state": state, "text": "обед", "can_retry": can_retry,
                "plan": plan(item("Сникерс", "26", "web", ("100", "488", "9", "24", "60")))}

    def test_failed_plan_is_reused_without_a_new_lookup(self):
        result = brain.BrainServer.retry_plan([self.row()], 1, "2026-11-20")
        self.assertNotIn("status", result)
        self.assertEqual(result["items"][0]["source"], "web")

    def test_never_repeats_saved_or_uncertain_or_running_or_stale(self):
        for row, date in ((self.row("complete"), "2026-11-20"), (self.row("uncertain", False), "2026-11-20"),
                          (self.row("executing", False), "2026-11-20"), (self.row(), "2026-11-21")):
            self.assertTrue(brain.BrainServer.retry_plan([row], 1, date)["reply"])
        self.assertTrue(brain.BrainServer.retry_plan([self.row()], 3, "2026-11-20")["reply"])


class Process(unittest.TestCase):
    def server(self, model):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        server = brain.BrainServer.__new__(brain.BrainServer)
        server.cwd, server.cli = Path(directory.name), "claude"
        server.busy = __import__("threading").Lock()
        server.jobs = MealJobs(Path(directory.name) / "jobs.sqlite", lambda payload: {"status": "complete"})
        self.addCleanup(server.jobs.close)
        server.web_search = lambda payload, schema, system: SNICKERS
        patcher = patch.object(brain, "parse_meal", side_effect=model)
        patcher.start()
        self.addCleanup(patcher.stop)
        return server

    def test_new_meal_gets_web_nutrition_and_a_table_then_retry_reuses_it(self):
        meal = plan(item("Сникерс", "26", "estimate", ("100", "1", "1", "1", "1")))
        answers = [meal, {"action": "retry", "ref": 1, "items": [], "question": "", "date": "2026-11-20", "meal": "lunch"}]
        server = self.server(lambda *args, **kwargs: answers.pop(0))
        first = server.process_custom_meal({"text": "обед сникерс 26 г", "date": "2026-11-20", "target": "phone", "_execution_id": "1" * 32})
        self.assertEqual(first["plan"]["items"][0]["source"], "web")
        self.assertIn("Итого 26 г: 127 ккал", first["plan"]["notes"])
        with server.jobs.connection() as db:
            db.execute("INSERT INTO jobs VALUES (?,?,?,?)", ("1" * 32, "f", "failed", json.dumps({"message": "дневник не прочитан"})))
        second = server.process_custom_meal({"text": "попробуй ещё раз", "date": "2026-11-20", "target": "phone", "_execution_id": "2" * 32})
        self.assertEqual(second["status"], "prepared")
        self.assertEqual(second["plan"]["_execution_id"], "2" * 32)
        self.assertEqual(second["plan"]["items"], first["plan"]["items"])
        with server.jobs.connection() as db:
            db.execute("INSERT INTO jobs VALUES (?,?,?,?)", ("2" * 32, "g", "failed", "{}"))
        self.assertEqual([row["key"] for row in server.jobs.recent("9" * 32)], ["2" * 32])

    def test_question_is_a_reply_not_an_error(self):
        server = self.server(lambda *args, **kwargs: {"question": "Что вы ели?", "items": [], "date": "2026-11-20", "meal": "other"})
        result = server.process_custom_meal({"text": "обед", "date": "2026-11-20", "target": "phone", "_execution_id": "3" * 32})
        self.assertEqual(result, {"status": "failed", "reply": True, "message": "Что вы ели?"})


if __name__ == "__main__":
    unittest.main()
