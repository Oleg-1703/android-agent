package com.oleg.androidagent

import org.json.JSONObject
import java.time.LocalDate

interface CustomMealScreen {
    fun snapshot(meal: String): DiarySnapshot
    fun createCustom(meal: String, item: JSONObject, onContext: (String) -> Unit): String
    fun prepareExisting(meal: String, item: JSONObject, onContext: (String) -> Unit): String = throw IllegalArgumentException("Existing food unsupported")
    fun prepareSaved(meal: String, item: JSONObject, onContext: (String) -> Unit): SavedMealDraft = throw IllegalArgumentException("Saved meal unsupported")
    fun saveSaved(draft: SavedMealDraft, meal: String, stamp: String) { throw IllegalArgumentException("Saved meal unsupported") }
    fun cancelExistingDraft() {}
    fun diaryName(): String? = null
    fun finishPreparedCustom(item: JSONObject): String = throw IllegalArgumentException("No prepared custom form")
    fun finishCustomPortion(item: JSONObject): String = throw IllegalArgumentException("No prepared custom portion")
    fun commitStarted(): Boolean = true
    fun cancelCustomDraft() {}
    fun screenStamp(): String
    fun searchContext(): String
    fun save(expectedPreview: String, expectedStamp: String, expectedContext: String)
    /** Up to 60 visible view resource ids of the current screen (ids only, no text), for diagnosing a failed scan or verification. */
    fun fingerprint(): List<String> = emptyList()
}

