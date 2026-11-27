package com.oleg.androidagent

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.graphics.Rect
import android.provider.Settings
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** A fixed recipe for the observed FatSecret 11 UI. The model never supplies actions. */
class FatUi(private val context: Context, private val beforeCustomCommit: () -> Unit = {}) : MealScreen, CustomMealScreen {
    private class FrameRangeMismatch : IllegalArgumentException("FatSecret не сообщил полные границы списка. Результат требует ручной проверки.")
    var candidateResolver: ((org.json.JSONObject, List<Row>, Boolean) -> Int?)? = null
    var searchProgress: ((String) -> Unit)? = null
    private var screenLease: ScreenLease? = null
    fun canExecute() = (FatAccessibilityService.instance != null || AccessibilityGuard.current?.ensure() == true) &&
        context.getSystemService(android.os.PowerManager::class.java).isInteractive &&
        !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    fun <T> withExecutionScreen(block: () -> T): T {
        requireReady()
        check(screenLease == null)
        val lease = main { ScreenLease(requireNotNull(FatAccessibilityService.instance)) }
        screenLease = lease
        try { return block() } finally {
            main { lease.close() }
            screenLease = null
        }
    }
    var searchState: String = "idle"
        private set
    var diaryRangeState: String = ""
        private set
    data class Row(val name: String, val detail: String)
    private val prefix = "com.fatsecret.android:id/"
    private var customCommitStarted = false
    private var existingIdentity: String? = null
    override fun diaryName(): String? = if (existingIdentity == null) null else main { text("food_name_tv").single() }
    override fun commitStarted() = customCommitStarted
    override fun screenStamp(): String = main { root(); requireNotNull(FatAccessibilityService.instance).screenStamp }
    private fun contextOnMain(): String = text("actionbar_title").single() + "\n" + text("actionbar_subtitle").single()
    override fun searchContext(): String = main { contextOnMain() }
    private fun <T> main(block: () -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper())
        check(!Thread.currentThread().isInterrupted) { "Screen operation cancelled" }
        val task = FutureTask(block)
        Handler(Looper.getMainLooper()).post(task)
        return try { task.get(4, TimeUnit.SECONDS) } catch (e: java.util.concurrent.ExecutionException) {
            val cause = e.cause
            if (cause is IllegalArgumentException) throw cause
            throw IllegalArgumentException("Не удалось прочитать экран FatSecret.", cause)
        } catch (e: Exception) {
            task.cancel(false)
            if (e is InterruptedException) Thread.currentThread().interrupt()
            throw IllegalArgumentException("Экран FatSecret недоступен. Разблокируй телефон и включи специальные возможности Android Agent.", e)
        }
    }
    private fun root(): AccessibilityNodeInfo {
        require(screenLease?.active != false) { "Время управления экраном истекло. Проверь результат; повторного сохранения не было." }
        require(context.getSystemService(android.os.PowerManager::class.java).isInteractive) { "Включи экран телефона и открой FatSecret." }
        require(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Разблокируй телефон. Агент не обходит блокировку экрана." }
        val service = requireNotNull(FatAccessibilityService.instance) { "Включи специальные возможности Android Agent в настройках телефона." }
        val root = requireNotNull(service.rootInActiveWindow) { "Открой FatSecret." }
        require(root.packageName?.toString() == FatAccessibilityService.FATSECRET_PACKAGE) { "Открой FatSecret. Другими приложениями агент не управляет." }
        return root
    }
    private fun visibleNodes(id: String): List<AccessibilityNodeInfo> = root().findAccessibilityNodeInfosByViewId(prefix + id)
        .filter { it.isVisibleToUser && !it.isPassword }
    private fun nodes(id: String): List<AccessibilityNodeInfo> = visibleNodes(id).filter { it.isEnabled }
    private fun text(id: String): List<String> = nodes(id).map { it.text?.toString().orEmpty().trim().take(240) }
    private fun click(node: AccessibilityNodeInfo) {
        var target = node
        repeat(4) {
            if (target.isClickable) {
                require(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "FatSecret отклонил нажатие. Повтор автоматически не выполнялся." }
                return
            }
            target = requireNotNull(target.parent) { "Кнопка FatSecret недоступна." }
            require(target.packageName?.toString() == FatAccessibilityService.FATSECRET_PACKAGE)
        }
        throw IllegalArgumentException("Элемент FatSecret недоступен для нажатия.")
    }
    private fun clickId(id: String) = main {
        val found = nodes(id)
        require(found.size == 1) { "Экран FatSecret изменился ($id). Действие остановлено." }
        click(found.single())
    }
    private fun setText(id: String, value: String) = main {
        val found = nodes(id)
        require(found.size == 1 && found.single().isEditable)
        require(found.single().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        })) { "FatSecret не принял текст." }
    }
    /** Loading indicator or a changed screen keeps waiting (up to ~30 s); an idle wrong screen stops at [timeoutMillis]. */
    private fun waitFor(timeoutMillis: Long = 10_000, test: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        val patience = ScreenPatience(timeoutMillis)
        while (true) {
            check(!Thread.currentThread().isInterrupted) { "Screen operation cancelled" }
            if (try { main(test) } catch (_: IllegalArgumentException) { false }) return
            val stamp = try { main { FatAccessibilityService.instance?.screenStamp } } catch (_: IllegalArgumentException) { null }
            val loading = try { main { loadingVisible() } } catch (_: IllegalArgumentException) { false }
            if (patience.shouldStop(SystemClock.elapsedRealtime() - start, stamp, loading)) break
            SystemClock.sleep(200)
        }
        throw ScreenTimeout("FatSecret не открыл ожидаемый экран. Запрос остановлен. Проверь дневник перед повторной отправкой; автоматического повтора не было.")
    }
    private fun loadingVisible(): Boolean {
        var visited = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (depth > 12 || visited++ > 300) return false
            if (node.isVisibleToUser && node.className?.toString() == "android.widget.ProgressBar") return true
            for (index in 0 until node.childCount) if (node.getChild(index)?.let { walk(it, depth + 1) } == true) return true
            return false
        }
        return walk(root(), 0)
    }
    /**
     * Before any save press a screen that did not open is retried from a known state (diary) up to [attempts] times in all.
     * Never repeats once the custom product was created or a save started.
     */
    private fun <T> navigating(attempts: Int = 3, block: () -> T): T {
        var tries = 0
        val started = SystemClock.elapsedRealtime()
        customCommitStarted = false
        while (true) {
            try { return block() } catch (error: ScreenTimeout) {
                // The whole transaction runs under one 120 s screen lease: leave time for the save and its verification.
                if (customCommitStarted || ++tries >= attempts || SystemClock.elapsedRealtime() - started > 45_000) throw error
                searchProgress?.invoke("FatSecret открывается медленно, пробую ещё раз ($tries из ${attempts - 1}).")
                try { cancelCustomDraft() } catch (_: IllegalArgumentException) { /* already on a plain screen */ }
                try { diary(allowDraft = true) } catch (recovery: IllegalArgumentException) { error.addSuppressed(recovery); throw error }
            }
        }
    }
    fun requireReady() {
        // Outside main(): re-enabling may wait for the system and the outage message uses the network.
        if (FatAccessibilityService.instance == null) AccessibilityGuard.current?.ensure()
        requireReadyOnMain()
    }
    private fun requireReadyOnMain() = main {
        require(context.getSystemService(android.os.PowerManager::class.java).isInteractive) { "Включи экран телефона и открой FatSecret." }
        require(FatAccessibilityService.instance != null) { "Включи специальные возможности Android Agent в настройках телефона." }
        FatAccessibilityService.instance?.syncKeyboardPackage()
        require(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Разблокируй телефон. Агент не обходит блокировку экрана." }
    }
    fun diary(allowDraft: Boolean = false) {
        requireReady()
        val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(FatAccessibilityService.FATSECRET_PACKAGE)) { "Установи FatSecret." }
        val alreadyOpen = main { FatAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString() == FatAccessibilityService.FATSECRET_PACKAGE }
        if (!alreadyOpen) context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor {
            root()
            nodes("tab_food").size == 1 || text("actionbar_title").any { it.isNotBlank() } ||
                nodes("fem_save_btn").isNotEmpty() || nodes("multi_add_save_section_holder").isNotEmpty() ||
                visibleNodes("food_journal_settings_list_view_text").isNotEmpty()
        }
        repeat(5) {
            if (!allowDraft) main {
                require(nodes("fem_save_btn").isEmpty() && nodes("multi_add_save_section_holder").isEmpty()) {
                    "В FatSecret открыт несохранённый продукт. Закончи его вручную или отмени черновик агента через /cancel."
                }
            }
            if (main { nodes("tab_food").size == 1 }) {
                if (main { nodes("date_in_navigation_bar").isEmpty() }) clickId("tab_food")
                waitFor { nodes("date_in_navigation_bar").size == 1 }
                return
            }
            main { root(); FatAccessibilityService.instance!!.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) }
            SystemClock.sleep(300)
        }
        throw IllegalArgumentException("Не найден дневник FatSecret. Войди в свой аккаунт в самом приложении.")
    }
    private fun requireToday() {
        val current = main { text("date_in_navigation_bar").singleOrNull()?.lowercase() in setOf("today", "сегодня") }
        if (!current) {
            clickId("today_link")
            waitFor { text("date_in_navigation_bar").singleOrNull()?.lowercase() in setOf("today", "сегодня") }
        }
    }
    private fun scrollDiary(forward: Boolean): Boolean {
        val moved = main {
            val list = nodes("food_journal_new_body")
            require(list.size == 1) { "Не найден список дневника. Открой дневник FatSecret." }
            list.single().performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        }
        if (moved) SystemClock.sleep(450)
        return moved
    }
    private fun top() { for (i in 0 until 12) if (!scrollDiary(false)) return }
    private fun labels(meal: String): Set<String> = when (meal) {
        "breakfast" -> setOf("breakfast", "завтрак")
        "lunch" -> setOf("lunch", "обед")
        "dinner" -> setOf("dinner", "ужин")
        else -> setOf("snacks/other", "перекус/другое", "перекус", "перекусы", "другое", "snacks", "snack", "other", "закуски")
    }
    private data class DiaryRow(val kind: String, val title: String, val detail: String, val calories: String)
    private data class Frame(val from: Int, val count: Int, val rows: List<DiaryRow?>)
    private fun frame(): Frame {
        var failure: IllegalArgumentException? = null
        repeat(8) { attempt ->
            try { return readFrame() } catch (error: IllegalArgumentException) {
                failure = error
                // A cold-start layout can change after the last scroll event. Refresh once, without relaxing coverage.
                if (error is FrameRangeMismatch && attempt == 3) {
                    if (indexedScroll(true)) indexedScroll(false) else if (indexedScroll(false)) indexedScroll(true)
                }
            }
            SystemClock.sleep(150)
        }
        throw requireNotNull(failure)
    }
    private fun readFrame(): Frame = main {
        requireTodayOnMain()
        val list = nodes("food_journal_new_body").single()
        val position = FatAccessibilityService.instance?.lastScroll ?: throw FrameRangeMismatch()
        diaryRangeState = "range=${position.from}..${position.to}/${position.count} children=${list.childCount} sameRoot=${position.rootHash == list.hashCode()} sameWindow=${position.window == list.windowId}"
        if (!(position.rootHash == list.hashCode() && position.window == list.windowId &&
            position.from >= 0 && position.to >= position.from && position.count in 1..300 &&
            position.to < position.count && list.childCount == position.to - position.from + 1)) throw FrameRangeMismatch()
        fun field(node: AccessibilityNodeInfo, id: String): String = node.findAccessibilityNodeInfosByViewId(prefix + id)
            .singleOrNull()?.text?.toString()?.trim().orEmpty()
        val rows = (0 until list.childCount).map { index ->
            val child = requireNotNull(list.getChild(index))
            val kind = when (child.viewIdResourceName) {
                prefix + "food_journal_add_food_holder" -> "heading"
                prefix + "food_journal_nutrition_holder" -> "summary"
                prefix + "swipe_layout" -> "entry"
                prefix + "food_journal_custom_meals_holder", prefix + "food_journal_water_row_holder" -> "boundary"
                else -> if (listOf("food_journal_show_less", "copy_meal_text", "save_meal_tv", "food_journal_bottom_number_of_images_holder")
                    .any { child.findAccessibilityNodeInfosByViewId(prefix + it).isNotEmpty() }) "footer" else "other"
            }
            val value = when (kind) {
                "heading" -> DiaryRow(kind, field(child, "food_journal_heading_row_title"), "", field(child, "food_journal_heading_row_total_calories"))
                "summary" -> DiaryRow(kind, field(child, "food_journal_subtitle_nutrition_info"), "", "")
                "entry" -> DiaryRow(kind, field(child, "food_journal_item_row_title"), field(child, "food_journal_item_row_details"), field(child, "food_journal_item_row_title_energy"))
                "footer" -> DiaryRow(kind, "", if (child.findAccessibilityNodeInfosByViewId(prefix + "food_journal_show_less").isNotEmpty()) "expanded" else "", "")
                else -> DiaryRow(kind, "", "", "")
            }
            value.takeIf {
                when (kind) {
                    "entry" -> it.title.isNotBlank() && it.detail.isNotBlank() && it.calories.isNotBlank()
                    "heading" -> it.title.isNotBlank()
                    "summary" -> it.title.isNotBlank() || child.findAccessibilityNodeInfosByViewId(prefix + "food_journal_expand_collapse_header_icon")
                        .singleOrNull()?.isVisibleToUser == true
                    // Unknown adapter rows still occupy an index, even when they are empty spacers.
                    // They remain "other" and are rejected if they occur inside the requested meal.
                    else -> true
                }
            }
        }
        Frame(position.from, position.count, rows)
    }
    private fun requireTodayOnMain() {
        require(text("date_in_navigation_bar").singleOrNull()?.lowercase() in setOf("today", "сегодня"))
    }
    private fun scanDiary(): Map<Int, DiaryRow> {
        // Refresh adapter bounds once, without a return gesture or a reset to the top.
        if (!indexedScroll(true)) indexedScroll(false)
        val coverage = DiaryCoverage<DiaryRow>()
        fun collect(): Boolean {
            val current = frame()
            return coverage.add(current.from, current.count, current.rows)
        }
        for (forward in listOf(true, false)) for (page in 0 until 60) {
            if (collect()) return coverage.rows
            if (!indexedScroll(forward)) break
        }
        diaryRangeState += " missing=" + coverage.missing()
        throw DiaryReadFailure("Не удалось прочитать весь дневник. Проверь запись вручную.")
    }
    private fun indexedScroll(forward: Boolean): Boolean {
        val sequence = FatAccessibilityService.instance?.lastScroll?.sequence ?: 0
        val moved = scrollDiary(forward)
        if (moved) require((FatAccessibilityService.instance?.lastScroll?.sequence ?: 0) > sequence) {
            "FatSecret не подтвердил новые границы списка. Проверь результат вручную."
        }
        return moved
    }
    private fun indexedTop() { for (page in 0 until 60) if (!indexedScroll(false)) return }
    /** What a diary read got through before it stopped; only counts and a fixed step name reach the event log. */
    private class ScanTrace { var step = "start"; var rows = 0; var sections = 0; var sectionFound = false; var expanded = false }
    override fun snapshot(meal: String): DiarySnapshot {
        val started = SystemClock.elapsedRealtime()
        val trace = ScanTrace()
        try {
            val result = snapshotTraced(meal, trace)
            Telemetry.log("diary_scan", ok = true, ms = SystemClock.elapsedRealtime() - started,
                n = mapOf("rows" to trace.rows, "sections" to trace.sections, "entries" to result.entries.size),
                flags = mapOf("step" to "done", "expanded" to trace.expanded, "section_found" to trace.sectionFound, "complete" to result.complete))
            return result
        } catch (error: Throwable) {
            if (error is InterruptedException) throw error
            Telemetry.log("diary_scan", ok = false, ms = SystemClock.elapsedRealtime() - started, errClass = Telemetry.errClass(error),
                n = mapOf("rows" to trace.rows, "sections" to trace.sections),
                flags = mapOf("step" to trace.step, "expanded" to trace.expanded, "section_found" to trace.sectionFound, "complete" to false),
                ids = fingerprint())
            throw error
        }
    }
    override fun fingerprint(): List<String> = try {
        main {
            val root = FatAccessibilityService.instance?.rootInActiveWindow
            val ids = linkedSetOf<String>()
            if (root?.packageName?.toString() == FatAccessibilityService.FATSECRET_PACKAGE) {
                var visited = 0
                fun walk(node: AccessibilityNodeInfo, depth: Int) {
                    if (depth > 24 || visited++ >= 600 || ids.size >= 60) return
                    if (node.isVisibleToUser && !node.isPassword) node.viewIdResourceName?.removePrefix(prefix)?.let { ids.add(it) }
                    for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1) }
                }
                walk(root, 0)
            }
            ids.toList()
        }
    } catch (_: Exception) { emptyList() }
    private fun snapshotTraced(meal: String, trace: ScanTrace): DiarySnapshot {
        require(meal in Meals.types)
        val date = java.time.LocalDate.now().toString()
        trace.step = "open_diary"
        diary(); requireToday()
        fun section(rows: Map<Int, DiaryRow>): List<Pair<Int, DiaryRow>> {
            trace.step = "find_section"
            trace.rows = rows.size
            trace.sections = rows.values.count { it.kind == "heading" }
            val heading = rows.entries.singleOrNull { it.value.kind == "heading" && it.value.title.lowercase() in labels(meal) }?.key
                ?: throw DiaryReadFailure("Не найден раздел приёма пищи в дневнике FatSecret.")
            return rows.entries.dropWhile { it.key <= heading }.takeWhile { it.value.kind !in setOf("heading", "boundary") }.map { it.key to it.value }
        }
        trace.step = "scan_first"
        var rows = scanDiary()
        var content = section(rows)
        trace.sectionFound = true
        if (content.isEmpty()) {
            val heading = rows.values.single { it.kind == "heading" && it.title.lowercase() in labels(meal) }
            if (heading.calories !in setOf("", "0")) throw DiaryReadFailure("Раздел приёма пищи не раскрылся: заголовок показывает калории, строк нет.")
            require(java.time.LocalDate.now().toString() == date)
            return DiarySnapshot(date, meal, emptyList(), true)
        }
        var summary = content.firstOrNull { it.second.kind == "summary" }
            ?: throw IllegalArgumentException("Не найден счётчик продуктов FatSecret.")
        var expectedCount = DiarySectionProof.count(summary.second.title)
        if (expectedCount != 0 && content.none { it.second.kind == "entry" }) {
            val original = summary
            trace.step = "expand"
            indexedTop()
            var expanded = false
            for (page in 0 until 60) {
                val current = frame()
                if (original.first in current.from until current.from + current.rows.size) {
                    main {
                        val list = nodes("food_journal_new_body").single()
                        val holder = requireNotNull(list.getChild(original.first - current.from))
                        require(holder.viewIdResourceName == prefix + "food_journal_nutrition_holder")
                        val icon = holder.findAccessibilityNodeInfosByViewId(prefix + "food_journal_expand_collapse_header_icon").single()
                        click(icon)
                    }
                    SystemClock.sleep(400)
                    expanded = true
                    break
                }
                if (!indexedScroll(true)) break
            }
            require(expanded) { "Не удалось раскрыть продукты приёма пищи." }
            trace.expanded = true
            trace.step = "scan_after_expand"
            rows = scanDiary()
            content = section(rows)
            summary = content.first { it.second.kind == "summary" }
            if (expectedCount != null) require(summary.second.title == original.second.title)
            expectedCount = DiarySectionProof.count(summary.second.title)
        }
        trace.step = "check_entries"
        val entries = content.filter { it.second.kind == "entry" }.map { (_, row) ->
            require(row.title.isNotBlank() && row.detail.isNotBlank() && row.calories.matches(Regex("[0-9]+")))
            DiaryEntry(row.title, row.detail, row.calories)
        }
        if (!DiarySectionProof.complete(expectedCount, entries.size,
                content.any { it.second.kind == "footer" && it.second.detail == "expanded" },
                content.any { it.second.kind == "other" })) throw DiaryReadFailure("Состав приёма пищи прочитан не полностью.")
        require(java.time.LocalDate.now().toString() == date) { "Дата изменилась во время чтения." }
        return DiarySnapshot(date, meal, entries, true)
    }
    fun today(): String {
        diary(); requireToday(); top()
        val meals = linkedMapOf<String, String>()
        for (i in 0 until 12) {
            main {
                for (heading in nodes("food_journal_heading_row_title")) {
                    val holder = heading.parent ?: continue
                    require(holder.viewIdResourceName == prefix + "food_journal_add_food_holder")
                    val name = heading.text.toString().trim().take(100)
                    val calories = holder.findAccessibilityNodeInfosByViewId(prefix + "food_journal_heading_row_total_calories")
                        .singleOrNull()?.text?.toString()?.trim().orEmpty()
                    if (calories.isNotBlank()) meals[name] = "$calories ккал"
                    else if (name !in meals) meals[name] = "значение не показано"
                }
            }
            if (!scrollDiary(true)) break
        }
        require(meals.isNotEmpty()) { "Не удалось прочитать разделы дневника." }
        return "Сводка с экрана FatSecret за сегодня:\n" + meals.entries.joinToString("\n") { "${it.key}: ${it.value}" } +
            "\nЭто сводка разделов, полный состав - в FatSecret."
    }
    /** The «Food» tab of the search screen, or null while it is scrolled out of the tab strip. Call on the main thread. */
    private fun foodTab(): AccessibilityNodeInfo? {
        val matches = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            require(depth <= 10 && visited++ < 100)
            if (node.isVisibleToUser && node.isEnabled && SearchTabs.isFood(node.text)) matches += node
            for (index in 0 until node.childCount) node.getChild(index)?.let { walk(it, depth + 1) }
        }
        walk(nodes("food_journal_add_indicator").singleOrNull() ?: return null, 0)
        require(matches.size <= 1) { "Вкладка поиска продуктов недоступна. Ничего не записано." }
        return matches.singleOrNull()
    }
    private fun openMeal(meal: String) {
        require(meal in Meals.types)
        diary(); requireToday()
        val labels = when(meal) {
                "breakfast" -> setOf("breakfast", "завтрак")
                "lunch" -> setOf("lunch", "обед")
                "dinner" -> setOf("dinner", "ужин")
                else -> setOf("snacks/other", "перекус/другое", "перекус", "перекусы", "другое", "snacks", "snack", "other", "закуски")
            }
        var opened = false
        val visible = main { nodes("food_journal_heading_row_title").filter { it.text?.toString()?.lowercase() in labels } }
        require(visible.size <= 1) { "Неоднозначный раздел дневника." }
        if (visible.isNotEmpty()) {
            main { click(visible.single()) }
            opened = true
        } else top()
        for (i in 0 until 12) {
            if (opened) break
            opened = main {
                val target = nodes("food_journal_heading_row_title").filter { it.text?.toString()?.lowercase() in labels }
                require(target.size <= 1) { "Неоднозначный раздел дневника." }
                if (target.isEmpty()) false else { click(target.single()); true }
            }
            if (opened || !scrollDiary(true)) break
        }
        require(opened) { "Нужный приём пищи не найден в дневнике FatSecret." }
        waitFor { nodes("food_journal_add_indicator").size == 1 }
        // FatSecret reopens the last used tab, and «Recipes» has a search field too, so a field alone proves nothing.
        if (main { nodes("edit_text").isEmpty() || foodTab()?.isSelected != true }) {
            for (step in 0 until 5) {
                val moved = main { nodes("food_journal_add_indicator").single().performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }
                if (!moved) break
                SystemClock.sleep(150)
            }
            main { click(requireNotNull(foodTab()) { "Вкладка поиска продуктов недоступна. Ничего не записано." }) }
            // The tab may report selected before the pages slide, while the old tab's search field is still the only one visible.
            SystemClock.sleep(700)
        }
        waitFor { nodes("edit_text").size == 1 && foodTab()?.isSelected != false }
        main {
            require(FatUiDate.matches(text("actionbar_subtitle").singleOrNull().orEmpty(), java.time.LocalDate.now())) {
                "Дата на экране FatSecret не совпадает с сегодняшней или её формат неизвестен. Проверь дату в приложении."
            }
        }
    }
    override fun search(meal: String, query: String): List<Row> = searchResults(meal, query, false)
    fun existingCandidates(meal: String, query: String): List<Row> = searchResults(meal, query, true)
    private fun searchResults(meal: String, query: String, exactQuery: Boolean): List<Row> {
        require(query.length in 2..150)
        openMeal(meal)
        clickId("edit_text")
        setText("edit_text", "")
        SystemClock.sleep(150)
        setText("edit_text", query)
        SystemClock.sleep(350)
        val suggested = main {
            val suggestions = nodes("auto_suggestion_row_image_row_label")
            if (suggestions.isEmpty()) false else {
                val exact = suggestions.filter { it.text?.toString()?.trim()?.equals(query.trim(), true) == true }
                require(exact.size <= 1) { "Неоднозначная подсказка поиска FatSecret." }
                if (exactQuery && exact.isEmpty()) return@main false
                // FatSecret may translate a query. This opens candidates, never picks a food.
                click(exact.singleOrNull() ?: suggestions.first())
                true
            }
        }
        searchState = if (suggested) "suggestion" else "keyboard_search"
        if (!suggested) keyboardSearch(query)
        waitFor { nodes("title_description").isNotEmpty() || nodes("search_results_nomatch").isNotEmpty() }
        if (main { nodes("title_description").isEmpty() }) return emptyList()
        searchState = "results"
        var previous = emptyList<Row>()
        var stableSince = SystemClock.elapsedRealtime()
        val deadline = stableSince + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = try { rows() } catch (_: IllegalArgumentException) { emptyList() }
            if (current.isNotEmpty() && current == previous && SystemClock.elapsedRealtime() - stableSince >= 450) return current
            if (current != previous) { previous = current; stableSince = SystemClock.elapsedRealtime() }
            SystemClock.sleep(150)
        }
        throw IllegalArgumentException("Результаты поиска ещё меняются. Повтори запрос после загрузки FatSecret.")
    }
    private fun keyboardSearch(query: String) {
        val expectedContext = main { contextOnMain() }
        waitFor {
            contextOnMain() == expectedContext && nodes("edit_text").singleOrNull()?.text?.toString() == query && keyboardSearchKey() != null
        }
        main {
            val key = requireNotNull(keyboardSearchKey()) { "Клавиша поиска изменилась. Подготовь запрос заново." }
            require(contextOnMain() == expectedContext && nodes("edit_text").single().text?.toString() == query)
            require(key.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Клавиатура не приняла поиск." }
        }
    }
    private fun keyboardSearchKey(): AccessibilityNodeInfo? {
            root()
            val selected = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            val defaultPackage = ComponentName.unflattenFromString(selected.orEmpty())?.packageName.orEmpty()
            val ime = requireNotNull(FatAccessibilityService.instance).windows
                .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }.singleOrNull() ?: return null
            val keyboard = ime.root ?: return null
            if (!keyboard.refresh()) return null
            val keyboardPackage = keyboard.packageName?.toString().orEmpty()
            val keyboardBounds = Rect().also { keyboard.getBoundsInScreen(it) }
            val matches = mutableListOf<AccessibilityNodeInfo>()
            var visited = 0
            fun walk(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 20 || visited++ >= 250) return
                if (!node.refresh()) return
                val label = node.contentDescription?.toString().orEmpty().ifBlank { node.text?.toString().orEmpty() }
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if (node.isVisibleToUser && node.isEnabled && node.isClickable && !node.isPassword &&
                    bounds.height() > 0 && keyboardBounds.contains(bounds) && bounds.centerY() > keyboardBounds.centerY() &&
                    KeyboardSearchPolicy.accepts(defaultPackage, keyboardPackage, node.packageName?.toString().orEmpty(), label, node.viewIdResourceName.orEmpty())) matches += node
                for (index in 0 until node.childCount) node.getChild(index)?.let { walk(it, depth + 1) }
            }
            walk(keyboard, 0)
            return matches.singleOrNull()
    }
    private fun candidateRows(): List<Pair<AccessibilityNodeInfo, Row>> {
        fun field(row: AccessibilityNodeInfo, id: String) = row.findAccessibilityNodeInfosByViewId(prefix + id)
            .filter { it.isVisibleToUser && it.isEnabled && !it.isPassword }.singleOrNull()
        return nodes("multi_add_item_row").mapNotNull { row ->
            val name = field(row, "title_description") ?: return@mapNotNull null
            val portion = field(row, "sub_title_portion_description") ?: return@mapNotNull null
            name to Row(name.text?.toString().orEmpty().trim().take(240), portion.text?.toString().orEmpty().trim().take(240))
        }.filter { it.second.name.isNotBlank() && it.second.detail.isNotBlank() }.take(6)
    }
    fun rows(): List<Row> = main {
        val found = candidateRows().map { it.second }
        require(found.isNotEmpty()) { "Не удалось прочитать результаты поиска FatSecret." }
        found
    }
    private fun customRow(labels: Set<String>) {
        for (attempt in 0 until 6) {
            val clicked = main {
                require(nodes("custom_entry_advanced_ok").size == 1)
                val rows = root().findAccessibilityNodeInfosByViewId(prefix + "custom_entry_product_info_row_title")
                    .filter { it.isEnabled && it.text?.toString()?.trim()?.lowercase() in labels }
                require(rows.size <= 1) { "Неоднозначное поле своей еды. Запись остановлена." }
                val row = rows.singleOrNull()
                if (row?.isVisibleToUser == true) { click(row); return@main true }
                if (row != null) row.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                else {
                    // Localized labels wrap onto more lines; the regional form is a ListView.
                    val scrolls = root().findAccessibilityNodeInfosByViewId("android:id/list").filter {
                        it.isVisibleToUser && it.className?.toString() == "android.widget.ListView" &&
                            it.findAccessibilityNodeInfosByViewId(prefix + "custom_entry_product_info_row_title").isNotEmpty()
                    }
                    require(scrolls.size == 1) { "Поле своей еды не найдено. Запись остановлена." }
                    scrolls.single().performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
                false
            }
            if (clicked) return
            SystemClock.sleep(250)
        }
        throw IllegalArgumentException("Поле своей еды не найдено. Запись остановлена.")
    }
    private fun customScroll(forward: Boolean): Boolean = main {
        require(visibleNodes("save_food_preview_btn").size == 1) { "Экран ввода КБЖУ изменился." }
        val body = nodes("scroll_body").single()
        body.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }.also { SystemClock.sleep(150) }
    private fun customNumber(holder: String, value: String) {
        require(holder in setOf("energy_input", "fat_input", "total_carbs_input", "protein_input"))
        for (attempt in 0 until 30) {
            val found = main {
                searchState = "custom_field_${holder}_$attempt"
                val container = root().findAccessibilityNodeInfosByViewId(prefix + holder).singleOrNull()
                    ?: run { searchState += "_container_missing"; return@main 0 }
                if (!container.isVisibleToUser && container.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)) return@main 2
                val edit = mutableListOf<AccessibilityNodeInfo>()
                fun editors(node: AccessibilityNodeInfo, depth: Int) {
                    if (depth > 5) return
                    if (node.viewIdResourceName == prefix + "edit_text" && node.isEnabled && node.className?.toString() == "android.widget.EditText") edit += node
                    for (index in 0 until minOf(node.childCount, 12)) node.getChild(index)?.let { editors(it, depth + 1) }
                }
                editors(container, 0)
                if (edit.size != 1) {
                    searchState += "_editor_count_${edit.size}"
                    return@main if (container.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)) 2 else 0
                }
                val area = Rect().also { nodes("scroll_body").single().getBoundsInScreen(it) }
                val field = Rect().also { edit.single().getBoundsInScreen(it) }
                if (!edit.single().isVisibleToUser || !area.contains(field)) {
                    if (edit.single().performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)) return@main 2
                    return@main if (field.top < area.top) -1 else 0
                }
                if (holder == "energy_input") {
                    val hint = edit.single().hintText?.toString()?.lowercase().orEmpty()
                    val conversion = text("footer_unit_measurement_conversion_text").joinToString(" ").lowercase()
                    require(hint in setOf("calories", "калории", "ккал") || conversion.startsWith("1 cal =") || conversion.startsWith("1 кал =")) { "Ожидались калории, а не кДж. Запись остановлена." }
                }
                edit.single().performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                require(edit.single().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }))
                edit.single().refresh()
                require(edit.single().text?.toString()?.replace(',', '.')?.toBigDecimalOrNull()?.compareTo(value.toBigDecimal()) == 0)
                1
            }
            if (found == 1) return
            if (found == 2) { SystemClock.sleep(150); continue }
            require(customScroll(found != -1)) { "Не найдено обязательное поле КБЖУ." }
        }
        throw IllegalArgumentException("Не удалось заполнить КБЖУ своей еды.")
    }
    private fun nutritionSaveNodes(): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 20 || visited++ > 600) return
            if (node.isVisibleToUser && node.isEnabled && node.text?.toString() in setOf("Save", "Сохранить")) found += node
            for (index in 0 until node.childCount) node.getChild(index)?.let { walk(it, depth + 1) }
        }
        walk(root(), 0)
        return found
    }
    private fun saveNutritionPreview() = main {
        require(text("actionbar_subtitle").singleOrNull()?.lowercase() in setOf("preview", "предпросмотр", "предварительный просмотр"))
        val exact = nutritionSaveNodes()
        require(exact.size == 1) { "Кнопка подтверждения КБЖУ изменилась." }
        click(exact.single())
    }
    /** Custom-food creation is itself journaled before entering this method. */
    override fun createCustom(meal: String, item: org.json.JSONObject, onContext: (String) -> Unit): String = navigating { createCustomOnce(meal, item, onContext) }
    private fun createCustomOnce(meal: String, item: org.json.JSONObject, onContext: (String) -> Unit): String {
        customCommitStarted = false
        MealNutrition.validate(item)
        val n = item.getJSONObject("nutrition")
        val basis = n.getString("basis_grams").toBigDecimal()
        fun per100(key: String) = (n.getString(key).toBigDecimal() * java.math.BigDecimal("100"))
            .divide(basis, 6, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        val query = "agent" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        searchState = "custom_open"
        openMeal(meal)
        onContext(main { contextOnMain() })
        clickId("edit_text"); setText("edit_text", query)
        keyboardSearch(query)
        waitFor { nodes("search_results_addcustom").size == 1 }
        clickId("search_results_addcustom")
        waitFor { nodes("custom_entry_advanced_ok").size == 1 }
        customRow(setOf("brand", "бренд", "торговая марка", "марка"))
        waitFor { nodes("titled_list_item_title").isNotEmpty() }
        main {
            val row = nodes("titled_list_item_title").filter { it.text?.toString()?.lowercase() in setOf("my custom food entry", "ввод моей собственной еды", "моя собственная еда", "мой продукт", "мой собственный продукт") }
            require(row.size == 1) { "Не найден режим своей еды." }; click(row.single())
        }
        waitFor { nodes("custom_entry_adv_brand_input_field").size == 1 }
        setText("custom_entry_adv_brand_input_field", "Моя еда")
        waitFor { nodes("action_save").size == 1 }; clickId("action_save")
        waitFor { nodes("custom_entry_advanced_ok").size == 1 }
        customRow(setOf("product name", "название продукта", "наименование продукта"))
        waitFor { nodes("custom_entry_adv_product_name_input").size == 1 }
        setText("custom_entry_adv_product_name_input", item.getString("query"))
        waitFor { nodes("action_save").size == 1 }; clickId("action_save")
        waitFor { nodes("custom_entry_advanced_ok").size == 1 }
        customRow(setOf("nutrition information", "пищевая ценность", "информация о пищевой ценности", "питательная ценность"))
        waitFor { root().findAccessibilityNodeInfosByViewId("android:id/text1").any {
            it.isVisibleToUser && it.text?.toString()?.replace(" ", "") in setOf("100g", "100г")
        } }
        main {
            val entries = root().findAccessibilityNodeInfosByViewId("android:id/text1").filter {
                it.isVisibleToUser && it.text?.toString()?.replace(" ", "") in setOf("100g", "100г")
            }
            require(entries.size == 1) { "Не найдена основа 100 г." }; click(entries.single())
        }
        waitFor { visibleNodes("save_food_preview_btn").size == 1 }
        require(main { text("metric_serving_size_label").singleOrNull() == "100" && text("metric_serving_size_text").singleOrNull()?.lowercase() in setOf("grams", "граммы", "грамм", "г") })
        searchState = "custom_nutrition"
        customNumber("energy_input", per100("calories"))
        customNumber("fat_input", per100("fat"))
        customNumber("total_carbs_input", per100("carbs"))
        customNumber("protein_input", per100("protein"))
        searchState = "custom_nutrition_done"
        clickId("save_food_preview_btn")
        waitFor { text("actionbar_subtitle").singleOrNull()?.lowercase() in setOf("preview", "предпросмотр", "предварительный просмотр") }
        searchState = "custom_nutrition_preview_save"
        waitFor { nutritionSaveNodes().size == 1 }
        saveNutritionPreview()
        searchState = "custom_nutrition_review"
        waitFor { nodes("custom_entry_advanced_ok").size == 1 }
        main {
            val summary = text("custom_entry_product_info_row_value").filter { it.contains("Calories:") || it.contains("Калории:") }
            require(summary.size == 1) { "Не удалось прочитать итоговые КБЖУ своей еды." }
            val values = Regex("[0-9]+(?:[.,][0-9]+)?").findAll(summary.single()).map { it.value.replace(',', '.').toBigDecimal() }.toList()
            val expected = listOf("100", per100("calories"), per100("fat"), per100("carbs"), per100("protein")).map { it.toBigDecimal() }
            require(values.size == expected.size && values.indices.all { values[it].compareTo(expected[it]) == 0 }) { "FatSecret изменил введённые КБЖУ. Сохранение остановлено." }
        }
        return finishPreparedCustom(item)
    }
    override fun finishPreparedCustom(item: org.json.JSONObject): String {
        require(main { nodes("custom_entry_advanced_ok").size == 1 && text("custom_entry_product_info_row_value").contains(item.getString("query")) && text("custom_entry_product_info_row_value").contains("Моя еда") })
        searchState = "custom_tag"
        customRow(setOf("add/edit tags", "добавить/редактировать категории", "добавить/изменить теги", "добавить/изменить метки", "теги", "метки"))
        waitFor { nodes("custom_entry_adv_tag_input_field").isNotEmpty() || nodes("checkbox_list_item_row_title").isNotEmpty() }
        if (main { nodes("custom_entry_adv_tag_input_field").isNotEmpty() }) {
            setText("custom_entry_adv_tag_input_field", item.getString("query"))
            waitFor { nodes("custom_entry_adv_tag_save").size == 1 }
            clickId("custom_entry_adv_tag_save")
            waitFor { nodes("checkbox_list_item_row_title").isNotEmpty() }
        }
        main {
            val categories = nodes("checkbox_list_item_row_title").filter { !it.text.isNullOrBlank() }
            // Categories are required metadata, not catalog foods or a source of nutrition.
            val title = categories.firstOrNull { it.text.toString().equals(item.getString("query"), ignoreCase = true) }
                ?: categories.firstOrNull()
            requireNotNull(title) { "FatSecret не предложил категорию для своей еды." }
            val checks = mutableListOf<AccessibilityNodeInfo>()
            fun walk(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 5) return
                if (node.viewIdResourceName == prefix + "checkbox_list_item_row_checkbox") checks += node
                for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1) }
            }
            walk(requireNotNull(title.parent), 0)
            require(checks.size == 1)
            if (!checks.single().isChecked) click(checks.single())
        }
        waitFor { nodes("checkbox_list_item_row_checkbox").any { it.isChecked } }
        clickId("action_save")
        waitFor { nodes("custom_entry_advanced_ok").size == 1 }
        main {
            val n = item.getJSONObject("nutrition")
            fun value(key: String) = (n.getString(key).toBigDecimal() * java.math.BigDecimal("100")).divide(n.getString("basis_grams").toBigDecimal(), 6, java.math.RoundingMode.HALF_UP)
            val summary = text("custom_entry_product_info_row_value").single { it.contains("Calories:") || it.contains("Калории:") }
            val actual = Regex("[0-9]+(?:[.,][0-9]+)?").findAll(summary).map { it.value.replace(',', '.').toBigDecimal() }.toList()
            val expected = listOf(java.math.BigDecimal("100"), value("calories"), value("fat"), value("carbs"), value("protein"))
            require(actual.size == expected.size && actual.indices.all { actual[it].compareTo(expected[it]) == 0 }) { "КБЖУ своей еды изменились. Запись остановлена." }
        }
        searchState = "custom_form_ready"
        beforeCustomCommit()
        searchState = "custom_product_save"
        customCommitStarted = true
        clickId("custom_entry_advanced_ok")
        waitFor(30_000) { nodes("food_name_tv").size == 1 && nodes("fem_save_btn").size == 1 }
        searchState = "custom_portion"
        return finishCustomPortion(item)
    }
    override fun finishCustomPortion(item: org.json.JSONObject) = setPortion(item.getString("grams"), item.getString("query"))
    override fun cancelCustomDraft() {
        require(!customCommitStarted) { "Создание продукта уже начиналось. Автоматическая отмена запрещена." }
        repeat(6) {
            val cancelled = main {
                if (nodes("custom_entry_advanced_cancel").size == 1) { click(nodes("custom_entry_advanced_cancel").single()); return@main true }
                val servingSize = text("alertTitle").singleOrNull()?.lowercase() in setOf("serving size", "размер порции") &&
                    root().findAccessibilityNodeInfosByViewId("android:id/text1").any { it.text?.toString()?.replace(" ", "") in setOf("100g", "100г") }
                require(visibleNodes("save_food_preview_btn").isNotEmpty() || nodes("title_of_food_edit").isNotEmpty() ||
                    servingSize || nodes("custom_entry_adv_tag_input_field").isNotEmpty() || nodes("checkbox_list_item_row_title").isNotEmpty() ||
                    text("actionbar_subtitle").singleOrNull()?.lowercase() in setOf("preview", "предпросмотр", "предварительный просмотр", "add new food", "добавить новый продукт", "добавить новую еду"))
                requireNotNull(FatAccessibilityService.instance).performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                false
            }
            if (cancelled) return
            SystemClock.sleep(250)
        }
        throw IllegalArgumentException("Не удалось закрыть несохранённую форму своей еды.")
    }
    override fun choose(index: Int, expected: List<Row>, grams: String): String {
        require(rows() == expected) { "Результаты поиска изменились. Отправь еду заново." }
        main {
            val candidates = candidateRows()
            require(candidates.map { it.second } == expected && index in candidates.indices) { "Результаты поиска изменились." }
            click(candidates[index].first)
        }
        waitFor { nodes("food_name_tv").size == 1 && nodes("fem_save_btn").size == 1 }
        return setPortion(grams)
    }
    private fun searchList() = root().findAccessibilityNodeInfosByViewId("android:id/list")
        .filter { it.isVisibleToUser && it.isEnabled }.single()
    private fun scrollResults(forward: Boolean): Boolean {
        val sequence = FatAccessibilityService.instance?.resultScroll?.sequence ?: 0
        val moved = main { searchList()
            .performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }
        if (moved) {
            SystemClock.sleep(450)
            require((FatAccessibilityService.instance?.resultScroll?.sequence ?: 0) > sequence) { "Поиск не подтвердил границы выдачи. Ничего не записано." }
        }
        return moved
    }
    private fun resultsTop() { repeat(60) { if (!scrollResults(false)) return }; error("Search is too long") }
    private fun resultFrame(): Pair<Int, List<Row?>> = main {
        val list = searchList()
        val range = requireNotNull(FatAccessibilityService.instance?.resultScroll)
        require(range.rootHash == list.hashCode() && range.window == list.windowId && range.from >= 0 &&
            range.count in 1..300 && range.to < range.count && list.childCount == range.to - range.from + 1) {
            "Не удалось полностью прочитать выдачу. Укажи точное название с размером и брендом. Ничего не записано."
        }
        val values = (0 until list.childCount).map { index ->
            val row = requireNotNull(list.getChild(index))
            fun field(id: String) = row.findAccessibilityNodeInfosByViewId(prefix + id).singleOrNull()?.text?.toString()?.trim()
            if (row.viewIdResourceName == prefix + "multi_add_item_row") {
                val name = field("title_description")
                val detail = field("sub_title_portion_description")
                if (name.isNullOrBlank() || detail.isNullOrBlank()) null else Row(name, detail)
            } else {
                if (listOf("search_results_next", "search_results_previous", "search_results_addcustom").any { field(it) != null }) Row("", "") else null
            }
        }
        range.from to values
    }
    private fun readResultPage(): Map<Int, Row> {
        if (scrollResults(true)) scrollResults(false) else if (scrollResults(false)) scrollResults(true)
        resultsTop()
        val all = sortedMapOf<Int, Row>()
        var total = -1
        for (viewport in 0 until 60) {
            val (from, visible) = resultFrame()
            val count = requireNotNull(FatAccessibilityService.instance?.resultScroll).count
            require(total == -1 || total == count)
            total = count
            visible.forEachIndexed { index, row ->
                if (row != null) {
                    require(all[from + index] == null || all[from + index] == row) { "Выдача изменилась. Ничего не записано." }
                    all[from + index] = row
                }
            }
            if (all.size == total || !scrollResults(true)) break
        }
        require(all.size == total && all.keys.first() == 0 && all.keys.last() == total - 1) { "Выдача прочитана не полностью. Ничего не записано." }
        return all
    }
    private fun clickResult(index: Int, expected: Row) {
        resultsTop()
        for (viewport in 0 until 60) {
            val (from, visible) = resultFrame()
            if (index in from until from + visible.size) {
                if (visible[index - from] == null) {
                    main { requireNotNull(searchList().getChild(index - from)).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) }
                    SystemClock.sleep(250)
                    continue
                }
                require(visible[index - from] == expected) { "Результат поиска изменился." }
                main {
                    val row = requireNotNull(searchList().getChild(index - from))
                    val title = row.findAccessibilityNodeInfosByViewId(prefix + "title_description").single()
                    require(title.text.toString().trim() == expected.name)
                    require(row.findAccessibilityNodeInfosByViewId(prefix + "sub_title_portion_description").single().text.toString().trim() == expected.detail)
                    click(title)
                }
                return
            }
            if (!scrollResults(true)) break
        }
        throw IllegalArgumentException("Выбранная еда больше не видна. Ничего не записано.")
    }
    override fun prepareExisting(meal: String, item: org.json.JSONObject, onContext: (String) -> Unit): String = navigating { prepareExistingOnce(meal, item, onContext) }
    private fun prepareExistingOnce(meal: String, item: org.json.JSONObject, onContext: (String) -> Unit): String {
        existingIdentity = null
        val queries = FoodSearch.queries(item)
        val found = linkedMapOf<Row, String>()
        var chosen: Pair<Row, String>? = null
        for ((attempt, query) in queries.withIndex()) {
            searchState = "search_attempt_${attempt + 1}"
            searchProgress?.invoke("Ищу еду в FatSecret: $query")
            if (existingCandidates(meal, query).isEmpty()) continue
            onContext(searchContext())
            val page = readResultPage().values.filter { it.name.isNotBlank() }
            page.forEach { found.putIfAbsent(it, query) }
            val matching = page.filter { FoodSearch.matches(item.getString("query"), it.name) }.distinct()
            if (matching.size == 1) { chosen = matching.single() to query; break }
        }
        if (chosen == null && found.isNotEmpty()) {
            searchProgress?.invoke("Сверяю найденные названия с твоим описанием через ИИ на ПК.")
            val candidates = found.keys.take(80)
            val index = candidateResolver?.invoke(item, candidates, false)
            if (index != null) {
                require(index in candidates.indices)
                val row = candidates[index]
                chosen = row to found.getValue(row)
            }
        }
        requireNotNull(chosen) { "Проверил варианты поиска, подходящую еду однозначно не нашёл. Уточни название, бренд или размер; ничего не записано." }
        val (expected, query) = chosen
        existingCandidates(meal, query)
        onContext(searchContext())
        val selected = readResultPage().entries.singleOrNull { it.value == expected }
            ?: throw IllegalArgumentException("Выбранная карточка изменилась или продублирована. Ничего не записано.")
        clickResult(selected.key, expected)
        waitFor { nodes("food_name_tv").size == 1 && nodes("fem_save_btn").size == 1 }
        existingIdentity = expected.name
        return setPortion(FoodSearch.grams(item, expected.detail))
    }
    private fun savedList(meal: String): Map<Int, Row> {
        openMeal(meal)
        for (attempt in 0 until 8) {
            val opened = main {
                val tabs = nodes("food_journal_add_indicator").single()
                val matches = mutableListOf<AccessibilityNodeInfo>()
                fun walk(node: AccessibilityNodeInfo) {
                    if (node.isVisibleToUser && node.text?.toString()?.trim()?.lowercase() in setOf("saved meals", "сохраненная еда", "сохранённая еда")) matches += node
                    for (index in 0 until node.childCount) node.getChild(index)?.let(::walk)
                }
                walk(tabs)
                require(matches.size <= 1)
                if (matches.size == 1) { click(matches.single()); true }
                else { require(tabs.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) { "Не найдена вкладка сохранённой еды." }; false }
            }
            if (opened) {
                waitFor { nodes("title_description").isNotEmpty() }
                return readResultPage().filterValues { it.name.isNotBlank() }
            }
            SystemClock.sleep(200)
        }
        throw IllegalArgumentException("Не найдена вкладка сохранённой еды.")
    }
    private fun inspectSaved(index: Int, row: Row, meal: String): SavedMealDraft {
        clickResult(index, row)
        waitFor { nodes("recipe_header_tv").size == 1 && nodes("saved_meal_contents_recycler_view").size == 1 }
        val ingredients = main {
            val root = root()
            val indicator = nodes("recipes_indicator").single()
            val captions = mutableListOf<String>()
            fun labels(node: AccessibilityNodeInfo) {
                node.text?.toString()?.let { captions += it }
                for (i in 0 until node.childCount) node.getChild(i)?.let(::labels)
            }
            labels(indicator)
            val count = captions.mapNotNull { Regex("^([0-9]+)\\s+(?:items?|шт)$", RegexOption.IGNORE_CASE).find(it.trim())?.groupValues?.get(1)?.toInt() }.single()
            require(count in 1..20)
            val list = nodes("saved_meal_contents_recycler_view").single()
            require(list.childCount == count) { "Не удалось прочитать полный состав сохранённой еды." }
            (0 until list.childCount).map { position ->
                val child = requireNotNull(list.getChild(position))
                val name = child.findAccessibilityNodeInfosByViewId(prefix + "item_name_tv").single().text.toString().trim()
                val portion = child.findAccessibilityNodeInfosByViewId(prefix + "portion_desc_tv").single().text.toString().trim()
                SavedIngredient(name, FoodSearch.grams(org.json.JSONObject(), portion), portion)
            }
        }
        main {
            val tabs = nodes("recipes_indicator").single()
            val found = mutableListOf<AccessibilityNodeInfo>()
            fun walk(node: AccessibilityNodeInfo) {
                if (node.text?.toString()?.lowercase() in setOf("diary", "дневник")) found += node
                for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
            }
            walk(tabs); require(found.size == 1); click(found.single())
        }
        waitFor { nodes("fem_save_btn").size == 1 && nodes("calories_tv").size == 1 }
        return main {
            require(text("recipe_header_tv").single().trim() == row.name.trim())
            require(text("meal_type_tv").single().lowercase() in labels(meal)) { "В сохранённой еде выбран другой приём пищи." }
            require(text("fem_title").single().lowercase() in setOf("add to my diary", "добавить в мой дневник"))
            fun number(id: String) = Regex("[0-9]+(?:[.,][0-9]+)?").find(text(id).single())!!.value.replace(',', '.')
            SavedMealDraft(row.name.trim(), ingredients, number("calories_tv"), number("protein_tv"), number("fat_tv"), number("carbohydrates_tv"))
        }
    }
    override fun prepareSaved(meal: String, item: org.json.JSONObject, onContext: (String) -> Unit): SavedMealDraft = navigating { prepareSavedOnce(meal, item, onContext) }
    private fun prepareSavedOnce(meal: String, item: org.json.JSONObject, onContext: (String) -> Unit): SavedMealDraft {
        existingIdentity = null
        customCommitStarted = false
        require(!item.has("grams") && item.optString("count", "1").toBigDecimal().compareTo(java.math.BigDecimal.ONE) == 0 && item.optString("unit", "servings") == "servings") {
            "Для изменённой порции сохранённого приёма укажи продукты и количество отдельно. Ничего не записано."
        }
        searchProgress?.invoke("Проверяю сохранённую еду FatSecret и её состав.")
        val rows = savedList(meal)
        onContext(searchContext())
        val matching = rows.entries.filter { entry -> FoodSearch.queries(item).any { FoodSearch.matches(it, entry.value.name) } }
        val selected = if (matching.isNotEmpty()) matching.first() else {
            val candidates = rows.entries.take(80)
            val choice = candidateResolver?.invoke(item, candidates.map { it.value }, true)
            requireNotNull(choice) { "Подходящая сохранённая еда не найдена. Уточни её название или состав." }
            require(choice in candidates.indices)
            candidates[choice]
        }
        val duplicates = rows.entries.filter { FoodSearch.words(it.value.name) == FoodSearch.words(selected.value.name) }
        require(duplicates.size <= 4) { "Слишком много разных сохранённых вариантов с одним названием." }
        var expected: SavedMealDraft? = null
        for (duplicate in duplicates) {
            val currentRows = savedList(meal)
            require(currentRows[duplicate.key] == duplicate.value) { "Сохранённая еда изменилась." }
            val draft = inspectSaved(duplicate.key, duplicate.value, meal)
            if (expected != null) require(draft.copy(name = "") == expected.copy(name = "")) { "С одним названием сохранены разные блюда. Уточни состав или КБЖУ нужного варианта." }
            expected = draft
            cancelExistingDraft()
        }
        val currentRows = savedList(meal)
        require(currentRows[selected.key] == selected.value)
        val draft = inspectSaved(selected.key, selected.value, meal)
        require(draft.copy(name = "") == requireNotNull(expected).copy(name = ""))
        searchState = "saved_meal_ready"
        beforeCustomCommit()
        return draft
    }
    override fun saveSaved(draft: SavedMealDraft, meal: String, stamp: String) {
        main {
            require(requireNotNull(FatAccessibilityService.instance).screenStamp == stamp)
            require(text("recipe_header_tv").single().trim() == draft.name)
            require(text("meal_type_tv").single().lowercase() in labels(meal))
            require(text("fem_title").single().lowercase() in setOf("add to my diary", "добавить в мой дневник"))
            customCommitStarted = true
            searchState = "saved_meal_save"
            click(nodes("fem_save_btn").single())
        }
        SystemClock.sleep(500)
        diary(allowDraft = true)
    }
    override fun cancelExistingDraft() { diary(allowDraft = true) }
    private fun setPortion(grams: String, expectedName: String? = null): String {
        val alreadyOpen = main { nodes("fem_dialog_spinner_item_text").isNotEmpty() && nodes("fem_save_btn").isEmpty() }
        if (!alreadyOpen) clickId("portion_name")
        waitFor { nodes("fem_dialog_spinner_item_text").isNotEmpty() }
        main {
            val gramsOption = nodes("fem_dialog_spinner_item_text").filter { it.text?.toString()?.trim() in setOf("g", "г") }
            require(gramsOption.size == 1) { "У этого продукта нет порции в граммах. Выбери другой продукт." }
            click(gramsOption.single())
        }
        waitFor { nodes("fem_save_btn").size == 1 && nodes("portions_count_et").singleOrNull()?.isEditable == true }
        if (expectedName != null) require(main { text("food_name_tv").singleOrNull()?.equals(expectedName, ignoreCase = true) == true }) { "Открыт другой продукт. Сохранение остановлено." }
        setText("portions_count_et", grams)
        SystemClock.sleep(350)
        return preview()
    }
    override fun preview(): String = main { previewOnMain() }
    private fun previewOnMain(): String {
        require(nodes("fem_save_btn").size == 1)
        require(nodes("fem_delete_btn").isEmpty() && nodes("meal_type_tv").isEmpty()) { "Открыта существующая запись. Изменять её агент не будет." }
        val unit = text("fem_dialog_spinner_item_text").singleOrNull()
        require(unit in setOf("g", "г")) { "Единицы порции изменились. Подготовь запись заново." }
        val grams = text("portions_count_et").singleOrNull().orEmpty()
        require(grams.replace(',', '.').toBigDecimalOrNull()?.signum() == 1)
        val name = text("food_name_tv").single()
        val brand = text("brand_name_tv").singleOrNull().orEmpty()
        val identity = existingIdentity
        if (identity != null) require(identity == name || (brand.isNotBlank() && identity == "$name ($brand)")) { "Название или бренд карточки изменились. Ничего не записано." }
        return "${identity ?: name}: $grams $unit\n" +
            "${text("calories_tv").singleOrNull().orEmpty()} ккал; Б ${text("protein_tv").singleOrNull().orEmpty()}, Ж ${text("fat_tv").singleOrNull().orEmpty()}, У ${text("carbohydrates_tv").singleOrNull().orEmpty()}"
    }
    /** Caller must commit the write journal before this single, never-retried click. */
    override fun save(expectedPreview: String, expectedStamp: String, expectedContext: String) {
        main {
            require(requireNotNull(FatAccessibilityService.instance).screenStamp == expectedStamp && previewOnMain() == expectedPreview) {
                "Экран изменился перед сохранением. Запись заблокирована до ручной проверки."
            }
            val save = nodes("fem_save_btn")
            require(save.size == 1)
            click(save.single())
        }
        // Custom foods commit directly; catalog results can require a second toolbar save.
        waitFor { (nodes("multi_add_save_section_holder").size == 1 && text("multi_add_save").singleOrNull() == "1") ||
            (nodes("date_in_navigation_bar").size == 1 && nodes("multi_add_save_section_holder").isEmpty()) }
        if (main { nodes("date_in_navigation_bar").size == 1 && nodes("multi_add_save_section_holder").isEmpty() }) return
        main {
            require(contextOnMain() == expectedContext) { "Дата или приём пищи изменились. Запись остановлена." }
            val checked = nodes("multi_add_item_checked").filter { it.isChecked }
            require(checked.size == 1 && text("multi_add_save").singleOrNull() == "1") { "Выбран не один продукт. Сохранение остановлено." }
            val row = requireNotNull(checked.single().parent)
            require(row.viewIdResourceName == prefix + "multi_add_item_row")
            val name = row.findAccessibilityNodeInfosByViewId(prefix + "title_description").single().text.toString().trim()
            val portion = row.findAccessibilityNodeInfosByViewId(prefix + "sub_title_portion_description").single().text.toString().trim()
            val approved = expectedPreview.substringBefore('\n')
            val approvedName = approved.substringBeforeLast(": ")
            val approvedGrams = approved.substringAfterLast(": ").substringBefore(' ')
            require(name == approvedName && Regex("^" + Regex.escape(approvedGrams) + "\\s*[gг](?:\\s|$)").containsMatchIn(portion)) {
                "Выбранная порция отличается от подтверждённой. Сохранение остановлено."
            }
            val commit = nodes("multi_add_save_section_holder")
            require(commit.size == 1)
            click(commit.single())
        }
        waitFor { nodes("date_in_navigation_bar").size == 1 && nodes("multi_add_save_section_holder").isEmpty() }
    }
    override fun leaveDraft() { diary(allowDraft = true) }
}
