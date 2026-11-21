package com.oleg.androidagent

import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PcHealthTest {
    private val config = JSONObject().put("pc_url", "https://pc.example.invalid/")
        .put("pc_pairing", "fixture-pairing-".repeat(3)).put("pc_consent", false)
    @Test fun healthUsesOnlyGetAndDoesNotRequireFoodUploadConsent() {
        var calls = 0
        val client = Http.client().newBuilder().addInterceptor { chain ->
            calls++
            assertEquals("GET", chain.request().method)
            assertEquals("/health", chain.request().url.encodedPath)
            assertNull(chain.request().body)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(null, """{"status":"ready","provider":"claude-subscription"}""")).build()
        }.build()
        assertFalse(PcBrain(config, client).check().limited)
        assertEquals(1, calls)
    }
    @Test fun resetHintCannotExposeArbitraryServerText() {
        val client = Http.client().newBuilder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(null, """{"status":"ready","provider":"claude-subscription","subscription_limited":true,"reset":"private-value"}""")).build()
        }.build()
        val health = PcBrain(config, client).check()
        assertTrue(health.limited)
        assertEquals("", health.reset)
    }
    @Test fun oldServerCannotSilentlyExecutePhoneRequestOnEmulator() {
        val selected = JSONObject(config.toString()).put("pc_consent", true).put("pc_execute_on_phone", true)
        var requests = 0
        val client = Http.client().newBuilder().addInterceptor { chain ->
            requests++
            assertEquals("/health", chain.request().url.encodedPath)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(null, """{"status":"ready","provider":"claude-subscription"}""")).build()
        }.build()
        var writes = 0
        val message = JSONObject().put("message_id", 1).put("text", "Breakfast fixture100g")
        val token = "1:" + "x".repeat(24)
        try {
            PcBrain(selected, client).submit(listOf(message), TelegramApi(token, client)) { writes++; JSONObject() }
            fail()
        } catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("не поддерживает")) }
        assertEquals(0, writes); assertEquals(1, requests)
    }
}
