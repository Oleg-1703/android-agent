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
class FatUi(private val context: Context) : MealScreen {
    var searchState: String = "idle"
        private set
    data class Row(val name: String, val detail: String)
    private val prefix = "com.fatsecret.android:id/"
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
        require(context.getSystemService(android.os.PowerManager::class.java).isInteractive) { "Включи экран телефона и открой FatSecret." }
        require(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Разблокируй телефон. Агент не обходит блокировку экрана." }
        val service = requireNotNull(FatAccessibilityService.instance) { "Включи специальные возможности Android Agent в настройках телефона." }
        val root = requireNotNull(service.rootInActiveWindow) { "Открой FatSecret." }
        require(root.packageName?.toString() == FatAccessibilityService.FATSECRET_PACKAGE) { "Открой FatSecret. Другими приложениями агент не управляет." }
        return root
    }
    private fun nodes(id: String): List<AccessibilityNodeInfo> = root().findAccessibilityNodeInfosByViewId(prefix + id)
        .filter { it.isVisibleToUser && it.isEnabled && !it.isPassword }
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
    private fun waitFor(test: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < end) {
            check(!Thread.currentThread().isInterrupted) { "Screen operation cancelled" }
            if (try { main(test) } catch (_: IllegalArgumentException) { false }) return
            SystemClock.sleep(200)
        }
        throw IllegalArgumentException("FatSecret не открыл ожидаемый экран. Запрос остановлен. Проверь дневник перед повторной отправкой; автоматического повтора не было.")
    }
    fun requireReady() = main {
        require(context.getSystemService(android.os.PowerManager::class.java).isInteractive) { "Включи экран телефона и открой FatSecret." }
        require(FatAccessibilityService.instance != null) { "Включи специальные возможности Android Agent в настройках телефона." }
        FatAccessibilityService.instance?.syncKeyboardPackage()
        require(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Разблокируй телефон. Агент не обходит блокировку экрана." }
    }
    fun diary(allowDraft: Boolean = false) {
        requireReady()
        val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(FatAccessibilityService.FATSECRET_PACKAGE)) { "Установи FatSecret." }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor { root().packageName?.toString() == FatAccessibilityService.FATSECRET_PACKAGE }
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
    private fun requireToday() = main {
        require(text("date_in_navigation_bar").singleOrNull()?.lowercase() in setOf("today", "сегодня")) {
            "Выбери «Сегодня» в дневнике FatSecret. Агент не будет угадывать дату."
        }
    }
    private fun scrollDiary(forward: Boolean): Boolean {
        val moved = main {
            val list = nodes("food_journal_new_body").filter { it.isScrollable }
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
        else -> setOf("snacks/other", "перекус/другое", "перекус", "другое")
    }
    private data class DiaryRow(val kind: String, val title: String, val detail: String, val calories: String)
    private data class Frame(val from: Int, val count: Int, val rows: List<DiaryRow>)
    private fun frame(): Frame = main {
        requireTodayOnMain()
        val list = nodes("food_journal_new_body").single()
        val position = requireNotNull(FatAccessibilityService.instance?.lastScroll)
        require(position.rootHash == list.hashCode() && position.window == list.windowId &&
            position.from >= 0 && position.to >= position.from && position.count in 1..300 &&
            position.to < position.count && list.childCount == position.to - position.from + 1) {
            "FatSecret не сообщил полные границы списка. Результат требует ручной проверки."
        }
        fun field(node: AccessibilityNodeInfo, id: String): String = node.findAccessibilityNodeInfosByViewId(prefix + id)
            .singleOrNull()?.text?.toString()?.trim().orEmpty()
        val rows = (0 until list.childCount).map { index ->
            val child = requireNotNull(list.getChild(index))
            val kind = when (child.viewIdResourceName) {
                prefix + "food_journal_add_food_holder" -> "heading"
                prefix + "food_journal_nutrition_holder" -> "summary"
                prefix + "swipe_layout" -> "entry"
                else -> if (child.findAccessibilityNodeInfosByViewId(prefix + "food_journal_show_less").isNotEmpty()) "footer" else "other"
            }
            when (kind) {
                "heading" -> DiaryRow(kind, field(child, "food_journal_heading_row_title"), "", field(child, "food_journal_heading_row_total_calories"))
                "summary" -> DiaryRow(kind, field(child, "food_journal_subtitle_nutrition_info"), "", "")
                "entry" -> DiaryRow(kind, field(child, "food_journal_item_row_title"), field(child, "food_journal_item_row_details"), field(child, "food_journal_item_row_title_energy"))
                else -> DiaryRow(kind, "", "", "")
            }
        }
        Frame(position.from, position.count, rows)
    }
    private fun requireTodayOnMain() {
        require(text("date_in_navigation_bar").singleOrNull()?.lowercase() in setOf("today", "сегодня"))
    }
    private fun scanDiary(): Map<Int, DiaryRow> {
        // Force a fresh adapter-range event, including after expansion changes row positions.
        if (indexedScroll(true)) indexedScroll(false) else if (indexedScroll(false)) indexedScroll(true)
        indexedTop()
        val rows = sortedMapOf<Int, DiaryRow>()
        var total = -1
        for (page in 0 until 60) {
            val current = frame()
            require(total == -1 || total == current.count) { "Дневник изменился во время чтения." }
            total = current.count
            current.rows.forEachIndexed { index, row ->
                val position = current.from + index
                require(rows[position] == null || rows[position] == row) { "Строка дневника изменилась во время чтения." }
                rows[position] = row
            }
            if (rows.size == total && rows.keys.first() == 0 && rows.keys.last() == total - 1) return rows
            if (!indexedScroll(true)) break
        }
        throw IllegalArgumentException("Не удалось прочитать весь дневник. Проверь запись вручную.")
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
    override fun snapshot(meal: String): DiarySnapshot {
        require(meal in Meals.types)
        val date = java.time.LocalDate.now().toString()
        diary(); requireToday()
        fun section(rows: Map<Int, DiaryRow>): List<Pair<Int, DiaryRow>> {
            val heading = rows.entries.single { it.value.kind == "heading" && it.value.title.lowercase() in labels(meal) }.key
            return rows.entries.dropWhile { it.key <= heading }.takeWhile { it.value.kind != "heading" }.map { it.key to it.value }
        }
        var rows = scanDiary()
        var content = section(rows)
        if (content.isEmpty()) {
            val heading = rows.values.single { it.kind == "heading" && it.title.lowercase() in labels(meal) }
            require(heading.calories in setOf("", "0"))
            require(java.time.LocalDate.now().toString() == date)
            return DiarySnapshot(date, meal, emptyList(), true)
        }
        val summary = content.firstOrNull { it.second.kind == "summary" }
            ?: throw IllegalArgumentException("Не найден счётчик продуктов FatSecret.")
        val count = Regex("^([0-9]+)\\s+(?:items?|продукт[а-я]*)\\b", RegexOption.IGNORE_CASE)
            .find(summary.second.title)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw IllegalArgumentException("Неизвестный формат счётчика продуктов FatSecret.")
        if (count > 0 && content.none { it.second.kind == "entry" }) {
            indexedTop()
            var expanded = false
            for (page in 0 until 60) {
                val current = frame()
                if (summary.first in current.from until current.from + current.rows.size) {
                    main {
                        val list = nodes("food_journal_new_body").single()
                        val holder = requireNotNull(list.getChild(summary.first - current.from))
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
            rows = scanDiary()
            content = section(rows)
            require(content.first { it.second.kind == "summary" }.second.title == summary.second.title)
        }
        val entries = content.filter { it.second.kind == "entry" }.map { (_, row) ->
            require(row.title.isNotBlank() && row.detail.isNotBlank() && row.calories.matches(Regex("[0-9]+")))
            DiaryEntry(row.title, row.detail, row.calories)
        }
        require(entries.size == count && content.none { it.second.kind == "other" }) { "Состав приёма пищи прочитан не полностью." }
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
    override fun search(meal: String, query: String): List<Row> {
        require(meal in Meals.types && query.length in 2..150)
        diary(); requireToday(); top()
        val labels = when(meal) {
                "breakfast" -> setOf("breakfast", "завтрак")
                "lunch" -> setOf("lunch", "обед")
                "dinner" -> setOf("dinner", "ужин")
                else -> setOf("snacks/other", "перекус/другое", "перекус", "другое")
            }
        var opened = false
        for (i in 0 until 12) {
            opened = main {
                val target = nodes("food_journal_heading_row_title").filter { it.text?.toString()?.lowercase() in labels }
                require(target.size <= 1) { "Неоднозначный раздел дневника." }
                if (target.isEmpty()) false else { click(target.single()); true }
            }
            if (opened || !scrollDiary(true)) break
        }
        require(opened) { "Нужный приём пищи не найден в дневнике FatSecret." }
        waitFor { nodes("edit_text").size == 1 }
        main {
            require(FatUiDate.matches(text("actionbar_subtitle").singleOrNull().orEmpty(), java.time.LocalDate.now())) {
                "Дата на экране FatSecret не совпадает с сегодняшней или её формат неизвестен. Проверь дату в приложении."
            }
        }
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
                // FatSecret may translate a query. This opens candidates, never picks a food.
                click(exact.singleOrNull() ?: suggestions.first())
                true
            }
        }
        searchState = if (suggested) "suggestion" else "keyboard_search"
        if (!suggested) keyboardSearch(query)
        waitFor { nodes("title_description").isNotEmpty() }
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
    override fun choose(index: Int, expected: List<Row>, grams: String): String {
        require(rows() == expected) { "Результаты поиска изменились. Отправь еду заново." }
        main {
            val candidates = candidateRows()
            require(candidates.map { it.second } == expected && index in candidates.indices) { "Результаты поиска изменились." }
            click(candidates[index].first)
        }
        waitFor { nodes("food_name_tv").size == 1 && nodes("fem_save_btn").size == 1 }
        clickId("portion_name")
        waitFor { nodes("fem_dialog_spinner_item_text").size > 1 }
        main {
            val gramsOption = nodes("fem_dialog_spinner_item_text").filter { it.text?.toString()?.trim() in setOf("g", "г") }
            require(gramsOption.size == 1) { "У этого продукта нет порции в граммах. Выбери другой продукт." }
            click(gramsOption.single())
        }
        waitFor { nodes("fem_save_btn").size == 1 && nodes("portions_count_et").singleOrNull()?.isEditable == true }
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
        return "${text("food_name_tv").single()}: $grams $unit\n" +
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
        // The portion SAVE stages the item; only the toolbar button commits it.
        waitFor { nodes("multi_add_save_section_holder").size == 1 && text("multi_add_save").singleOrNull() == "1" }
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
