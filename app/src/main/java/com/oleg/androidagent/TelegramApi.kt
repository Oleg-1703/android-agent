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
/** Inline-button presses: same owner/chat rules as messages, and only buttons this bot attached. */
object TelegramCallbacks {
    const val MAX_AGE_SECONDS = 2 * 60 * 60L
    sealed class Decision {
        object Ignore : Decision()
        /** Known button, but on a message that is too old or whose age cannot be proven. */
        object Stale : Decision()
        data class Accept(val confirm: Boolean) : Decision()
    }
    fun decide(callback: JSONObject, owner: Long, serverNow: Long?): Decision {
        val from = callback.optJSONObject("from")
        val message = callback.optJSONObject("message")
        val chat = message?.optJSONObject("chat")
        if (owner <= 0 || from == null || message == null || chat == null) return Decision.Ignore
        if (from.optLong("id") != owner || from.optBoolean("is_bot", true) || chat.optLong("id") != owner || chat.optString("type") != "private") return Decision.Ignore
        val confirm = when (callback.optString("data")) {
            TelegramApi.CALLBACK_CONFIRM -> true
            TelegramApi.CALLBACK_CANCEL -> false
            else -> return Decision.Ignore
        }
        val sent = message.optLong("date")
        // A cancel can never create a diary entry, so only the confirm needs a provable age.
        if (confirm && (serverNow == null || sent <= 0 || serverNow - sent > MAX_AGE_SECONDS || serverNow - sent < -30)) return Decision.Stale
        return Decision.Accept(confirm)
    }
    /** A button acts only on the newest draft message the bot sent, and only once ([marker] is cleared on use). */
    fun isCurrentDraft(messageId: Long, marker: String) = messageId > 0 && marker.toLongOrNull() == messageId
    /** The owner text message a button press stands for. Its id is derived from the unique callback id, so the PC request identity is stable per press. */
    fun asMessage(callback: JSONObject, confirm: Boolean): JSONObject {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(("callback:" + callback.optString("id")).toByteArray())
        var id = 0L
        for (i in 0 until 8) id = (id shl 8) or (digest[i].toLong() and 0xff)
        val chat = callback.getJSONObject("message").getJSONObject("chat")
        return JSONObject().put("message_id", (id and Long.MAX_VALUE) or (1L shl 40)).put("chat", chat)
            .put("from", callback.getJSONObject("from")).put("date", callback.getJSONObject("message").optLong("date"))
            .put("text", if (confirm) TelegramApi.CONFIRM_BUTTON else "Отмена")
    }
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
    fun chat(chat: Long): JSONObject = call("getChat", mapOf("chat_id" to chat.toString())).getJSONObject("result")
    fun serverTimeSeconds(): Long? = clock.nowSeconds()
    fun webhookConfigured(): Boolean = call("getWebhookInfo", emptyMap()).getJSONObject("result").optString("url").isNotBlank()
    fun updates(offset: Long, timeout: Int = 45): List<JSONObject> = call("getUpdates", mapOf(
        "offset" to offset.toString(), "timeout" to timeout.coerceIn(0, 45).toString(), "limit" to "20", "allowed_updates" to "[\"message\",\"callback_query\"]"
    )).objects("result")
    /** [parseMode] "HTML" only for text built by [MealTable]; it is kept in one chunk so a <pre> block is never cut. */
    /** Returns the id of the last message sent. A Telegram 400 on HTML (bad markup) falls back to the plain text, so a reply is never lost. */
    fun send(chat: Long, text: String, confirm: Boolean = false, parseMode: String? = null): Long {
        val chunks = if (parseMode == null) text.chunked(3500) else listOf(text.take(4000))
        var last = 0L
        chunks.forEach { chunk ->
            fun post(body: String, mode: String?) = call("sendMessage", mutableMapOf("chat_id" to chat.toString(), "text" to body,
                "reply_markup" to (if (confirm) confirmButtons() else keyboard()).toString()).apply { if (mode != null) put("parse_mode", mode) })
            val sent = try { post(chunk, parseMode) }
            catch (error: ApiFailure) { if (parseMode != null && error.status == 400) post(MealTable.plain(chunk), null) else throw error }
            last = sent.optJSONObject("result")?.optLong("message_id") ?: 0L
        }
        return last
    }
    fun answerCallback(id: String, text: String = "") {
        call("answerCallbackQuery", mutableMapOf("callback_query_id" to id).apply { if (text.isNotBlank()) put("text", text.take(150)) })
    }
    /** Best effort: removes the inline buttons of an already handled message. */
    fun clearButtons(chat: Long, messageId: Long) {
        call("editMessageReplyMarkup", mapOf("chat_id" to chat.toString(), "message_id" to messageId.toString(),
            "reply_markup" to JSONObject().put("inline_keyboard", JSONArray()).toString()))
    }
    companion object {
        const val CONFIRM_BUTTON = "✅ Записать"
        const val CALLBACK_CONFIRM = "meal:confirm"
        const val CALLBACK_CANCEL = "meal:cancel"
        fun keyboard(): JSONObject {
            val rows = JSONArray().put(JSONArray().put("Сегодня").put("Статус")).put(JSONArray().put("Помощь").put("Отмена"))
            return JSONObject().put("keyboard", rows).put("resize_keyboard", true).put("input_field_placeholder", "Команда или описание еды")
        }
        fun confirmButtons(): JSONObject = JSONObject().put("inline_keyboard", JSONArray()
            .put(JSONArray().put(JSONObject().put("text", CONFIRM_BUTTON).put("callback_data", CALLBACK_CONFIRM)))
            .put(JSONArray().put(JSONObject().put("text", "❌ Отменить").put("callback_data", CALLBACK_CANCEL))))
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
