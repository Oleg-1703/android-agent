package com.oleg.androidagent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PhonePlanQueueTest {
    private class Store : StateStore {
        val values = mutableMapOf<String, String>()
        var failQueueRemoval = false
        override fun get(key: String) = values[key].orEmpty()
        override fun put(key: String, value: String) {
            if (failQueueRemoval && key.startsWith("phone_plans_") && value == "[]") error("Disk unavailable")
            values[key] = value
        }
    }
    private fun prepared() = JSONObject().put("target", "phone").put("status", "prepared").put("plan",
        Meals.parse("/meal ${LocalDate.now()} lunch food=100").also {
            it.getJSONArray("items").getJSONObject(0).put("nutrition", JSONObject("""{"basis_grams":"100","calories":"100","protein":"2","fat":"2","carbs":"18"}"""))
        })
    @Test fun lockedPhoneDoesNotClaimAndQueueSurvivesRestart() {
        val store = Store()
        val queue = PhonePlanQueue(store, "first")
        queue.add("a".repeat(64), prepared())
        queue.add("a".repeat(64), prepared())
        assertTrue(queue.drain({ false }) { _, _ -> error("Locked phone executed") }.isEmpty())
        val resumed = PhonePlanQueue(store, "first")
        var calls = 0
        assertEquals(1, resumed.drain({ true }) { _, _ -> calls++; JSONObject().put("status", "complete") }.size)
        assertEquals(1, calls)
        assertFalse(resumed.pending())
        assertFalse(PhonePlanQueue(store, "different-pairing").pending())
    }
    @Test fun failureBeforeClaimKeepsThePlanForUnlock() {
        val queue = PhonePlanQueue(Store(), "scope")
        queue.add("a".repeat(64), prepared())
        try { queue.drain({ true }) { _, _ -> throw IllegalStateException("Screen locked while acquiring lease") }; fail() }
        catch (_: IllegalStateException) {}
        assertTrue(queue.pending())
    }
    @Test fun lostRemovalCannotRepeatADurableExecution() {
        val store = Store()
        val queue = PhonePlanQueue(store, "scope")
        val remote = object : PhoneMealRunner.Remote {
            override fun claim(id: String) = JSONObject().put("status", "claimed")
            override fun report(id: String, result: JSONObject) {}
        }
        var writes = 0
        val runner = PhoneMealRunner(store, remote) { writes++; JSONObject().put("status", "complete").put("receipts", org.json.JSONArray()) }
        queue.add("a".repeat(64), prepared())
        store.failQueueRemoval = true
        try { queue.drain({ true }, runner::run); fail() } catch (_: IllegalStateException) {}
        store.failQueueRemoval = false
        assertEquals(1, queue.drain({ true }, runner::run).size)
        assertEquals(1, writes)
    }
    @Test fun uncertainResultDoesNotStopTheRestOfTheQueue() {
        val queue = PhonePlanQueue(Store(), "scope")
        queue.add("a".repeat(64), prepared()); queue.add("b".repeat(64), prepared())
        var calls = 0
        assertEquals(2, queue.drain({ true }) { _, _ -> calls++; JSONObject().put("status", "uncertain") }.size)
        assertEquals(2, calls)
        assertFalse(queue.contains("b".repeat(64)))
    }
}
