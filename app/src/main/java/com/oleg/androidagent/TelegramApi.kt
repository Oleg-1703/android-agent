package com.oleg.androidagent

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

object TelegramAccess {
    fun allowed(message: JSONObject, owner: Long): Boolean = owner > 0 &&
        message.optJSONObject("chat")?.optString("type") == "private" &&
        message.optJSONObject("chat")?.optLong("id") == owner &&
        message.optJSONObject("from")?.optLong("id") == owner &&
        message.optJSONObject("from")?.optBoolean("is_bot", true) == false &&
        !message.has("forward_origin") && !message.has("forward_date") && !message.has("via_bot")
}
class TelegramApi(private val token: String, private val client: OkHttpClient) {
    init { require(token.matches(Regex("[0-9]+:[A-Za-z0-9_-]{20,}"))) { "Invalid Telegram bot token" } }
    private fun call(method: String, fields: Map<String, String>): JSONObject {
        val form = FormBody.Builder().apply { fields.forEach { (k,v) -> add(k,v) } }.build()
        val json = Http.json(client, Request.Builder().url("https://api.telegram.org/bot$token/$method").post(form).build())
        if (!json.optBoolean("ok")) throw ApiFailure(json.optInt("error_code"), json.optJSONObject("parameters")?.optLong("retry_after") ?: 0)
        return json
    }
    fun updates(offset: Long): List<JSONObject> = call("getUpdates", mapOf(
        "offset" to offset.toString(), "timeout" to "45", "limit" to "20", "allowed_updates" to "[\"message\"]"
    )).objects("result")
    fun send(chat: Long, text: String) {
        text.chunked(3500).forEach { chunk ->
            call("sendMessage", mapOf("chat_id" to chat.toString(), "text" to chunk))
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
