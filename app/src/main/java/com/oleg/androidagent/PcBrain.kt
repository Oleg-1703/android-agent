package com.oleg.androidagent

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.LocalDate

/** The pairing token authorizes this private PC, never a paid inference API. */
class PcBrain(private val config: JSONObject, private val client: OkHttpClient) {
    fun parse(text: String): JSONObject {
        require(config.optBoolean("pc_consent")) { "Включи ИИ по подписке на вкладке ИИ. Сообщение будет отправлено на твой ПК и в Claude." }
        require(text.length in 1..4000) { "Пришли текст еды длиной до 4000 символов." }
        val endpoint = endpoint(config.optString("pc_url"))
        val token = config.optString("pc_pairing")
        require(token.length in 32..200 && token.all { it.isLetterOrDigit() || it in "_-" }) { "Подключи ПК в разделе ИИ. API-ключ не нужен." }
        val request = Request.Builder().url(endpoint.newBuilder().addPathSegments("v1/meal").build())
            .header("Authorization", "Bearer $token")
            .post(JSONObject().put("text", text).put("date", LocalDate.now().toString()).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val response = try { JSONObject(Http.text(client, request, 16000)) }
        catch (e: ApiFailure) {
            throw IllegalArgumentException(when(e.status) {
                401 -> "ПК не принял код сопряжения. Проверь подключение в разделе ИИ."
                429 -> "ПК занят предыдущим запросом. Подожди и отправь сообщение заново."
                503, 504 -> "Подписка на ПК сейчас недоступна или исчерпан лимит. Платный API не подключался. Можно использовать /meal."
                else -> "Не удалось связаться с ПК. Проверь, что ПК и помощник включены. Можно использовать /meal."
            })
        }
        require(response.optString("question").isBlank()) { response.optString("question").take(500) }
        return Meals.validate(response)
    }
    companion object {
        fun endpoint(value: String): okhttp3.HttpUrl {
            val url = try { value.trim().toHttpUrl() } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Укажи HTTPS-адрес своего ПК.")
            }
            require(url.isHttps && url.username.isBlank() && url.password.isBlank() && url.query == null && url.fragment == null && url.encodedPath == "/") {
                "Нужен HTTPS-адрес ПК без пути, логина и параметров."
            }
            return url
        }
    }
}
