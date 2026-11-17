package com.oleg.androidagent

import org.json.JSONObject
import java.time.LocalDate

interface CustomMealScreen {
    fun snapshot(meal: String): DiarySnapshot
    fun createCustom(meal: String, item: JSONObject, onContext: (String) -> Unit): String
    fun prepareExisting(meal: String, item: JSONObject, onContext: (String) -> Unit): String = throw IllegalArgumentException("Existing food unsupported")
    fun cancelExistingDraft() {}
    fun diaryName(): String? = null
    fun finishPreparedCustom(item: JSONObject): String = throw IllegalArgumentException("No prepared custom form")
    fun finishCustomPortion(item: JSONObject): String = throw IllegalArgumentException("No prepared custom portion")
    fun commitStarted(): Boolean = true
    fun cancelCustomDraft() {}
    fun screenStamp(): String
    fun searchContext(): String
    fun save(expectedPreview: String, expectedStamp: String, expectedContext: String)
}

/** The owner authorizes exact custom foods; a catalog substitute is never used. */
class CustomMealEngine(private val store: StateStore, private val ui: CustomMealScreen,
    private val cleanupPrewrite: Boolean = true,
    private val progress: (String) -> Unit = {},
    private val pause: () -> Unit = { Thread.sleep(700) }) {
    private fun beforeSnapshot(meal: String): DiarySnapshot {
        var failure: IllegalArgumentException? = null
        for (attempt in 0 until 3) {
            if (attempt > 0) pause()
            try { return ui.snapshot(meal) } catch (error: IllegalArgumentException) {
                failure = error
                if (error is DiaryReadFailure) break
            }
        }
        throw IllegalArgumentException("Не удалось прочитать дневник перед записью. Эта еда не добавлена.", failure)
    }
    fun reconcile(): JSONObject {
        val journal = JSONObject(store.get("write_intent"))
        require(journal.getString("mode") == "custom" && journal.has("preview")) { "Нет результата сохранения для сверки." }
        val before = DiarySnapshot.parse(journal.getJSONObject("before"))
        require(before.complete && before.date == LocalDate.now().toString())
        val preview = journal.getString("preview")
        var verified = false
        for (attempt in 0 until 3) {
            if (attempt > 0) pause()
            val after = try { ui.snapshot(before.meal) } catch (_: IllegalArgumentException) { null }
            if (after != null && DiaryVerification.addedExactly(before, after, preview, journal.optString("diary_name").takeIf { it.isNotBlank() })) { verified = true; break }
        }
        require(verified) { "Не удалось подтвердить ровно одну запись. Повтор сохранения запрещён." }
        val item = journal.getJSONObject("item")
        store.put("write_intent", "")
        val receipts = org.json.JSONArray().put(JSONObject().put("preview", preview).put("source", item.optString("source", "user")))
        store.put("custom_receipts", receipts.toString())
        return JSONObject().put("status", "complete").put("receipts", receipts)
    }
    fun finishInspectedForm(portionOnly: Boolean = false): JSONObject {
        val journal = JSONObject(store.get("write_intent"))
        require(journal.getString("mode") == "custom" && !journal.has("preview")) { "Повтор сохранения запрещён." }
        val before = DiarySnapshot.parse(journal.getJSONObject("before"))
        require(before.complete && before.date == LocalDate.now().toString())
        val item = journal.getJSONObject("item")
        val preview = if (portionOnly) ui.finishCustomPortion(item) else ui.finishPreparedCustom(item)
        MealNutrition.mismatch(item, preview)?.let { throw IllegalArgumentException(it) }
        store.put("write_intent", journal.put("preview", preview).toString())
        ui.save(preview, ui.screenStamp(), journal.getString("context"))
        var verified = false
        for (attempt in 0 until 3) {
            if (attempt > 0) pause()
            val after = try { ui.snapshot(before.meal) } catch (_: IllegalArgumentException) { null }
            if (after != null && DiaryVerification.addedExactly(before, after, preview)) { verified = true; break }
        }
        require(verified) { "Запись выполнена один раз. Проверь дневник; автоматического повтора нет." }
        store.put("write_intent", "")
        val receipts = org.json.JSONArray().put(JSONObject().put("preview", preview).put("source", item.optString("source", "user")))
        store.put("custom_receipts", receipts.toString())
        return JSONObject().put("status", "complete").put("receipts", receipts)
    }
    fun log(plan: JSONObject): JSONObject {
        Meals.validate(plan)
        require(plan.getString("date") == LocalDate.now().toString()) { "Пока поддерживается только сегодняшний дневник." }
        require(store.get("write_intent").isBlank()) { "Предыдущая запись не проверена. Повторное сохранение заблокировано." }
        val items = plan.objects("items")
        items.forEach {
            if (it.optString("source") == "existing") require(!it.has("nutrition") && !it.has("barcode"))
            else { require(it.has("nutrition")) { "Для своей еды нужны КБЖУ." }; MealNutrition.validate(it) }
        }
        val receipts = org.json.JSONArray()
        store.put("custom_receipts", receipts.toString())
        for (item in items) {
            progress("ИИ разобрал запрос. Открываю FatSecret и читаю дневник перед записью.")
            val before = beforeSnapshot(plan.getString("meal"))
            require(before.complete) { "Не удалось полностью прочитать дневник перед записью. Ничего нового не сохранено." }
            store.put("write_intent", JSONObject().put("mode", "custom").put("execution_id", plan.optString("_execution_id")).put("item", item).put("before", before.json()).toString())
            val existing = item.optString("source") == "existing"
            progress(if (existing) "Ищу указанную существующую еду в FatSecret." else "Дневник прочитан. Вношу свою еду и КБЖУ в FatSecret.")
            val onContext: (String) -> Unit = { context ->
                store.put("write_intent", JSONObject(store.get("write_intent")).put("context", context).toString())
            }
            val preview = try {
                if (existing) ui.prepareExisting(plan.getString("meal"), item, onContext)
                else ui.createCustom(plan.getString("meal"), item, onContext)
            } catch (error: Exception) {
                if (existing || (cleanupPrewrite && !ui.commitStarted())) {
                    try {
                        if (existing) ui.cancelExistingDraft() else ui.cancelCustomDraft()
                    } catch (cleanupError: Exception) {
                        error.addSuppressed(cleanupError)
                        throw error
                    }
                    store.put("write_intent", "")
                }
                throw error
            }
            if (!existing) MealNutrition.mismatch(item, preview)?.let { throw IllegalArgumentException(it) }
            val diaryName = if (existing) ui.diaryName() else null
            val intent = JSONObject(store.get("write_intent")).put("preview", preview)
            if (diaryName != null) intent.put("diary_name", diaryName)
            store.put("write_intent", intent.toString())
            ui.save(preview, ui.screenStamp(), JSONObject(store.get("write_intent")).getString("context"))
            progress("Сохранение отправлено. Проверяю запись в дневнике; повторно сохранять не буду.")
            var verified = false
            for (attempt in 0 until 3) {
                if (attempt > 0) pause()
                val after = try { ui.snapshot(plan.getString("meal")) } catch (_: IllegalArgumentException) { null }
                if (after != null && DiaryVerification.addedExactly(before, after, preview, diaryName)) { verified = true; break }
            }
            require(verified) { "Сохранение выполнено один раз, но дневник не удалось сверить. Проверь FatSecret; автоматически не повторяю." }
            receipts.put(JSONObject().put("preview", preview).put("source", item.optString("source", "user")))
            store.put("custom_receipts", receipts.toString())
            store.put("last_result", preview)
            store.put("write_intent", "")
        }
        return JSONObject().put("status", "complete").put("receipts", receipts).put("notes", plan.optString("notes"))
    }
}
