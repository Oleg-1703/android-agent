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
        meal = plan(grams_item("Сникерс", "26", "estimate", "stated", ("100", "1", "1", "1", "1")))
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


def grams_item(query, grams, source, grams_source, nutrition=("100", "200", "10", "10", "10")):
    return {**item(query, grams, source, nutrition), "grams_source": grams_source}


class Confirmation(unittest.TestCase):
    DATE = "2026-11-20"

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.server = server = brain.BrainServer.__new__(brain.BrainServer)
        server.cwd, server.cli = Path(directory.name), "claude"
        server.busy = __import__("threading").Lock()
        server.jobs = MealJobs(Path(directory.name) / "jobs.sqlite", lambda payload: {"status": "complete"})
        self.addCleanup(server.jobs.close)
        self.searches, self.contexts, self.answers, self.counter = [], [], [], 0

        def web(payload, schema, system):
            self.searches.append(payload["product"])
            return SNICKERS
        server.web_search = web

        def model(text, date, cli, cwd, **kwargs):
            self.contexts.append(kwargs.get("context"))
            return self.answers.pop(0)
        patcher = patch.object(brain, "parse_meal", side_effect=model)
        patcher.start()
        self.addCleanup(patcher.stop)

    def send(self, text, **extra):
        self.counter += 1
        key = f"{self.counter:032x}"
        result = self.server.process_custom_meal({"text": text, "date": self.DATE, "target": "phone", "_execution_id": key, **extra})
        with self.server.jobs.connection() as db:
            db.execute("INSERT OR REPLACE INTO jobs VALUES (?,?,?,?)", (key, key, result["status"], json.dumps(result, ensure_ascii=False)))
        return result

    def photo_meal(self):
        return plan(grams_item("Курица", "150", "estimate", "estimate", ("100", "165", "31", "3.6", "0")),
                    grams_item("Картошка", "200", "estimate", "estimate", ("100", "80", "2", "0.1", "17")))

    def draft(self):
        self.answers.append(self.photo_meal())
        result = self.send("обед")
        self.assertTrue(result["confirm"] and result["reply"])
        return result

    def test_exact_plan_is_prepared_without_confirmation(self):
        self.answers.append(plan(grams_item("Котлеты", "335", "label", "stated", ("100", "200", "15", "10", "10"))))
        result = self.send("котлеты 335 г по этикетке")
        self.assertEqual(result["status"], "prepared")
        self.assertNotIn("confirm", result)

    def test_estimate_is_shown_with_table_and_not_prepared(self):
        result = self.draft()
        self.assertEqual(result["status"], "failed")
        self.assertIn("1. Курица - 150 г", result["message"])
        self.assertIn("Итого 350 г", result["message"])
        self.assertIn("✅ Записать", result["message"])
        self.assertEqual(self.server.jobs.recent("z" * 32)[0]["awaiting"], True)
        self.assertEqual(self.server.jobs.recent("z" * 32)[0]["plan"]["items"][1]["query"], "Картошка")

    def test_ok_and_button_prepare_the_same_plan_without_a_model_call(self):
        for word in ("ок", "✅ Записать", "Всё верно!"):
            self.setUp()
            draft = self.draft()
            before = len(self.contexts)
            result = self.send(word)
            self.assertEqual(result["status"], "prepared", word)
            self.assertEqual(len(self.contexts), before)
            self.assertEqual([row["query"] for row in result["plan"]["items"]], ["Курица", "Картошка"])
            self.assertEqual(result["plan"]["_execution_id"], f"{self.counter:032x}")
            self.assertEqual(result["plan"]["notes"].split("\n\n")[0], draft["message"].split("\n\n")[0])

    def test_confirm_word_with_photo_goes_to_the_model(self):
        self.draft()
        self.answers.append(plan(grams_item("Пицца", "100", "label", "stated")))
        image = {"media_type": "image/jpeg", "data": "x"}
        self.assertEqual(self.send("ок", images=[image])["status"], "prepared")
        self.assertEqual(len(self.contexts), 2)

    def test_second_ok_does_not_prepare_again(self):
        self.draft()
        self.assertEqual(self.send("ок")["status"], "prepared")
        self.answers.append({"question": "", "items": [], "date": self.DATE, "meal": "other", "action": "answer", "reply": "Уже записано."})
        again = self.send("ок")
        self.assertEqual(again["status"], "failed")
        self.assertEqual(len(self.contexts), 2)

    def test_retry_of_a_draft_says_it_was_not_written(self):
        self.draft()
        row = self.server.jobs.recent("z" * 32)[0]
        text = brain.BrainServer.retry_plan([row], 1, self.DATE)["message"]
        self.assertIn("не записан", text)
        self.assertIn("✅ Записать", text)
        self.assertNotIn("частично", text)
        row["minutes_ago"] = 200
        self.assertNotIn("✅ Записать", brain.BrainServer.retry_plan([row], 1, self.DATE)["message"])
        self.assertNotIn("частично", brain.BrainServer.retry_plan([row, row], 2, self.DATE)["message"])

    def test_ok_without_a_draft_falls_through_to_the_model(self):
        self.answers.append(plan(grams_item("Яблоко", "100", "user", "stated")))
        self.assertEqual(self.send("ок")["status"], "prepared")
        self.assertEqual(len(self.contexts), 1)

    def test_old_draft_is_not_confirmed(self):
        self.draft()
        with self.server.jobs.connection() as db:
            db.execute("UPDATE memory SET created=created-?", (121 * 60,))
        self.answers.append({"question": "Что записать?", "items": [], "date": self.DATE, "meal": "other"})
        self.assertEqual(self.send("ок")["message"], "Что записать?")
        self.assertEqual(len(self.contexts), 2)
        self.assertFalse(any(row.get("awaiting_confirmation") for row in self.contexts[1]["recent_requests"]))

    def test_correction_sees_the_draft_and_is_shown_again(self):
        self.draft()
        fixed = plan(grams_item("Курица", "100", "estimate", "stated", ("100", "165", "31", "3.6", "0")),
                     grams_item("Картошка", "200", "estimate", "estimate", ("100", "80", "2", "0.1", "17")))
        self.answers.append(fixed)
        result = self.send("курицы было меньше, 100 грамм")
        context = self.contexts[1]["recent_requests"][0]
        self.assertTrue(context["awaiting_confirmation"])
        self.assertEqual(context["meal"], "lunch")
        self.assertEqual([row["query"] for row in context["draft_items"]], ["Курица", "Картошка"])
        self.assertEqual(context["draft_items"][0]["grams_source"], "estimate")
        self.assertTrue(result["confirm"])
        self.assertIn("1. Курица - 100 г", result["message"])
        self.assertEqual(self.send("ок")["plan"]["items"][0]["grams"], "100")

    def test_fully_stated_correction_is_prepared_at_once(self):
        self.draft()
        self.answers.append(plan(grams_item("Курица", "100", "estimate", "stated"), grams_item("Картошка", "250", "user", "stated")))
        self.assertEqual(self.send("курицы 100, картошки 250")["status"], "prepared")

    def test_unchanged_web_item_is_not_searched_again(self):
        self.answers.append(plan(grams_item("Сникерс", "26", "estimate", "estimate", ("100", "1", "1", "1", "1")),
                                 grams_item("Картошка", "200", "estimate", "estimate", ("100", "80", "2", "0.1", "17"))))
        first = self.send("обед")
        self.assertEqual(self.searches, ["Сникерс", "Картошка"])
        self.assertEqual(first["confirm"], True)
        self.searches.clear()
        self.answers.append(plan(grams_item("сникерс ", "26", "estimate", "estimate", ("100", "1", "1", "1", "1")),
                                 grams_item("Картошка", "300", "user", "stated", ("100", "80", "2", "0.1", "17"))))
        second = self.send("картошки больше, 300")
        self.assertEqual(self.searches, [])
        row = second["message"] if second["status"] == "failed" else second["plan"]["items"][0]
        self.assertEqual(second["status"], "failed")
        self.assertIn("[сайт www.mars.com]", second["message"])

    def test_needs_confirmation_rules(self):
        exact = [grams_item("A", "1", "label", "stated"), grams_item("B", "1", "web", "stated"),
                 {"query": "C", "source": "existing", "count": "1"}, grams_item("D", "1", "user", "stated")]
        self.assertFalse(brain.needs_confirmation(plan(*exact)))
        for bad in (grams_item("E", "1", "estimate", "stated"), grams_item("E", "1", "label", "estimate"), item("G", "1", "user")):
            self.assertTrue(brain.needs_confirmation(plan(*exact, bad)))
        self.assertFalse(brain.needs_confirmation(plan({"query": "F", "source": "saved", "grams_source": "estimate"})))

    def test_ok_after_cancel_is_not_confirmed(self):
        self.draft()
        self.assertTrue(self.server.jobs.cancel_draft())
        self.assertFalse(self.server.jobs.cancel_draft())
        self.answers.append({"question": "Что записать?", "items": [], "date": self.DATE, "meal": "other"})
        self.assertEqual(self.send("ок")["message"], "Что записать?")
        self.assertEqual(len(self.contexts), 2)

    def test_cancel_only_touches_the_newest_awaiting_draft(self):
        self.draft()
        self.assertEqual(self.send("ок")["status"], "prepared")
        self.assertFalse(self.server.jobs.cancel_draft())

    def test_catalog_off_schema_and_prompt_have_no_existing_or_saved(self):
        schema, system = brain.build_schema(False), brain.build_system(False)
        self.assertEqual(schema["properties"]["items"]["items"]["properties"]["source"]["enum"], ["user", "label", "estimate", "barcode"])
        self.assertNotIn("source=existing", system.replace("Never use source=existing or source=saved", ""))
        self.assertIn("grams_source=estimate", system)
        self.assertIn("User statements override all defaults.", system)
        self.assertIn("existing", brain.build_schema(True)["properties"]["items"]["items"]["properties"]["source"]["enum"])
        self.assertIs(brain.build_system(True), brain.SYSTEM)

    def test_catalog_flag_is_passed_to_the_model(self):
        seen = []
        patcher = patch.object(brain, "parse_meal", side_effect=lambda *a, **k: seen.append(k) or plan(grams_item("Плов", "300", "user", "stated")))
        patcher.start()
        self.addCleanup(patcher.stop)
        self.send("плов 300 г")
        self.assertNotIn("existing", seen[0]["schema"]["properties"]["items"]["items"]["properties"]["source"]["enum"])
        self.server.config = {"phone_catalog": True}
        self.send("плов 300 г ещё")
        self.assertIn("existing", seen[1]["schema"]["properties"]["items"]["items"]["properties"]["source"]["enum"])
        self.assertIs(seen[1]["system"], brain.SYSTEM)

    def test_catalog_item_without_grams_is_a_question_when_catalog_is_off(self):
        meal = plan({"query": "Бешбармак с кониной", "source": "existing", "count": "1", "unit": "servings"})
        self.answers.append(meal)
        result = self.send("бешбармак")
        self.assertEqual(result["status"], "failed")
        self.assertTrue(result["reply"])
        self.assertIn("вес", result["message"])
        self.assertNotIn("plan", result)
        self.server.config = {"phone_catalog": True}
        self.answers.append(meal)
        self.assertEqual(self.send("бешбармак 2")["status"], "prepared")

    def test_confirm_words(self):
        for word in ("ок", "ОК.", "✅ Записать", "Окей!", "ok", "да", "Всё верно", "все верно", "подтверждаю", "запиши"):
            self.assertTrue(brain.is_confirm_word(word), word)
        for word in ("ок, но картошки больше", "не надо", "", "записать 200"):
            self.assertFalse(brain.is_confirm_word(word), word)


if __name__ == "__main__":
    unittest.main()
