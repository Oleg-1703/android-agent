package com.oleg.androidagent

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.LocalDate

/** The pairing token authorizes this private PC, never a paid inference API. */
class PcBrain(private val config: JSONObject, httpClient: OkHttpClient, private val store: StateStore? = null) {
    /** With a store (the running agent) a dead tunnel address is replaced by the one pinned in Telegram and the same request is retried once. */
    private val client: OkHttpClient = if (store == null) httpClient else {
        val token = config.optString("bot_token")
        if (token.isBlank()) httpClient else httpClient.newBuilder().addInterceptor(PcUrlInterceptor(config, PcUrlRecovery(config, store, { chat -> TelegramApi(token, httpClient).chat(chat) }))).build()
    }
    private fun plans(): PhonePlanQueue {
        // The address is not part of the scope: it changes with every tunnel restart and must not orphan queued plans.
        val identity = config.optString("pc_pairing") + ":" + config.optString("bot_token") + ":" + config.optString("chat_id")
        val scope = java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        return PhonePlanQueue(requireNotNull(store), scope)
    }
    fun hasPendingPhone() = config.optBoolean("pc_execute_on_phone") && plans().pending()
    fun resolveFood(item: JSONObject, rows: List<FatUi.Row>, saved: Boolean): Int? {
        require(rows.size in 1..80)
        val body = JSONObject().put("intent", item).put("saved", saved).put("candidates", org.json.JSONArray(rows.map {
            JSONObject().put("name", it.name).put("detail", it.detail)
        }))
        val request = jobRequest("v1/resolve-food").post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val resolver = client.newBuilder().callTimeout(35, java.util.concurrent.TimeUnit.SECONDS).build()
        val index = JSONObject(Http.text(resolver, request, 2000)).getInt("index")
        require(index == -1 || index in rows.indices)
        return index.takeIf { it >= 0 }
    }
    /** Best effort: sends buffered phone events to the PC (`POST /v1/events`). False keeps them for a later attempt. */
    fun sendEvents(body: JSONObject): Boolean {
        if (!config.optBoolean("pc_consent")) return false
        return try {
            val request = jobRequest("v1/events").post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            JSONObject(Http.text(client.newBuilder().callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build(), request, 2000)).optInt("accepted", -1) >= 0
        } catch (e: ApiFailure) { e.status == 400 /* a batch the PC calls malformed would block the queue for ever */ }
        catch (_: Exception) { false }
    }
    fun resumePhone(ready: () -> Boolean, stage: () -> String, session: (() -> JSONObject) -> JSONObject = { it() }, execute: (JSONObject) -> JSONObject): List<String> =
        plans().drain(ready) { id, prepared -> session { phoneRunner(stage, execute).run(id, prepared) } }.map(::receipt)
    /** Best effort: a cancelled estimated draft must not be confirmable by a later "ок". Never breaks the local cancel. */
    fun cancelDraft() {
        try {
            val request = jobRequest("v1/drafts/cancel").post("{}".toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            Http.text(client.newBuilder().callTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build(), request, 2000)
        } catch (_: java.io.IOException) {
        } catch (_: IllegalArgumentException) {
        }
    }
    fun cancelPendingPhone(): List<String> = plans().drain({ true }) { id, prepared ->
        phoneRunner { error("Cancel never executes") }.cancel(id, prepared)
    }.map(::receipt)
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
    /** True when the last PC result is an estimated meal shown for confirmation; the reply then gets the "Записать" button. */
    @Volatile var lastConfirm = false
        private set
    /** Progress texts of the poll loop (one attempt per [POLL_MILLIS]); neutral, since the request may be a correction or a question. */
    object Progress {
        const val POLL_MILLIS = 3000L
        const val THINKING = "Думаю…"
        const val STILL = "Всё ещё считаю, отвечу здесь."
        fun message(attempt: Int): String? = when {
            attempt == 1 -> THINKING                       // ~6 s after the job was accepted
            attempt >= 20 && attempt % 20 == 0 -> STILL    // then at most every ~60 s
            else -> null
        }
    }
    fun submit(messages: List<JSONObject>, telegram: TelegramApi, phoneStage: () -> String = { "" }, progress: (String) -> Unit = {}, phoneReady: () -> Boolean = { true }, phoneSession: (() -> JSONObject) -> JSONObject = { it() }, executeOnPhone: ((JSONObject) -> JSONObject)? = null): String {
        require(messages.size in 1..12)
        val payload = JSONObject().put("date", LocalDate.now().toString())
        val descriptions = messages.map { it.optString("text", it.optString("caption")) }.filter { it.isNotBlank() }
        val description = descriptions.joinToString("\n").ifBlank { "Разбери присланные фото еды, этикеток или штрихкодов. Запиши свою еду в дневник." }
        require(description.length <= 4000) { "Описание еды слишком длинное." }
        payload.put("text", description)
        val photos = org.json.JSONArray()
        var voices = 0
        var photoBytes = 0L
        for (message in messages) {
            if (message.has("photo")) {
                val photo = message.objects("photo").filter { it.optLong("file_size") in 1..4_000_000 }.lastOrNull()
                require(photo != null && photos.length() < 10) { "Пришли до 10 фото по 4 МБ в одном запросе." }
                photoBytes += photo.optLong("file_size")
                require(photoBytes <= 16_000_000) { "Фото в одном запросе вместе больше 16 МБ. Пришли меньше фото." }
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
        payload.put("capabilities", org.json.JSONArray().put("rich_table"))
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
        val callStarted = System.nanoTime()
        val response = try { JSONObject(Http.text(client, request, 16000)) } catch (e: Exception) {
            Telemetry.log("pc_call", Telemetry.requestHash(id), false, (System.nanoTime() - callStarted) / 1_000_000, Telemetry.errClass(e), flags = mapOf("call" to "log"))
            throw e
        }
        Telemetry.log("pc_call", Telemetry.requestHash(id), true, (System.nanoTime() - callStarted) / 1_000_000, flags = mapOf("call" to "log"))
        fun finish(result: JSONObject): String {
            lastConfirm = result.optString("status") == "failed" && result.optBoolean("confirm")
            if (lastConfirm) MealTable.draft(result)?.let { return it }
            if (phone && result.optString("status") in setOf("prepared", "complete", "executing")) {
                require(result.optString("target") == "phone") { "ПК вернул результат другого исполнителя. Автоматически на эмулятор не переключаюсь." }
            }
            return if (result.optString("status") == "prepared" && phone) {
                phoneRunner(phoneStage, requireNotNull(executeOnPhone)).status(id)?.let { return receipt(it) }
                plans().add(id, result)
                val completed = resumePhone(phoneReady, phoneStage, phoneSession, requireNotNull(executeOnPhone))
                val pending = if (plans().contains(id)) "ИИ распознал еду. План сохранён в очередь. Можно выключить экран. Запишу автоматически после разблокировки телефона; повторно отправлять еду не нужно."
                else ""
                MealTable.join(completed + listOf(pending).filter { it.isNotBlank() })
            } else receipt(result)
        }
        if (response.optString("status") !in setOf("queued", "running")) return finish(response)
        for (attempt in 0 until 200) {
            Thread.sleep(Progress.POLL_MILLIS)
            val status = try { JSONObject(Http.text(client, jobRequest("v1/jobs/$id").get().build(), 16000)) } catch (e: Exception) {
                Telemetry.log("pc_call", Telemetry.requestHash(id), false, errClass = Telemetry.errClass(e), flags = mapOf("call" to "poll"))
                throw e
            }
            if (status.optString("status") !in setOf("queued", "running")) return finish(status)
            Progress.message(attempt)?.let(progress)
        }
        return "ПК ещё обрабатывает запрос. Не отправляй еду повторно. Результат: /status."
    }
    fun jobStatus(): String {
        val id = store?.get("pc_last_job").orEmpty()
        if (!id.matches(Regex("[a-f0-9]{64}"))) return "Помощник ПК подключён. Опиши еду или пришли фото с голосом."
        if (plans().contains(id)) return "Еда распознана и ждёт готовности телефона: включённого экрана, снятой блокировки и подключённой службы управления Android Agent. Повторно отправлять не нужно."
        phoneRunner { throw IllegalStateException("Status never writes") }.status(id)?.let { return lastRequest(receipt(it)) }
        return lastRequest(receipt(JSONObject(Http.text(client, jobRequest("v1/jobs/$id").get().build(), 16000))))
    }
    private fun lastRequest(text: String) = if (MealTable.isMarked(text)) MealTable.join(listOf("Последний запрос:", text)) else "Последний запрос:\n$text"
    private fun phoneRunner(stage: () -> String = { "" }, execute: (JSONObject) -> JSONObject) = PhoneMealRunner(requireNotNull(store), object : PhoneMealRunner.Remote {
        private fun post(id: String, action: String, body: JSONObject): JSONObject = JSONObject(Http.text(client.newBuilder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build(),
            jobRequest("v1/jobs/$id/$action").post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build(), 16000))
        override fun claim(id: String) = post(id, "claim", JSONObject())
        override fun report(id: String, result: JSONObject) {
            // table/meal are phone-side presentation only; the PC receipt schema does not carry them.
            val wire = JSONObject(result.toString()).apply { remove("table"); remove("meal") }
            if (post(id, "receipt", wire).optString("status") != "accepted") throw java.io.IOException("ПК не подтвердил квитанцию телефона.")
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
            "complete" -> MealTable.receipt(result) ?: ("Еда сохранена и проверена в твоём FatSecret:\n" +
                result.objects("receipts").joinToString("\n\n") { it.optString("preview") + if (it.optString("source") == "estimate") "\nКБЖУ и/или вес оценены по фото/описанию." else "" } +
                result.optString("notes").takeIf { it.isNotBlank() }?.let { "\n\n$it" }.orEmpty() +
                result.optJSONArray("sources")?.let { values -> (0 until values.length()).joinToString("\n", "\n") { values.getString(it) } }.orEmpty())
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
