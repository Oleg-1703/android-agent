package com.oleg.androidagent

import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class ReliabilityTest {
    private class Store : StateStore {
        val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key].orEmpty()
        override fun put(key: String, value: String) { values[key] = value }
    }
    private fun message(id: Long, date: Long = 1000) = JSONObject().put("message_id", id).put("date", date).put("text", "Food fixture")
    @Test fun acceptedBatchGrowsDurablyAndReplaysOnlyOnceWithSameIds() {
        val store = Store()
        AcceptedPcMessages(store, "scope").accept(listOf(message(1)), 3)
        AcceptedPcMessages(store, "scope").accept(listOf(message(1), message(2)), 4)
        val restarted = AcceptedPcMessages(store, "scope")
        val replay = restarted.recover(restarted.rows().single(), 1100)!!
        assertEquals(listOf(1L, 2L), replay.objects("agent_batch").map { it.getLong("message_id") })
        assertEquals(4L, restarted.rows().single().getLong("offset"))
        assertNull(AcceptedPcMessages(store, "scope").recover(restarted.rows().single(), 1100))
        restarted.remove(1)
        assertTrue(restarted.rows().isEmpty())
    }
    @Test fun acceptedBatchFreshnessUsesNewestMessageAndPairingScope() {
        val store = Store()
        val accepted = AcceptedPcMessages(store, "scope")
        accepted.accept(listOf(message(1, 1), message(2, 1000)), 3)
        assertNotNull(accepted.recover(accepted.rows().single(), 1100))
        assertTrue(AcceptedPcMessages(store, "other-pairing").rows().isEmpty())
        accepted.accept(listOf(message(3)), 4)
        assertNull(accepted.recover(accepted.rows().last(), 1301))
    }
    @Test fun confirmMessageIsRecoverableAndUnknownTimeNeverExecutes() {
        val accepted = AcceptedPcMessages(Store(), "scope")
        accepted.accept(listOf(message(7).put("text", TelegramApi.CONFIRM_BUTTON)), 9)
        assertNull(accepted.recover(accepted.rows().single(), null))
        assertEquals(7L, accepted.recover(accepted.rows().single(), 1000)!!.getLong("message_id"))
    }
    @Test fun controlsIncludingMediaCaptionsAreNeverAcceptedForReplay() {
        val accepted = AcceptedPcMessages(Store(), "scope")
        for (control in listOf("/status", "/stop", "/cancel", "отмена")) {
            val message = message(1).put("text", control).put("photo", org.json.JSONArray())
            assertFalse(MealBatch.eligible(message))
            try { accepted.accept(listOf(message), 2); fail() } catch (_: IllegalArgumentException) { }
        }
        assertTrue(accepted.rows().isEmpty())
    }
    @Test fun cancellationClearsAcceptedDataDurably() {
        val store = Store(); val accepted = AcceptedPcMessages(store, "scope")
        accepted.accept(listOf(message(1)), 2); accepted.clear()
        assertTrue(AcceptedPcMessages(store, "scope").rows().isEmpty())
    }
    private val config = JSONObject().put("pc_url", "https://pc.example.invalid/").put("pc_pairing", "x".repeat(40)).put("pc_consent", true)
    private fun prepared() = JSONObject().put("status", "prepared").put("target", "phone").put("plan", Meals.parse("/meal ${LocalDate.now()} lunch Fixture=100"))
    private fun client(answer: (String, String) -> Pair<Int, String>) = Http.client().newBuilder().addInterceptor { chain ->
        val (code, body) = answer(chain.request().method, chain.request().url.encodedPath)
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(body.toResponseBody()).build()
    }.build()
    @Test fun lostSubmitReplyResumesPreparedPhoneJobAndNeverResubmits() {
        val store = Store(); val selected = JSONObject(config.toString()).put("pc_execute_on_phone", true)
        var posts = 0; var writes = 0; var offline = true
        val http = client { method, path ->
            when {
                path == "/health" -> 200 to """{"status":"ready","provider":"claude-subscription","execution_targets":["phone"]}"""
                path == "/v1/log" -> { posts++; throw IOException() }
                method == "GET" -> if (offline) throw IOException() else 200 to prepared().toString()
                path.endsWith("/claim") -> 200 to """{"status":"claimed"}"""
                else -> 200 to """{"status":"accepted"}"""
            }
        }
        val brain = PcBrain(selected, http, store)
        val telegram = TelegramApi("1:" + "x".repeat(24), http)
        brain.acceptedMessages().accept(listOf(message(1)), 2)
        assertTrue(brain.submit(listOf(message(1)), telegram) { writes++; JSONObject() }.contains("задерживается"))
        assertTrue(brain.hasPendingPhone())
        assertTrue(brain.acceptedMessages().rows().isEmpty())
        assertTrue(brain.resumePhoneRendered({ true }, { "" }) { error("Offline") }.isEmpty())
        offline = false
        val replies = PcBrain(selected, http, store).resumePhoneRendered({ true }, { "" }) {
            writes++; JSONObject().put("status", "complete").put("receipts", org.json.JSONArray().put(JSONObject().put("preview", "Fixture")))
        }
        assertEquals(1, replies.size); assertEquals(1, writes); assertEquals(1, posts)
        assertFalse(brain.hasPendingPhone())
    }
    @Test fun unknownJobClearsPendingAndDoesNotBlockNextMeal() {
        val store = Store(); var failPost = true
        val http = client { _, path -> if (path == "/v1/log" && failPost) throw IOException() else if (path == "/v1/log") 200 to """{"status":"failed","reply":true,"message":"done"}""" else 404 to "{}" }
        val brain = PcBrain(config, http, store); val tg = TelegramApi("1:" + "x".repeat(24), http)
        brain.submit(listOf(message(1)), tg)
        assertTrue(brain.resumePhoneRendered({ false }, { "" }) { error("No write") }.single().text.contains("заново"))
        assertFalse(brain.hasPendingPhone())
        failPost = false
        assertEquals("done", brain.submit(listOf(message(2)), tg))
    }
    @Test fun pendingReplyExpiresSameDayWithoutContactingPcOrWriting() {
        val store = Store(); var calls = 0
        val http = client { _, _ -> calls++; throw IOException() }
        val brain = PcBrain(config, http, store)
        brain.submit(listOf(message(1)), TelegramApi("1:" + "x".repeat(24), http))
        val key = store.values.keys.single { it.startsWith("pending_pc_") }
        val rows = org.json.JSONArray(store.get(key))
        rows.getJSONObject(0).put("date", LocalDate.now().minusDays(1).toString())
        store.put(key, rows.toString())
        assertTrue(brain.resumePhoneRendered({ false }, { "" }) { error("Expired") }.single().text.contains("истекло"))
        assertEquals(1, calls); assertFalse(brain.hasPendingPhone())
    }
    @Test fun transientPollFailuresRecoverInsideSubmit() {
        var polls = 0; val store = Store()
        val http = client { _, path ->
            if (path == "/v1/log") 200 to """{"status":"queued"}"""
            else { polls++; if (polls < 3) throw IOException() else 200 to """{"status":"failed","reply":true,"message":"answer"}""" }
        }
        val brain = PcBrain(config, http, store)
        assertEquals("answer", brain.submit(listOf(message(1)), TelegramApi("1:" + "x".repeat(24), http)))
        assertEquals(3, polls); assertFalse(brain.hasPendingPhone())
    }
    @Test fun pendingPreparedPlanHonoursCancelBeforeUnlock() {
        val store = Store(); val selected = JSONObject(config.toString()).put("pc_execute_on_phone", true)
        var claims = 0; var writes = 0
        val http = client { _, path -> when {
            path == "/health" -> 200 to """{"status":"ready","provider":"claude-subscription","execution_targets":["phone"]}"""
            path == "/v1/log" -> throw IOException()
            path.endsWith("/claim") -> { claims++; 200 to """{"status":"claimed"}""" }
            path.endsWith("/receipt") -> 200 to """{"status":"accepted"}"""
            else -> 200 to prepared().toString()
        } }
        val brain = PcBrain(selected, http, store)
        brain.submit(listOf(message(1)), TelegramApi("1:" + "x".repeat(24), http)) { writes++; JSONObject() }
        assertTrue(brain.cancelPendingPhone().isNotEmpty())
        assertTrue(brain.resumePhoneRendered({ false }, { "" }) { writes++; JSONObject() }.single().text.contains("отменён"))
        assertEquals(0, writes); assertEquals(1, claims); assertFalse(brain.hasPendingPhone())
    }
    @Test fun pendingJobsExpireAndKeepIndependentRequests() {
        val store = Store(); val pending = PendingPcJobs(store, "scope")
        pending.add("a".repeat(64), true); pending.add("b".repeat(64), false)
        pending.add("a".repeat(64), true)
        assertEquals(2, pending.rows().size)
        pending.cancelAll(); assertTrue(pending.rows().all { it.getBoolean("cancel") })
        pending.remove("a".repeat(64)); assertEquals("b".repeat(64), pending.rows().single().getString("id"))
    }
    @Test fun pollFailuresAreBoundedAndResumeReturnsDraftWithoutAnotherPost() {
        val store = Store(); var polls = 0; var posts = 0; var recovered = false
        val http = client { _, path ->
            if (path == "/v1/log") { posts++; 200 to """{"status":"queued"}""" }
            else { polls++; if (!recovered) throw IOException() else 200 to """{"status":"failed","reply":true,"message":"draft","confirm":true}""" }
        }
        val brain = PcBrain(config, http, store)
        assertTrue(brain.submit(listOf(message(1)), TelegramApi("1:" + "x".repeat(24), http)).contains("задерживается"))
        assertEquals(3, polls)
        recovered = true
        assertTrue(brain.resumePhoneRendered({ false }, { "" }) { error("No write") }.single().confirm)
        assertEquals(1, posts); assertFalse(brain.hasPendingPhone())
    }
    @Test fun requestConflictReturnsResendMessageWithoutPendingLoop() {
        val store = Store(); val http = client { _, _ -> 409 to """{"error":"request_id_conflict"}""" }
        val brain = PcBrain(config, http, store)
        assertTrue(brain.submit(listOf(message(1)), TelegramApi("1:" + "x".repeat(24), http)).contains("новое сообщение"))
        assertFalse(brain.hasPendingPhone())
    }
    @Test fun unreadablePendingAnswerKeepsRowAndDoesNotStopTheOthers() {
        val store = Store(); var post = true
        val http = client { _, path -> when {
            path == "/v1/log" && post -> throw IOException()
            path.endsWith("a".repeat(64)) -> 200 to "<html>"
            else -> 200 to """{"status":"failed","reply":true,"message":"second"}"""
        } }
        val brain = PcBrain(config, http, store)
        brain.submit(listOf(message(1)), TelegramApi("1:" + "x".repeat(24), http))
        val key = store.values.keys.single { it.startsWith("pending_pc_") }
        val rows = org.json.JSONArray(store.get(key))
        val bad = JSONObject(rows.getJSONObject(0).toString()).put("id", "a".repeat(64))
        store.put(key, org.json.JSONArray().put(bad).put(rows.getJSONObject(0)).toString())
        assertEquals("second", brain.resumePhoneRendered({ false }, { "" }) { error("No write") }.single().text)
        assertEquals("a".repeat(64), org.json.JSONArray(store.get(key)).getJSONObject(0).getString("id"))
    }
    @Test fun cancelledRequestNeverComesBackAsAConfirmableDraft() {
        val store = Store(); var drafts = 0
        val http = client { _, path -> when (path) {
            "/v1/log" -> throw IOException()
            "/v1/drafts/cancel" -> { drafts++; 200 to "{}" }
            else -> 200 to """{"status":"failed","reply":true,"message":"draft","confirm":true}"""
        } }
        val brain = PcBrain(config, http, store)
        brain.submit(listOf(message(1)), TelegramApi("1:" + "x".repeat(24), http))
        brain.cancelPendingPhone()
        val reply = brain.resumePhoneRendered({ false }, { "" }) { error("No write") }.single()
        assertFalse(reply.confirm); assertTrue(reply.text.contains("отменён"))
        assertEquals(1, drafts); assertFalse(brain.hasPendingPhone())
    }
    @Test fun interruptedScreenCleanupReleasesAndResetsWithoutClearingInterrupt() {
        var released = false; var reset = false
        Thread.currentThread().interrupt()
        try {
            closeScreenLease(AutoCloseable { released = true }) { reset = true }
            assertTrue(released); assertTrue(reset); assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
    @Test fun screenCleanupResetsEvenIfReleaseThrows() {
        var reset = false
        try { closeScreenLease(AutoCloseable { throw IllegalStateException() }) { reset = true }; fail() }
        catch (_: IllegalStateException) { assertTrue(reset) }
    }
}
