package com.oleg.androidagent

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject
import java.io.File

/** Test APK only. Refuses physical devices and never exports configured secrets. */
class DeviceSmoke : Instrumentation() {
    private var phase = "write"
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); phase = arguments?.getString("phase") ?: "write"; start() }
    private fun waitUntil(label: String, timeout: Long = 30_000, test: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!test() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(200)
        check(test()) { label }
    }
    private fun launch() = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).also { waitForIdleSync() }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun click(activity: Activity, label: String) {
        runOnMainSync {
            val view = views(activity.window.decorView).filterIsInstance<TextView>().firstOrNull { it.text.toString() == label && it.isShown && it.isClickable }
            checkNotNull(view) { "Missing control: $label" }; view.performClick()
        }
        waitForIdleSync(); SystemClock.sleep(300)
    }
    private fun secure(activity: Activity) = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    private fun capture(activity: Activity, name: String) {
        check(SecretStore(targetContext).config().length() == 0) { "Screenshots require empty configuration" }
        runOnMainSync { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        waitForIdleSync(); SystemClock.sleep(400)
        try {
            val bitmap = checkNotNull(uiAutomation.takeScreenshot())
            File(targetContext.cacheDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } finally { runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) } }
    }
    override fun onStart() {
        val result = Bundle()
        try {
            val qemu = uiAutomation.executeShellCommand("getprop ro.kernel.qemu").use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().readText().trim()
            }
            check(qemu == "1") { "Refusing a physical device" }
            val store = SecretStore(targetContext)
            val marker = "device-smoke-not-a-secret"
            when (phase) {
                "read" -> {
                    check(store.get("device_smoke") == marker) { "Keystore decrypt after force-stop failed" }
                    targetContext.getSharedPreferences("agent_private", 0).edit().remove("device_smoke").commit()
                    result.putString("stream", "PASS: encrypted state survives force-stop/process restart\n")
                }
                "ui" -> {
                    check(store.config().length() == 0) { "Use empty configuration" }
                    store.put("service_state", "stopped"); store.put("service_status", "")
                    val activity = launch()
                    try {
                        runOnMainSync { check(secure(activity)) }
                        capture(activity, "home")
                        click(activity, "Запустить")
                        runOnMainSync { check(views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.contains("Токен бота не сохранён") }) }
                        check(!AgentService.workerActive)
                        for ((label, file) in listOf("Telegram" to "telegram", "FatSecret" to "fatsecret", "ИИ" to "ai", "Помощь" to "help")) {
                            click(activity, label); capture(activity, file)
                            runOnMainSync { check(secure(activity)) }
                        }
                        click(activity, "Главная")
                        click(activity, targetContext.getString(R.string.diag_open))
                        runOnMainSync { check(!secure(activity)) }
                        val bitmap = checkNotNull(uiAutomation.takeScreenshot())
                        File(targetContext.cacheDir, "diagnostics.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                        click(activity, "Назад")
                        runOnMainSync { check(secure(activity)) }
                        click(activity, "Telegram")
                        click(activity, "Сохранить")
                        check(store.config().length() == 0) { "Invalid form was persisted" }
                        val fixture = "999999:" + "fixture".repeat(5)
                        fun enter(token: String, chat: String) = runOnMainSync {
                            val fields = views(activity.window.decorView).filterIsInstance<EditText>()
                            fields[0].setText(token); fields[1].setText(chat)
                        }
                        enter(fixture, "-42"); click(activity, "Сохранить")
                        check(store.config().length() == 0) { "Negative owner accepted" }
                        enter(fixture, "12345"); click(activity, "Сохранить")
                        check(store.config().getString("bot_token") == fixture)
                        check(store.config().getString("chat_id") == "12345")
                        runOnMainSync {
                            val tokenField = views(activity.window.decorView).filterIsInstance<EditText>()[0]
                            check(tokenField.text.isEmpty()) { "Secret remains in the field" }
                        }
                        click(activity, "Сохранить")
                        check(store.config().getString("bot_token") == fixture) { "Blank token overwrote saved secret" }
                        result.putString("stream", "PASS: five Russian tabs; start/invalid-owner guards; encrypted form save and blank-secret retention; safe diagnostics; secure flag restored; empty-config screenshots\n")
                    } finally { store.put("config", ""); runOnMainSync { activity.finish() } }
                }
                "configure" -> {
                    check(!AgentService.workerActive && store.config().length() == 0) { "Use a clean stopped emulator" }
                    val file = File(targetContext.filesDir, "test-config.json")
                    val config = try { JSONObject(file.readText()) } finally { file.delete() }
                    require(config.optString("chat_id").toLongOrNull()?.let { it > 0 } == true)
                    val api = TelegramApi(config.getString("bot_token"), Http.client())
                    api.identity(); check(!api.webhookConfigured()) { "Webhook conflict; left unchanged" }
                    store.put("config", config.toString())
                    result.putString("stream", "PASS: live Telegram authentication on Android; no webhook; configuration encrypted; temporary import removed\n")
                }
                "live" -> {
                    check(store.config().optString("bot_token").isNotBlank())
                    val activity = launch()
                    try {
                        val started = System.currentTimeMillis()
                        click(activity, "Запустить")
                        waitUntil("Live service did not connect: " + store.get("service_status"), 90_000) {
                            store.get("service_state") == "listening" &&
                                (store.get("last_contact").toLongOrNull() ?: 0) >= started &&
                                (store.get("last_delivery").toLongOrNull() ?: 0) >= started
                        }
                        check(AgentService.workerActive)
                        runOnMainSync { check(secure(activity)) }
                        click(activity, "Остановить")
                        waitUntil("Live worker did not stop") { !AgentService.workerActive && store.get("service_state") == "stopped" }
                        result.putString("stream", "PASS: live Android getMe/getWebhookInfo/getUpdates/sendMessage; secure screen; UI stop cancels and releases the same live poller\n")
                    } finally {
                        try {
                            targetContext.stopService(Intent(targetContext, AgentService::class.java))
                            waitUntil("Worker did not finish") { !AgentService.workerActive }
                        } finally {
                            store.put("config", "")
                            val prefs = targetContext.getSharedPreferences("agent_private", 0)
                            val edit = prefs.edit()
                            prefs.all.keys.filter { it.startsWith("offset_") }.forEach { edit.remove(it) }
                            check(edit.commit())
                            runOnMainSync { activity.finish() }
                        }
                    }
                }
                else -> {
                    check(store.config().length() == 0) { "Use a clean disposable emulator" }
                    store.put("device_smoke", marker)
                    val raw = targetContext.getSharedPreferences("agent_private", 0).getString("device_smoke", null)!!
                    check(!raw.contains(marker) && raw.contains(':'))
                    check(SecretStore(targetContext).get("device_smoke") == marker)
                    store.put("service_state", "")
                    val activity = launch()
                    runOnMainSync { check(secure(activity)) }
                    targetContext.startForegroundService(Intent(targetContext, AgentService::class.java))
                    waitUntil("Unconfigured worker did not stop safely") { !AgentService.workerActive && store.get("service_state") == "error" }
                    runOnMainSync { activity.finish() }
                    result.putString("stream", "PASS: Activity; FLAG_SECURE; Keystore ciphertext/round-trip; safe unconfigured service stop\n")
                }
            }
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            // Exception messages can contain request URLs. Export the type only.
            val site = t.stackTrace.firstOrNull()?.let { " at ${it.fileName}:${it.lineNumber}" }.orEmpty()
            result.putString("stream", "FAIL: ${t.javaClass.simpleName} in phase $phase$site\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
