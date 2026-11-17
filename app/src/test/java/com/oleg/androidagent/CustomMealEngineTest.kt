package com.oleg.androidagent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CustomMealEngineTest {
    private class Store : StateStore {
        val values = mutableMapOf<String, String>()
        var fail = false
        override fun get(key: String) = values[key].orEmpty()
        override fun put(key: String, value: String) {
            if (fail && key == "write_intent") error("disk full")
            values[key] = value
        }
    }
    private class Screen(val store: Store) : CustomMealScreen {
        var creations = 0
        var existingSelections = 0
        var saves = 0
        var complete = true
        var uncertain = false
        var wrongNutrition = false
        override fun snapshot(meal: String) = DiarySnapshot(LocalDate.now().toString(), meal,
            if (uncertain && saves > 0) emptyList() else List(saves) { DiaryEntry("Own food", "200 g", "209") }, complete)
        override fun createCustom(meal: String, item: JSONObject, onContext: (String) -> Unit): String {
            check(store.get("write_intent").isNotBlank())
            onContext("Breakfast\nToday")
            creations++
            return "Own food: 200 g\n${if (wrongNutrition) 418 else 209} ккал; Б 9.5, Ж 11, У 18"
        }
        override fun screenStamp() = "fixed"
        override fun prepareExisting(meal: String, item: JSONObject, onContext: (String) -> Unit): String {
            onContext("Breakfast\nToday")
            existingSelections++
            return "Own food: 200 g\n209 ккал; Б 9.5, Ж 11, У 18"
        }
        override fun searchContext() = "Breakfast\nToday"
        override fun save(expectedPreview: String, expectedStamp: String, expectedContext: String) { saves++ }
    }
    private val store = Store()
    private val screen = Screen(store)
    private val engine = CustomMealEngine(store, screen) {}
    private fun plan(): JSONObject = Meals.parse("/meal ${LocalDate.now()} breakfast own food=200").also {
        it.getJSONArray("items").getJSONObject(0).put("nutrition", JSONObject("""{"basis_grams":"200","calories":"209","protein":"9.5","fat":"11","carbs":"18"}"""))
    }
    @Test fun customFoodIsSavedAndVerifiedWithoutCatalogOrConfirmation() {
        assertEquals("complete", engine.log(plan()).getString("status"))
        assertEquals(1, screen.creations); assertEquals(1, screen.saves)
        assertEquals("", store.get("write_intent"))
    }
    @Test fun failedJournalPreventsProductCreation() {
        store.fail = true
        try { engine.log(plan()); fail() } catch (_: IllegalStateException) {}
        assertEquals(0, screen.creations); assertEquals(0, screen.saves)
    }
    @Test fun existingFoodIsLoggedWithoutCreatingOrEstimatingNutrition() {
        val meal = plan()
        meal.getJSONArray("items").getJSONObject(0).also { it.remove("nutrition"); it.put("source", "existing") }
        assertEquals("complete", engine.log(meal).getString("status"))
        assertEquals(0, screen.creations); assertEquals(1, screen.existingSelections); assertEquals(1, screen.saves)
    }
    @Test fun incompleteDiaryPreventsAllWrites() {
        screen.complete = false
        try { engine.log(plan()); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(0, screen.creations)
    }
    @Test fun prewriteReadFailureReportsNoWriteAndKeepsNoJournal() {
        var reads = 0
        val failing = object : CustomMealScreen by screen {
            override fun snapshot(meal: String): DiarySnapshot {
                reads++
                throw IllegalArgumentException("Missing adapter row")
            }
        }
        try { CustomMealEngine(store, failing) {}.log(plan()); fail() }
        catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().contains("Эта еда не добавлена")) }
        assertEquals(3, reads)
        assertEquals(0, screen.creations)
        assertEquals(0, screen.saves)
        assertEquals("", store.get("write_intent"))
    }
    @Test fun exhaustedDiaryScanDoesNotRepeatTheSameTraversal() {
        var reads = 0
        val failing = object : CustomMealScreen by screen {
            override fun snapshot(meal: String): DiarySnapshot {
                reads++
                throw DiaryReadFailure("Missing row after both bounded passes")
            }
        }
        try { CustomMealEngine(store, failing) {}.log(plan()); fail() }
        catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().contains("Эта еда не добавлена")) }
        assertEquals(1, reads)
        assertEquals(0, screen.creations)
        assertEquals(0, screen.saves)
        assertEquals("", store.get("write_intent"))
    }
    @Test fun progressSeparatesReadingWritingAndVerification() {
        val stages = mutableListOf<String>()
        CustomMealEngine(store, screen, progress = { stages.add(it) }) {}.log(plan())
        assertEquals(3, stages.size)
        assertTrue(stages[0].contains("читаю дневник"))
        assertTrue(stages[1].contains("Вношу свою еду"))
        assertTrue(stages[2].contains("Проверяю запись"))
        assertEquals(1, screen.saves)
    }
    @Test fun nutritionCannotBeSilentlyDoubled() {
        screen.wrongNutrition = true
        try { engine.log(plan()); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(0, screen.saves)
        assertTrue(store.get("write_intent").isNotBlank())
    }
    @Test fun uncertainResultCannotReplayAfterRecreation() {
        screen.uncertain = true
        try { engine.log(plan()); fail() } catch (_: IllegalArgumentException) {}
        try { CustomMealEngine(store, screen).log(plan()); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(1, screen.saves); assertEquals(1, screen.creations)
    }
    @Test fun missingMacrosDoNotStartScreenExecution() {
        val meal = plan(); meal.getJSONArray("items").getJSONObject(0).remove("nutrition")
        try { engine.log(meal); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(0, screen.creations)
    }
    @Test fun failedCleanupKeepsJournalAndPreservesOriginalFailure() {
        val failing = object : CustomMealScreen by screen {
            override fun commitStarted() = false
            override fun createCustom(meal: String, item: JSONObject, onContext: (String) -> Unit): String =
                throw IllegalArgumentException("Original field failure")
            override fun cancelCustomDraft() { throw IllegalStateException("Cleanup unavailable") }
        }
        try {
            CustomMealEngine(store, failing) {}.log(plan())
            fail()
        } catch (error: IllegalArgumentException) {
            assertEquals("Original field failure", error.message)
            assertEquals("Cleanup unavailable", error.suppressed.single().message)
        }
        assertTrue(store.get("write_intent").isNotBlank())
        assertEquals(0, screen.saves)
    }
    @Test fun reconciliationOnlyReadsAndNeverRepeatsAWrite() {
        val before = screen.snapshot("breakfast")
        val item = plan().getJSONArray("items").getJSONObject(0)
        store.put("write_intent", JSONObject().put("mode", "custom").put("before", before.json()).put("item", item)
            .put("preview", "Own food: 200 g\n209 ккал; Б 9.5, Ж 11, У 18").toString())
        screen.saves = 1
        assertEquals("complete", engine.reconcile().getString("status"))
        assertEquals(0, screen.creations); assertEquals(1, screen.saves)
        assertEquals("", store.get("write_intent"))
    }
}
