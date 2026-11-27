package com.oleg.androidagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import java.security.MessageDigest
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class AgentService : Service() {
    companion object { @Volatile var workerActive = false; private set }
    private val worker = Executors.newSingleThreadExecutor()
    private val onboarding = Executors.newSingleThreadExecutor()
    @Volatile private var workerThread: Thread? = null
    @Volatile private var idlePolling = false
    @Volatile private var waitingForPhone = false
    private val unlockSignal = UnlockSignal()
    private var unlockEvents: PhoneUnlockEvents? = null
    private val client = Http.client().newBuilder().addInterceptor { chain ->
        if (!idlePolling || !chain.request().url.encodedPath.endsWith("/getUpdates")) chain.proceed(chain.request())
        else {
            val call = chain.call()
            unlockSignal.begin(call)
            try { chain.proceed(chain.request()) } finally { unlockSignal.end(call) }
        }
    }.build()
    @Volatile private var running = false
    private lateinit var store: SecretStore
    override fun onCreate() {
        super.onCreate()
        store = SecretStore(this)
        Telemetry.buffer = EventBuffer(store)
        unlockEvents = PhoneUnlockEvents(this) { if (running && waitingForPhone) unlockSignal.request() }
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(message: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("agent", "Связь с Telegram", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, AgentService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "agent").setContentTitle("Android Agent").setContentText(message)
            .setSmallIcon(R.drawable.ic_stat_agent).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Остановить", stop).build()).build()
    }

    private fun status(state: String, message: String) {
        store.put("service_state", state)
        store.put("service_status", message)
        if (running) getSystemService(NotificationManager::class.java).notify(1, notification(message))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        startForeground(1, notification("Подключение к Telegram…"))
        if (!running && workerActive) {
            status("stopped", "Предыдущий запуск ещё завершается. Подожди несколько секунд и запусти снова.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            workerActive = true
            status("starting", "Проверяем токен и подключение…")
            worker.submit {
                workerThread = Thread.currentThread()
                try { poll() } finally { workerThread = null }
            }
        }
        return START_NOT_STICKY
    }

    private fun poll() {
        try {
            if (!running) return
            val config = store.config()
            val owner = config.optString("chat_id").toLongOrNull() ?: 0L
            require(owner > 0)
            val token = config.optString("bot_token")
            val telegram = TelegramApi(token, client, TelegramClock { SystemClock.elapsedRealtime() })
            val scope = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
            val offsetKey = "offset_$scope"
            var offset = store.get(offsetKey).toLongOrNull() ?: 0L
            val commands = AgentCommands(store, config, FatSecretApi(config, client), client, FatUi(this))
            val accepted = PcBrain(config, client, store).acceptedMessages()
            var recoveryRows = accepted.rows()
            if (recoveryRows.isNotEmpty()) {
                offset = maxOf(offset, recoveryRows.maxOf { it.getLong("offset") })
                store.put(offsetKey, offset.toString())
            }
            var failures = 0
            val info = try { packageManager.getPackageInfo(packageName, 0) } catch (_: Exception) { null }
            Telemetry.log("app_start", n = mapOf("version_code" to (info?.longVersionCode ?: 0L).toInt(), "sdk" to android.os.Build.VERSION.SDK_INT),
                flags = mapOf("version" to (info?.versionName?.takeIf { it.matches(Regex("[A-Za-z0-9_.+-]{1,32}")) } ?: "unknown"),
                    "write_secure" to (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED),
                    "phone_exec" to config.optBoolean("pc_execute_on_phone"), "pc_consent" to config.optBoolean("pc_consent")))
            val flusher = Telemetry.buffer?.let { buffer -> EventFlusher(buffer) { body -> PcBrain(config, client, store).sendEvents(body) } }
            val a11y = if (config.optBoolean("pc_execute_on_phone")) AccessibilityGuard.create(this, store) { telegram.send(owner, it) }.also { AccessibilityGuard.current = it } else null
            var firstPoll = true
            fun quiet(action: () -> Unit) { try { action() } catch (_: java.io.IOException) { } catch (_: ApiFailure) { } }
            fun deliver(reply: CommandReply) {
                val sentId = telegram.send(owner, reply.text, reply.confirm, if (reply.html) "HTML" else null)
                if (reply.confirm && sentId > 0) {
                    // Only the newest draft keeps live buttons: an older draft must never confirm the newer plan.
                    val previous = store.get("confirm_msg").toLongOrNull()
                    store.put("confirm_msg", sentId.toString())
                    if (previous != null && previous != sentId) quiet { telegram.clearButtons(owner, previous) }
                }
                store.put("last_delivery", System.currentTimeMillis().toString())
            }
            fun respond(message: JSONObject): Boolean {
                val reply = try { commands.handle(message, telegram) }
                catch (e: IllegalArgumentException) { CommandReply(e.message?.take(700) ?: "Проверь формат сообщения. Помощь: /help.") }
                catch (_: Exception) { CommandReply("Запрос не выполнен. Проверь подключение и настройки на телефоне. Запись в дневник автоматически не повторялась. Состояние: /status.") }
                val control = message.optString("text", message.optString("caption")).trim().lowercase()
                if (reply.stop || control.substringBefore(' ').substringBefore('@') == "/cancel" || control in setOf("отмена", "отмени", "отмени черновик")) accepted.clear()
                if (running) {
                    deliver(reply)
                    accepted.remove(message.getLong("message_id"))
                }
                return reply.stop
            }
            while (running && !Thread.currentThread().isInterrupted) {
                try {
                    if (firstPoll) {
                        val identity = telegram.identity()
                        store.put("telegram_username", identity.optString("username"))
                        if (telegram.webhookConfigured()) {
                            status("error", "У бота включён webhook. Отключи его в прежнем сервисе или используй отдельного бота. Настройки автоматически не менялись.")
                            return
                        }
                    }
                    a11y?.ensure()
                    // A successful getMe alone does not prove that this bot can poll.
                    waitingForPhone = commands.hasPendingPhone()
                    val updates = try {
                        idlePolling = true
                        telegram.updates(offset, if (firstPoll) 0 else 45)
                    } catch (error: java.io.IOException) {
                        if (!running) break
                        if (!unlockSignal.consume()) throw error
                        idlePolling = false
                        telegram.updates(offset, 0)
                    } finally { idlePolling = false }
                    unlockSignal.consume()
                    if (!running) break
                    store.put("last_contact", System.currentTimeMillis().toString())
                    flusher?.flush()
                    status("listening", if (commands.hasPendingPhone()) "Есть незавершённый запрос. Жду ответа ПК или готовности телефона."
                        else "На связи. Открой бота в Telegram и отправь /help.")
                    if (firstPoll) {
                        firstPoll = false
                        onboarding.submit {
                            val bounded = client.newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
                            TelegramApi(token, bounded).setupOwner(owner, scope, store)
                        }
                    }
                    val queue = java.util.ArrayDeque(updates)
                    while (queue.isNotEmpty()) {
                        val update = queue.removeFirst()
                        if (!running || Thread.currentThread().isInterrupted) break
                        val id = update.getLong("update_id")
                        if (id < offset) continue
                        val acceptedMessage = update.optJSONObject("message")
                        if (config.optBoolean("pc_consent") && acceptedMessage != null && TelegramAccess.allowed(acceptedMessage, owner) &&
                            MealBatch.eligible(acceptedMessage) && TelegramFreshness.rejection(acceptedMessage, telegram.serverTimeSeconds()) == null) {
                            accepted.accept(listOf(acceptedMessage), id + 1)
                        }
                        val acceptedCallback = update.optJSONObject("callback_query")
                        if (config.optBoolean("pc_consent") && acceptedCallback != null) {
                            val decision = TelegramCallbacks.decide(acceptedCallback, owner, telegram.serverTimeSeconds())
                            if (decision is TelegramCallbacks.Decision.Accept && decision.confirm &&
                                TelegramCallbacks.isCurrentDraft(acceptedCallback.getJSONObject("message").optLong("message_id"), store.get("confirm_msg"))) {
                                accepted.accept(listOf(TelegramCallbacks.asMessage(acceptedCallback, true)), id + 1)
                            }
                        }
                        offset = id + 1
                        store.put(offsetKey, offset.toString())
                        val callback = update.optJSONObject("callback_query")
                        if (callback != null) {
                            val callbackId = callback.optString("id")
                            when (val decision = TelegramCallbacks.decide(callback, owner, telegram.serverTimeSeconds())) {
                                is TelegramCallbacks.Decision.Ignore -> quiet { telegram.answerCallback(callbackId) }
                                is TelegramCallbacks.Decision.Stale -> {
                                    quiet { telegram.answerCallback(callbackId, "Кнопка устарела") }
                                    quiet { telegram.send(owner, "Этому сообщению больше двух часов. Опиши еду заново или напиши «ок» к свежему расчёту.") }
                                }
                                is TelegramCallbacks.Decision.Accept -> {
                                    val source = callback.getJSONObject("message")
                                    val current = TelegramCallbacks.isCurrentDraft(source.optLong("message_id"), store.get("confirm_msg"))
                                    quiet { telegram.answerCallback(callbackId, if (!current) "Кнопка устарела" else if (decision.confirm) "Записываю…" else "Отменяю") }
                                    quiet { telegram.clearButtons(owner, source.getLong("message_id")) }
                                    if (!current) {
                                        // Double tap, tap after a cancel/confirm, or tap on an older draft: nothing is sent to the PC.
                                        quiet { telegram.send(owner, "Эта кнопка относится к уже обработанному или старому расчёту. Используй кнопки под последним расчётом или опиши еду заново.") }
                                        continue
                                    }
                                    store.put("confirm_msg", "")
                                    store.put("last_message", System.currentTimeMillis().toString())
                                    val pressed = TelegramCallbacks.asMessage(callback, decision.confirm)
                                    // The confirmation is an ordinary owner text; no batching grace for a button.
                                    if (decision.confirm) pressed.put("agent_batch", org.json.JSONArray().put(JSONObject(pressed.toString())))
                                    if (respond(pressed)) { running = false }
                                }
                            }
                            if (!running) break else continue
                        }
                        val message = update.optJSONObject("message") ?: continue
                        if (!TelegramAccess.allowed(message, owner)) continue
                        val rejection = TelegramFreshness.rejection(message, telegram.serverTimeSeconds())
                        if (rejection != null) {
                            telegram.send(owner, rejection.reply)
                            continue
                        }
                        store.put("last_message", System.currentTimeMillis().toString())
                        if (config.optBoolean("pc_consent") && MealBatch.eligible(message)) {
                            val batch = mutableListOf(message)
                            var acked = false
                            MealBatch.ack(batch)?.let { telegram.send(owner, it); acked = true }
                            val started = SystemClock.elapsedRealtime()
                            var deadline = started + MealBatch.graceMillis(batch)
                            var cancelled = false
                            while (running && batch.size < 12 && !Thread.currentThread().isInterrupted) {
                                if (queue.isEmpty()) {
                                    if (SystemClock.elapsedRealtime() >= deadline) break
                                    val seconds = ((deadline - SystemClock.elapsedRealtime() + 999) / 1000).coerceIn(1, 45).toInt()
                                    queue.addAll(telegram.updates(offset, seconds))
                                    continue
                                }
                                val next = queue.first
                                val nextId = next.getLong("update_id")
                                if (nextId < offset) { queue.removeFirst(); continue }
                                val pending = next.optJSONObject("callback_query")
                                if (pending != null) {
                                    // Buttons are handled by the main loop; a cancel press also drops the batch being collected.
                                    cancelled = pending.optString("data") == TelegramApi.CALLBACK_CANCEL &&
                                        TelegramCallbacks.decide(pending, owner, telegram.serverTimeSeconds()) is TelegramCallbacks.Decision.Accept
                                    break
                                }
                                val extra = next.optJSONObject("message")
                                if (extra != null && TelegramAccess.allowed(extra, owner) && !MealBatch.eligible(extra)) {
                                    val control = extra.optString("text", extra.optString("caption")).trim().lowercase()
                                    cancelled = control.takeWhile { !it.isWhitespace() }.substringBefore('@') in setOf("/cancel", "/stop") ||
                                        control in setOf("отмена", "отмени", "отмени черновик", "останови агента")
                                    break
                                }
                                queue.removeFirst()
                                if (extra != null && TelegramAccess.allowed(extra, owner) && TelegramFreshness.rejection(extra, telegram.serverTimeSeconds()) == null) {
                                    accepted.accept(batch + extra, nextId + 1)
                                }
                                offset = nextId + 1
                                store.put(offsetKey, offset.toString())
                                if (extra == null || !TelegramAccess.allowed(extra, owner)) continue
                                val stale = TelegramFreshness.rejection(extra, telegram.serverTimeSeconds())
                                if (stale != null) { telegram.send(owner, stale.reply); continue }
                                batch.add(extra)
                                deadline = minOf(started + 180_000, SystemClock.elapsedRealtime() + MealBatch.graceMillis(batch))
                            }
                            if (cancelled) accepted.remove(message.getLong("message_id"))
                            if (cancelled || !running) continue
                            if (!acked) MealBatch.ack(batch)?.let { telegram.send(owner, it) }
                            message.put("agent_batch", org.json.JSONArray(batch.map { org.json.JSONObject(it.toString()) }))
                        }
                        if (respond(message)) { running = false; break }
                    }
                    if (running && recoveryRows.isNotEmpty()) {
                        val rows = recoveryRows
                        recoveryRows = emptyList()
                        for (row in rows) {
                            if (accepted.rows().none { it.getLong("id") == row.getLong("id") }) continue
                            offset = maxOf(offset, row.getLong("offset"))
                            store.put(offsetKey, offset.toString())
                            val message = accepted.recover(row, telegram.serverTimeSeconds())
                            if (message == null) {
                                telegram.send(owner, "Не удалось восстановить принятый запрос: он устарел или уже восстанавливался. Проверь /status и пришли еду заново, если она не записана.")
                                accepted.remove(row.getLong("id"))
                            } else if (respond(message)) { running = false; break }
                        }
                    }
                    if (running) for (receipt in commands.resumePhone(telegram)) deliver(receipt)
                    failures = 0
                } catch (e: Exception) {
                    if (!running || Thread.currentThread().isInterrupted) break
                    val code = (e as? ApiFailure)?.status
                    if (code in listOf(401, 403, 404, 409)) {
                        status("error", when (code) {
                            401, 404 -> "Telegram отклонил токен ($code). Проверь токен в настройках и запусти снова."
                            403 -> "Бот не может написать тебе (403). Открой его в Telegram, нажми «Начать» и проверь ID владельца."
                            else -> "Конфликт Telegram (409): этот же бот запущен на другом устройстве или в другом сервисе. Останови второй приёмник и запусти снова."
                        })
                        break
                    }
                    failures = (failures + 1).coerceAtMost(8)
                    val retry = (e as? ApiFailure)?.retrySeconds ?: 0
                    val delay = maxOf(retry.coerceIn(0, 3600), (1L shl failures).coerceAtMost(60))
                    status("reconnecting", "Нет связи с Telegram. Повтор через $delay с. Проверь интернет.")
                    TimeUnit.MILLISECONDS.sleep(delay * 1000 + Random.nextLong(500))
                }
            }
        } catch (e: Exception) {
            if (running) {
                val code = (e as? ApiFailure)?.status
                try {
                    status("error", when (code) {
                        401 -> "Telegram отклонил токен (401). Исправь его в настройках."
                        403 -> "Доступ к боту запрещён (403). Проверь настройки Telegram."
                        else -> if (e is IllegalArgumentException) "Агент не настроен. Сохрани токен и положительный ID в разделе Telegram."
                                else "Не удалось подключиться. Проверь интернет и настройки Telegram, затем запусти снова."
                    })
                } catch (_: Exception) { /* Storage may be unavailable; the worker must still stop. */ }
            }
        } finally {
            running = false
            try {
                if (store.get("service_state") != "error") status("stopped", "Агент остановлен. Запуск - кнопкой в приложении.")
            } finally {
                workerActive = false
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        running = false
        AccessibilityGuard.current = null
        unlockEvents?.close()
        unlockEvents = null
        workerThread?.interrupt()
        client.dispatcher.cancelAll()
        onboarding.shutdownNow()
        worker.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
