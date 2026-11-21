package com.oleg.androidagent

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class ConfirmKeyboardTest {
    private val token = "123456:" + "x".repeat(30)

    private fun sent(confirm: Boolean, parseMode: String? = null): Map<String, String> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"ok\":true,\"result\":{}}"))
            server.start()
            val client = Http.client().newBuilder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            TelegramApi(token, client).send(1, "Расчёт", confirm, parseMode)
            return server.takeRequest().body.readUtf8().split('&').associate { part ->
                val pair = part.split('=', limit = 2)
                URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
            }
        }
    }

    @Test fun confirmReplyCarriesInlineButtonsNotAReplyKeyboardRow() {
        val markup = JSONObject(sent(true).getValue("reply_markup"))
        assertFalse(markup.has("keyboard"))
        val rows = markup.getJSONArray("inline_keyboard")
        assertEquals(2, rows.length())
        assertEquals("✅ Записать", rows.getJSONArray(0).getJSONObject(0).getString("text"))
        assertEquals("meal:confirm", rows.getJSONArray(0).getJSONObject(0).getString("callback_data"))
        assertEquals("❌ Отменить", rows.getJSONArray(1).getJSONObject(0).getString("text"))
        assertEquals("meal:cancel", rows.getJSONArray(1).getJSONObject(0).getString("callback_data"))
    }

    @Test fun ordinaryReplyKeepsTheUsualKeyboardAndPlainText() {
        val fields = sent(false)
        val rows = JSONObject(fields.getValue("reply_markup")).getJSONArray("keyboard")
        assertEquals(2, rows.length())
        assertEquals("Сегодня", rows.getJSONArray(0).getString(0))
        assertFalse(fields.containsKey("parse_mode"))
    }

    @Test fun htmlParseModeIsOptIn() {
        assertEquals("HTML", sent(true, "HTML").getValue("parse_mode"))
    }

    private val config = JSONObject().put("pc_url", "https://pc.example.invalid/")
        .put("pc_pairing", "fixture-pairing-".repeat(3)).put("pc_consent", true)

    @Test fun cancelDraftPostsToThePcAndSurvivesAFailure() {
        val seen = mutableListOf<String>()
        val ok = Http.client().newBuilder().addInterceptor { chain ->
            seen += chain.request().method + " " + chain.request().url.encodedPath
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(okhttp3.ResponseBody.create(null, "{\"cancelled\":true}")).build()
        }.build()
        PcBrain(config, ok).cancelDraft()
        assertEquals(listOf("POST /v1/drafts/cancel"), seen)
        val down = Http.client().newBuilder().addInterceptor { throw java.io.IOException("offline") }.build()
        PcBrain(config, down).cancelDraft()
    }

    @Test fun writeButtonIsAnOrdinaryTextRequest() {
        assertTrue(MealBatch.eligible(JSONObject().put("text", TelegramApi.CONFIRM_BUTTON)))
        assertFalse(CommandReply("x").confirm)
    }
}
