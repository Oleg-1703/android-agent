import base64
from pathlib import Path
import json
import os
import tempfile
import threading
import unittest
from unittest.mock import patch

import brain
from food_library import FoodLibrary
from meal_jobs import MealJobs
from meal_thread import MealThreads
import dialog_runner
from nutrition import enrich, exact_facts, table, table_rows

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


class TableRows(unittest.TestCase):
    def test_rows_total_and_rounding_match_the_plain_table(self):
        meal = plan(item("Котлеты", "335", "label", ("100", "200", "15", "10", "10")),
                    item("Сникерс", "26", "estimate", ("100", "488", "9", "24", "60")),
                    {"query": "Ролл", "source": "existing", "count": "1", "unit": "pieces"})
        data = table_rows(meal)
        self.assertEqual(data["rows"][0], {"name": "Котлеты", "grams": "335", "kcal": 670, "protein": 50.2, "fat": 33.5,
                                           "carbs": 33.5, "estimate": False, "catalog": False})
        self.assertTrue(data["rows"][1]["estimate"])
        self.assertEqual(data["rows"][2], {"name": "Ролл", "grams": None, "kcal": None, "protein": None, "fat": None,
                                           "carbs": None, "estimate": False, "catalog": True})
        self.assertEqual(data["skipped_catalog"], 1)
        self.assertEqual(data["total"], {"grams": "361", "kcal": 797, "protein": 52.6, "fat": 39.7, "carbs": 49.1})
        self.assertIn("Итого 361 г: 797 ккал, Б 52.6 / Ж 39.7 / У 49.1", table(meal))
        json.dumps(data)


def grams_item(query, grams, source, grams_source, nutrition=("100", "200", "10", "10", "10")):
    return {**item(query, grams, source, nutrition), "grams_source": grams_source}


def meal_answer(*items, kind="meal", reply=""):
    return {"kind": kind, "reply": reply, "meal": {"date": "2026-11-20", "meal": "lunch", "items": list(items), "notes": "оценка"}}


PNG = base64.b64encode(dialog_runner.make_png("1")).decode()


def image():
    return {"media_type": "image/png", "data": PNG}


