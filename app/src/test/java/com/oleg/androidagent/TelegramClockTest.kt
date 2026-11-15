package com.oleg.androidagent

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class TelegramClockTest {
    private val epoch = Instant.parse("2026-11-15T16:00:00Z").epochSecond
    private fun date(seconds: Long) = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochSecond(seconds).atZone(ZoneOffset.UTC))
    private fun message(text: String, seconds: Long = epoch) = JSONObject().put("text", text).put("date", seconds)
        .put("chat", JSONObject().put("type", "private").put("id", 12345))
        .put("from", JSONObject().put("id", 12345).put("is_bot", false))
    private val token = "123456:" + "x".repeat(30)
    private fun client(server: MockWebServer): OkHttpClient = Http.client().newBuilder().addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
    }.build()

    @Test fun noClockIsInventedForMissingOrInvalidHttpDate() {
        val clock = TelegramClock { 0 }
        assertNull(clock.nowSeconds())
        for (header in listOf(null, "not-a-date", date(0))) {
            clock.observe(header)
            assertNull(clock.nowSeconds())
        }
    }
    @Test fun remoteTimeAdvancesWithElapsedTimeAndExpires() {
        var elapsed = 1000L
        val clock = TelegramClock { elapsed }
        clock.observe(date(epoch))
        assertEquals(epoch, clock.nowSeconds())
        elapsed += 45_000
        assertEquals(epoch + 45, clock.nowSeconds())
        elapsed += 75_001
        assertNull(clock.nowSeconds())
        clock.observe(date(epoch + 120))
        assertEquals(epoch + 120, clock.nowSeconds())
    }
    @Test fun elapsedClockMovingBackwardsInvalidatesSample() {
        var elapsed = 1000L
        val clock = TelegramClock { elapsed }
        clock.observe(date(epoch))
        elapsed = 999
        assertNull(clock.nowSeconds())
    }
    @Test fun recoveryCommandsNeverDependOnDatesOrDeviceClock() {
        for (text in listOf("Помощь", "Статус", "Отмена", "Привет!", "/help", "/status@owner_bot", "/start payload", "/cancel", "/stop")) {
            for (timestamp in listOf(0L, epoch - 86_400, epoch + 86_400)) {
                assertNull(text, TelegramFreshness.rejection(message(text, timestamp), null))
                assertNull(text, TelegramFreshness.rejection(message(text, timestamp), epoch))
            }
        }
    }
    @Test fun writesStillNeedKnownServerTimeAndAValidMessageDate() {
        for (text in listOf("/confirm code", "/meal 2026-11-15 lunch rice=150", "/pick 1 2", "рис 150 г")) {
            assertEquals(TelegramFreshness.Rejection.UNKNOWN_TIME, TelegramFreshness.rejection(message(text), null))
            assertEquals(TelegramFreshness.Rejection.INVALID_DATE, TelegramFreshness.rejection(message(text, 0), epoch))
        }
    }
    @Test fun freshnessBoundaryAndSmallFutureSkewAreHandled() {
        for (age in listOf(-30L, -1L, 0L, 1L, 299L, 300L)) {
            assertNull("age=$age", TelegramFreshness.rejection(message("/confirm code", epoch - age), epoch))
        }
        assertEquals(TelegramFreshness.Rejection.TOO_OLD, TelegramFreshness.rejection(message("/confirm code", epoch - 301), epoch))
        assertEquals(TelegramFreshness.Rejection.FUTURE_DATE, TelegramFreshness.rejection(message("/confirm code", epoch + 31), epoch))
    }
    @Test fun newerHttpResponseRefreshesTheSample() {
        var elapsed = 0L
        val clock = TelegramClock { elapsed }
        clock.observe(date(epoch))
        elapsed += 45_000
        clock.observe(date(epoch + 46))
        assertEquals(epoch + 46, clock.nowSeconds())
    }
    @Test fun apiUsesResponseDateForIncomingMessagesDespiteWallClockSkew() {
        for (skew in listOf(-86_400L, -18_000L, -1L, 1L, 18_000L, 86_400L)) {
            MockWebServer().use { server ->
                val remoteNow = System.currentTimeMillis() / 1000 + skew
                val updates = JSONArray()
                listOf("Помощь", "Статус").forEachIndexed { i, text ->
                    updates.put(JSONObject().put("update_id", i + 1).put("message", message(text, remoteNow + 1)))
                }
                server.enqueue(MockResponse().setHeader("Date", date(remoteNow)).setBody(JSONObject().put("ok", true).put("result", updates).toString()))
                repeat(2) { server.enqueue(MockResponse().setHeader("Date", date(remoteNow)).setBody("{\"ok\":true}")) }
                server.start()
                val http = client(server)
                val api = TelegramApi(token, http, TelegramClock { 0 })
                val store = object : StateStore {
                    override fun get(key: String) = ""
                    override fun put(key: String, value: String) { error("Recovery commands must not change persistent state") }
                }
                val diary = object : DiaryApi {
                    override fun search(query: String): List<JSONObject> = error("Unexpected diary access")
                    override fun food(id: String): JSONObject = error("Unexpected diary access")
                    override fun entries(date: String): List<JSONObject> = error("Unexpected diary access")
                    override fun create(date: String, meal: String, item: JSONObject): String = error("Unexpected write")
                }
                val commands = AgentCommands(store, JSONObject(), diary, http)
                for (update in api.updates(0, 0)) {
                    val incoming = update.getJSONObject("message")
                    assertTrue(TelegramAccess.allowed(incoming, 12345))
                    assertNull(TelegramFreshness.rejection(incoming, api.serverTimeSeconds()))
                    val reply = commands.handle(incoming, api)
                    assertTrue(reply.text.contains("Я на связи") || reply.text.contains("Агент работает"))
                    api.send(12345, reply.text)
                }
                assertEquals(remoteNow, api.serverTimeSeconds())
                assertEquals(3, server.requestCount)
                http.connectionPool.evictAll()
            }
        }
    }
    @Test fun responseDateAlsoProtectsActionFreshnessAcrossLargeClockSkew() {
        MockWebServer().use { server ->
            val remoteNow = System.currentTimeMillis() / 1000 + 86_400
            server.enqueue(MockResponse().setHeader("Date", date(remoteNow)).setBody("{\"ok\":true,\"result\":[]}"))
            server.start()
            val api = TelegramApi(token, client(server), TelegramClock { 0 })
            api.updates(0, 0)
            assertNull(TelegramFreshness.rejection(message("/confirm code", remoteNow), api.serverTimeSeconds()))
            assertEquals(TelegramFreshness.Rejection.TOO_OLD, TelegramFreshness.rejection(message("/confirm code", remoteNow - 301), api.serverTimeSeconds()))
        }
    }
    @Test fun unknownTimeDoesNotRelaxOwnerAuthorization() {
        val forged = message("Помощь").put("from", JSONObject().put("id", 9).put("is_bot", false))
        assertFalse(TelegramAccess.allowed(forged, 12345))
        assertFalse(TelegramAccess.allowed(message("/stop").put("forward_origin", JSONObject()), 12345))
    }
}
