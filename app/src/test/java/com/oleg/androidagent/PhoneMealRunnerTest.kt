package com.oleg.androidagent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class PhoneMealRunnerTest {
    private class Store : StateStore {
        val values = mutableMapOf<String, String>()
        var failWrites = false
        override fun get(key: String) = values[key].orEmpty()
        override fun put(key: String, value: String) { check(!failWrites); values[key] = value }
    }
    private val store = Store()
    private val id = "a".repeat(64)
    private var claims = 0
    private var reports = 0
    private var writes = 0
    private var lostClaim = false
    private var lostReceipt = false
    private val remote = object : PhoneMealRunner.Remote {
        override fun claim(id: String): JSONObject {
            assertEquals("claiming", JSONObject(store.get("phone_job_$id")).getString("state"))
            claims++
            if (lostClaim) throw IOException("Lost claim response")
            return JSONObject().put("status", if (claims == 1) "claimed" else "already_started")
        }
        override fun report(id: String, result: JSONObject) {
            reports++
            assertEquals(result.toString(), JSONObject(store.get("phone_job_$id")).getJSONObject("result").toString())
            if (lostReceipt) throw IOException("Lost receipt response")
        }
    }
    private fun prepared() = JSONObject().put("status", "prepared").put("target", "phone")
        .put("plan", Meals.parse("/meal ${LocalDate.now()} breakfast Fixture=100"))
    private fun saved() = JSONObject("""{"status":"complete","receipts":[{"preview":"Fixture: 100 g\n89 ккал; Б 1, Ж 0, У 20"}]}""")
    private fun runner() = PhoneMealRunner(store, remote) {
        assertEquals("executing", JSONObject(store.get("phone_job_$id")).getString("state"))
        writes++
        saved()
    }
    @Test fun claimedPhoneJobWritesOnceAcrossNewRunnerAndDuplicateDelivery() {
        assertEquals("complete", runner().run(id, prepared()).getString("status"))
        assertEquals("complete", runner().run(id, prepared()).getString("status"))
        assertEquals(1, writes); assertEquals(1, claims); assertEquals(1, reports)
    }
    @Test fun wholeMealCompositionSurvivesReceiptCachingAndReporting() {
        val runner = PhoneMealRunner(store, remote) { saved().put("notes", "Estimate\nComposition: rice, fish") }
        val request = prepared().also { it.getJSONObject("plan").put("notes", "Estimate") }
        assertEquals("Estimate\nComposition: rice, fish", runner.run(id, request).getString("notes"))
        assertEquals("Estimate\nComposition: rice, fish", runner.status(id)!!.getString("notes"))
        assertEquals(1, claims)
        assertEquals(1, reports)
    }
    @Test fun lostClaimNeverStartsPhoneOrRepeatsClaim() {
        lostClaim = true
        assertEquals("uncertain", runner().run(id, prepared()).getString("status"))
        assertEquals("uncertain", runner().run(id, prepared()).getString("status"))
        assertEquals(0, writes); assertEquals(1, claims)
    }
    @Test fun lostReceiptReturnsLocalSuccessAndStatusOnlyRetriesReceipt() {
        lostReceipt = true
        assertEquals("complete", runner().run(id, prepared()).getString("status"))
        lostReceipt = false
        assertEquals("complete", runner().status(id)!!.getString("status"))
        assertEquals(1, writes); assertEquals(1, claims); assertEquals(2, reports)
    }
    @Test fun interruptedSaveCannotBeReplayed() {
        val runner = PhoneMealRunner(store, remote, stage = { "portion_preview" }) {
            writes++
            store.put("write_intent", "pending")
            throw IOException("Save uncertain")
        }
        assertEquals("uncertain", runner.run(id, prepared()).getString("status"))
        assertEquals("portion_preview", runner.status(id)!!.getString("stage"))
        assertEquals("uncertain", runner().run(id, prepared()).getString("status"))
        assertEquals(1, writes); assertEquals(1, claims)
    }
    @Test fun storageFailurePreventsRemoteClaimAndPhoneWrite() {
        store.failWrites = true
        try { runner().run(id, prepared()); fail() } catch (_: IllegalStateException) {}
        assertEquals(0, claims); assertEquals(0, writes)
    }
    @Test fun phoneExecutorRejectsAnEmulatorPlan() {
        try { runner().run(id, prepared().put("target", "emulator")); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(0, claims); assertEquals(0, writes)
    }
    @Test fun unresolvedPreviousWriteDoesNotBlockTheNewRequestAndIsNotedInItsReceipt() {
        store.put("write_intent", "previous uncertain write")
        val result = runner().run(id, prepared())
        assertEquals("complete", result.getString("status"))
        assertTrue(result.getString("notes").contains("Предыдущая запись осталась не сверена"))
        assertEquals("", store.get("write_intent"))
        assertEquals(1, claims); assertEquals(1, writes)
    }
    @Test fun noteOfAStaleWriteIsInTheMessageOfAnUncertainReceipt() {
        store.put("write_intent", "previous uncertain write")
        val result = PhoneMealRunner(store, remote) { JSONObject().put("status", "uncertain").put("receipts", org.json.JSONArray()).put("message", "Возможно записано") }.run(id, prepared())
        assertEquals("uncertain", result.getString("status"))
        assertTrue(result.getString("message").startsWith("Возможно записано"))
        assertTrue(result.getString("message").contains("Предыдущая запись осталась не сверена"))
        assertFalse(result.has("stale_note"))
    }
    @Test fun cancellingUnstartedPlanPreservesAnUncertainOlderWrite() {
        store.put("write_intent", "previous uncertain write")
        store.put("custom_receipts", "older verified receipts")
        assertEquals("failed", runner().cancel(id, prepared()).getString("status"))
        assertEquals("failed", runner().run(id, prepared()).getString("status"))
        assertEquals("previous uncertain write", store.get("write_intent"))
        assertEquals("older verified receipts", store.get("custom_receipts"))
        assertEquals(0, writes); assertEquals(1, claims); assertEquals(1, reports)
    }
}