class Store(unittest.TestCase):
    def store(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.dir = Path(directory.name)
        return MealThreads(self.dir / "t.sqlite", self.dir / "thread-images")

    def test_turns_keep_order_images_and_draft(self):
        threads = self.store()
        thread = threads.open_thread(1000)
        threads.add_turn(thread, "user", "обед", "", [image(), image()], now=1001)
        threads.add_turn(thread, "bot", "черновик", now=1002)
        threads.set_draft(thread, {"items": []})
        self.assertEqual(threads.open_thread(1100), thread)
        turns = threads.turns(thread)
        self.assertEqual([(t["role"], len(t["images"])) for t in turns], [("user", 2), ("bot", 0)])
        self.assertEqual(turns[0]["images"][0]["data"], PNG)
        self.assertEqual(len(list((self.dir / "thread-images").iterdir())), 2)
        self.assertEqual(threads.draft(thread), {"items": []})

    def test_closing_deletes_images_and_the_draft(self):
        threads = self.store()
        thread = threads.open_thread(1000)
        threads.add_turn(thread, "user", "обед", "", [image()], now=1001)
        threads.set_draft(thread, {"items": []})
        threads.close(thread, "prepared", now=1002, job="j", meal={"meal": "lunch", "items": []})
        self.assertEqual(list((self.dir / "thread-images").iterdir()), [])
        self.assertIsNone(threads.draft(thread))
        self.assertNotEqual(threads.open_thread(1003), thread)

    def test_cancel_closes_and_reports_a_waiting_draft(self):
        threads = self.store()
        self.assertFalse(threads.cancel(1000))
        thread = threads.open_thread(1000)
        threads.add_turn(thread, "user", "обед", "", [image()], now=1001)
        self.assertFalse(threads.cancel(1002))
        thread = threads.open_thread(1003)
        threads.set_draft(thread, {"items": []})
        self.assertTrue(threads.cancel(1004))
        self.assertEqual(list((self.dir / "thread-images").iterdir()), [])
        self.assertIsNone(threads.draft(threads.open_thread(1005)))

    def test_idle_thread_expires_after_three_hours(self):
        threads = self.store()
        thread = threads.open_thread(1000)
        threads.add_turn(thread, "user", "обед", "", [image()], now=1000)
        self.assertEqual(threads.open_thread(1000 + 3 * 3600), thread)
        fresh = threads.open_thread(1000 + 3 * 3600 + 1)
        self.assertNotEqual(fresh, thread)
        self.assertEqual(threads.turns(fresh), [])
        self.assertEqual(list((self.dir / "thread-images").iterdir()), [])

    def test_cleanup_removes_images_after_24_hours_and_stray_files(self):
        threads = self.store()
        thread = threads.open_thread(1000)
        threads.add_turn(thread, "user", "обед", "", [image()], now=1000)
        stray = self.dir / "thread-images" / "t9-old.png"
        stray.write_bytes(b"x")
        os.utime(stray, (1, 1))
        threads.cleanup(1000 + 24 * 3600 + 1)
        self.assertEqual(list((self.dir / "thread-images").iterdir()), [])
        self.assertEqual(threads.turns(thread)[0]["images"], [])

    def test_closed_meals_resolve_the_job_state_lazily(self):
        threads = self.store()
        for number, job in enumerate(("w", "u", "f", "p"), 1):
            thread = threads.open_thread(1000 * number)
            threads.close(thread, "prepared", now=1000 * number + 1, job=job, meal={"meal": "lunch", "items": [{"query": job}]})
        states = {"w": "complete", "u": "uncertain", "f": "failed", "p": "executing"}
        rows = threads.closed_meals(5000, hours=6, job_state=states.get)
        self.assertEqual([(row["items"][0]["query"], row["state"]) for row in rows], [("p", "pending"), ("u", "uncertain"), ("w", "written")])
        self.assertEqual(threads.closed_meals(5000 + 7 * 3600, hours=6, job_state=states.get), [])


class Prompt(unittest.TestCase):
    def turn(self, role, text, count=0, ts=1000):
        return {"role": role, "ts": ts, "text": text, "voice": "", "images": [image() for _ in range(count)]}

    def test_dialog_order_voice_draft_and_closed_meals(self):
        closed = [{"closed": 1, "meal": "lunch", "state": "written", "items": [{"query": "Суп", "grams": "300"}]}]
        voice = {**self.turn("user", "", 0), "voice": "курицы 150"}
        prompt, images = brain.build_prompt("2026-11-20", 2000, closed, [self.turn("user", "обед", 1), self.turn("bot", "Черновик?"), voice],
                                            {"date": "2026-11-20", "meal": "lunch", "items": [{"query": "Курица"}], "_execution_id": "k"},
                                            self.turn("user", "ок, но без соли"))
        self.assertEqual(len(images), 1)
        self.assertIn("УЖЕ записаны", prompt)
        self.assertIn("Суп 300 г", prompt)
        self.assertLess(prompt.index("Пользователь: обед [фото №1]"), prompt.index("Бот (ты): Черновик?"))
        self.assertLess(prompt.index("Черновик?"), prompt.index("[голос, расшифровка: курицы 150]"))
        self.assertIn('"Курица"', prompt)
        self.assertNotIn("_execution_id", prompt)
        self.assertTrue(prompt.rstrip().endswith("Пользователь: ок, но без соли"))

    def test_only_the_newest_ten_images_are_attached_and_numbered(self):
        prompt, images = brain.build_prompt("2026-11-20", 2000, [], [self.turn("user", "первые", 7), self.turn("user", "ещё", 5)], None,
                                            self.turn("user", "вот", 1))
        self.assertEqual(len(images), 10)
        self.assertEqual(prompt.count("не приложено"), 3)
        self.assertIn("фото №10", prompt)
        self.assertNotIn("фото №11", prompt)
        self.assertIn("Текущий черновик: нет.", prompt)

    def test_duplicate_needs_same_type_half_the_items_and_three_hours(self):
        plan_ = plan(item("Курица", "1", "label"), item("Рис", "1", "label"))
        row = {"closed": 1000, "meal": "lunch", "state": "written", "items": [{"query": "курица!"}]}
        self.assertIs(brain.duplicate_of(plan_, [row], 1000 + 3600), row)
        self.assertIsNone(brain.duplicate_of(plan_, [row], 1000 + 3 * 3600 + 1))
        self.assertIsNone(brain.duplicate_of(plan_, [{**row, "meal": "dinner"}], 1100))
        self.assertIsNone(brain.duplicate_of(plan(item("А", "1", "label"), item("Б", "1", "label"), item("В", "1", "label")), [row], 1100))


class ServerFlow(unittest.TestCase):
    DATE = "2026-11-20"

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.server = server = brain.BrainServer.__new__(brain.BrainServer)
        server.cwd, server.cli = Path(directory.name), "claude"
        server.busy = threading.Lock()
        server.jobs = MealJobs(Path(directory.name) / "jobs.sqlite", lambda payload: {"status": "complete"})
        server.threads = MealThreads(Path(directory.name) / "t.sqlite", Path(directory.name) / "thread-images")
        server.library = FoodLibrary(Path(directory.name) / "library.sqlite")
        self.addCleanup(server.jobs.close)
        self.searches, self.calls, self.answers, self.counter = [], [], [], 0

        def web(payload, schema, system):
            self.searches.append(payload["product"])
            return SNICKERS
        server.web_search = web

        def model(prompt, date, cli, cwd, **kwargs):
            self.calls.append({"prompt": prompt, **kwargs})
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

    def estimates(self):
        return meal_answer(grams_item("Курица", "150", "estimate", "estimate", ("100", "165", "31", "3.6", "0")),
                           grams_item("Картошка", "200", "estimate", "estimate", ("100", "80", "2", "0.1", "17")))

    def test_estimate_is_a_draft_with_table_and_the_thread_keeps_it(self):
        self.answers.append(self.estimates())
        result = self.send("обед", images=[image()])
        self.assertTrue(result["confirm"] and result["reply"])
        self.assertIn("1. Курица - 150 г", result["message"])
        self.assertIn("✅ Записать", result["message"])
        thread = self.server.threads.open_thread()
        self.assertEqual(self.server.threads.draft(thread)["items"][1]["query"], "Картошка")
        turns = self.server.threads.turns(thread)
        self.assertEqual([t["role"] for t in turns], ["user", "bot"])
        self.assertEqual(turns[1]["text"], result["message"])
        self.assertEqual(len(turns[0]["images"]), 1)

    def test_rich_table_replaces_the_plain_table_and_ok_prepares_it(self):
        self.answers.append(self.estimates())
        result = self.send("обед", capabilities=["rich_table"])
        self.assertEqual(result["table"]["total"]["grams"], "350")
        self.assertEqual(result["meal"], "lunch")
        self.assertNotIn("Расчёт КБЖУ", result["message"])
        self.assertNotIn("Записать так", result["message"])
        self.assertIn("Итого 350 г", self.server.threads.turns(self.server.threads.open_thread())[1]["text"])
        confirmed = self.send("ок", capabilities=["rich_table"])
        self.assertEqual(confirmed["status"], "prepared")
        self.assertEqual(confirmed["plan"]["table"], result["table"])
        self.assertEqual(len(self.calls), 1)

    def test_plain_table_without_capability(self):
        self.answers.append(self.estimates())
        result = self.send("обед")
        self.assertNotIn("table", result)
        self.assertIn("Расчёт КБЖУ по позициям", result["message"])

    def test_ok_words_and_button_prepare_without_a_model_call(self):
        for word in ("ок", "✅ Записать", "Всё верно!"):
            self.setUp()
            self.answers.append(self.estimates())
            self.send("обед")
            result = self.send(word)
            self.assertEqual(result["status"], "prepared", word)
            self.assertEqual(len(self.calls), 1)
            self.assertEqual(result["plan"]["_execution_id"], f"{self.counter:032x}")
            self.assertEqual(self.server.threads.open_thread() and self.server.threads.draft(self.server.threads.open_thread()), None)

    def test_confirm_word_with_a_photo_goes_to_the_model(self):
        self.answers.append(self.estimates())
        self.send("обед")
        self.answers.append(meal_answer(grams_item("Пицца", "100", "label", "stated")))
        self.assertEqual(self.send("ок", images=[image()])["status"], "prepared")
        self.assertEqual(len(self.calls), 2)

    def test_ok_without_a_draft_goes_to_the_model(self):
        self.answers.append(meal_answer(grams_item("Яблоко", "100", "user", "stated")))
        self.assertEqual(self.send("ок")["status"], "prepared")

    def test_chat_keeps_the_draft_and_empty_reply_gets_a_fallback(self):
        self.answers.append(self.estimates())
        self.send("обед")
        self.answers.append({"kind": "chat", "reply": "Около 36 г.", "meal": None})
        self.assertEqual(self.send("сколько белка?"), {"status": "failed", "reply": True, "message": "Около 36 г."})
        self.assertIsNotNone(self.server.threads.draft(self.server.threads.open_thread()))
        self.answers.append({"kind": "chat", "reply": "", "meal": None})
        self.assertTrue(self.send("эээ")["reply"])
        self.answers.append({"kind": "meal", "reply": "Записал!", "meal": None})
        self.assertNotIn("Записал", self.send("ну")["message"])

    def test_model_failure_leaves_the_thread_untouched(self):
        self.answers.append(self.estimates())
        self.send("обед", images=[image()])
        patcher = patch.object(brain, "parse_meal", side_effect=brain.SubscriptionLimit("19:00"))
        patcher.start()
        self.addCleanup(patcher.stop)
        with self.assertRaises(ValueError):
            self.send("поправка")
        self.assertEqual(len(self.server.threads.turns(self.server.threads.open_thread())), 2)

    def test_unchanged_web_item_is_not_searched_again(self):
        self.answers.append(meal_answer(grams_item("Сникерс", "26", "estimate", "estimate", ("100", "1", "1", "1", "1")),
                                        grams_item("Картошка", "200", "estimate", "estimate", ("100", "80", "2", "0.1", "17"))))
        self.send("обед")
        self.assertEqual(self.searches, ["Сникерс", "Картошка"])
        self.searches.clear()
        self.answers.append(meal_answer(grams_item("сникерс ", "26", "estimate", "estimate", ("100", "1", "1", "1", "1")),
                                        grams_item("Картошка", "300", "user", "stated", ("100", "80", "2", "0.1", "17"))))
        second = self.send("картошки больше, 300")
        self.assertEqual(self.searches, [])
        self.assertEqual(second["status"], "failed")
        self.assertIn("[сайт www.mars.com]", second["message"])

    def test_corrected_plan_with_wrong_date_is_refused_and_recorded(self):
        answer = self.estimates()
        answer["meal"]["date"] = "2026-11-19"
        self.answers.append(answer)
        with self.assertRaises(ValueError):
            self.send("обед")
        self.assertEqual([t["role"] for t in self.server.threads.turns(self.server.threads.open_thread())], ["user", "bot"])

    def test_prompt_carries_closed_meals_and_the_draft(self):
        self.answers.append(meal_answer(grams_item("Суп", "300", "label", "stated")))
        self.assertEqual(self.send("суп 300 г")["status"], "prepared")
        self.answers.append(self.estimates())
        self.send("обед")
        self.assertIn("УЖЕ записаны", self.calls[1]["prompt"])
        self.assertIn("Суп 300 г", self.calls[1]["prompt"])

    def test_needs_confirmation_rules(self):
        exact = [grams_item("A", "1", "label", "stated"), grams_item("B", "1", "web", "stated"),
                 {"query": "C", "source": "existing", "count": "1"}, grams_item("D", "1", "user", "stated")]
        self.assertFalse(brain.needs_confirmation(plan(*exact)))
        for bad in (grams_item("E", "1", "estimate", "stated"), grams_item("E", "1", "label", "estimate"), item("G", "1", "user")):
            self.assertTrue(brain.needs_confirmation(plan(*exact, bad)))
        self.assertFalse(brain.needs_confirmation(plan({"query": "F", "source": "saved", "grams_source": "estimate"})))

    def test_catalog_flag_selects_schema_and_prompt(self):
        enum = lambda schema: schema["properties"]["meal"]["anyOf"][0]["properties"]["items"]["items"]["properties"]["source"]["enum"]
        self.assertEqual(enum(brain.build_schema(False)), ["user", "label", "estimate", "barcode", "library"])
        self.assertIn("existing", enum(brain.build_schema(True)))
        self.assertIn("Never use source=existing", brain.build_system(False))
        self.assertIn("source=saved", brain.build_system(True))
        self.answers.append(meal_answer(grams_item("Плов", "300", "user", "stated")))
        self.send("плов 300 г")
        self.assertNotIn("existing", enum(self.calls[0]["schema"]))
        self.assertEqual(self.calls[0]["system"], brain.build_system(False))
        self.assertTrue(self.calls[0]["raw"])

    def test_catalog_item_without_grams_is_a_question_when_catalog_is_off(self):
        answer = meal_answer({"query": "Бешбармак с кониной", "source": "existing", "count": "1", "unit": "servings"})
        self.answers.append(answer)
        result = self.send("бешбармак")
        self.assertTrue(result["reply"])
        self.assertIn("вес", result["message"])
        self.assertNotIn("plan", result)
        self.server.config = {"phone_catalog": True}
        self.answers.append(answer)
        self.assertEqual(self.send("бешбармак 2")["status"], "prepared")

    ROLL = {"action": "save", "name": "Ролл цезарь", "aliases": ["цезарь ролл"],
            "nutrition": {"basis_grams": "100", "calories": "220", "protein": "9", "fat": "10", "carbs": "23"}, "default_grams": "180"}

    def test_library_action_saves_at_once_and_is_acknowledged(self):
        self.answers.append({"kind": "chat", "reply": "Ок.", "meal": None, "library": [self.ROLL]})
        result = self.send("запомни ролл")
        self.assertIn("Сохранил в библиотеке: Ролл цезарь", result["message"])
        self.assertEqual(self.server.library.find("цезарь ролл")["default_grams"], "180")
        self.answers.append({"kind": "chat", "reply": "", "meal": None, "library": [{"action": "delete", "name": "ролл ЦЕЗАРЬ!"}]})
        self.assertIn("Удалил", self.send("забудь ролл")["message"])
        self.assertIsNone(self.server.library.find("Ролл цезарь"))

    def test_invalid_library_action_is_refused_and_reported(self):
        bad = {**self.ROLL, "nutrition": {**self.ROLL["nutrition"], "basis_grams": "0"}}
        self.answers.append({"kind": "chat", "reply": "Ок.", "meal": None, "library": [bad]})
        self.assertIn("Не сохранил", self.send("запомни")["message"])
        self.assertEqual(self.server.library.all(), [])

    def test_library_item_gets_exact_values_separate_flag_and_default_grams(self):
        self.server.library.save(self.ROLL)
        self.answers.append(meal_answer({"query": "цезарь ролл", "source": "library", "nutrition": {"basis_grams": "1", "calories": "999", "protein": "0", "fat": "0", "carbs": "0"}}))
        result = self.send("мой ролл")
        self.assertEqual(result["status"], "failed")  # the amount is a default: still asked as a draft
        item = self.server.threads.draft(self.server.threads.open_thread())["items"][0]
        self.assertEqual((item["query"], item["grams"], item["grams_source"], item["separate"]), ("Ролл цезарь", "180", "estimate", True))
        self.assertEqual(item["nutrition"]["calories"], "220")
        self.assertIn("из библиотеки", result["message"])

    def test_library_item_with_stated_grams_is_prepared_and_prompt_lists_the_library(self):
        self.server.library.save(self.ROLL)
        self.answers.append(meal_answer({"query": "Ролл цезарь", "grams": "200", "grams_source": "stated", "source": "library"}))
        result = self.send("ролл цезарь 200 г")
        self.assertEqual(result["status"], "prepared")
        self.assertTrue(result["plan"]["items"][0]["separate"])
        self.assertIn("Ролл цезарь (цезарь ролл) | на 100 г: 220 ккал", self.calls[0]["prompt"])

    def test_unknown_library_name_or_missing_default_grams_asks_and_writes_nothing(self):
        self.answers.append(meal_answer({"query": "Лазанья", "grams": "200", "grams_source": "stated", "source": "library"}))
        result = self.send("лазанья")
        self.assertIn("В библиотеке нет", result["message"])
        self.assertNotIn("plan", result)
        self.server.library.save({**self.ROLL, "default_grams": None})
        self.answers.append(meal_answer({"query": "Ролл цезарь", "source": "library"}))
        self.assertIn("Сколько граммов", self.send("ролл")["message"])

    def test_reusable_item_is_saved_only_when_the_meal_is_prepared(self):
        reusable = {**grams_item("Попкорн Х", "50", "user", "estimate"), "reusable": True}
        self.answers.append(meal_answer(reusable))
        self.send("попкорн", capabilities=[])
        self.assertEqual(self.server.library.all(), [])
        draft = self.server.threads.draft(self.server.threads.open_thread())
        self.assertTrue(draft["items"][0]["separate"])
        result = self.send("ок")
        self.assertEqual(result["status"], "prepared")
        self.assertIsNotNone(self.server.library.find("Попкорн Х"))
        self.assertNotIn("reusable", result["plan"]["items"][0])
        self.assertTrue(result["plan"]["items"][0]["separate"])

    def test_reusable_estimate_is_not_saved(self):
        def no_web(payload, schema, system):
            raise RuntimeError("offline")
        self.server.web_search = no_web
        self.answers.append(meal_answer({**grams_item("Нечто", "50", "estimate", "stated"), "reusable": True}))
        self.send("нечто")
        self.assertIn("не сохранено", self.send("ок")["plan"]["notes"])
        self.assertEqual(self.server.library.all(), [])

    def test_library_source_counts_as_exact_in_needs_confirmation(self):
        exact = {"query": "Aa", "grams": "1", "source": "library", "grams_source": "stated"}
        self.assertFalse(brain.needs_confirmation(plan(exact)))
        self.assertTrue(brain.needs_confirmation(plan(exact, grams_item("E", "1", "estimate", "stated"))))
        self.assertTrue(brain.needs_confirmation(plan({**exact, "grams_source": "estimate"})))

    def test_validate_plan_accepts_separate_and_rejects_unknown_source(self):
        from meal_jobs import validate_plan
        good = grams_item("Aa", "10", "library", "stated")
        validate_plan(plan({**good, "separate": True}))
        for bad in ({**good, "source": "invented"}, {**good, "separate": "yes"}):
            with self.assertRaises(ValueError):
                validate_plan(plan(bad))

    def test_table_marks_library_rows(self):
        row = {**grams_item("A", "100", "library", "stated"), "separate": True}
        self.assertIn("из библиотеки", table(plan(row)))
        self.assertTrue(table_rows(plan(row))["rows"][0]["separate"])

    def test_cancel_endpoint_target_closes_the_thread(self):
        self.answers.append(self.estimates())
        self.send("обед", images=[image()])
        self.assertTrue(self.server.threads.cancel())
        self.assertFalse(self.server.threads.cancel())
        self.answers.append({"kind": "chat", "reply": "Что записать?", "meal": None})
        self.assertEqual(self.send("ок")["message"], "Что записать?")

    def test_confirm_words(self):
        for word in ("ок", "ОК.", "✅ Записать", "Окей!", "ok", "да", "Всё верно", "все верно", "подтверждаю", "запиши"):
            self.assertTrue(brain.is_confirm_word(word), word)
        for word in ("ок, но картошки больше", "не надо", "", "записать 200"):
            self.assertFalse(brain.is_confirm_word(word), word)


class RecordedDialogs(unittest.TestCase):
    def test_every_dialog_passes_with_a_scripted_model(self):
        dialogs = dialog_runner.load_dialogs()
        self.assertGreaterEqual(len(dialogs), 8)
        for dialog in dialogs:
            with self.subTest(dialog["name"]):
                failures = [(number, problems) for number, problems in dialog_runner.run_dialog(dialog) if problems]
                self.assertEqual(failures, [])

    def test_the_runner_detects_a_wrong_expectation(self):
        dialog = next(d for d in dialog_runner.load_dialogs() if d["name"] == "ok_after_draft")
        dialog["turns"][1]["expect"] = {"outcome": "draft", "model_called": True}
        problems = dict(dialog_runner.run_dialog(dialog))[2]
        self.assertEqual(len(problems), 2)

    def test_png_placeholder_is_a_valid_png(self):
        data = dialog_runner.make_png("12 3")
        self.assertTrue(data.startswith(b"\x89PNG\r\n\x1a\n"))
        brain.attachment({"media_type": "image/png", "data": base64.b64encode(data).decode()}, "image")


if __name__ == "__main__":
    unittest.main()