/** The owner authorizes exact custom foods; a catalog substitute is never used. */
class CustomMealEngine(private val store: StateStore, private val ui: CustomMealScreen,
    private val cleanupPrewrite: Boolean = true,
    private val progress: (String) -> Unit = {},
    private val pause: () -> Unit = { Thread.sleep(700) }) {
    private fun beforeSnapshot(meal: String): DiarySnapshot {
        var failure: Exception? = null
        for (attempt in 0 until 3) {
            if (attempt > 0) pause()
            try { return ui.snapshot(meal) } catch (error: Exception) {
                if (error is InterruptedException) throw error
                failure = error
                if (error is DiaryReadFailure) break
            }
        }
        throw IllegalArgumentException("Не удалось прочитать дневник перед записью.", failure)
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
            if (after != null && if (journal.has("saved_recipe")) SavedMealDraft.parse(journal.getJSONObject("saved_recipe")).verified(before, after)
                else DiaryVerification.addedExactly(before, after, preview, journal.optString("diary_name").takeIf { it.isNotBlank() })) { verified = true; break }
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
        val req = Telemetry.requestHash(plan.optString("_execution_id"))
        val notes = mutableListOf<String>()
        StaleWrite.clear(store)?.let { notes.add(it) }
        plan.objects("items").forEach {
            if (it.optString("source") in setOf("existing", "saved")) require(!it.has("nutrition") && !it.has("barcode"))
            else { require(it.has("nutrition")) { "Для своей еды нужны КБЖУ." }; MealNutrition.validate(it) }
        }
        val items = WholeMeal.items(plan)
        val receipts = org.json.JSONArray()
        val unverified = mutableListOf<String>()
        store.put("custom_receipts", receipts.toString())
        store.put("custom_unverified", "")
        for (item in items) {
            progress("ИИ разобрал запрос. Открываю FatSecret и читаю дневник перед записью.")
            // An unreadable diary does not stop the write (a duplicate is cheap); it only makes verification impossible.
            val before = try { Telemetry.stage(req, "before_snapshot") { beforeSnapshot(plan.getString("meal")) }.takeIf { it.complete } } catch (error: IllegalArgumentException) { null }
                ?: DiarySnapshot(LocalDate.now().toString(), plan.getString("meal"), emptyList(), false).also {
                    val note = "Дневник перед записью прочитан не полностью, поэтому записанное нельзя сверить автоматически."
                    if (note !in notes) notes.add(note)
                }
            store.put("write_intent", JSONObject().put("mode", "custom").put("execution_id", plan.optString("_execution_id")).put("item", item).put("before", before.json()).toString())
            val saved = item.optString("source") == "saved"
            val existing = item.optString("source") in setOf("existing", "saved")
            progress(if (existing) "Ищу указанную существующую еду в FatSecret." else "Дневник прочитан. Вношу свою еду и КБЖУ в FatSecret.")
            val onContext: (String) -> Unit = { context ->
                store.put("write_intent", JSONObject(store.get("write_intent")).put("context", context).toString())
            }
            var savedDraft: SavedMealDraft? = null
            val preview = try {
                Telemetry.stage(req, if (existing) "prepare_existing" else "create_custom") {
                    if (saved) ui.prepareSaved(plan.getString("meal"), item, onContext).also { savedDraft = it }.preview()
                    else if (existing) ui.prepareExisting(plan.getString("meal"), item, onContext)
                    else ui.createCustom(plan.getString("meal"), item, onContext)
                }
            } catch (error: Exception) {
                if (error is InterruptedException) throw error
                if (existing || (cleanupPrewrite && !ui.commitStarted())) {
                    // Nothing was saved and Save was never pressed: the journal must not turn this into an "uncertain" write,
                    // even when the screen is too broken to close the draft (the next request starts from the diary anyway).
                    try {
                        if (existing) ui.cancelExistingDraft() else ui.cancelCustomDraft()
                    } catch (cleanupError: Exception) {
                        error.addSuppressed(cleanupError)
                    }
                    store.put("write_intent", "")
                    val reason = if (error is ScreenTimeout) "FatSecret не открылся вовремя, ничего не записано."
                        else (error.message?.trimEnd()?.takeIf { it.isNotBlank() } ?: "Сбой экрана FatSecret.") + " Ничего не записано."
                    throw PreCommitFailure(reason.take(700), "open_screen", error)
                }
                throw error
            }
            if (!existing) MealNutrition.mismatch(item, preview)?.let { throw IllegalArgumentException(it) }
            val diaryName = if (existing) ui.diaryName() else null
            val intent = JSONObject(store.get("write_intent")).put("preview", preview)
            if (diaryName != null) intent.put("diary_name", diaryName)
            savedDraft?.let { intent.put("saved_recipe", it.json()) }
            store.put("write_intent", intent.toString())
            Telemetry.stage(req, "save") {
                if (saved) ui.saveSaved(requireNotNull(savedDraft), plan.getString("meal"), ui.screenStamp())
                else ui.save(preview, ui.screenStamp(), JSONObject(store.get("write_intent")).getString("context"))
            }
            progress("Сохранение отправлено. Проверяю запись в дневнике; повторно сохранять не буду.")
            var verified = false
            if (before.complete) for (attempt in 0 until 3) {
                if (attempt > 0) pause()
                val after = try { ui.snapshot(plan.getString("meal")) } catch (error: Exception) { if (error is InterruptedException) throw error; null }
                if (after != null && (savedDraft?.verified(before, after) ?: DiaryVerification.addedExactly(before, after, preview, diaryName))) { verified = true; break }
            }
            Telemetry.log("stage", req, verified, flags = mapOf("stage" to "verify", "diary_complete" to before.complete))
            if (!verified) Telemetry.log("verify_failed", req, false, ids = ui.fingerprint())
            if (verified) {
                receipts.put(JSONObject().put("preview", preview).put("source", item.optString("source", "user")))
                store.put("custom_receipts", receipts.toString())
                store.put("last_result", preview)
            } else {
                // Save was pressed exactly once. Unreadable or unmatched diary = uncertain, never a failure and never a lock.
                unverified.add(preview.substringBefore('\n'))
                store.put("custom_unverified", "1") // a later pre-commit failure must not look like "nothing was written"
                store.put("last_result", "Возможно записано (дневник не сверен): " + preview.substringBefore('\n'))
            }
            store.put("write_intent", "")
        }
        if (unverified.isNotEmpty()) return JSONObject().put("status", "uncertain").put("receipts", receipts)
            .put("message", "Возможно записано, проверь дневник FatSecret: " + unverified.joinToString("; ") + ". Дневник не удалось сверить; повторно не записываю. Новые запросы не заблокированы." +
                notes.joinToString("") { "\n$it" })
        val allNotes = (listOf(plan.optString("notes"), WholeMeal.composition(plan)) + notes).filter { it.isNotBlank() }.joinToString("\n")
        return JSONObject().put("status", "complete").put("receipts", receipts).put("notes", allNotes)
    }
}
