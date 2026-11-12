package com.oleg.androidagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class AgentService : Service() {
    companion object { @Volatile var workerActive = false; private set }
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var workerThread: Thread? = null
    private val client = Http.client()
    @Volatile private var running = false
    private lateinit var store: SecretStore
    override fun onCreate() { super.onCreate(); store = SecretStore(this) }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun notification(status: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("agent", "Telegram agent", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, AgentService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "agent").setContentTitle("Android Agent").setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }
    private fun status(message: String) {
        store.put("service_status", message)
        getSystemService(NotificationManager::class.java).notify(1, notification(message))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        startForeground(1, notification("Starting authorized Telegram polling"))
        if (!running && workerActive) {
            status("Previous worker is stopping. Wait, then Start again.")
            stopSelf(); return START_NOT_STICKY
        }
        if (!running && !workerActive) { running = true; workerActive = true; worker.submit { workerThread = Thread.currentThread(); try { poll() } finally { workerThread = null } } }
        // Explicit user start after process death/reboot; no unexpected background activation.
        return START_NOT_STICKY
    }
    private fun poll() {
        try {
            if (!running) return
            val config = store.config()
            val owner = config.optString("chat_id").toLongOrNull() ?: 0L
            require(owner > 0) { "Configure a positive private Telegram chat ID" }
            val token = config.optString("bot_token")
            val telegram = TelegramApi(token, client)
            val scope = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
            val offsetKey = "offset_$scope"
            var offset = store.get(offsetKey).toLongOrNull() ?: 0L
            val engine = MealEngine(store, FatSecretApi(config, client))
            var failures = 0
            status("Listening to configured private Telegram chat")
            while (running && !Thread.currentThread().isInterrupted) {
                try {
                    val updates = telegram.updates(offset)
                    for (update in updates) {
                        if (!running || Thread.currentThread().isInterrupted) break
                        val id = update.getLong("update_id")
                        if (id < offset) continue
                        // At-most-once dispatch; a crash can lose a command but cannot replay a write.
                        offset = id + 1
                        store.put(offsetKey, offset.toString())
                        val message = update.optJSONObject("message") ?: continue
                        if (!TelegramAccess.allowed(message, owner)) continue
                        val age = System.currentTimeMillis()/1000 - message.optLong("date")
                        if (age !in 0..300) continue
                        val answer = try { handle(message, telegram, engine, config) }
                        catch (e: IllegalArgumentException) { e.message?.take(700) ?: "Invalid input. Send /help." }
                        catch (_: Exception) { "Request failed. Check phone status, network and credentials. No diary write was retried. Send /status." }
                        if (running) telegram.send(owner, answer)
                    }
                    failures = 0
                    if (running) status("Listening to configured private Telegram chat")
                } catch (e: Exception) {
                    if (!running || Thread.currentThread().isInterrupted) break
                    failures = (failures + 1).coerceAtMost(8)
                    val retry = (e as? ApiFailure)?.retrySeconds ?: 0
                    if ((e as? ApiFailure)?.status in listOf(401,403,409)) {
                        status("Polling stopped: credentials, chat access or another poller conflict; fix and restart")
                        break
                    }
                    status("Network/API error; waiting to reconnect")
                    val delay = maxOf(retry.coerceIn(0,3600), (1L shl failures).coerceAtMost(60))
                    TimeUnit.MILLISECONDS.sleep(delay * 1000 + Random.nextLong(500))
                }
            }
        } catch (_: Exception) {
            try { status("Agent stopped: check encrypted configuration") } catch (_: Exception) { }
        } finally { running = false; workerActive = false; stopSelf() }
    }
    private fun handle(message: JSONObject, telegram: TelegramApi, engine: MealEngine, config: JSONObject): String {
        val text = message.optString("text", message.optString("caption")).trim()
        when {
            text == "/help" || text == "/start" -> return "Commands:\n/meal YYYY-MM-DD lunch food cooked=200;rice cooked=150\n/pick ITEM CHOICE\n/confirm CODE\n/cancel\n/status\n/stop\nMeal types: breakfast, lunch, dinner, other. Natural text/photo/voice requires AI consent in the app. All diary writes require review."
            text == "/status" -> return "Agent active. " + (if (store.get("write_intent").isNotBlank()) "Uncertain write blocked; inspect diary and acknowledge in app. " else "No uncertain write. ") + store.get("last_result")
            text == "/stop" -> { stopSelf(); return "Agent stopping. Restart from the phone app." }
            text == "/cancel" -> return engine.cancel()
            text.startsWith("/pick ") -> {
                val parts = text.split(Regex("\\s+"))
                require(parts.size == 3) { "Use /pick ITEM CHOICE" }
                return engine.pick(parts[1].toInt(), parts[2].toInt())
            }
            text.startsWith("/confirm ") -> return engine.confirm(text.removePrefix("/confirm ").trim())
            text.startsWith("/meal ") -> return engine.prepare(Meals.parse(text))
            text.startsWith("/") -> return "Unknown command. Send /help."
        }
        require(config.optBoolean("ai_consent")) { "Text/photo/voice AI uploads are disabled. Configure AI and consent on the phone or use /meal." }
        val ai = AiMeals(config, client)
        val voice = message.optJSONObject("voice")
        if (voice != null) {
            require(voice.optInt("duration") in 1..120) { "Voice limit: 120 seconds" }
            val bytes = telegram.download(voice.getString("file_id"), voice.optLong("file_size"), 8_000_000)
            return engine.prepare(ai.parse(ai.transcribe(bytes)))
        }
        val photo = message.objects("photo").filter { it.optLong("file_size") in 1..4_000_000 }.lastOrNull()
        if (message.has("photo")) {
            require(photo != null) { "Photo limit: 4 MB" }
            val bytes = telegram.download(photo.getString("file_id"), photo.optLong("file_size"), 4_000_000)
            return engine.prepare(ai.parse(text, bytes))
        }
        require(text.isNotBlank()) { "Send text, a photo with explicit portions, or a voice message." }
        return engine.prepare(ai.parse(text))
    }
    override fun onDestroy() {
        running = false
        workerThread?.interrupt()
        client.dispatcher.cancelAll()
        // Let a queued-but-not-yet-started task enter finally and release workerActive.
        worker.shutdown()
        try {
            val last = store.get("service_status")
            if (last.startsWith("Listening") || last.startsWith("Network")) store.put("service_status", "Stopped. Start the agent from the phone app.")
        } catch (_: Exception) { }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
