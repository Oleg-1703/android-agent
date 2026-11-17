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
    data class Health(val limited: Boolean, val reset: String, val phone: Boolean = false)
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
        val targets = json.optJSONArray("execution_targets")
        val phone = targets != null && (0 until targets.length()).any { targets.optString(it) == "phone" }
        return Health(json.optBoolean("subscription_limited"), reset, phone)
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
    private fun jobRequest(path: String): Request.Builder {
        require(config.optBoolean("pc_consent")) { "Подключи помощник ПК на вкладке ИИ. Он распознаёт еду, запись выполняется на выбранном устройстве." }
        val token = config.optString("pc_pairing")
        require(token.matches(Regex("[A-Za-z0-9_-]{32,200}"))) { "Сохрани код сопряжения ПК на вкладке ИИ." }
        return Request.Builder().url(endpoint(config.optString("pc_url")).newBuilder().addPathSegments(path).build())
            .header("Authorization", "Bearer $token")
    }
    fun submit(messages: List<JSONObject>, telegram: TelegramApi, phoneStage: () -> String = { "" }, executeOnPhone: ((JSONObject) -> JSONObject)? = null): String {
        require(messages.size in 1..10)
        val payload = JSONObject().put("date", LocalDate.now().toString())
        val descriptions = messages.map { it.optString("text", it.optString("caption")) }.filter { it.isNotBlank() }
        val description = descriptions.joinToString("\n").ifBlank { "Разбери присланные фото еды, этикеток или штрихкодов. Запиши свою еду в дневник." }
        require(description.length <= 4000) { "Описание еды слишком длинное." }
        payload.put("text", description)
        val photos = org.json.JSONArray()
        var voices = 0
        for (message in messages) {
            if (message.has("photo")) {
                val photo = message.objects("photo").filter { it.optLong("file_size") in 1..4_000_000 }.lastOrNull()
                require(photo != null && photos.length() < 4) { "Пришли до четырёх фото по 4 МБ." }
                val bytes = telegram.download(photo.getString("file_id"), photo.optLong("file_size"), 4_000_000)
                photos.put(JSONObject().put("media_type", "image/jpeg").put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)))
            }
            if (message.has("voice")) {
                val voice = message.getJSONObject("voice")
                require(++voices == 1 && voice.optInt("duration") in 1..120) { "Один голосовой комментарий до 120 секунд на запрос." }
                val bytes = telegram.download(voice.getString("file_id"), voice.optLong("file_size"), 4_000_000)
                payload.put("voice", JSONObject().put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)))
            }
        }
        payload.put("images", photos)
        val phone = config.optBoolean("pc_execute_on_phone")
        if (phone) {
            requireNotNull(executeOnPhone) { "Исполнитель на телефоне не готов." }
            require(check().phone) { "Помощник ПК пока не поддерживает запись на телефоне. Обнови помощник; в эмулятор ничего не отправляю." }
            payload.put("target", "phone")
        }
        val identity = config.optString("bot_token") + ":" + config.optString("chat_id") + ":" + messages.joinToString(",") { it.getLong("message_id").toString() }
        val id = java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        payload.put("request_id", id)
        val request = jobRequest("v1/log").post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        store?.put("pc_last_job", id)
        val response = JSONObject(Http.text(client, request, 16000))
        fun finish(result: JSONObject): String {
            if (phone && result.optString("status") in setOf("prepared", "complete", "executing")) {
                require(result.optString("target") == "phone") { "ПК вернул результат другого исполнителя. Автоматически на эмулятор не переключаюсь." }
            }
            return if (result.optString("status") == "prepared" && phone) {
                receipt(phoneRunner(phoneStage, requireNotNull(executeOnPhone)).run(id, result))
            } else receipt(result)
        }
        if (response.optString("status") !in setOf("queued", "running")) return finish(response)
        for (attempt in 0 until 200) {
            Thread.sleep(3000)
            val status = JSONObject(Http.text(client, jobRequest("v1/jobs/$id").get().build(), 16000))
            if (status.optString("status") !in setOf("queued", "running")) return finish(status)
        }
        return "ПК ещё обрабатывает запрос. Не отправляй еду повторно. Результат: /status."
    }
    fun jobStatus(): String {
        val id = store?.get("pc_last_job").orEmpty()
        if (!id.matches(Regex("[a-f0-9]{64}"))) return "Помощник ПК подключён. Опиши еду или пришли фото с голосом."
        phoneRunner { throw IllegalStateException("Status never writes") }.status(id)?.let { return "Последний запрос:\n" + receipt(it) }
        return "Последний запрос:\n" + receipt(JSONObject(Http.text(client, jobRequest("v1/jobs/$id").get().build(), 16000)))
    }
    private fun phoneRunner(stage: () -> String = { "" }, execute: (JSONObject) -> JSONObject) = PhoneMealRunner(requireNotNull(store), object : PhoneMealRunner.Remote {
        private fun post(id: String, action: String, body: JSONObject): JSONObject = JSONObject(Http.text(client,
            jobRequest("v1/jobs/$id/$action").post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build(), 16000))
        override fun claim(id: String) = post(id, "claim", JSONObject())
        override fun report(id: String, result: JSONObject) {
            if (post(id, "receipt", result).optString("status") != "accepted") throw java.io.IOException("ПК не подтвердил квитанцию телефона.")
        }
    }, stage, execute)
    private fun receipt(result: JSONObject): String {
        val savedBeforeFailure = result.objects("receipts").takeIf { it.isNotEmpty() }?.let { rows ->
            "До остановки уже сохранено и проверено:\n" + rows.joinToString("\n") { it.optString("preview") } + "\n\n"
        }.orEmpty()
        return when (result.optString("status")) {
            "queued", "running" -> "Запрос обрабатывается на ПК. Повторять еду не нужно."
            "prepared" -> "ПК распознал еду. Запись на телефоне ещё не началась. Автоматически из /status её не запускаю."
            "executing" -> "Запись выполняется на телефоне. Не отправляй еду повторно."
            "complete" -> "Еда сохранена и проверена в твоём FatSecret:\n" +
                result.objects("receipts").joinToString("\n\n") { it.optString("preview") + if (it.optString("source") == "estimate") "\nКБЖУ и/или вес оценены по фото/описанию." else "" } +
                result.optString("notes").takeIf { it.isNotBlank() }?.let { "\n\n$it" }.orEmpty() +
                result.optJSONArray("sources")?.let { values -> (0 until values.length()).joinToString("\n", "\n") { values.getString(it) } }.orEmpty()
            "failed" -> savedBeforeFailure + result.optString("message", "Запрос не выполнен. Ничего нового не записано.")
            else -> savedBeforeFailure + result.optString("message", "Результат неизвестен. Проверь дневник; повторять запись автоматически нельзя.")
        }
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
