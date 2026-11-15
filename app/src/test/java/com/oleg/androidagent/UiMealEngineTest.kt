package com.oleg.androidagent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class UiMealEngineTest {
    private class Store : StateStore {
        val values = mutableMapOf<String, String>()
        var failJournal = false
        override fun get(key: String) = values[key].orEmpty()
        override fun put(key: String, value: String) {
            check(!(failJournal && key == "write_intent"))
            values[key] = value
        }
    }
    private class Screen(private val store: Store) : MealScreen {
        var stamp = "window:1"
        var summary = "Banana: 100 g"
        var saves = 0
        var failSave = false
        override fun screenStamp() = stamp
        override fun searchContext() = "Lunch\nToday"
        override fun search(meal: String, query: String) = listOf(FatUi.Row("Banana", "89 kcal"))
        override fun choose(index: Int, expected: List<FatUi.Row>, grams: String) = summary
        override fun preview() = summary
        override fun save(expectedPreview: String, expectedStamp: String, expectedContext: String) {
            check(store.get("write_intent").isNotBlank())
            check(store.get("ui_pending").isBlank())
            saves++
            if (failSave) error("uncertain")
        }
        override fun leaveDraft() {}
    }
    private val store = Store()
    private val screen = Screen(store)
    private val engine = UiMealEngine(store, screen)
    private fun prepare(): String {
        engine.prepare(Meals.parse("/meal ${LocalDate.now()} lunch banana=100"))
        engine.pick(1, 1)
        return JSONObject(store.get("ui_pending")).getString("nonce")
    }
    private fun fails(block: () -> Unit) { try { block(); fail("Expected refusal") } catch (_: IllegalArgumentException) {} }
    @Test fun wrongConfirmationNeverClicksSave() {
        prepare(); fails { engine.confirm("wrong") }; assertEquals(0, screen.saves)
        assertEquals("", store.get("write_intent"))
    }
    @Test fun changedWindowEvenWithIdenticalFoodNeverSaves() {
        val nonce = prepare(); screen.stamp = "window:2"
        fails { engine.confirm(nonce) }; assertEquals(0, screen.saves)
    }
    @Test fun changedPortionNeverSaves() {
        val nonce = prepare(); screen.summary = "Banana: 200 g"
        fails { engine.confirm(nonce) }; assertEquals(0, screen.saves)
    }
    @Test fun journalFailurePreventsClick() {
        val nonce = prepare(); store.failJournal = true
        try { engine.confirm(nonce); fail() } catch (_: IllegalStateException) {}
        assertEquals(0, screen.saves)
    }
    @Test fun uncertainClickIsNeverReplayedAcrossEngineRecreation() {
        val nonce = prepare(); screen.failSave = true
        try { engine.confirm(nonce); fail() } catch (_: IllegalStateException) {}
        assertEquals(1, screen.saves)
        assertTrue(store.get("write_intent").isNotBlank())
        fails { UiMealEngine(store, screen).confirm(nonce) }
        assertEquals(1, screen.saves)
    }
    @Test fun cancellationRetainsUncertainWriteLock() {
        val nonce = prepare(); engine.confirm(nonce); engine.cancel()
        assertTrue(store.get("write_intent").isNotBlank())
        assertEquals("", store.get("ui_pending"))
    }
}
