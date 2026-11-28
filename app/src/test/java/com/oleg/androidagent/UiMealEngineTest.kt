package com.oleg.androidagent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class UiMealEngineTest {
    private class Store : StateStore {
        val values = mutableMapOf<String, String>()
        var failJournal = false
        var failClearJournal = false
        override fun get(key: String) = values[key].orEmpty()
        override fun put(key: String, value: String) {
            check(!(failJournal && key == "write_intent"))
            check(!(failClearJournal && key == "write_intent" && value.isBlank()))
            values[key] = value
        }
    }
    private class Screen(private val store: Store) : MealScreen {
        var stamp = "window:1"
        var summary = "Banana: 100 g"
        var saves = 0
        var failSave = false
        var complete = false
        var extraRow = false
        var failReadAfterSave = false
        var transientReads = 0
        override fun screenStamp() = stamp
        override fun searchContext() = "Lunch\nToday"
        override fun search(meal: String, query: String) = listOf(FatUi.Row("Banana", "89 kcal"))
        override fun choose(index: Int, expected: List<FatUi.Row>, grams: String) = summary
        override fun preview() = summary
        override fun snapshot(meal: String): DiarySnapshot {
            if (saves > 0 && transientReads > 0) { transientReads--; throw IllegalArgumentException("layout not settled") }
            require(!(failReadAfterSave && saves > 0))
            val entries = if (saves == 0) emptyList() else listOf(DiaryEntry("Banana", "100 g", "89")) +
                if (extraRow) listOf(DiaryEntry("Other", "1 g", "1")) else emptyList()
            return DiarySnapshot(LocalDate.now().toString(), meal, entries, complete)
        }
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
    private val engine = UiMealEngine(store, screen) {}
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
    @Test fun unverifiedSaveDoesNotLeaveALockAndTheNextPrepareIsNotBlocked() {
        val nonce = prepare()
        val reply = engine.confirm(nonce)
        assertTrue(reply.contains("Возможно записано, проверь дневник"))
        assertEquals("", store.get("write_intent"))
        engine.cancel()
        assertEquals("", store.get("ui_pending"))
        assertEquals(1, screen.saves)
        prepare()
    }
    @Test fun staleJournalOfACrashedWriteDoesNotBlockPrepareOrConfirm() {
        store.put("write_intent", "left by a killed process")
        val nonce = prepare()
        assertEquals("", store.get("write_intent"))
        engine.confirm(nonce)
        assertEquals(1, screen.saves)
    }
    private fun verifiedDraft(): String {
        screen.complete = true
        screen.summary = "Banana: 100 g\n89 ккал; Б 1, Ж 0, У 23"
        return prepare()
    }
    @Test fun exactReadbackClearsJournalAndOldNonceCannotReplay() {
        val nonce = verifiedDraft()
        assertTrue(engine.confirm(nonce).contains("Сохранено и проверено"))
        assertEquals("", store.get("write_intent"))
        fails { engine.confirm(nonce) }
        assertEquals(1, screen.saves)
    }
    @Test fun unexpectedExtraRowIsUncertainWithoutLock() {
        val nonce = verifiedDraft(); screen.extraRow = true
        assertTrue(engine.confirm(nonce).contains("Возможно записано"))
        assertEquals("", store.get("write_intent"))
    }
    @Test fun unreadableResultIsUncertainWithoutLock() {
        val nonce = verifiedDraft(); screen.failReadAfterSave = true
        assertTrue(engine.confirm(nonce).contains("Возможно записано"))
        assertEquals("", store.get("write_intent"))
    }
    @Test fun failureClearingVerifiedJournalStillPreventsReplay() {
        val nonce = verifiedDraft(); store.failClearJournal = true
        try { engine.confirm(nonce); fail() } catch (_: IllegalStateException) {}
        assertTrue(store.get("write_intent").isNotBlank())
        // The same draft is consumed: it can never be saved a second time, even though the journal could not be cleared.
        try { engine.confirm(nonce); fail() } catch (_: RuntimeException) {}
        assertEquals(1, screen.saves)
    }
    @Test fun transientReadbackRetriesOnlyReadingAndSavesExactlyOnce() {
        val nonce = verifiedDraft(); screen.transientReads = 2
        assertTrue(engine.confirm(nonce).contains("Сохранено и проверено"))
        assertEquals(1, screen.saves)
        assertEquals("", store.get("write_intent"))
    }
}
