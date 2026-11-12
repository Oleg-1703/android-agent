package com.oleg.androidagent

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var store: SecretStore
    private lateinit var status: TextView
    private val fields = linkedMapOf<String, EditText>()
    private val secrets = setOf("bot_token", "fs_key", "fs_secret", "llm_key")
    private val worker = Executors.newSingleThreadExecutor()
    private val client = Http.client()
    private var busy = false
    private lateinit var consent: CheckBox
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        store = SecretStore(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,32,24,24) }
        setContentView(ScrollView(this).apply { addView(layout) })
        layout.addView(TextView(this).apply { text = "Android Agent — FatSecret\nConfigure on this phone. Never send credentials to the bot. Saving stops the agent; Start enables polling. Existing diary writes require Telegram review."; textSize = 18f })
        status = TextView(this); layout.addView(status)
        val config = try { store.config() } catch (_: Exception) { status.text = "Keystore data unavailable. Clear app data and configure again."; return }
        fun field(key: String, label: String, default: String = "") {
            layout.addView(TextView(this).apply { text = label })
            val edit = EditText(this).apply {
                isSingleLine = true; isSaveEnabled = false
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                inputType = if (key in secrets) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                if (key !in secrets) setText(config.optString(key, default))
                else hint = if (config.optString(key).isNotBlank()) "Saved — leave blank to keep" else "Enter on phone"
            }
            fields[key] = edit; layout.addView(edit)
        }
        fun button(label: String, action: () -> Unit) { layout.addView(Button(this).apply { text=label; setOnClickListener { if (!busy) try { action() } catch (_: Exception) { status.text="Action failed. Check configuration." } } }) }
        field("bot_token", "Telegram bot token")
        field("chat_id", "Allowed private chat / sender ID (positive number)")
        field("fs_key", "FatSecret developer Consumer Key")
        field("fs_secret", "FatSecret Consumer Secret")
        field("llm_url", "OpenAI-compatible HTTPS API base", "https://api.openai.com/v1")
        field("llm_key", "Model API key (optional)")
        field("llm_model", "Vision/text model", "gpt-4o-mini")
        field("speech_model", "Transcription model", "whisper-1")
        consent = CheckBox(this).apply { text="Allow authorized food text/photos/voice to be sent to my configured AI provider (may incur costs)"; isChecked=config.optBoolean("ai_consent") }
        layout.addView(consent)
        button("Save configuration (stops agent)") { save() }
        button("Authorize existing FatSecret account") {
            stopService(Intent(this, AgentService::class.java))
            background {
                val c = store.config()
                val token = FatSecretApi(c, client).requestToken()
                if (!isDestroyed) {
                    store.put("oauth_pending", token.toString())
                    runOnUiThread {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://authentication.fatsecret.com/oauth/authorize?oauth_token=" + OAuth1.encode(token.getString("oauth_token")))))
                        status.text="Sign in to the existing account in the browser, then enter the displayed verifier below."
                    }
                }
                "Authorization opened in browser"
            }
        }
        val verifier = EditText(this).apply { hint="FatSecret verifier code"; isSaveEnabled=false; inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; importantForAutofill=android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS }
        layout.addView(verifier)
        button("Complete FatSecret authorization") {
            val code=verifier.text.toString().trim(); verifier.setText("")
            background {
                val pending=JSONObject(store.get("oauth_pending"))
                val c=store.config()
                val token=FatSecretApi(c,client).accessToken(pending,code)
                if (!isDestroyed) {
                    c.put("fs_token",token.getString("oauth_token")).put("fs_token_secret",token.getString("oauth_token_secret"))
                    store.put("config",c.toString()); store.put("oauth_pending", ""); store.put("pending", "")
                }
                "Existing account authorized. Verify diary visibility with the read-only check."
            }
        }
        button("Check today's existing diary (read only)") {
            background { val entries=FatSecretApi(store.config(),client).entries(java.time.LocalDate.now().toString()); "Read ${entries.size} entries for today. Compare count with FatSecret before the first write." }
        }
        button("Start Telegram agent") {
            val c=store.config()
            require(c.optString("chat_id").toLongOrNull()?.let { it > 0 } == true)
            TelegramApi(c.optString("bot_token"),client)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            startForegroundService(Intent(this,AgentService::class.java)); status.text="Agent starting; see persistent notification."
        }
        button("Stop agent") { stopService(Intent(this,AgentService::class.java)); status.text="Agent stopped" }
        button("Refresh status") { status.text=store.get("service_status")+"\n"+store.get("last_result")+if(store.get("write_intent").isNotBlank()) "\nUncertain write: inspect FatSecret before continuing." else "" }
        button("I inspected the diary: acknowledge uncertain write") {
            if (AgentService.workerActive) {
                stopService(Intent(this,AgentService::class.java)); status.text="Agent stopping. Wait for requests to finish, inspect the diary, then acknowledge again."
                return@button
            }
            AlertDialog.Builder(this).setTitle("Inspect FatSecret first")
                .setMessage("This only clears the write block. It does not retry, delete or verify anything. Check partial/duplicate entries manually and send only missing items in a new command.")
                .setNegativeButton("Cancel",null).setPositiveButton("I checked the diary") { _,_ ->
                    stopService(Intent(this,AgentService::class.java)); store.put("write_intent", ""); store.put("pending", ""); status.text="Block acknowledged. Start agent and send only missing items."
                }.show()
        }
        button("Accessibility settings") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button("Open FatSecret") {
            val launch=packageManager.getLaunchIntentForPackage(FatAccessibilityService.FATSECRET_PACKAGE)
            if(launch!=null) startActivity(launch) else status.text="FatSecret not installed or package not visible"
        }
        button("Battery settings / Realme setup") { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        button("Disconnect FatSecret") {
            stopService(Intent(this,AgentService::class.java))
            val c=store.config(); c.remove("fs_token");c.remove("fs_token_secret");store.put("config",c.toString());store.put("pending", "");store.put("oauth_pending", "")
            status.text="Local FatSecret tokens removed. Revoke app access in FatSecret as well if needed."
        }
        status.text="Configured locally. Agent requires explicit Start."
    }
    private fun save() {
        val c=store.config()
        val oldKey=c.optString("fs_key"); val oldSecret=c.optString("fs_secret")
        fields.forEach { (key,edit) -> val value=edit.text.toString().trim(); if (key !in secrets || value.isNotBlank()) c.put(key,value) }
        require(c.optString("chat_id").toLongOrNull()?.let { it>0 } == true)
        TelegramApi(c.optString("bot_token"),client)
        if(consent.isChecked) {
            val url=c.getString("llm_url").toHttpUrl()
            require(url.isHttps && url.username.isBlank() && url.password.isBlank() && url.query==null && url.fragment==null)
            require(c.optString("llm_key").isNotBlank())
        }
        c.put("ai_consent",consent.isChecked)
        if(oldKey!=c.optString("fs_key") || oldSecret!=c.optString("fs_secret")) { c.remove("fs_token");c.remove("fs_token_secret");store.put("oauth_pending", "") }
        stopService(Intent(this,AgentService::class.java))
        store.put("config",c.toString());store.put("pending", "")
        secrets.forEach { fields[it]?.setText(""); fields[it]?.hint="Saved — leave blank to keep" }
        status.text="Saved encrypted configuration. Start agent when ready."
    }
    private fun background(action: () -> String) {
        busy=true; status.text="Connecting…"
        worker.submit {
            val result=try { action() } catch (_: Exception) { "Connection/authorization failed. Check network, credentials and account API access." }
            runOnUiThread { busy=false; if(!isDestroyed) status.text=result }
        }
    }
    override fun onDestroy() { client.dispatcher.cancelAll(); worker.shutdownNow(); super.onDestroy() }
}
