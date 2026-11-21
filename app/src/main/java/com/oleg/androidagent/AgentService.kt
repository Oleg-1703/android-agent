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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class AgentService : Service() {
    companion object { @Volatile var workerActive = false; private set }
    private val worker = Executors.newSingleThreadExecutor()
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
            var failures = 0
            var firstPoll = true
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
                    status("listening", if (commands.hasPendingPhone()) "Еда распознана. Жду разблокировки для записи, экран удерживать не нужно."
                        else "На связи. Открой бота в Telegram и отправь /help.")
                    if (firstPoll) {
                        firstPoll = false
                        telegram.send(owner, "Android Agent запущен. Управление FatSecret через экран, без developer-ключей.\nНажми «Помощь», чтобы начать.")
                        store.put("last_delivery", System.currentTimeMillis().toString())
                    }
                    val queue = java.util.ArrayDeque(updates)
                    while (queue.isNotEmpty()) {
                        val update = queue.removeFirst()
                        if (!running || Thread.currentThread().isInterrupted) break
                        val id = update.getLong("update_id")
                        if (id < offset) continue
                        // Persist before dispatch: an interrupted command must never replay a diary write.
                        offset = id + 1
                        store.put(offsetKey, offset.toString())
                        val message = update.optJSONObject("message") ?: continue
                        if (!TelegramAccess.allowed(message, owner)) continue
                        val rejection = TelegramFreshness.rejection(message, telegram.serverTimeSeconds())
                        if (rejection != null) {
                            telegram.send(owner, rejection.reply)
                            continue
                        }
                        store.put("last_message", System.currentTimeMillis().toString())
                        if (config.optBoolean("pc_consent") && MealBatch.eligible(message)) {
                            val destination = if (config.optBoolean("pc_execute_on_phone")) "на этом телефоне" else "в эмуляторе ПК"
                            val waiting = if (MealBatch.waitingForVoice(listOf(message))) "Жду голосовой комментарий к фото. Экран можно выключить." else "Собираю описание для ИИ на ПК. Экран можно выключить."
                            telegram.send(owner, "Принял. $waiting Еду запишу в FatSecret $destination.")
                            val batch = mutableListOf(message)
                            val started = SystemClock.elapsedRealtime()
                            var deadline = started + MealBatch.graceMillis(batch)
                            var cancelled = false
                            while (running && batch.size < 10 && !Thread.currentThread().isInterrupted) {
                                if (queue.isEmpty()) {
                                    if (SystemClock.elapsedRealtime() >= deadline) break
                                    val seconds = ((deadline - SystemClock.elapsedRealtime() + 999) / 1000).coerceIn(1, 45).toInt()
                                    queue.addAll(telegram.updates(offset, seconds))
                                    continue
                                }
                                val next = queue.first
                                val nextId = next.getLong("update_id")
                                if (nextId < offset) { queue.removeFirst(); continue }
                                val extra = next.optJSONObject("message")
                                if (extra != null && TelegramAccess.allowed(extra, owner) && !MealBatch.eligible(extra)) {
                                    val control = extra.optString("text").trim().lowercase()
                                    cancelled = control.takeWhile { !it.isWhitespace() }.substringBefore('@') in setOf("/cancel", "/stop") ||
                                        control in setOf("отмена", "отмени", "отмени черновик", "останови агента")
                                    break
                                }
                                queue.removeFirst()
                                offset = nextId + 1
                                store.put(offsetKey, offset.toString())
                                if (extra == null || !TelegramAccess.allowed(extra, owner)) continue
                                val stale = TelegramFreshness.rejection(extra, telegram.serverTimeSeconds())
                                if (stale != null) { telegram.send(owner, stale.reply); continue }
                                batch.add(extra)
                                deadline = minOf(started + 180_000, SystemClock.elapsedRealtime() + MealBatch.graceMillis(batch))
                            }
                            if (cancelled || !running) continue
                            message.put("agent_batch", org.json.JSONArray(batch.map { org.json.JSONObject(it.toString()) }))
                        }
                        val reply = try { commands.handle(message, telegram) }
                        catch (e: IllegalArgumentException) { CommandReply(e.message?.take(700) ?: "Проверь формат сообщения. Помощь: /help.") }
                        catch (_: Exception) { CommandReply("Запрос не выполнен. Проверь подключение и настройки на телефоне. Запись в дневник автоматически не повторялась. Состояние: /status.") }
                        if (running) {
                            telegram.send(owner, reply.text, reply.confirm)
                            store.put("last_delivery", System.currentTimeMillis().toString())
                        }
                        if (reply.stop) { running = false; break }
                    }
                    if (running) for (receipt in commands.resumePhone(telegram)) {
                        telegram.send(owner, receipt)
                        store.put("last_delivery", System.currentTimeMillis().toString())
                    }
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
        unlockEvents?.close()
        unlockEvents = null
        workerThread?.interrupt()
        client.dispatcher.cancelAll()
        worker.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
