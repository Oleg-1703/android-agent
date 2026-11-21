package com.oleg.androidagent

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** A durable claim precedes every phone write; status and receipt retries never repeat it. */
class PhoneMealRunner(private val store: StateStore, private val remote: Remote, private val stage: () -> String = { "" },
    private val execute: (JSONObject) -> JSONObject) {
    interface Remote {
        fun claim(id: String): JSONObject
        fun report(id: String, result: JSONObject)
    }
    private fun key(id: String): String {
        require(id.matches(Regex("[a-f0-9]{64}")))
        return "phone_job_$id"
    }
    private fun cached(id: String): JSONObject? = store.get(key(id)).takeIf { it.isNotBlank() }?.let(::JSONObject)
    private fun uncertain(message: String) = JSONObject().put("status", "uncertain").put("message", message)
    fun status(id: String): JSONObject? {
        val local = cached(id) ?: return null
        if (!local.has("result")) return uncertain("Запрос уже начинал выполняться на телефоне. Проверь FatSecret; автоматического повтора не будет.")
        if (local.optBoolean("needs_report")) {
            try {
                remote.report(id, local.getJSONObject("result"))
                store.put(key(id), local.put("needs_report", false).toString())
            } catch (_: IOException) {
                // The verified local receipt survives a lost acknowledgement for a later /status.
            }
        }
        return local.getJSONObject("result")
    }
    fun run(id: String, prepared: JSONObject): JSONObject = start(id, prepared, false)
    fun cancel(id: String, prepared: JSONObject): JSONObject = start(id, prepared, true)
    private fun start(id: String, prepared: JSONObject, cancel: Boolean): JSONObject {
        status(id)?.let { return it }
        if (!cancel) require(store.get("write_intent").isBlank()) { "Предыдущая запись на телефоне не проверена. Проверь дневник; автоматически её не повторяю." }
        require(prepared.getString("target") == "phone" && prepared.getString("status") == "prepared")
        val plan = JSONObject(prepared.getJSONObject("plan").toString()).put("_execution_id", id)
        Meals.validate(plan)
        store.put(key(id), JSONObject().put("state", "claiming").toString())
        val claim = try { remote.claim(id) } catch (_: IOException) {
            return uncertain("Не удалось подтвердить начало запроса на ПК. Запись на телефоне не запускалась. Повтор автоматически запрещён.")
        }
        if (claim.optString("status") != "claimed") return uncertain(claim.optString("message", "Этот запрос уже выполнялся. Проверь дневник; повтор запрещён."))
        store.put(key(id), JSONObject().put("state", "executing").toString())
        if (!cancel) store.put("custom_receipts", "[]")
        val result = if (cancel) JSONObject().put("status", "failed").put("receipts", JSONArray())
            .put("message", "Запрос отменён до записи. Эта еда не добавлена.")
        else try { execute(plan) } catch (error: Exception) {
            JSONObject().put("status", if (store.get("write_intent").isBlank()) "failed" else "uncertain")
                .put("receipts", JSONArray(store.get("custom_receipts").ifBlank { "[]" }))
                .put("stage", stage().take(500))
                .put("message", error.message?.take(700) ?: "Не удалось выполнить запись на телефоне. Проверь дневник; автоматически не повторяю.")
        }
        if (result.optString("notes").isBlank()) result.put("notes", plan.optString("notes"))
        plan.optJSONObject("table")?.let { result.put("table", it).put("meal", plan.optString("meal")) }
        result.put("sources", JSONArray(plan.objects("items").mapNotNull { it.optString("source_url").takeIf(String::isNotBlank) }))
        store.put(key(id), JSONObject().put("state", "finished").put("result", result).put("needs_report", true).toString())
        return requireNotNull(status(id))
    }
}
