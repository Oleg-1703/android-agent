package com.oleg.androidagent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentLookTest {
    private fun node(text: String = "Ужин", password: Boolean = false) = AgentLook.Node(
        "android.widget.TextView", text, "private description", "com.fatsecret.android:id/title",
        listOf("clickable", "selected"), listOf(0, 0, 100, 100), password)

    @Test fun commandParsingAndMealIsolation() {
        assertEquals("Как добавить ужин?", AgentLook.task(" /LOOK@my_bot\tКак добавить ужин? "))
        assertEquals("", AgentLook.task("/look"))
        assertNull(AgentLook.task("/lookalike text"))
        assertNull(AgentLook.task("еда"))
        assertFalse(MealBatch.eligible(JSONObject().put("text", "/look Добавь ужин")))
    }

    @Test fun usageAndConsentRunBeforeAnyMealOrNetworkCode() {
        val state = object : StateStore {
            override fun get(key: String) = ""
            override fun put(key: String, value: String) { error("Look must not mutate meal state") }
        }
        val diary = java.lang.reflect.Proxy.newProxyInstance(DiaryApi::class.java.classLoader,
            arrayOf(DiaryApi::class.java)) { _, _, _ -> error("Look must not call diary") } as DiaryApi
        val client = Http.client()
        val config = JSONObject()
        val commands = AgentCommands(state, config, diary, client)
        val telegram = TelegramApi("123:" + "x".repeat(24), client)
        assertEquals(AgentLook.USAGE, commands.handle(JSONObject().put("text", "/look@bot"), telegram).text)
        assertTrue(commands.handle(JSONObject().put("text", "/look Задача"), telegram).text.contains("согласие"))
        config.put("pc_consent", true)
        assertTrue(commands.handle(JSONObject().put("text", "/look Задача"), telegram).text.contains("специальных возможностях"))
        assertTrue(commands.handle(JSONObject().put("text", "/help"), telegram).text.contains(AgentLook.DESCRIPTION))
    }

    @Test fun passwordBlankedBeforeSerializing() {
        val screen = AgentLook.screen(1080, 2400, sequenceOf(node(password = true)))
        val row = screen.getJSONArray("nodes").getJSONObject(0)
        assertEquals("", row.getString("text"))
        assertEquals("", row.getString("desc"))
        assertEquals("TextView", row.getString("cls"))
        assertEquals("title", row.getString("id"))
        assertEquals(0, row.getInt("index"))
        assertEquals("selected", row.getJSONArray("flags").getString(1))
        assertEquals(100, row.getJSONArray("bounds").getInt(2))
        assertFalse(screen.toString().contains("private"))
    }

    @Test fun nodeAndCharacterCapsAreExplicit() {
        val minimal = AgentLook.Node("", "", "", "", emptyList(), listOf(0, 0, 1, 1))
        val nodes = AgentLook.screen(1080, 2400, generateSequence { minimal }.take(400))
        assertEquals(300, nodes.getJSONArray("nodes").length())
        assertTrue(nodes.getBoolean("truncated"))
        val chars = AgentLook.screen(1080, 2400, generateSequence { node("x".repeat(1000)) }.take(300))
        assertTrue(chars.toString().length <= 40000)
        assertTrue(chars.getBoolean("truncated"))
        assertTrue(chars.getJSONArray("nodes").length() < 300)
        val longText = AgentLook.screen(1080, 2400, sequenceOf(node("x".repeat(1001))))
        assertTrue(longText.getBoolean("truncated"))
    }

    @Test fun plainReplyShowsReasonAndBothDurations() {
        val fake = JSONObject().put("nodes", 12).put("step", "нажать «Ужин»")
            .put("reason", "Это\nнужный раздел").put("model_ms", 1250).put("truncated", true)
        val reply = AgentLook.reply(fake, 2500)
        assertTrue(reply.contains("12 элементов (экран сокращён)"))
        assertTrue(reply.contains("нажать «Ужин»"))
        assertTrue(reply.contains("Это нужный раздел"))
        assertTrue(reply.contains("Всего 2.5 с, модель 1.3 с"))
        assertTrue(reply.endsWith("Без нажатий."))
    }

    @Test fun lookUsesPairedEndpointAndPlainJsonWithoutMealState() {
        var calls = 0
        val fake = JSONObject().put("nodes", 1).put("step", "готово").put("reason", "Цель достигнута").put("model_ms", 9)
        val client = Http.client().newBuilder().addInterceptor { chain ->
            calls++
            assertEquals("/v1/agent/look", chain.request().url.encodedPath)
            assertEquals("Bearer ${"a".repeat(32)}", chain.request().header("Authorization"))
            val buffer = okio.Buffer()
            chain.request().body!!.writeTo(buffer)
            assertEquals("задача", JSONObject(buffer.readUtf8()).getString("task"))
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(okhttp3.ResponseBody.create(null, fake.toString())).build()
        }.build()
        val config = JSONObject().put("pc_consent", true).put("pc_pairing", "a".repeat(32)).put("pc_url", "https://pc.example/")
        val result = PcBrain(config, client).look(JSONObject().put("task", "задача"))
        assertEquals("готово", result.getString("step"))
        assertEquals(1, calls)
    }
}
