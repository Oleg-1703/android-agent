package com.oleg.androidagent

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONArray

object TelegramAccess {
    fun allowed(message: JSONObject, owner: Long): Boolean = owner > 0 &&
        message.optJSONObject("chat")?.optString("type") == "private" &&
        message.optJSONObject("chat")?.optLong("id") == owner &&
        message.optJSONObject("from")?.optLong("id") == owner &&
        message.optJSONObject("from")?.optBoolean("is_bot", true) == false &&
        !message.has("forward_origin") && !message.has("forward_date") && !message.has("via_bot")
}
class TelegramApi(private val token: String, private val client: OkHttpClient, private val clock: TelegramClock = TelegramClock()) {
    fun progress(chat: Long, text: String) {
        val bounded = client.newBuilder().callTimeout(3, java.util.concurrent.TimeUnit.SECONDS).build()
        TelegramApi(token, bounded, clock).send(chat, text)
    }
    init { require(token.matches(Regex("[0-9]+:[A-Za-z0-9_-]{20,}"))) { "Invalid Telegram bot token" } }
    private fun call(method: String, fields: Map<String, String>): JSONObject {
        val form = FormBody.Builder().apply { fields.forEach { (k,v) -> add(k,v) } }.build()
        val request = Request.Builder().url("https://api.telegram.org/bot$token/$method").post(form).build()
        val json = JSONObject(Http.text(client, request, onHeaders = { clock.observe(it["Date"]) }))
        if (!json.optBoolean("ok")) throw ApiFailure(json.optInt("error_code"), json.optJSONObject("parameters")?.optLong("retry_after") ?: 0)
        return json
    }
    fun identity(): JSONObject = call("getMe", emptyMap()).getJSONObject("result")
    fun serverTimeSeconds(): Long? = clock.nowSeconds()
    fun webhookConfigured(): Boolean = call("getWebhookInfo", emptyMap()).getJSONObject("result").optString("url").isNotBlank()
    fun updates(offset: Long, timeout: Int = 45): List<JSONObject> = call("getUpdates", mapOf(
        "offset" to offset.toString(), "timeout" to timeout.coerceIn(0, 45).toString(), "limit" to "20", "allowed_updates" to "[\"message\"]"
    )).objects("result")
    fun send(chat: Long, text: String, confirm: Boolean = false) {
        text.chunked(3500).forEach { chunk ->
            call("sendMessage", mapOf("chat_id" to chat.toString(), "text" to chunk, "reply_markup" to keyboard(confirm).toString()))
        }
    }
    companion object {
        const val CONFIRM_BUTTON = "✅ Записать"
        fun keyboard(confirm: Boolean = false): JSONObject {
            val rows = JSONArray()
            if (confirm) rows.put(JSONArray().put(CONFIRM_BUTTON))
            rows.put(JSONArray().put("Сегодня").put("Статус")).put(JSONArray().put("Помощь").put("Отмена"))
            return JSONObject().put("keyboard", rows).put("resize_keyboard", true).put("input_field_placeholder", "Команда или описание еды")
        }
    }
    fun download(fileId: String, declaredSize: Long, maxBytes: Long): ByteArray {
        require(declaredSize in 1..maxBytes) { "Attachment missing size or too large" }
        val file = call("getFile", mapOf("file_id" to fileId)).getJSONObject("result")
        val path = file.getString("file_path")
        require(!path.contains("..") && !path.startsWith("/") && !path.contains(":") && !path.contains("?"))
        val url = "https://api.telegram.org/file/bot$token/".toHttpUrl().newBuilder().addPathSegments(path).build()
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw ApiFailure(r.code)
            val body = r.body ?: throw ApiFailure()
            require(body.contentLength() <= maxBytes)
            val source = body.source()
            source.request(maxBytes + 1)
            val bytes = source.buffer.readByteArray()
            require(bytes.size <= maxBytes) { "Attachment too large" }
            return bytes
        }
    }
}
