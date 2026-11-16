package com.oleg.androidagent

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
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
    private var reviewedTest = false
    private var inspectedUi: FatUi? = null
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        phase = arguments?.getString("phase") ?: "write"
        reviewedTest = arguments?.getString("reviewed_test") == "banana-1g-lunch"
        start()
    }
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
    private fun verifySignInStartsBrowser(activity: Activity, store: SecretStore) {
        val filter = IntentFilter(Intent.ACTION_VIEW).apply {
            addDataScheme("https"); addDataAuthority("authentication.fatsecret.com", null)
            addDataPath("/oauth/authorize", android.os.PatternMatcher.PATTERN_LITERAL)
        }
        val monitor = addMonitor(filter, ActivityResult(Activity.RESULT_CANCELED, null), true)
        // Replace transport only inside the test APK; the real button, storage and OAuth code still run.
        val field = MainActivity::class.java.getDeclaredField("client").apply { isAccessible = true }
        val original = field.get(activity) as okhttp3.OkHttpClient
        val mock = original.newBuilder().addInterceptor { chain ->
            check(chain.request().url.host == "authentication.fatsecret.com" && chain.request().url.encodedPath == "/oauth/request_token")
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(okhttp3.ResponseBody.create(null, "oauth_token=fixture&oauth_token_secret=fixture-secret&oauth_callback_confirmed=true")).build()
        }.build()
        try {
            runOnMainSync {
                field.set(activity, mock)
                val fields = views(activity.window.decorView).filterIsInstance<EditText>()
                fields[0].setText("fixture-key"); fields[1].setText("fixture-secret")
            }
            click(activity, targetContext.getString(R.string.fs_open_login))
            waitUntil("Sign-in button did not start browser authorization") { monitor.hits > 0 }
            check(store.config().getString("fs_key") == "fixture-key")
            check(store.get("oauth_pending").isNotBlank())
        } finally {
            removeMonitor(monitor)
            runOnMainSync { field.set(activity, original) }
            store.put("config", ""); store.put("oauth_pending", "")
        }
    }
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
            val automation = getUiAutomation(if (phase in setOf("ui-live-read", "ui-live-ru-read", "ui-command-contract", "ui-metadata", "ui-search-submit", "pc-live-draft", "pc-live-photo", "pc-live-voice", "ui-reviewed-write", "ui-reviewed-ack")) android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES else 0)
            val qemu = automation.executeShellCommand("getprop ro.kernel.qemu").use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().readText().trim()
            }
            check(qemu == "1") { "Refusing a physical device" }
            val store = SecretStore(targetContext)
            val marker = "device-smoke-not-a-secret"
            when (phase) {
                "ui-search-submit" -> {
                    waitUntil("Accessibility not connected") { FatAccessibilityService.instance != null }
                    val info = mutableListOf<String>()
                    runOnMainSync {
                        val root = requireNotNull(FatAccessibilityService.instance?.rootInActiveWindow)
                        check(root.packageName?.toString() == FatAccessibilityService.FATSECRET_PACKAGE)
                        val editors = root.findAccessibilityNodeInfosByViewId("com.fatsecret.android:id/edit_text")
                            .filter { it.isVisibleToUser && it.isEnabled && it.isEditable && it.text?.toString() in setOf("banana", "банан") }
                        info += "editor_count=${editors.size}"
                        val service = requireNotNull(FatAccessibilityService.instance)
                        val defaultPackage = android.content.ComponentName.unflattenFromString(android.provider.Settings.Secure.getString(
                            targetContext.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty())?.packageName.orEmpty()
                        for (window in service.windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
                            val keyboard = window.root ?: continue
                            keyboard.refresh()
                            val area = android.graphics.Rect().also { keyboard.getBoundsInScreen(it) }
                            info += "ime_bounds=$area; package_matches=${keyboard.packageName?.toString() == defaultPackage}"
                            var visited = 0
                            fun keys(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
                                if (depth > 24 || visited++ >= 600) return
                                val refreshed = node.refresh()
                                val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                                val label = node.contentDescription?.toString().orEmpty().ifBlank { node.text?.toString().orEmpty() }
                                val policy = KeyboardSearchPolicy.accepts(defaultPackage, keyboard.packageName?.toString().orEmpty(), node.packageName?.toString().orEmpty(), label, node.viewIdResourceName.orEmpty())
                                if (policy || (node.childCount == 0 && bounds.contains(660, 1130))) {
                                    info += "key_order=$visited; refreshed=$refreshed; policy=$policy; label_length=${label.length}; visible=${node.isVisibleToUser}; enabled=${node.isEnabled}; clickable=${node.isClickable}; bounds=$bounds; contained=${area.contains(bounds)}; lower=${bounds.centerY() > area.centerY()}"
                                    info += "key_id=${node.viewIdResourceName}; class=${node.className}"
                                    var ancestor = node.parent
                                    repeat(3) {
                                        ancestor?.let { parent ->
                                            val parentBounds = android.graphics.Rect().also { parent.getBoundsInScreen(it) }
                                            info += "key_ancestor_id=${parent.viewIdResourceName}; class=${parent.className}; bounds=$parentBounds"
                                            ancestor = parent.parent
                                        }
                                    }
                                }
                                for (index in 0 until node.childCount) node.getChild(index)?.let { keys(it, depth + 1) }
                            }
                            keys(keyboard, 0)
                            info += "visited=$visited"
                        }
                        if (editors.size == 1) {
                            val edit = editors.single()
                            info += "editor_actions=${edit.actionList.map { it.id }}"
                        }
                    }
                    SystemClock.sleep(2500)
                    runOnMainSync {
                        val root = requireNotNull(FatAccessibilityService.instance?.rootInActiveWindow)
                        val candidates = root.findAccessibilityNodeInfosByViewId("com.fatsecret.android:id/title_description")
                        info += "raw_candidate_count=${candidates.size}; visible_candidate_count=${candidates.count { it.isVisibleToUser }}"
                        info += "suggestion_count=${root.findAccessibilityNodeInfosByViewId("com.fatsecret.android:id/auto_suggestion_row_image_row_label").size}"
                    }
                    result.putString("stream", "PASS: read-only search metadata; no SAVE\n" + info.joinToString("\n") + "\n")
                }
                "ui-metadata" -> {
                    waitUntil("Accessibility not connected") { FatAccessibilityService.instance != null }
                    FatUi(targetContext).diary()
                    val metadata = mutableListOf<String>()
                    runOnMainSync {
                        val root = requireNotNull(FatAccessibilityService.instance?.rootInActiveWindow)
                        check(root.packageName?.toString() == FatAccessibilityService.FATSECRET_PACKAGE)
                        val list = root.findAccessibilityNodeInfosByViewId("com.fatsecret.android:id/food_journal_new_body").single()
                        metadata += "collection_rows=${list.collectionInfo?.rowCount}; actions=${list.actionList.map { it.id }}"
                        for (i in 0 until list.childCount) list.getChild(i)?.let {
                            metadata += "child_id=${it.viewIdResourceName}; class=${it.className}; row=${it.collectionItemInfo?.rowIndex}; child_count=${it.childCount}"
                        }
                        for (entry in root.findAccessibilityNodeInfosByViewId("com.fatsecret.android:id/entry_row_view")) {
                            var node = entry
                            repeat(5) {
                                metadata += "entry_ancestor_id=${node.viewIdResourceName}; row=${node.collectionItemInfo?.rowIndex}"
                                node.parent?.let { node = it }
                            }
                        }
                        list.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    }
                    SystemClock.sleep(700)
                    metadata += "after_forward=${FatAccessibilityService.instance?.lastScroll}"
                    runOnMainSync {
                        FatAccessibilityService.instance?.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("com.fatsecret.android:id/food_journal_new_body")
                            ?.singleOrNull()?.let {
                                metadata += "after_forward_children=${it.childCount}; hash=${it.hashCode()}; window=${it.windowId}"
                                it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                            }
                    }
                    SystemClock.sleep(700)
                    metadata += "after_backward=${FatAccessibilityService.instance?.lastScroll}"
                    val snapshot = FatUi(targetContext).snapshot("lunch")
                    check(snapshot.complete)
                    metadata += "complete_snapshot=true; entries=${snapshot.entries.size}"
                    if (store.get("write_intent").isNotBlank()) {
                        val intent = JSONObject(store.get("write_intent"))
                        if (intent.optString("mode") == "ui") {
                            val pending = intent.getJSONObject("draft")
                            metadata += "verified_delta_now=${DiaryVerification.addedExactly(DiarySnapshot.parse(pending.getJSONObject("before")), snapshot, pending.getString("preview"))}"
                        }
                    }
                    result.putString("stream", "PASS: local-only structural metadata, no food text exported\n" + metadata.joinToString("\n") + "\n")
                }
                "pc-live-health" -> {
                    val file = File(targetContext.filesDir, "test-config.json")
                    val config = try { JSONObject(file.readText()) } finally { file.delete() }
                    check(!config.has("bot_token"))
                    val brain = PcBrain(config, Http.client())
                    val health = brain.check()
                    if (health.limited) {
                        try { brain.parse("lunch banana100g"); error("Expected cached subscription limit") }
                        catch (e: IllegalArgumentException) { check(e.message.orEmpty().contains("лимит подписки")) }
                    }
                    result.putString("stream", "PASS: real paired HTTPS PC health; subscription_limited=${health.limited}; reset=${health.reset}; no Telegram or diary actions\n")
                }
                "ui-command-contract" -> {
                    check(!AgentService.workerActive && store.get("write_intent").isBlank())
                    waitUntil("Accessibility not connected") { FatAccessibilityService.instance != null }
                    val transport = Http.client().newBuilder().addInterceptor { error("No bot, API or model request expected") }.build()
                    val config = JSONObject()
                    val commands = AgentCommands(store, config, FatSecretApi(config, transport), transport, FatUi(targetContext))
                    val telegram = TelegramApi("999999:" + "fixture".repeat(5), transport)
                    fun command(text: String) = commands.handle(JSONObject().put("text", text), telegram)
                    check(command("Помощь").text.contains("Developer-ключи не нужны"))
                    check(command("/meal обед банан=100").text.contains("/pick"))
                    check(command("/pick 1 1").text.contains("/confirm"))
                    val nonce = JSONObject(store.get("ui_pending")).getString("nonce")
                    try { command("/confirm $nonce-invalid"); error("Wrong confirmation accepted") } catch (_: IllegalArgumentException) {}
                    check(store.get("write_intent").isBlank())
                    check(command("Отмена").text.contains("отменён"))
                    check(store.get("ui_pending").isBlank())
                    val today = command("/today").text
                    check(today.contains("FatSecret") && today.lines().size >= 6)
                    check(command("/stop").stop)
                    result.putString("stream", "PASS: actual production command dispatcher help/Russian short meal/pick/invalid confirm/cancel/today/stop controls real FatSecret without API keys or any bot/model request; diary unchanged\n")
                }
                "pc-contract" -> {
                    val values = mutableMapOf<String, String>()
                    val memory = object : StateStore {
                        override fun get(key: String) = values[key].orEmpty()
                        override fun put(key: String, value: String) { values[key] = value }
                    }
                    val config = JSONObject().put("pc_url", "https://pc.example.invalid/")
                        .put("pc_pairing", "fixture-pairing-".repeat(3)).put("pc_consent", true)
                    val id = "b8fac2e7-8546-46bc-b55a-600d45c8a461"
                    val image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                    val photo = java.io.ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                    image.recycle()
                    var calls = 0
                    val transport = Http.client().newBuilder().addInterceptor { chain ->
                        val buffer = okio.Buffer()
                        requireNotNull(chain.request().body).writeTo(buffer)
                        val payload = JSONObject(buffer.readUtf8())
                        calls++
                        val response = when (calls) {
                            1 -> {
                                val actual = android.util.Base64.decode(payload.getJSONObject("image").getString("data"), android.util.Base64.NO_WRAP)
                                check(actual.contentEquals(photo))
                                JSONObject().put("question", "Какой приём пищи?").put("conversation", id)
                            }
                            2 -> {
                                check(payload.getString("conversation") == id && payload.getString("text") == "lunch")
                                Meals.parse("/meal ${java.time.LocalDate.now()} lunch banana=100")
                            }
                            3 -> {
                                check(payload.has("voice") && !payload.has("image"))
                                Meals.parse("/meal ${java.time.LocalDate.now()} lunch banana=100").put("transcript", "banana 100g lunch")
                            }
                            else -> error("Unexpected request")
                        }
                        okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                            .body(okhttp3.ResponseBody.create(null, response.toString())).build()
                    }.build()
                    val brain = PcBrain(config, transport, memory)
                    try { brain.parse("100g", photo = photo); error("Expected clarification") }
                    catch (e: IllegalArgumentException) { check(e.message == "Какой приём пищи?") }
                    check(memory.get("pc_dialogue") == id)
                    check(brain.parse("lunch").getString("meal") == "lunch")
                    check(memory.get("pc_dialogue").isBlank())
                    check(brain.parse("voice", voice = "RIFFfixture".toByteArray()).getString("transcript").contains("100g"))
                    config.put("pc_consent", false)
                    try { brain.parse("food"); error("Consent bypass") } catch (_: IllegalArgumentException) {}
                    check(calls == 3)
                    val badPairing = "Ж".repeat(40)
                    config.put("pc_consent", true).put("pc_pairing", badPairing)
                    try { brain.parse("food"); error("Unicode pairing accepted") }
                    catch (e: IllegalArgumentException) { check(!e.message.orEmpty().contains(badPairing)) }
                    check(calls == 3)
                    result.putString("stream", "PASS: Android PNG/voice payloads, clarification context and consent guard with mock PC transport; no live model, Telegram or diary write\n")
                }
                "ui-reviewed-ack" -> {
                    check(reviewedTest && !AgentService.workerActive)
                    val journal = JSONObject(store.get("write_intent"))
                    val fixture = JSONObject(store.get("ui_test_receipt"))
                    check(journal.getString("mode") == "ui")
                    check(journal.getJSONObject("draft").getString("nonce") == fixture.getString("nonce"))
                    check(fixture.getString("preview").startsWith("Banana: 1 g\n"))
                    val activity = launch()
                    click(activity, targetContext.getString(R.string.write_ack))
                    val field = MainActivity::class.java.getDeclaredField("dialog").apply { isAccessible = true }
                    for (label in listOf("Дальше", "Да, я проверил дневник")) {
                        runOnMainSync {
                            val dialog = field.get(activity) as android.app.AlertDialog
                            check(dialog.isShowing)
                            val button = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                            check(button.text.toString().equals(label, true))
                            button.performClick()
                        }
                        SystemClock.sleep(250)
                    }
                    check(store.get("write_intent").isBlank())
                    activity.finish()
                    result.putString("stream", "PASS: inspected and removed authorized fixture acknowledged through both real app dialogs\n")
                }
                "ui-reviewed-write" -> {
                    check(reviewedTest) { "Requires the user's explicit Banana 1g lunch test approval" }
                    check(store.get("write_intent").isBlank()) { "Never replay an uncertain test write; verify and acknowledge manually" }
                    waitUntil("Accessibility not connected") { FatAccessibilityService.instance != null }
                    val ui = FatUi(targetContext).also { inspectedUi = it }
                    val engine = UiMealEngine(store, ui)
                    engine.prepare(Meals.parse("/meal ${java.time.LocalDate.now()} lunch banana=1"))
                    val review = engine.pick(1, 1)
                    val draft = JSONObject(store.get("ui_pending"))
                    check(draft.getJSONObject("before").getBoolean("complete"))
                    check(draft.getString("preview").startsWith("Banana: 1 g"))
                    check(review.contains("/confirm"))
                    store.put("ui_test_previous", store.get("ui_test_receipt"))
                    store.put("ui_test_receipt", draft.toString())
                    val saved = engine.confirm(draft.getString("nonce"))
                    check(saved.contains("Сохранено и проверено"))
                    check(store.get("write_intent").isBlank())
                    SystemClock.sleep(1000)
                    ui.diary()
                    result.putString("stream", "PASS: explicitly reviewed Banana 1g lunch test saved once; exact complete diary delta verified; lock cleared only after verification. Remove ONLY the test entry before clearing receipt\n")
                }
                "ui-live-read", "ui-live-ru-read", "pc-live-draft", "pc-live-photo", "pc-live-voice" -> {
                    check(!AgentService.workerActive)
                    waitUntil("Accessibility not connected") { FatAccessibilityService.instance != null }
                    val ui = FatUi(targetContext)
                    val summary = ui.today()
                    check(summary.contains("FatSecret") && summary.lines().size >= 6)
                    val meal = if (phase.startsWith("pc-live-")) {
                        val file = File(targetContext.filesDir, "test-config.json")
                        val config = try { JSONObject(file.readText()) } finally { file.delete() }
                        check(!config.has("bot_token"))
                        val brain = PcBrain(config, Http.client())
                        if (phase == "pc-live-draft") brain.parse("Lunch: banana 100g")
                        else {
                            val media = File(targetContext.filesDir, "test-media.bin")
                            val bytes = try { media.readBytes() } finally { media.delete() }
                            if (phase == "pc-live-photo") brain.parse("Обед, 100 г еды на фото", photo = bytes)
                            else brain.parse("Разбери описание еды голосом", voice = bytes)
                        }
                    } else Meals.parse("/meal ${java.time.LocalDate.now()} lunch ${if (phase == "ui-live-ru-read") "банан" else "banana"}=100")
                    val engine = UiMealEngine(store, ui)
                    check(engine.prepare(meal).contains("/pick"))
                    check(engine.pick(1, 1).contains("/confirm"))
                    check(ui.preview().contains("100"))
                    check(store.get("write_intent").isBlank())
                    check(engine.cancel().contains("отменён"))
                    check(store.get("ui_pending").isBlank())
                    result.putString("stream", "PASS: $phase - actual FatSecret Accessibility diary read, search, product selection, grams=100, reviewed preview and cancellation; SAVE never clicked; no diary write\n")
                }
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
                        click(activity, "FatSecret")
                        click(activity, "Экспериментальный developer API")
                        click(activity, targetContext.getString(R.string.fs_open_login))
                        runOnMainSync {
                            val error = views(activity.window.decorView).filterIsInstance<TextView>()
                                .first { it.text.toString() == targetContext.getString(R.string.fs_need_keys) }
                            check(error.getGlobalVisibleRect(android.graphics.Rect())) { "Sign-in error is hidden outside viewport" }
                        }
                        verifySignInStartsBrowser(activity, store)
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
                        result.putString("stream", "PASS: five Russian tabs; visible sign-in feedback; real sign-in button auto-saves keys and starts browser Intent with mock server response; owner guards; blank-secret retention; secure diagnostics\n")
                    } finally { store.put("config", ""); runOnMainSync { activity.finish() } }
                }
                "fatsecret" -> {
                    check(store.config().length() == 0 && !AgentService.workerActive)
                    val file = File(targetContext.filesDir, "test-config.json")
                    val config = try { JSONObject(file.readText()) } finally { file.delete() }
                    require(config.optString("fs_key").isNotBlank() && config.optString("fs_secret").isNotBlank())
                    val filter = IntentFilter(Intent.ACTION_VIEW).apply {
                        addDataScheme("https"); addDataAuthority("authentication.fatsecret.com", null)
                        addDataPath("/oauth/authorize", android.os.PatternMatcher.PATTERN_LITERAL)
                    }
                    val monitor = addMonitor(filter, ActivityResult(Activity.RESULT_CANCELED, null), true)
                    val activity = launch()
                    try {
                        click(activity, "FatSecret")
                        click(activity, "Экспериментальный developer API")
                        runOnMainSync {
                            val fields = views(activity.window.decorView).filterIsInstance<EditText>()
                            fields[0].setText(config.getString("fs_key")); fields[1].setText(config.getString("fs_secret"))
                        }
                        click(activity, targetContext.getString(R.string.fs_open_login))
                        val deadline = SystemClock.elapsedRealtime() + 90_000
                        var rejected = false
                        while (monitor.hits == 0 && !rejected && SystemClock.elapsedRealtime() < deadline) {
                            runOnMainSync {
                                rejected = views(activity.window.decorView).filterIsInstance<TextView>().any {
                                    it.isShown && (it.text.startsWith("FatSecret отклонил") || it.text.toString() == targetContext.getString(R.string.fs_failed))
                                }
                            }
                            SystemClock.sleep(200)
                        }
                        check(store.config().getString("fs_key") == config.getString("fs_key")) { "Unsaved key was not saved on sign-in" }
                        if (monitor.hits > 0) {
                            check(store.get("oauth_pending").isNotBlank())
                            result.putString("stream", "PASS: typed FatSecret keys auto-saved; live OAuth1 request token accepted; browser authorization Intent intercepted safely (no account login/write)\n")
                        } else {
                            check(rejected) { "No visible result from sign-in" }
                            var code = "unknown"
                            runOnMainSync {
                                val text = views(activity.window.decorView).filterIsInstance<TextView>().firstOrNull { it.text.startsWith("FatSecret отклонил") }?.text?.toString().orEmpty()
                                code = Regex("код ([0-9]+)").find(text)?.groupValues?.get(1) ?: "network"
                            }
                            result.putString("stream", "BLOCKED: sign-in feedback visible; FatSecret request-token rejected ($code); no browser/account/write success claimed\n")
                        }
                    } finally {
                        removeMonitor(monitor)
                        runOnMainSync { activity.finish() }; waitForIdleSync()
                        store.put("config", ""); store.put("oauth_pending", "")
                    }
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
                    val serverEpoch = java.time.Instant.now().plusSeconds(86_400).epochSecond
                    val clock = TelegramClock { SystemClock.elapsedRealtime() }
                    clock.observe(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.Instant.ofEpochSecond(serverEpoch).atZone(java.time.ZoneOffset.UTC)))
                    check(TelegramFreshness.rejection(JSONObject().put("text", "/confirm fixture").put("date", serverEpoch + 1), clock.nowSeconds()) == null)
                    check(TelegramFreshness.rejection(JSONObject().put("text", "Помощь"), null) == null)
                    check(TelegramFreshness.rejection(JSONObject().put("text", "/confirm fixture").put("date", serverEpoch - 301), clock.nowSeconds()) == TelegramFreshness.Rejection.TOO_OLD)
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
            val site = t.stackTrace.filter { it.className.startsWith("com.oleg.androidagent.") }.take(5)
                .joinToString(" <- ") { "${it.fileName}:${it.lineNumber}" }
            result.putString("stream", "FAIL: ${t.javaClass.simpleName} in phase $phase$site\n${inspectedUi?.searchState.orEmpty()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
