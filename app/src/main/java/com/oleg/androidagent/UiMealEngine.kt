package com.oleg.androidagent

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

/** UI drafts and API drafts are deliberately separate; both share the uncertain-write lock. */
class UiMealEngine(private val store: StateStore, private val ui: MealScreen,
    private val awaitReadback: () -> Unit = { Thread.sleep(500) }) {
    private fun draft(): JSONObject {
        val raw = store.get("ui_pending")
        require(raw.isNotBlank()) { "Нет подготовленной еды. Отправь /meal." }
        val p = JSONObject(raw)
        require(System.currentTimeMillis() - p.getLong("created") in 0..900_000) { "Черновик истёк. Отправь еду заново." }
        require(p.getString("date") == LocalDate.now().toString()) { "Дата изменилась. Подготовь еду заново." }
        return p
    }
    fun prepare(meal: JSONObject): String {
        Meals.validate(meal)
        require(store.get("write_intent").isBlank()) { "Есть непроверенная запись. Проверь дневник и сними блокировку в приложении." }
        require(meal.getString("date") == LocalDate.now().toString()) { "Управление экраном пока поддерживает только сегодня." }
        require(meal.getJSONArray("items").length() == 1) { "Сейчас добавляй по одному продукту: так проще проверить каждую порцию." }
        if (store.get("ui_pending").isNotBlank()) cancel()
        store.put("ui_pending", "")
        val before = try { ui.snapshot(meal.getString("meal")) } catch (_: IllegalArgumentException) {
            DiarySnapshot(meal.getString("date"), meal.getString("meal"), emptyList(), false)
        }
        val item = meal.getJSONArray("items").getJSONObject(0)
        val rows = ui.search(meal.getString("meal"), item.getString("query"))
        meal.put("choices", JSONArray(rows.map { JSONObject().put("name", it.name).put("detail", it.detail) }))
            .put("created", System.currentTimeMillis()).put("screen", ui.screenStamp()).put("context", ui.searchContext()).put("before", before.json())
        store.put("ui_pending", meal.toString())
        return "Поиск «${item.getString("query")}», ${item.getString("grams")} г. Выбери точный продукт из FatSecret:\n" + rows.mapIndexed { i, row -> "${i + 1}. ${row.name} - ${row.detail}" }.joinToString("\n") +
            "\n/pick 1 НОМЕР, например /pick 1 2. Ничего не сохранено. /cancel - отмена."
    }
    fun pick(item: Int, choice: Int): String {
        require(item == 1)
        val p = draft()
        require(ui.screenStamp() == p.getString("screen")) { "Экран менялся после поиска. Подготовь еду заново." }
        val rows = p.objects("choices").map { FatUi.Row(it.getString("name"), it.getString("detail")) }
        require(choice in 1..rows.size) { "Выбери номер из списка." }
        val preview = ui.choose(choice - 1, rows, p.getJSONArray("items").getJSONObject(0).getString("grams"))
        val nonce = UUID.randomUUID().toString().take(8)
        p.put("preview", preview).put("nonce", nonce).put("screen", ui.screenStamp())
        store.put("ui_pending", p.toString())
        return "Проверь запись за сегодня / ${p.getString("meal")}:\n$preview\nСохранить: /confirm $nonce\nОтмена: /cancel. Запись пока не сохранена."
    }
    fun confirm(nonce: String): String {
        require(store.get("write_intent").isBlank()) { "Предыдущая запись ещё не проверена. Автоматического повтора не будет." }
        val p = draft()
        require(p.has("nonce") && p.getString("nonce") == nonce) { "Неверный или устаревший код подтверждения." }
        require(ui.screenStamp() == p.getString("screen")) { "Экран менялся после выбора. Подготовь еду заново." }
        require(ui.preview() == p.getString("preview")) { "Экран или порция изменились. Подготовь еду заново." }
        store.put("write_intent", JSONObject().put("mode", "ui").put("draft", p).toString())
        store.put("ui_pending", "")
        ui.save(p.getString("preview"), p.getString("screen"), p.getString("context"))
        val before = DiarySnapshot.parse(p.getJSONObject("before"))
        var verified = false
        if (before.complete) for (attempt in 0 until 3) {
            if (attempt > 0) try { awaitReadback() } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalArgumentException("Проверка прервана. Проверь дневник вручную; сохранение не повторялось.", e)
            }
            val after = try { ui.snapshot(p.getString("meal")) } catch (_: IllegalArgumentException) { null }
            if (after != null && DiaryVerification.addedExactly(before, after, p.getString("preview"))) { verified = true; break }
        }
        if (verified) {
            store.put("last_result", "Запись сохранена и проверена по полному составу приёма пищи FatSecret.")
            store.put("write_intent", "")
            return "Сохранено и проверено в FatSecret:\n${p.getString("preview")}\nМожно отправлять следующую еду."
        }
        store.put("last_result", "Нажата кнопка сохранения FatSecret. Результат требует проверки в дневнике.")
        return "Нажал «Сохранить» один раз. Проверь запись в дневнике FatSecret. До проверки следующая запись заблокирована; снять блокировку можно в Android Agent. Автоматического повтора нет."
    }
    fun cancel(): String {
        val raw = store.get("ui_pending")
        store.put("ui_pending", "")
        val expectedScreen = if (raw.isBlank()) "" else JSONObject(raw).optString("screen")
        val currentScreen = try { ui.screenStamp() } catch (_: IllegalArgumentException) { "" }
        if (expectedScreen.isNotBlank() && expectedScreen == currentScreen) ui.leaveDraft()
        return "Черновик агента отменён. Существующие записи сохранены."
    }
}
