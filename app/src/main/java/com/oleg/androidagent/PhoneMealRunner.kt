package com.oleg.androidagent

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** A durable claim precedes every phone write; status and receipt retries never repeat it. */
class PhoneMealRunner(private val store: StateStore, private val remote: Remote, private val stage: () -> String = { "" },
    private val execute: (JSONObject) -> JSONObject) {
    interface Remote {
        fun claim(id: String): JSONObject
        fun claim(id: String, token: String): JSONObject = claim(id)
        /** The PC's receipt response; `draft_restored: false` (+ `reason`) means the confirmed meal did not return as a draft. */
        fun report(id: String, result: JSONObject): JSONObject
    }
    private fun key(id: String): String {
        require(id.matches(Regex("[a-f0-9]{64}")))
        return "phone_job_$id"
    }
    private fun cached(id: String): JSONObject? = store.get(key(id)).takeIf { it.isNotBlank() }?.let(::JSONObject)
    private fun uncertain(message: String) = JSONObject().put("status", "uncertain").put("message", message)
    fun status(id: String): JSONObject? {
        val local = cached(id) ?: return null
        if (local.optString("state") == "claiming" && local.has("claim_token")) return null
        if (!local.has("result")) return uncertain("Запрос уже начинал выполняться на телефоне. Проверь FatSecret; автоматического повтора не будет.")
        if (local.optBoolean("needs_report")) {
            try {
                val answer = remote.report(id, local.getJSONObject("result"))
                // Old PCs send no draft_restored: then the old behaviour (the draft is there) stays.
                if (answer.has("draft_restored") && !answer.optBoolean("draft_restored", true)) local.put("not_restored", answer.optString("reason", "unknown"))
                store.put(key(id), local.put("needs_report", false).toString())
            } catch (_: IOException) {
                // The verified local receipt survives a lost acknowledgement for a later /status.
            }
        }
        // Transient (never stored): the PC has not acknowledged this receipt yet, so it cannot have restored a draft.
        return local.getJSONObject("result").also {
            if (local.optBoolean("needs_report")) it.put("unreported", true)
            // Transient (never stored in the result): the PC did not restore the draft for this job, so a ✅ would hit another one.
            if (local.has("not_restored")) it.put("not_restored", local.getString("not_restored"))
        }
    }
    /** Only the fixed stage identifier (first token, e.g. custom_nutrition_review) is logged, never the free text of the stage. */
    private fun stageFlag(result: JSONObject): Map<String, Any> =
        result.optString("ui_state").substringBefore(' ').takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,32}")) }?.let { mapOf("last_state" to it) } ?: emptyMap()
    fun run(id: String, prepared: JSONObject): JSONObject = start(id, prepared, false)
    fun cancel(id: String, prepared: JSONObject): JSONObject = start(id, prepared, true)
    private fun start(id: String, prepared: JSONObject, cancel: Boolean): JSONObject {
        status(id)?.let { return it }
        require(prepared.getString("target") == "phone" && prepared.getString("status") == "prepared")
        val plan = JSONObject(prepared.getJSONObject("plan").toString()).put("_execution_id", id)
        Meals.validate(plan)
        val local = cached(id) ?: JSONObject().put("state", "claiming").put("claim_token", java.util.UUID.randomUUID().toString())
        val expired = plan.getString("date") != java.time.LocalDate.now().toString()
        val stopping = cancel || expired || local.optBoolean("cancel")
        if (stopping) local.put("cancel", true)
        store.put(key(id), local.toString())
        val claim = try { remote.claim(id, local.getString("claim_token")) } catch (error: IOException) {
            // 409 = the PC refused the claim (unknown job, other executor): retrying cannot help and would hold the queue.
            val refused = (error as? ApiFailure)?.status == 409
            if (stopping || refused) {
                val message = if (stopping) "Запрос отменён до записи. Пришли еду заново, если нужно."
                    else "ПК не дал начать запись по этому плану. На телефоне ничего не записано. Пришли еду заново."
                val result = JSONObject().put("status", "failed").put("message", message).put("receipts", JSONArray())
                store.put(key(id), local.put("state", "finished").put("result", result).toString())
                return result
            }
            val notify = !local.optBoolean("claim_notified")
            store.put(key(id), local.put("claim_notified", true).toString())
            return JSONObject().put("status", "claiming").put("notify", notify)
                .put("message", "Нет ответа ПК на начало записи. На телефоне ничего не записано. Проверь, что ПК включён; повторно еду не отправляй.")
        }
        if (claim.optString("status") != "claimed") {
            val result = uncertain(claim.optString("message", "Этот запрос уже выполнялся. Проверь дневник; повтор запрещён."))
            store.put(key(id), local.put("state", "finished").put("result", result).toString())
            return result
        }
        store.put(key(id), JSONObject().put("state", "executing").toString())
        val stale = if (stopping) null else StaleWrite.clear(store)
        if (!stopping) { store.put("custom_receipts", "[]"); store.put("custom_unverified", "") }
        val startedAt = System.nanoTime()
        val result = if (stopping) JSONObject().put("status", "failed").put("receipts", JSONArray())
            .put("message", "Запрос отменён до записи. Эта еда не добавлена.")
        else try { execute(plan) } catch (error: Exception) {
            val receipts = JSONArray(store.get("custom_receipts").ifBlank { "[]" })
            // Nothing saved and no Save press started: the journal is empty (the engine clears it), so this is a plain failure.
            val unverified = store.get("custom_unverified").isNotBlank() // an earlier item pressed Save but could not be verified
            val before = store.get("write_intent").isBlank() && receipts.length() == 0 && !unverified
            val fallback = if (store.get("write_intent").isBlank() && receipts.length() > 0) "Часть еды уже записана, остановка до следующей. Проверь дневник; автоматически не повторяю."
                else "Не удалось выполнить запись на телефоне. Проверь дневник; автоматически не повторяю."
            JSONObject().put("status", if (store.get("write_intent").isBlank() && !unverified) "failed" else "uncertain")
                .put("receipts", receipts)
                .put("stage", if (before) (error as? PreCommitFailure)?.stage ?: "before_commit" else "after_commit")
                .put("ui_state", stage().take(500))
                .put("message", error.message?.take(700) ?: fallback)
        }
        if (result.optString("status") == "uncertain" && !result.has("stage")) result.put("stage", "after_commit")
        if (result.optString("notes").isBlank()) result.put("notes", plan.optString("notes"))
        if (stale != null) {
            // The PC receipt schema has no extra keys; a complete receipt shows notes, the others show the message.
            if (result.optString("status") == "complete") result.put("notes", listOf(result.optString("notes"), stale).filter { it.isNotBlank() }.joinToString("\n"))
            else result.put("message", listOf(result.optString("message"), stale).filter { it.isNotBlank() }.joinToString("\n"))
        }
        Telemetry.log("phone_job", Telemetry.requestHash(id), result.optString("status") == "complete", (System.nanoTime() - startedAt) / 1_000_000,
            flags = mapOf("status" to result.optString("status").take(20), "cancel" to cancel) + stageFlag(result))
        plan.optJSONObject("table")?.let { result.put("table", it).put("meal", plan.optString("meal")) }
        result.put("sources", JSONArray(plan.objects("items").mapNotNull { it.optString("source_url").takeIf(String::isNotBlank) }))
        store.put(key(id), JSONObject().put("state", "finished").put("result", result).put("needs_report", true).toString())
        return requireNotNull(status(id))
    }
}
