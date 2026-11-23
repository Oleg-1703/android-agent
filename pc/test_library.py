import tempfile
import unittest
from pathlib import Path

from food_library import FoodLibrary, MAX_ENTRIES, library_lines, normalize

N = {"basis_grams": "100", "calories": "220", "protein": "9", "fat": "10", "carbs": "23"}


def entry(name="Ролл цезарь Small Food", **extra):
    return {"name": name, "nutrition": dict(N), **extra}


class Library(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name) / "lib.sqlite"
        self.lib = FoodLibrary(self.path)

    def test_save_find_by_normalized_name_then_alias(self):
        self.lib.save(entry(aliases=["ролл цезарь"], note="магазин", default_grams="180"))
        self.lib.save(entry("Цезарь салат", aliases=["Ролл Цезарь Small Food"]))
        self.assertEqual(self.lib.find("  РОЛЛ цезарь, small-food!  ")["name"], "Ролл цезарь Small Food")  # exact name beats the alias of another entry
        self.assertEqual(self.lib.find("ролл цезарь")["default_grams"], "180")
        self.assertIsNone(self.lib.find("ролл"))
        self.assertIsNone(self.lib.find("!!"))

    def test_upsert_by_normalized_name_and_persistence(self):
        _, created = self.lib.save(entry())
        saved, created_again = self.lib.save(entry("ролл ЦЕЗАРЬ small food", note="новая"))
        self.assertTrue(created)
        self.assertFalse(created_again)
        self.assertEqual(len(self.lib.all()), 1)
        self.assertEqual(FoodLibrary(self.path).find("Ролл цезарь Small Food")["note"], "новая")
        self.assertRegex(saved["updated"], r"^\d{4}-\d\d-\d\d$")

    def test_delete_by_name_or_alias(self):
        self.lib.save(entry(aliases=["цезарь"]))
        self.assertTrue(self.lib.delete("Цезарь"))
        self.assertFalse(self.lib.delete("цезарь"))
        self.assertEqual(self.lib.all(), [])

    def test_validation(self):
        bad = [entry("a"), entry("x" * 151), entry(aliases=["a"]), entry(aliases=["bb"] * 6), entry(default_grams="0"),
               entry(default_grams="5001"), entry(default_grams="x"), {"name": "Плов", "nutrition": {**N, "basis_grams": "0"}},
               {"name": "Плов", "nutrition": {**N, "calories": "-1"}}, {"name": "Плов", "nutrition": {**N, "fat": "10001"}},
               {"name": "Плов", "nutrition": {"basis_grams": "100"}}, {"name": "Плов"}, "text", entry(note=5)]
        for value in bad:
            with self.subTest(value=str(value)[:60]), self.assertRaises(ValueError):
                self.lib.save(value)
        self.assertEqual(self.lib.all(), [])

    def test_alias_equal_to_name_is_dropped(self):
        saved, _ = self.lib.save(entry("Плов", aliases=["плов", "узбекский плов"]))
        self.assertEqual(saved["aliases"], ["узбекский плов"])

    def test_cap_of_200_entries_blocks_new_but_not_updates(self):
        for number in range(MAX_ENTRIES):
            self.lib.save(entry(f"Еда номер {number}"))
        with self.assertRaises(ValueError):
            self.lib.save(entry("Лишняя еда"))
        self.lib.save(entry("Еда номер 5", note="обновлено"))
        self.assertEqual(len(self.lib.all()), MAX_ENTRIES)

    def test_prompt_lines_are_compact(self):
        self.lib.save(entry(aliases=["ролл"], default_grams="180", note="кастрюля 1800 г"))
        line = library_lines(self.lib.all())[0]
        self.assertEqual(line, "- Ролл цезарь Small Food (ролл) | на 100 г: 220 ккал, Б 9 / Ж 10 / У 23 | вес по умолчанию: 180 | кастрюля 1800 г")
        self.assertEqual(normalize("✅ Записать!"), "записать")


if __name__ == "__main__":
    unittest.main()
