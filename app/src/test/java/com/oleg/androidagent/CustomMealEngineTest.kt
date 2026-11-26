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
    @Test fun multipleCustomComponentsCreateAndSaveOneCard() {
        val request = plan()
        val half = JSONObject(request.getJSONArray("items").getJSONObject(0).toString()).put("grams", "100")
        request.put("items", org.json.JSONArray().put(half).put(JSONObject(half.toString())))
        var captured: JSONObject? = null
        val recording = object : CustomMealScreen by screen {
            override fun createCustom(meal: String, item: JSONObject, onContext: (String) -> Unit): String {
                captured = item
                return screen.createCustom(meal, item, onContext)
            }
        }
        val result = CustomMealEngine(store, recording) {}.log(request)
        assertEquals("complete", result.getString("status"))
        assertEquals(1, screen.creations)
        assertEquals(1, screen.saves)
        assertEquals(1, result.getJSONArray("receipts").length())
        assertEquals("200", requireNotNull(captured).getString("grams"))
        assertEquals("209", requireNotNull(captured).getJSONObject("nutrition").getString("calories"))
        assertTrue(result.getString("notes").contains("Состав:"))
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
    @Test fun incompleteBeforeSnapshotStillWritesAndReportsUncertain() {
        screen.complete = false
        val result = engine.log(plan())
        assertEquals("uncertain", result.getString("status"))
        assertEquals(1, screen.creations); assertEquals(1, screen.saves)
        assertTrue(result.getString("message").contains("Возможно записано, проверь дневник"))
        assertTrue(result.getString("message").contains("прочитан не полностью"))
        assertEquals("", store.get("write_intent"))
    }
    @Test fun prewriteReadFailureReportsNoWriteAndKeepsNoJournal() {
        var reads = 0
        val failing = object : CustomMealScreen by screen {
            override fun snapshot(meal: String): DiarySnapshot {
                reads++
                throw IllegalArgumentException("Missing adapter row")
            }
        }
        val result = CustomMealEngine(store, failing) {}.log(plan())
        assertEquals("uncertain", result.getString("status"))
        assertEquals(3, reads)
        assertEquals(1, screen.creations)
        assertEquals(1, screen.saves)
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
        val result = CustomMealEngine(store, failing) {}.log(plan())
        assertEquals("uncertain", result.getString("status"))
        assertEquals(1, reads)
        assertEquals(1, screen.saves)
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
    @Test fun unverifiedSaveIsUncertainNotAFailureAndDoesNotBlockTheNextRequest() {
        screen.uncertain = true
        val first = engine.log(plan())
        assertEquals("uncertain", first.getString("status"))
        assertEquals(0, first.getJSONArray("receipts").length())
        assertTrue(first.getString("message").contains("Возможно записано, проверь дневник"))
        assertEquals("", store.get("write_intent"))
        screen.uncertain = false
        // A different request is not blocked; the first one was saved exactly once and never replayed by itself.
        assertEquals(1, screen.saves)
        val second = CustomMealEngine(store, screen) {}.log(plan())
        assertEquals(2, screen.saves); assertEquals(2, screen.creations)
        assertNotNull(second)
    }
    @Test fun staleJournalIsClearedWhenANewRequestStartsAndNotedInItsReceipt() {
        store.put("write_intent", JSONObject().put("mode", "custom").put("preview", "Old food: 100 g\n90 ккал").toString())
        val result = engine.log(plan())
        assertEquals("complete", result.getString("status"))
        assertTrue(result.getString("notes").contains("Предыдущая запись осталась не сверена (Old food: 100 g)"))
        assertEquals("", store.get("write_intent"))
        assertEquals(1, screen.saves)
    }
    @Test fun missingMacrosDoNotStartScreenExecution() {
        val meal = plan(); meal.getJSONArray("items").getJSONObject(0).remove("nutrition")
        try { engine.log(meal); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(0, screen.creations)
    }
    @Test fun failedCleanupStillClearsTheJournalWhenNothingWasSaved() {
        val failing = object : CustomMealScreen by screen {
            override fun commitStarted() = false
            override fun createCustom(meal: String, item: JSONObject, onContext: (String) -> Unit): String =
                throw IllegalArgumentException("Original field failure")
            override fun cancelCustomDraft() { throw IllegalStateException("Cleanup unavailable") }
        }
        try {
            CustomMealEngine(store, failing) {}.log(plan())
            fail()
        } catch (error: PreCommitFailure) {
            assertEquals("open_screen", error.stage)
            assertEquals("Original field failure Ничего не записано.", error.message)
            assertEquals("Original field failure", error.cause!!.message)
            assertEquals("Cleanup unavailable", error.cause!!.suppressed.single().message)
        }
        assertEquals("", store.get("write_intent"))
        assertEquals(0, screen.saves)
    }
    @Test fun slowScreenBeforeCommitIsAClearPreCommitFailure() {
        val slow = object : CustomMealScreen by screen {
            override fun commitStarted() = false
            override fun createCustom(meal: String, item: JSONObject, onContext: (String) -> Unit): String = throw ScreenTimeout("old text")
        }
        try { CustomMealEngine(store, slow) {}.log(plan()); fail() } catch (error: PreCommitFailure) {
            assertEquals("FatSecret не открылся вовремя, ничего не записано.", error.message)
        }
        assertEquals("", store.get("write_intent"))
    }
    @Test fun unverifiedFirstCardThenPreCommitFailureOfTheSecondIsFlaggedUnverified() {
        val request = plan()
        request.put("items", org.json.JSONArray().put(request.getJSONArray("items").getJSONObject(0).put("source", "existing").also { it.remove("nutrition") })
            .put(JSONObject(request.getJSONArray("items").getJSONObject(0).toString()).put("query", "other")))
        screen.uncertain = true
        var calls = 0
        val second = object : CustomMealScreen by screen {
            override fun prepareExisting(meal: String, item: JSONObject, onContext: (String) -> Unit): String =
                if (++calls == 1) screen.prepareExisting(meal, item, onContext) else throw ScreenTimeout("slow")
            override fun commitStarted() = false
        }
        try { CustomMealEngine(store, second) {}.log(request); fail() } catch (_: PreCommitFailure) {}
        assertEquals(1, screen.saves)
        assertEquals("1", store.get("custom_unverified"))
        assertEquals("", store.get("write_intent"))
    }
    @Test fun failureAfterTheProductWasCreatedKeepsTheJournalAsUncertain() {
        val late = object : CustomMealScreen by screen {
            override fun commitStarted() = true
            override fun createCustom(meal: String, item: JSONObject, onContext: (String) -> Unit): String = throw ScreenTimeout("portion screen")
        }
        try { CustomMealEngine(store, late) {}.log(plan()); fail() } catch (error: ScreenTimeout) {
            assertEquals("portion screen", error.message)
        }
        assertTrue(store.get("write_intent").isNotBlank())
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
