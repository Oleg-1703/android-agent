package com.oleg.androidagent

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class MainActivity : Activity() {
    /** Read-only view of persisted state; contains booleans and public status text, never credentials. */
    private data class Snap(
        val configOk: Boolean = true, val active: Boolean = false,
        val state: String = "", val status: String = "", val contact: String = "", val message: String = "",
        val username: String = "", val writeIntent: Boolean = false,
        val tokenSaved: Boolean = false, val tokenValid: Boolean = false, val chatOk: Boolean = false,
        val fsKeys: Boolean = false, val fsToken: Boolean = false, val aiConsent: Boolean = false, val aiKey: Boolean = false
    )
    private class Outcome(val tone: Ui.Tone, val text: String, val after: (() -> Unit)? = null)

    private lateinit var store: SecretStore
    private lateinit var ui: Ui
    private val client = Http.client()
    private val worker = Executors.newSingleThreadExecutor()
    private val reader = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false
    private var stopping = false
    private var snap = Snap()
    private var seq = 0
    private var applied = 0
    private var startedAt = 0L
    private var tab = 0
    private var diagnosticsOpen = false
    private var resumed = false
    private var dialog: AlertDialog? = null

    private lateinit var root: LinearLayout
    private lateinit var strip: TextView
    private lateinit var pageHost: LinearLayout
    private lateinit var scroll: ScrollView
    private val tabViews = mutableListOf<TextView>()
    private val pages = mutableListOf<View>()
    private lateinit var diagRoot: View
    private lateinit var diagBody: TextView

    // dashboard
    private lateinit var stateCard: LinearLayout
    private lateinit var stateTitle: TextView
    private lateinit var stateDetail: TextView
    private lateinit var stateMeta: TextView
    private lateinit var feedback: TextView
    private lateinit var writeCard: LinearLayout
    private lateinit var writeNote: TextView
    private lateinit var setupSummary: TextView
    private val rowPill = mutableListOf<TextView>()
    private val rowDetail = mutableListOf<TextView>()
    // telegram
    private lateinit var tgToken: Ui.Field
    private lateinit var tgChat: Ui.Field
    private lateinit var tgResult: TextView
    // fatsecret
    private lateinit var fsKey: Ui.Field
    private lateinit var fsSecret: Ui.Field
    private lateinit var fsVerifier: Ui.Field
    private lateinit var fsStatus: TextView
    private lateinit var fsResult: TextView
    // ai
    private lateinit var consent: CheckBox
    private lateinit var aiUrl: Ui.Field
    private lateinit var aiKey: Ui.Field
    private lateinit var aiModel: Ui.Field
    private lateinit var aiSpeech: Ui.Field
    private lateinit var aiResult: TextView
    // help
    private lateinit var helpResult: TextView

    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        @Suppress("DEPRECATION") window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        store = SecretStore(this); ui = Ui(this)
        tab = savedInstanceState?.getInt("tab") ?: 0
        val config = try { store.config() } catch (_: Exception) { null }
        buildRoot(config)
        buildDiagnostics()
        setContentView(root)
        styleSystemBars()
        selectTab(tab)
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putInt("tab", tab) }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (diagnosticsOpen) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        handler.removeCallbacks(tick); handler.post(tick)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(tick)
        // Never leave the window screenshot-able while the app is in the background or recents.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onPause()
    }

    @Deprecated("Closes the diagnostics screen before leaving")
    override fun onBackPressed() {
        if (diagnosticsOpen) { closeDiagnostics(); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        dialog?.dismiss()
        client.dispatcher.cancelAll(); worker.shutdownNow(); reader.shutdownNow()
        super.onDestroy()
    }

    // ---------- layout ----------

    private fun buildRoot(config: JSONObject?) = with(ui) {
        root = column().apply { setBackgroundColor(color(R.color.bg)) }
        val header = column().apply { setPadding(dp(20), dp(16), dp(20), dp(8)) }
        header.add(title(getString(R.string.app_title)))
        strip = text("", 13f, bold = true).apply {
            gravity = Gravity.CENTER_VERTICAL; minHeight = dp(32); setPadding(dp(14), dp(4), dp(14), dp(4))
        }
        header.addView(strip, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        root.add(header)

        val tabRow = row().apply { setPadding(dp(20), dp(4), dp(20), dp(8)) }
        val labels = intArrayOf(R.string.tab_home, R.string.tab_telegram, R.string.tab_fatsecret, R.string.tab_ai, R.string.tab_help)
        labels.forEachIndexed { i, res ->
            val t = text(getString(res), 15f, bold = true).apply {
                gravity = Gravity.CENTER; minHeight = dp(48); minimumHeight = dp(48)
                setPadding(dp(18), 0, dp(18), 0); isClickable = true; isFocusable = true
                setOnClickListener { selectTab(i) }
            }
            tabViews += t
            tabRow.addView(t, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        root.addView(HorizontalScrollView(this@MainActivity).apply { isHorizontalScrollBarEnabled = false; addView(tabRow) }, LinearLayout.LayoutParams(-1, -2))

        pageHost = column().apply { setPadding(dp(20), dp(8), dp(20), dp(32)) }
        scroll = ScrollView(this@MainActivity).apply { isFillViewport = true; addView(pageHost) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        pages += dashboardPage(); pages += telegramPage(config); pages += fatSecretPage(config); pages += aiPage(config); pages += helpPage()
    }

    private fun styleSystemBars() {
        @Suppress("DEPRECATION") window.statusBarColor = ui.color(R.color.bg)
        @Suppress("DEPRECATION") window.navigationBarColor = ui.color(R.color.bg)
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(light, light)
        listOf(root, diagRoot).forEach { v ->
            v.setOnApplyWindowInsetsListener { view, ins ->
                val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(b.left, b.top, b.right, b.bottom); WindowInsets.CONSUMED
            }
        }
        root.requestApplyInsets()
    }

    private fun selectTab(i: Int) {
        tab = i
        tabViews.forEachIndexed { n, t ->
            val on = n == i
            t.setBackgroundResource(if (on) R.drawable.bg_tab_on else R.drawable.bg_tab_off)
            t.setTextColor(ui.color(if (on) R.color.white else R.color.navy))
            t.stateDescription = if (on) getString(R.string.tab_selected) else null
        }
        tabViews[i].post {
            val selected = tabViews[i]
            selected.requestRectangleOnScreen(android.graphics.Rect(0, 0, selected.width, selected.height), true)
        }
        pageHost.removeAllViews(); pageHost.addView(pages[i], LinearLayout.LayoutParams(-1, -2))
        scroll.scrollTo(0, 0)
        strip.visibility = if (i == 0) View.GONE else View.VISIBLE
    }

    private fun pageTitle(page: LinearLayout, title: Int, subtitle: Int) = with(ui) {
        page.add(heading(getString(title)).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f) })
        page.add(body(getString(subtitle)).apply { setTextColor(color(R.color.muted)) }, 6)
    }

    private fun resultView() = ui.text("", 14f).apply {
        visibility = View.GONE; setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    private fun show(view: TextView, tone: Ui.Tone, msg: CharSequence) {
        view.text = msg; view.background = ui.tinted(tone); view.setTextColor(ui.color(tone.fg)); view.visibility = View.VISIBLE
    }

    private fun pill(): TextView = ui.text("", 12f, bold = true).apply {
        gravity = Gravity.CENTER; setPadding(ui.dp(12), ui.dp(5), ui.dp(12), ui.dp(5))
    }
    private fun setPill(p: TextView, tone: Ui.Tone, res: Int) {
        p.text = getString(res); p.background = ui.tinted(tone); p.setTextColor(ui.color(tone.fg))
    }

    private fun selectable(): Drawable? {
        val a = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        return a.getDrawable(0).also { a.recycle() }
    }

    // ---------- dashboard ----------

    private fun dashboardPage(): View = with(ui) {
        val page = column()
        stateCard = column().apply { setPadding(dp(20), dp(20), dp(20), dp(20)) }
        stateTitle = text("", 24f, bold = true).apply { isAccessibilityHeading = true }
        stateDetail = body("")
        stateMeta = small("")
        feedback = resultView()
        stateCard.add(small(getString(R.string.state_label)).apply { setTextColor(color(R.color.navy)) })
        stateCard.add(stateTitle, 2); stateCard.add(stateDetail, 6); stateCard.add(stateMeta, 6)
        val stacked = resources.configuration.screenWidthDp < 360 || resources.configuration.fontScale > 1.15f
        val buttons = if (stacked) column() else row()
        val start = button(getString(R.string.start)) { startAgent() }
        val stop = button(getString(R.string.stop), Ui.Style.SECONDARY) { stopAgent() }
        if (stacked) { buttons.add(start); buttons.add(stop, 8) }
        else { buttons.addWeighted(start, 10); buttons.addWeighted(stop) }
        stateCard.add(buttons, 16)
        stateCard.add(button(getString(R.string.open_bot), Ui.Style.SECONDARY) {
            if (snap.username.isNotBlank()) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/${snap.username}"))) }
                catch (_: ActivityNotFoundException) { show(feedback, Ui.Tone.WARN, getString(R.string.no_browser)) }
            } else show(feedback, Ui.Tone.WARN, getString(R.string.open_bot_check))
        }, 10)
        stateCard.add(feedback, 12)
        page.add(stateCard)

        writeCard = card().apply { visibility = View.GONE; background = tinted(Ui.Tone.WARN) }
        writeCard.add(heading(getString(R.string.write_title)).apply { setTextColor(color(R.color.warn_fg)) })
        writeCard.add(body(getString(R.string.write_body)).apply { setTextColor(color(R.color.warn_fg)) }, 6)
        writeCard.add(button(getString(R.string.write_ack), Ui.Style.SECONDARY) { acknowledge() }, 12)
        writeNote = resultView(); writeCard.add(writeNote, 10)
        page.add(writeCard, 14)

        val setup = card()
        setup.add(heading(getString(R.string.setup_title)))
        setupSummary = small(""); setup.add(setupSummary, 4)
        val names = intArrayOf(R.string.tab_telegram, R.string.tab_fatsecret, R.string.tab_ai)
        names.forEachIndexed { i, name ->
            val left = column()
            left.add(body(getString(name)).apply { typeface = Typeface.DEFAULT_BOLD })
            val detail = small(""); left.add(detail, 2)
            val p = pill()
            rowPill += p; rowDetail += detail
            val r = row().apply {
                gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56); isClickable = true; isFocusable = true
                background = selectable(); setOnClickListener { selectTab(i + 1) }
            }
            r.addWeighted(left, 10)
            r.addView(p, LinearLayout.LayoutParams(-2, -2))
            setup.add(r, if (i == 0) 8 else 0)
        }
        page.add(setup, 14)

        val how = card()
        how.add(heading(getString(R.string.how_title)))
        listOf(R.string.how_1, R.string.how_2, R.string.how_3, R.string.how_4).forEach { how.add(body(getString(it)), 8) }
        page.add(how, 14)
        page.add(button(getString(R.string.diag_open), Ui.Style.SECONDARY) { openDiagnostics() }, 14)
        page
    }

    private fun startAgent() {
        if (!snap.configOk) { show(feedback, Ui.Tone.ERR, getString(R.string.storage_broken)); return }
        if (AgentService.workerActive) {
            show(feedback, if (stopping) Ui.Tone.WARN else Ui.Tone.OK, getString(if (stopping) R.string.start_wait_stop else R.string.start_already))
            return
        }
        val c = try { store.config() } catch (_: Exception) { show(feedback, Ui.Tone.ERR, getString(R.string.storage_broken)); return }
        val token = c.optString("bot_token")
        when {
            token.isBlank() -> { show(feedback, Ui.Tone.WARN, getString(R.string.start_no_token)); return }
            !validToken(token) -> { show(feedback, Ui.Tone.WARN, getString(R.string.start_bad_token)); return }
            c.optString("chat_id").toLongOrNull()?.let { it > 0 } != true -> { show(feedback, Ui.Tone.WARN, getString(R.string.start_no_chat)); return }
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        try {
            startedAt = System.currentTimeMillis()
            startForegroundService(Intent(this, AgentService::class.java))
            show(feedback, Ui.Tone.IDLE, getString(R.string.start_requested))
        } catch (_: Exception) {
            startedAt = 0
            show(feedback, Ui.Tone.ERR, getString(R.string.start_refused))
        }
        refresh()
    }

    private fun stopAgent() {
        stopping = true
        startedAt = 0
        stopService(Intent(this, AgentService::class.java))
        show(feedback, Ui.Tone.IDLE, getString(R.string.stop_requested))
        refresh()
    }

    /** Clears the uncertain-write block only after two explicit confirmations and only while the service is inactive. */
    private fun acknowledge() {
        if (AgentService.workerActive) {
            stopService(Intent(this, AgentService::class.java))
            show(writeNote, Ui.Tone.WARN, getString(R.string.write_stopping)); return
        }
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this).setTitle(R.string.write_dialog1_title).setMessage(R.string.write_dialog1_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.write_dialog1_ok) { _, _ ->
                dialog = AlertDialog.Builder(this).setTitle(R.string.write_dialog2_title).setMessage(R.string.write_dialog2_body)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.write_dialog2_ok) { _, _ -> finishAcknowledge() }.show()
            }.show()
    }

    private fun finishAcknowledge() {
        if (AgentService.workerActive) {
            stopService(Intent(this, AgentService::class.java))
            show(writeNote, Ui.Tone.WARN, getString(R.string.write_stopping)); return
        }
        try {
            store.put("write_intent", ""); store.put("pending", "")
            show(writeNote, Ui.Tone.OK, getString(R.string.write_cleared))
        } catch (_: Exception) { show(writeNote, Ui.Tone.ERR, getString(R.string.save_failed)) }
        refresh()
    }

    // ---------- telegram ----------

    private fun telegramPage(config: JSONObject?): View = with(ui) {
        val page = column()
        pageTitle(page, R.string.tg_title, R.string.tg_intro)
        val c = card()
        tgToken = field(getString(R.string.tg_token), secret = true, hint = getString(R.string.tg_token_hint))
        tgChat = field(getString(R.string.tg_chat), numeric = true, hint = getString(R.string.tg_chat_hint))
        tgChat.edit.setText(config?.optString("chat_id").orEmpty())
        c.add(tgToken.box); c.add(tgChat.box, 14)
        c.add(button(getString(R.string.save)) { saveTelegram() }, 16)
        c.add(button(getString(R.string.tg_check), Ui.Style.SECONDARY) { checkTelegram() }, 10)
        tgResult = resultView(); c.add(tgResult, 12)
        page.add(c, 14)
        val help = card()
        help.add(heading(getString(R.string.tg_help_title)))
        help.add(body(getString(R.string.tg_help_body)), 8)
        page.add(help, 14)
        tgToken.edit.addTextChangedListener(watcher { updateTokenNote() })
        tgChat.edit.addTextChangedListener(watcher { updateChatNote() })
        updateTokenNote(); updateChatNote()
        page
    }

    private fun watcher(after: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = after()
    }

    private fun validToken(t: String) = try { TelegramApi(t, client); true } catch (_: IllegalArgumentException) { false }

    private fun updateTokenNote() = with(ui) {
        val typed = tgToken.edit.text.toString().trim()
        when {
            typed.isEmpty() -> tgToken.setNote(getString(if (snap.tokenSaved) R.string.tg_token_saved else R.string.tg_token_format), false)
            validToken(typed) -> tgToken.setNote(getString(R.string.tg_token_ok), false)
            else -> tgToken.setNote(getString(R.string.tg_token_bad), true)
        }
    }

    private fun updateChatNote() = with(ui) {
        val typed = tgChat.edit.text.toString().trim()
        val id = typed.toLongOrNull()
        when {
            typed.isEmpty() -> tgChat.setNote(getString(R.string.tg_chat_format), false)
            id == null || id <= 0 -> tgChat.setNote(getString(R.string.tg_chat_bad), true)
            else -> tgChat.setNote("", false)
        }
    }

    private fun saveTelegram() {
        if (busy) { show(tgResult, Ui.Tone.WARN, getString(R.string.wait_busy)); return }
        val c = try { store.config() } catch (_: Exception) { show(tgResult, Ui.Tone.ERR, getString(R.string.storage_broken)); return }
        val typed = tgToken.edit.text.toString().trim()
        val token = typed.ifEmpty { c.optString("bot_token") }
        val id = tgChat.edit.text.toString().trim().toLongOrNull()
        if (!validToken(token)) { updateTokenNote(); show(tgResult, Ui.Tone.ERR, getString(R.string.tg_save_need_token)); tgToken.edit.requestFocus(); return }
        if (id == null || id <= 0) { updateChatNote(); show(tgResult, Ui.Tone.ERR, getString(R.string.tg_save_need_chat)); tgChat.edit.requestFocus(); return }
        try {
            stopService(Intent(this, AgentService::class.java)); startedAt = 0
            if (token != c.optString("bot_token")) store.put("telegram_username", "")
            c.put("bot_token", token).put("chat_id", id.toString())
            store.put("config", c.toString()); store.put("pending", "")
        } catch (_: Exception) { show(tgResult, Ui.Tone.ERR, getString(R.string.save_failed)); return }
        tgToken.edit.setText("")
        show(tgResult, Ui.Tone.OK, getString(R.string.tg_saved))
        refresh()
    }

    private fun checkTelegram() {
        if (busy) { show(tgResult, Ui.Tone.WARN, getString(R.string.wait_busy)); return }
        val c = try { store.config() } catch (_: Exception) { show(tgResult, Ui.Tone.ERR, getString(R.string.storage_broken)); return }
        val unsaved = tgToken.edit.text.isNotBlank() || tgChat.edit.text.toString().trim() != c.optString("chat_id")
        if (unsaved) { show(tgResult, Ui.Tone.WARN, getString(R.string.tg_check_unsaved)); return }
        val token = c.optString("bot_token")
        if (!validToken(token)) { show(tgResult, Ui.Tone.WARN, getString(R.string.tg_check_no_token)); return }
        background(tgResult, R.string.tg_check_failed) { telegramCheck(token) }
    }

    private fun telegramCheck(token: String): Outcome {
        val api = TelegramApi(token, client)
        val name = try {
            val j = api.identity()
            (j.optJSONObject("result") ?: j).optString("username")
        } catch (e: ApiFailure) {
            val res = when (e.status) { 401, 404 -> R.string.tg_bad_token; 0 -> R.string.tg_network; else -> R.string.tg_api_error }
            return Outcome(Ui.Tone.ERR, getString(res, e.status))
        } catch (_: IOException) { return Outcome(Ui.Tone.ERR, getString(R.string.tg_network)) }
        // Remote text is untrusted: keep only a plain Telegram username.
        val user = name.takeIf { Regex("[A-Za-z0-9_]{3,64}").matches(it) }.orEmpty()
        store.put("telegram_username", user)
        val head = if (user.isEmpty()) getString(R.string.tg_found) else getString(R.string.tg_found_named, user)
        val hook = try { api.webhookConfigured() } catch (_: Exception) { null }
        return when (hook) {
            true -> Outcome(Ui.Tone.ERR, head + "\n\n" + getString(R.string.tg_webhook_conflict))
            false -> Outcome(Ui.Tone.OK, head + "\n\n" + getString(R.string.tg_webhook_clear))
            null -> Outcome(Ui.Tone.WARN, head + "\n\n" + getString(R.string.tg_webhook_unknown))
        }
    }

    // ---------- fatsecret ----------

    private fun fatSecretPage(config: JSONObject?): View = with(ui) {
        val page = column()
        pageTitle(page, R.string.fs_title, R.string.fs_intro)
        val keys = card()
        keys.add(heading(getString(R.string.fs_keys_title)))
        keys.add(body(getString(R.string.fs_get_keys_help)), 6)
        keys.add(button(getString(R.string.fs_get_keys), Ui.Style.SECONDARY) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.fatsecret.com/register"))) }
            catch (_: ActivityNotFoundException) { show(fsResult, Ui.Tone.WARN, getString(R.string.no_browser)) }
        }, 10)
        fsKey = field(getString(R.string.fs_key), secret = true)
        fsSecret = field(getString(R.string.fs_secret), secret = true)
        keys.add(fsKey.box, 12); keys.add(fsSecret.box, 14)
        keys.add(button(getString(R.string.fs_save_keys)) { saveFatSecret() }, 16)
        page.add(keys, 14)

        val auth = card()
        auth.add(heading(getString(R.string.fs_auth_title)))
        fsStatus = body("").apply { setTextColor(color(R.color.muted)) }
        auth.add(fsStatus, 6)
        auth.add(body(getString(R.string.fs_step1)), 12)
        auth.add(button(getString(R.string.fs_open_login), Ui.Style.SECONDARY) { fsAuthorize() }, 8)
        fsVerifier = field(getString(R.string.fs_verifier), secret = true)
        auth.add(body(getString(R.string.fs_step2)), 16)
        auth.add(fsVerifier.box, 8)
        auth.add(button(getString(R.string.fs_complete)) { fsComplete() }, 10)
        auth.add(body(getString(R.string.fs_step3)), 16)
        auth.add(button(getString(R.string.fs_read), Ui.Style.SECONDARY) { fsRead() }, 8)
        fsResult = resultView(); auth.add(fsResult, 12)
        page.add(auth, 14)

        val off = card()
        off.add(heading(getString(R.string.fs_disc_title)))
        off.add(body(getString(R.string.fs_disc_body)), 6)
        off.add(button(getString(R.string.fs_disconnect), Ui.Style.DANGER) { confirmDisconnect() }, 12)
        page.add(off, 14)
        page
    }

    private fun saveFatSecret() {
        if (busy) { show(fsResult, Ui.Tone.WARN, getString(R.string.wait_busy)); return }
        val c = try { store.config() } catch (_: Exception) { show(fsResult, Ui.Tone.ERR, getString(R.string.storage_broken)); return }
        val oldKey = c.optString("fs_key"); val oldSecret = c.optString("fs_secret")
        val key = fsKey.edit.text.toString().trim().ifEmpty { oldKey }
        val secret = fsSecret.edit.text.toString().trim().ifEmpty { oldSecret }
        if (key.isEmpty() || secret.isEmpty()) { show(fsResult, Ui.Tone.ERR, getString(R.string.fs_need_both)); return }
        try {
            stopService(Intent(this, AgentService::class.java)); startedAt = 0
            c.put("fs_key", key).put("fs_secret", secret)
            if (oldKey != key || oldSecret != secret) { c.remove("fs_token"); c.remove("fs_token_secret"); store.put("oauth_pending", "") }
            store.put("config", c.toString()); store.put("pending", "")
        } catch (_: Exception) { show(fsResult, Ui.Tone.ERR, getString(R.string.save_failed)); return }
        fsKey.edit.setText(""); fsSecret.edit.setText("")
        show(fsResult, Ui.Tone.OK, getString(R.string.fs_keys_saved))
        refresh()
    }

    private fun fsAuthorize() {
        if (busy) { show(fsResult, Ui.Tone.WARN, getString(R.string.wait_busy)); return }
        if (!snap.fsKeys) { show(fsResult, Ui.Tone.WARN, getString(R.string.fs_need_keys)); return }
        stopService(Intent(this, AgentService::class.java)); startedAt = 0
        background(fsResult, R.string.fs_failed) {
            val token = FatSecretApi(store.config(), client).requestToken()
            val url = "https://authentication.fatsecret.com/oauth/authorize?oauth_token=" + OAuth1.encode(token.getString("oauth_token"))
            if (!isDestroyed) store.put("oauth_pending", token.toString())
            Outcome(Ui.Tone.OK, getString(R.string.fs_browser_opened)) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
    }

    private fun fsComplete() {
        if (busy) { show(fsResult, Ui.Tone.WARN, getString(R.string.wait_busy)); return }
        val code = fsVerifier.edit.text.toString().trim()
        if (code.isEmpty()) { show(fsResult, Ui.Tone.WARN, getString(R.string.fs_need_verifier)); return }
        fsVerifier.edit.setText("")
        background(fsResult, R.string.fs_failed) {
            val pendingRaw = store.get("oauth_pending")
            if (pendingRaw.isBlank()) return@background Outcome(Ui.Tone.WARN, getString(R.string.fs_need_login))
            val c = store.config()
            val token = FatSecretApi(c, client).accessToken(JSONObject(pendingRaw), code)
            if (!isDestroyed) {
                c.put("fs_token", token.getString("oauth_token")).put("fs_token_secret", token.getString("oauth_token_secret"))
                store.put("config", c.toString()); store.put("oauth_pending", ""); store.put("pending", "")
            }
            Outcome(Ui.Tone.OK, getString(R.string.fs_token_saved))
        }
    }

    private fun fsRead() {
        if (busy) { show(fsResult, Ui.Tone.WARN, getString(R.string.wait_busy)); return }
        background(fsResult, R.string.fs_failed) {
            val c = store.config()
            if (c.optString("fs_token").isBlank()) return@background Outcome(Ui.Tone.WARN, getString(R.string.fs_need_token))
            val n = FatSecretApi(c, client).entries(java.time.LocalDate.now().toString()).size
            Outcome(Ui.Tone.OK, getString(R.string.fs_read_ok, n))
        }
    }

    private fun confirmDisconnect() {
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this).setTitle(R.string.fs_disc_dialog_title).setMessage(R.string.fs_disc_dialog_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.fs_disc_dialog_ok) { _, _ -> disconnect() }.show()
    }

    private fun disconnect() {
        if (busy) { show(fsResult, Ui.Tone.WARN, getString(R.string.wait_busy)); return }
        try {
            stopService(Intent(this, AgentService::class.java)); startedAt = 0
            val c = store.config(); c.remove("fs_token"); c.remove("fs_token_secret")
            store.put("config", c.toString()); store.put("pending", ""); store.put("oauth_pending", "")
            show(fsResult, Ui.Tone.OK, getString(R.string.fs_disconnected))
        } catch (_: Exception) { show(fsResult, Ui.Tone.ERR, getString(R.string.save_failed)) }
        refresh()
    }

    // ---------- ai ----------

    private fun aiPage(config: JSONObject?): View = with(ui) {
        val page = column()
        pageTitle(page, R.string.ai_title, R.string.ai_intro)
        val c = card()
        consent = CheckBox(this@MainActivity).apply {
            text = getString(R.string.ai_consent); isChecked = config?.optBoolean("ai_consent") ?: false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTextColor(color(R.color.navy)); minHeight = dp(48)
        }
        c.add(consent)
        aiUrl = field(getString(R.string.ai_url))
        aiUrl.edit.setText(config?.optString("llm_url", "https://api.openai.com/v1") ?: "https://api.openai.com/v1")
        aiKey = field(getString(R.string.ai_key), secret = true)
        aiModel = field(getString(R.string.ai_model))
        aiModel.edit.setText(config?.optString("llm_model", "gpt-4o-mini") ?: "gpt-4o-mini")
        aiSpeech = field(getString(R.string.ai_speech))
        aiSpeech.edit.setText(config?.optString("speech_model", "whisper-1") ?: "whisper-1")
        c.add(aiUrl.box, 14); c.add(aiKey.box, 14); c.add(aiModel.box, 14); c.add(aiSpeech.box, 14)
        c.add(button(getString(R.string.save)) { saveAi() }, 16)
        aiResult = resultView(); c.add(aiResult, 12)
        page.add(c, 14)
        page
    }

    private fun saveAi() {
        if (busy) { show(aiResult, Ui.Tone.WARN, getString(R.string.wait_busy)); return }
        val c = try { store.config() } catch (_: Exception) { show(aiResult, Ui.Tone.ERR, getString(R.string.storage_broken)); return }
        val url = aiUrl.edit.text.toString().trim()
        val typedKey = aiKey.edit.text.toString().trim()
        val key = typedKey.ifEmpty { c.optString("llm_key") }
        if (consent.isChecked) {
            val ok = try { url.toHttpUrl().let { it.isHttps && it.username.isBlank() && it.password.isBlank() && it.query == null && it.fragment == null } }
                catch (_: IllegalArgumentException) { false }
            if (!ok) { show(aiResult, Ui.Tone.ERR, getString(R.string.ai_bad_url)); aiUrl.edit.requestFocus(); return }
            if (key.isBlank()) { show(aiResult, Ui.Tone.ERR, getString(R.string.ai_need_key)); aiKey.edit.requestFocus(); return }
        }
        try {
            stopService(Intent(this, AgentService::class.java)); startedAt = 0
            c.put("llm_url", url).put("llm_model", aiModel.edit.text.toString().trim()).put("speech_model", aiSpeech.edit.text.toString().trim())
            if (typedKey.isNotEmpty()) c.put("llm_key", typedKey)
            c.put("ai_consent", consent.isChecked)
            store.put("config", c.toString()); store.put("pending", "")
        } catch (_: Exception) { show(aiResult, Ui.Tone.ERR, getString(R.string.save_failed)); return }
        aiKey.edit.setText("")
        show(aiResult, Ui.Tone.OK, getString(if (consent.isChecked) R.string.ai_saved_on else R.string.ai_saved_off))
        refresh()
    }

    // ---------- help ----------

    private fun helpPage(): View = with(ui) {
        val page = column()
        pageTitle(page, R.string.help_title, R.string.help_intro)
        val trouble = card()
        trouble.add(heading(getString(R.string.help_silent_title)))
        listOf(R.string.help_silent_1, R.string.help_silent_2, R.string.help_silent_3, R.string.help_silent_4, R.string.help_silent_5)
            .forEachIndexed { i, s -> trouble.add(body(getString(s)), if (i == 0) 8 else 6) }
        page.add(trouble, 14)

        val bg = card()
        bg.add(heading(getString(R.string.help_bg_title)))
        bg.add(body(getString(R.string.help_bg_body)), 6)
        bg.add(button(getString(R.string.help_app_settings), Ui.Style.SECONDARY) { openAppSettings() }, 12)
        page.add(bg, 14)

        val tools = card()
        tools.add(heading(getString(R.string.help_tools_title)))
        tools.add(body(getString(R.string.help_acc_body)), 6)
        tools.add(button(getString(R.string.help_acc_open), Ui.Style.SECONDARY) { launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, 12)
        tools.add(button(getString(R.string.help_open_fs), Ui.Style.SECONDARY) { openFatSecret() }, 10)
        tools.add(button(getString(R.string.diag_open), Ui.Style.SECONDARY) { openDiagnostics() }, 10)
        helpResult = resultView(); tools.add(helpResult, 12)
        page.add(tools, 14)
        page
    }

    private fun launch(intent: Intent) {
        try { startActivity(intent) } catch (_: ActivityNotFoundException) { show(helpResult, Ui.Tone.ERR, getString(R.string.help_no_screen)) }
    }

    private fun openAppSettings() = launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

    private fun openFatSecret() {
        val intent = packageManager.getLaunchIntentForPackage(FatAccessibilityService.FATSECRET_PACKAGE)
        if (intent != null) launch(intent) else show(helpResult, Ui.Tone.WARN, getString(R.string.help_no_fs))
    }

    // ---------- diagnostics (the only screen without FLAG_SECURE) ----------

    private fun buildDiagnostics() = with(ui) {
        val col = column().apply { setBackgroundColor(color(R.color.bg)); setPadding(dp(20), dp(16), dp(20), dp(24)) }
        col.add(button(getString(R.string.diag_back), Ui.Style.SECONDARY) { closeDiagnostics() })
        col.add(title(getString(R.string.diag_title)), 16)
        col.add(body(getString(R.string.diag_intro)).apply { setTextColor(color(R.color.muted)) }, 6)
        diagBody = body("").apply { setLineSpacing(0f, 1.25f) }
        val c = card(); c.add(diagBody)
        col.add(c, 14)
        diagRoot = ScrollView(this@MainActivity).apply { setBackgroundColor(color(R.color.bg)); addView(col) }
    }

    private fun openDiagnostics() {
        diagnosticsOpen = true
        setContentView(diagRoot); diagRoot.requestApplyInsets()
        // Keep the old credential frame secure until the redacted frame is committed.
        diagRoot.viewTreeObserver.registerFrameCommitCallback {
            handler.post { if (diagnosticsOpen && resumed) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
        renderDiagnostics()
    }

    private fun closeDiagnostics() {
        diagnosticsOpen = false
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(root); root.requestApplyInsets()
        refresh()
    }

    private fun renderDiagnostics() {
        val yes = getString(R.string.val_yes); val no = getString(R.string.val_no)
        fun b(v: Boolean) = if (v) yes else no
        val power = getSystemService(PowerManager::class.java)
        val lines = listOf(
            R.string.diag_app to (try { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() } catch (_: PackageManager.NameNotFoundException) { "-" }),
            R.string.diag_android to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            R.string.diag_device to "${Build.MANUFACTURER} ${Build.MODEL}",
            R.string.diag_service to getString(if (snap.active) R.string.diag_service_on else R.string.diag_service_off),
            R.string.diag_state to getString(stateOf(snap).first),
            R.string.diag_status to snap.status.ifBlank { "-" },
            R.string.diag_contact to ago(snap.contact).ifBlank { "-" },
            R.string.diag_token to b(snap.tokenValid),
            R.string.diag_chat to b(snap.chatOk),
            R.string.diag_fs_keys to b(snap.fsKeys),
            R.string.diag_fs_token to b(snap.fsToken),
            R.string.diag_ai to b(snap.aiConsent),
            R.string.diag_ai_key to b(snap.aiKey),
            R.string.diag_write to b(snap.writeIntent),
            R.string.diag_notif to b(getSystemService(NotificationManager::class.java).areNotificationsEnabled()),
            R.string.diag_battery to b(power.isIgnoringBatteryOptimizations(packageName)),
            R.string.diag_updated to SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        )
        diagBody.text = lines.joinToString("\n") { (label, value) -> getString(label) + ": " + value }
    }

    // ---------- state refresh ----------

    private fun refresh() {
        val my = ++seq
        try {
            reader.submit {
                val s = readSnap()
                runOnUiThread { if (!isDestroyed && my > applied) { applied = my; apply(s) } }
            }
        } catch (_: RejectedExecutionException) { }
    }

    private fun readSnap(): Snap {
        val active = AgentService.workerActive
        return try {
            val c = store.config()
            val token = c.optString("bot_token")
            Snap(
                active = active, state = store.get("service_state"), status = store.get("service_status"),
                contact = store.get("last_contact"), message = store.get("last_message"),
                username = store.get("telegram_username").takeIf { Regex("[A-Za-z0-9_]{3,64}").matches(it) }.orEmpty(),
                writeIntent = store.get("write_intent").isNotBlank(),
                tokenSaved = token.isNotBlank(), tokenValid = validToken(token),
                chatOk = c.optString("chat_id").toLongOrNull()?.let { it > 0 } == true,
                fsKeys = c.optString("fs_key").isNotBlank() && c.optString("fs_secret").isNotBlank(),
                fsToken = c.optString("fs_token").isNotBlank(),
                aiConsent = c.optBoolean("ai_consent"), aiKey = c.optString("llm_key").isNotBlank()
            )
        } catch (_: Exception) { Snap(configOk = false, active = active) }
    }

    private fun stateOf(s: Snap): Pair<Int, Ui.Tone> {
        if (!s.configOk) return R.string.state_storage to Ui.Tone.ERR
        val starting = !s.active && System.currentTimeMillis() - startedAt < 5000
        return when {
            s.active -> when (s.state) {
                "listening" -> R.string.state_listening to Ui.Tone.OK
                "reconnecting" -> R.string.state_reconnecting to Ui.Tone.WARN
                "error" -> R.string.state_error to Ui.Tone.ERR
                else -> R.string.state_starting to Ui.Tone.WARN
            }
            starting -> R.string.state_starting to Ui.Tone.WARN
            s.state == "error" -> R.string.state_error to Ui.Tone.ERR
            else -> R.string.state_stopped to Ui.Tone.IDLE
        }
    }

    /** Updates labels and tints only; it never touches text typed into the forms. */
    private fun apply(s: Snap) {
        snap = s
        if (!s.active) stopping = false
        val (titleRes, tone) = stateOf(s)
        stateCard.background = ui.tinted(tone)
        stateTitle.text = getString(if (stopping && s.active) R.string.state_stopping else titleRes); stateTitle.setTextColor(ui.color(tone.fg))
        val detail = when {
            !s.configOk -> getString(R.string.storage_broken)
            stopping && s.active -> getString(R.string.stop_requested)
            s.status.isNotBlank() -> s.status
            else -> getString(when (titleRes) {
                R.string.state_listening -> R.string.detail_listening
                R.string.state_stopped -> R.string.detail_stopped
                else -> R.string.detail_other
            })
        }
        stateDetail.text = detail; stateDetail.setTextColor(ui.color(tone.fg))
        val meta = buildList {
            if (s.username.isNotEmpty()) add(getString(R.string.meta_bot, s.username))
            ago(s.contact).takeIf { it.isNotEmpty() }?.let { add(getString(R.string.meta_contact, it)) }
            ago(s.message).takeIf { it.isNotEmpty() }?.let { add(getString(R.string.meta_message, it)) }
            if (titleRes == R.string.state_error && s.configOk) add(getString(R.string.error_hint))
        }
        stateMeta.text = meta.joinToString("\n"); stateMeta.setTextColor(ui.color(tone.fg))
        stateMeta.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE

        strip.text = getString(R.string.strip, getString(titleRes))
        strip.background = ui.tinted(tone); strip.setTextColor(ui.color(tone.fg))
        writeCard.visibility = if (s.writeIntent) View.VISIBLE else View.GONE

        val tg = s.tokenValid && s.chatOk
        setPill(rowPill[0], if (tg) Ui.Tone.OK else Ui.Tone.WARN, if (tg) R.string.pill_ready else R.string.pill_needed)
        rowDetail[0].text = getString(if (tg) R.string.row_tg_ready else R.string.row_tg_needed)
        val fsTone = if (s.fsToken) Ui.Tone.OK else if (s.fsKeys) Ui.Tone.WARN else Ui.Tone.IDLE
        setPill(rowPill[1], fsTone, if (s.fsToken) R.string.pill_token else if (s.fsKeys) R.string.pill_login else R.string.pill_optional)
        rowDetail[1].text = getString(if (s.fsToken) R.string.row_fs_token else if (s.fsKeys) R.string.row_fs_keys else R.string.row_fs_none)
        val ai = s.aiConsent && s.aiKey
        setPill(rowPill[2], if (ai) Ui.Tone.OK else Ui.Tone.IDLE, if (ai) R.string.pill_on else R.string.pill_off)
        rowDetail[2].text = getString(if (ai) R.string.row_ai_on else R.string.row_ai_off)
        setupSummary.text = getString(if (tg) R.string.setup_done else R.string.setup_todo)

        fsStatus.text = getString(if (s.fsToken) R.string.fs_status_token else if (s.fsKeys) R.string.fs_status_keys else R.string.fs_status_none)
        setHint(tgToken.edit, if (s.tokenSaved) R.string.hint_saved else R.string.tg_token_hint)
        setHint(fsKey.edit, if (s.fsKeys) R.string.hint_saved else R.string.hint_enter)
        setHint(fsSecret.edit, if (s.fsKeys) R.string.hint_saved else R.string.hint_enter)
        setHint(aiKey.edit, if (s.aiKey) R.string.hint_saved else R.string.hint_optional)
        updateTokenNote()
        if (diagnosticsOpen) renderDiagnostics()
    }

    private fun setHint(e: EditText, res: Int) { val h = getString(res); if (e.hint?.toString() != h) e.hint = h }

    /** Accepts epoch seconds or millis; anything else is ignored rather than displayed. */
    private fun ago(raw: String): String {
        val n = raw.trim().toLongOrNull() ?: return ""
        val ms = if (n < 100_000_000_000L) n * 1000 else n
        val sec = (System.currentTimeMillis() - ms) / 1000
        return when {
            sec < 5 -> getString(R.string.ago_now)
            sec < 60 -> getString(R.string.ago_sec, sec)
            sec < 3600 -> getString(R.string.ago_min, sec / 60)
            else -> SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(ms))
        }
    }

    /** Runs one network task at a time; results go to the given box and are dropped after destroy. */
    private fun background(box: TextView, fail: Int, action: () -> Outcome) {
        busy = true; show(box, Ui.Tone.IDLE, getString(R.string.working))
        try {
            worker.submit {
                val r = try { action() } catch (_: Exception) { Outcome(Ui.Tone.ERR, getString(fail)) }
                runOnUiThread {
                    busy = false
                    if (isDestroyed) return@runOnUiThread
                    show(box, r.tone, r.text)
                    try { r.after?.invoke() } catch (_: ActivityNotFoundException) { show(box, Ui.Tone.ERR, getString(R.string.no_browser)) }
                    refresh()
                }
            }
        } catch (_: RejectedExecutionException) { busy = false }
    }
}
