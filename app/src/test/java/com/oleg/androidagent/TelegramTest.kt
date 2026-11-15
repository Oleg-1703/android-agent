package com.oleg.androidagent

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class TelegramTest {
    private val token = "123456:" + "x".repeat(30)
    private class Memory : StateStore {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key].orEmpty()
        override fun put(key: String, value: String) { values[key] = value }
    }
    private class Diary : DiaryApi {
        var reads = 0
        var writes = 0
        override fun search(query: String) = emptyList<JSONObject>()
        override fun food(id: String) = JSONObject()
        override fun entries(date: String): List<JSONObject> {
            reads++
            return listOf(JSONObject().put("food_entry_name", "Рис").put("calories", "120"))
        }
        override fun create(date: String, meal: String, item: JSONObject): String { writes++; error("Unexpected write") }
    }
    @Test fun identityAndWebhookAreReadOnlyAndPollingIsExplicit() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"ok\":true,\"result\":{\"username\":\"test_bot\"}}"))
            server.enqueue(MockResponse().setBody("{\"ok\":true,\"result\":{\"url\":\"https://example.org/hook\"}}"))
            server.enqueue(MockResponse().setBody("{\"ok\":true,\"result\":[]}"))
            server.start()
            val client = Http.client().newBuilder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            val api = TelegramApi(token, client)
            assertEquals("test_bot", api.identity().getString("username"))
            assertTrue(api.webhookConfigured())
            assertTrue(api.updates(42, 0).isEmpty())
            assertEquals("/bot$token/getMe", server.takeRequest().path)
            assertEquals("/bot$token/getWebhookInfo", server.takeRequest().path)
            val poll = server.takeRequest()
            val fields = poll.body.readUtf8().split('&').associate { part ->
                val pair = part.split('=', limit = 2)
                URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
            }
            assertEquals("42", fields["offset"])
            assertEquals("0", fields["timeout"])
            assertEquals("[\"message\"]", fields["allowed_updates"])
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun telegramHelpWorksWithoutFatSecretOrAi() {
        val diary = Diary()
        val client = Http.client()
        val commands = AgentCommands(Memory(), JSONObject(), diary, client)
        val api = TelegramApi(token, client)
        val reply = commands.handle(JSONObject().put("text", "/start onboarding"), api)
        assertTrue(reply.text.contains("Я на связи"))
        assertFalse(reply.stop)
        assertEquals(0, diary.reads)
        assertEquals(0, diary.writes)
    }
    @Test fun naturalDiaryQuestionNeedsNoAiAndNeverWrites() {
        val diary = Diary()
        val client = Http.client()
        val config = JSONObject().put("fs_token", "test").put("fs_token_secret", "test")
        val commands = AgentCommands(Memory(), config, diary, client)
        val reply = commands.handle(JSONObject().put("text", "Что я сегодня ел?"), TelegramApi(token, client))
        assertTrue(reply.text.contains("Рис - 120 ккал"))
        assertEquals(1, diary.reads)
        assertEquals(0, diary.writes)
    }
    @Test fun missingDiaryExplainsSetupWithoutNetworkOrAi() {
        val diary = Diary()
        val client = Http.client()
        val commands = AgentCommands(Memory(), JSONObject(), diary, client)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            commands.handle(JSONObject().put("text", "/today"), TelegramApi(token, client))
        }
        assertTrue(failure.message!!.contains("Developer-ключи для этого сценария не нужны"))
        assertEquals(0, diary.reads)
        assertEquals(0, diary.writes)
    }
    @Test fun stopReturnsReplyBeforeServiceStopsAndCancelPreservesUncertainWrite() {
        val store = Memory().apply { put("write_intent", "uncertain"); put("pending", "review") }
        val client = Http.client()
        val commands = AgentCommands(store, JSONObject(), Diary(), client)
        val api = TelegramApi(token, client)
        assertTrue(commands.handle(JSONObject().put("text", "/stop"), api).stop)
        commands.handle(JSONObject().put("text", "Отмена"), api)
        assertEquals("uncertain", store.get("write_intent"))
        assertEquals("", store.get("pending"))
    }
}
