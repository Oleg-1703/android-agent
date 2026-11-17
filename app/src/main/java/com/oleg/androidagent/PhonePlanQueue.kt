package com.oleg.androidagent

import org.json.JSONArray
import org.json.JSONObject

/** Only unclaimed plans wait here. PhoneMealRunner owns deduplication after execution starts. */
class PhonePlanQueue(private val store: StateStore, private val scope: String) {
    private val key = "phone_plans_$scope"
    private fun read() = JSONArray(store.get(key).ifBlank { "[]" })
    private fun save(rows: List<JSONObject>) = store.put(key, JSONArray(rows).toString())
    private fun rows(): List<JSONObject> = read().let { values -> (0 until values.length()).map { values.getJSONObject(it) } }
    fun pending() = rows().isNotEmpty()
    fun contains(id: String) = rows().any { it.getString("id") == id }
    fun add(id: String, prepared: JSONObject) {
        require(id.matches(Regex("[a-f0-9]{64}")))
        require(prepared.getString("status") == "prepared" && prepared.getString("target") == "phone")
        Meals.validate(prepared.getJSONObject("plan"))
        val rows = rows()
        if (rows.any { it.getString("id") == id }) return
        require(rows.size < 10) { "В очереди уже 10 запросов. Сначала разблокируй телефон для записи." }
        save(rows + JSONObject().put("id", id).put("prepared", prepared))
    }
    fun drain(ready: () -> Boolean, run: (String, JSONObject) -> JSONObject): List<JSONObject> {
        val results = mutableListOf<JSONObject>()
        while (ready()) {
            val row = rows().firstOrNull() ?: break
            val id = row.getString("id")
            val result = run(id, row.getJSONObject("prepared"))
            // If this commit fails, the runner's durable receipt prevents a second execution.
            save(rows().filterNot { it.getString("id") == id })
            results.add(result)
            if (result.optString("status") == "uncertain") break
        }
        return results
    }
}
