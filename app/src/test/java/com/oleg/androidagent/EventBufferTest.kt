package com.oleg.androidagent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class EventBufferTest {
    private class Store : StateStore {
        val values = mutableMapOf<String, String>()
        var failing = false
        override fun get(key: String) = values[key].orEmpty()
        override fun put(key: String, value: String) { if (failing) error("Disk unavailable"); values[key] = value }
    }
    private var counter = 0
    private fun buffer(store: Store, cap: Int = 300) = EventBuffer(store, cap, { 1_000L }) { "id%06d".format(counter++) }
    private fun event(i: Int) = JSONObject().put("type", "stage").put("n", JSONObject().put("i", i))

    @After fun reset() { Telemetry.buffer = null }

    @Test fun ringKeepsTheNewestThreeHundred() {
        val store = Store()
        val buffer = buffer(store)
        repeat(350) { buffer.add(event(it)) }
        assertEquals(300, buffer.size())
        val all = buffer.pending(1000)
        assertEquals(50, all.first().getJSONObject("n").getInt("i"))
        assertEquals(349, all.last().getJSONObject("n").getInt("i"))
        assertEquals(300, buffer(store).size()) // persisted in the store
    }

    @Test fun payloadHasOnlyWhitelistedFieldsAndNoFoodOrChatText() {
        val raw = JSONObject().put("type", "diary_scan").put("ok", false).put("ms", 1200)
            .put("food", "борщ").put("text", "съел борщ 300 г").put("name", "Творог").put("photo", "AAAA").put("transcript", "x").put("token", "123456789:AAAbbbCCCdddEEEfffGGGhhhIII")
            .put("err", "boom 123456789:AAAbbbCCCdddEEEfffGGGhhhIII https://api.telegram.org/bot1/x\nnext")
            .put("n", JSONObject().put("rows", 12).put("food", "борщ").put("Bad Key", 1))
            .put("flags", JSONObject().put("step", "scan").put("note", "съел борщ").put("expanded", true).put("Upper", 1))
            .put("ids", JSONArray(listOf("food_journal_new_body", "борщ 300 г", "x".repeat(300)) + (1..80).map { "id_$it" }))
        val buffer = buffer(Store())
        assertTrue(buffer.add(raw))
        val sent = buffer.pending().single()
        assertTrue(EventSchema.ALLOWED.containsAll(sent.keys().asSequence().toList()))
        val json = sent.toString()
        for (forbidden in listOf("борщ", "Творог", "AAAA", "123456789", "telegram.org", "token", "photo", "transcript")) assertFalse(forbidden, json.contains(forbidden))
        assertEquals(12, sent.getJSONObject("n").getInt("rows"))
        assertEquals(1, sent.getJSONObject("n").length())
        assertEquals(2, sent.getJSONObject("flags").length())
        val ids = sent.getJSONArray("ids")
        assertEquals(60, ids.length())
        assertEquals("food_journal_new_body", ids.getString(0))
        assertFalse(sent.getString("err").contains("\n"))
        assertTrue(sent.getString("err").length <= 200)
    }

    @Test fun invalidTypeIsRejected() {
        val buffer = buffer(Store())
        for (type in listOf("", "Bad", "a b", "x".repeat(60))) assertFalse(buffer.add(JSONObject().put("type", type)))
        assertFalse(buffer.add(JSONObject()))
        assertEquals(0, buffer.size())
    }

    @Test fun flushSendsBatchesOfAtMostOneHundredAndAcknowledgesOnlyDelivered() {
        val buffer = buffer(Store())
        repeat(250) { buffer.add(event(it)) }
        val sizes = mutableListOf<Int>()
        val flusher = EventFlusher(buffer, { 0L }, send = { body -> sizes.add(body.getJSONArray("events").length()); true })
        assertEquals(250, flusher.flush())
        assertEquals(listOf(100, 100, 50), sizes)
        assertEquals(0, buffer.size())
    }

    @Test fun aCallBeforeTheMinimumIntervalDoesNothing() {
        val buffer = buffer(Store())
        buffer.add(event(1))
        var now = 0L
        var calls = 0
        val flusher = EventFlusher(buffer, { now }, minIntervalMillis = 20_000, send = { calls++; false })
        flusher.flush()
        buffer.add(event(2))
        now = 5_000
        assertEquals(0, flusher.flush())
        assertEquals(1, calls)
    }

    @Test fun failedSendKeepsEventsAndBacksOff() {
        val buffer = buffer(Store())
        repeat(5) { buffer.add(event(it)) }
        var now = 0L
        var online = false
        var calls = 0
        val flusher = EventFlusher(buffer, { now }, backoffMillis = 300_000, send = { calls++; online })
        assertEquals(0, flusher.flush())
        assertEquals(5, buffer.size())
        online = true
        now = 60_000
        assertEquals(0, flusher.flush()) // still backing off
        assertEquals(1, calls)
        now = 300_001
        assertEquals(5, flusher.flush())
        assertEquals(0, buffer.size())
    }

    @Test fun resentBatchKeepsStableIds() {
        val buffer = buffer(Store())
        buffer.add(event(1))
        val first = buffer.pending().single().getString("eid")
        assertEquals(first, buffer.pending().single().getString("eid"))
    }

    @Test fun telemetryIsANoOpWithoutBufferAndNeverThrows() {
        Telemetry.log("stage") // no buffer installed
        val store = Store()
        Telemetry.buffer = buffer(store)
        store.failing = true
        Telemetry.log("stage", ok = true) // storage fails: swallowed
        store.failing = false
        Telemetry.log("stage", "abcd1234", false, 5, "IOException", mapOf("n" to 1), mapOf("stage" to "save"))
        val saved = Telemetry.buffer!!.pending().single()
        assertEquals("IOException", saved.getString("err_class"))
        assertEquals("abcd1234", saved.getString("req"))
    }

    @Test fun stageRecordsFailureClassWithoutMessage() {
        Telemetry.buffer = buffer(Store())
        assertEquals(3, Telemetry.stage("abcd1234", "save") { 3 })
        try { Telemetry.stage("abcd1234", "verify") { throw IllegalStateException("Творог 200 г не найден") } } catch (_: IllegalStateException) { }
        val events = Telemetry.buffer!!.pending()
        assertEquals(true, events[0].getBoolean("ok"))
        assertEquals(false, events[1].getBoolean("ok"))
        assertEquals("IllegalStateException", events[1].getString("err_class"))
        assertFalse(events.toString().contains("Творог"))
    }

    @Test fun requestHashMatchesThePcFunction() {
        // pinned against Python: hashlib.sha256(("a" * 64).encode()).hexdigest()[:8]
        assertEquals(PY_HASH, Telemetry.requestHash("a".repeat(64)))
        assertNull(Telemetry.requestHash(""))
    }
    private companion object { const val PY_HASH = "ffe054fe" }
}
