package com.oleg.androidagent

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.LocalDate

/** The pairing token authorizes this private PC, never a paid inference API. */
class PcBrain(private val config: JSONObject, private val client: OkHttpClient, private val store: StateStore? = null) {
    data class Health(val limited: Boolean, val reset: String)
    fun check(): Health {
        val endpoint = endpoint(config.optString("pc_url"))
        val token = config.optString("pc_pairing")
        require(token.matches(Regex("[A-Za-z0-9_-]{32,200}"))) { "Введи код сопряжения из помощника на ПК." }
        val request = Request.Builder().url(endpoint.newBuilder().addPathSegment("health").build())
            .header("Authorization", "Bearer $token").get().build()
        val checker = client.newBuilder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
        val json = try { JSONObject(Http.text(checker, request, 4000)) }
        catch (e: ApiFailure) {
            throw IllegalArgumentException(if (e.status == 401) "ПК не принял код сопряжения." else "ПК недоступен. Проверь помощник и HTTPS-адрес.")
        }
        require(json.optString("status") == "ready" && json.optString("provider") == "claude-subscription") { "По этому адресу не найден помощник Android Agent." }
        val reset = json.optString("reset").takeIf { it.matches(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) }.orEmpty()
        return Health(json.optBoolean("subscription_limited"), reset)
    }
    fun parse(text: String, photo: ByteArray? = null, voice: ByteArray? = null): JSONObject {
        require(config.optBoolean("pc_consent")) { "Включи ИИ по подписке на вкладке ИИ. Сообщение будет отправлено на твой ПК и в Claude." }
        require(text.length in 1..4000) { "Пришли текст еды длиной до 4000 символов." }
        val endpoint = endpoint(config.optString("pc_url"))
        val token = config.optString("pc_pairing")
        require(token.matches(Regex("[A-Za-z0-9_-]{32,200}"))) { "Подключи ПК в разделе ИИ. API-ключ не нужен." }
        require(photo == null || voice == null)
        val payload = JSONObject().put("text", text).put("date", LocalDate.now().toString())
        val conversation = store?.get("pc_dialogue").orEmpty()
        if (conversation.matches(Regex("[a-f0-9-]{36}"))) payload.put("conversation", conversation)
        if (photo != null) {
            require(photo.size in 1..4_000_000)
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(photo, 0, photo.size, bounds)
            require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096) { "Фотография слишком большая или повреждена. Пришли её как фото в Telegram." }
            val mime = if (photo.size >= 3 && photo[0] == 0xff.toByte() && photo[1] == 0xd8.toByte()) "image/jpeg" else "image/png"
            payload.put("image", JSONObject().put("media_type", mime).put("data", android.util.Base64.encodeToString(photo, android.util.Base64.NO_WRAP)))
        }
        if (voice != null) {
            require(voice.size in 1..4_000_000)
            payload.put("voice", JSONObject().put("data", android.util.Base64.encodeToString(voice, android.util.Base64.NO_WRAP)))
        }
        val request = Request.Builder().url(endpoint.newBuilder().addPathSegments("v1/meal").build())
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val response = try { JSONObject(Http.text(client, request, 16000)) }
        catch (e: ApiFailure) {
            throw IllegalArgumentException(when(e.status) {
                401 -> "ПК не принял код сопряжения. Проверь подключение в разделе ИИ."
                429 -> if (e.reason == "subscription_limit") "Достигнут лимит подписки Claude." +
                    (if (e.resetHint.isNotBlank()) " Сброс в ${e.resetHint} по времени ПК." else "") +
                    " Платный API не подключался. Пока можно использовать /meal."
                    else "ПК занят предыдущим запросом. Подожди и отправь сообщение заново."
                410 -> {
                    store?.put("pc_dialogue", "")
                    "Контекст уточнения истёк или ПК перезапустился. Пришли описание еды или фото заново."
                }
                503, 504 -> "Подписка на ПК сейчас недоступна или исчерпан лимит. Платный API не подключался. Можно использовать /meal."
                else -> "Не удалось связаться с ПК. Проверь, что ПК и помощник включены. Можно использовать /meal."
            })
        }
        val nextConversation = response.optString("conversation")
        store?.put("pc_dialogue", if (nextConversation.matches(Regex("[a-f0-9-]{36}"))) nextConversation else "")
        require(response.optString("question").isBlank()) {
            (response.optString("transcript").takeIf { it.isNotBlank() }?.let { "Распознал: ${it.take(400)}\n" }.orEmpty()) + response.optString("question").take(500)
        }
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
