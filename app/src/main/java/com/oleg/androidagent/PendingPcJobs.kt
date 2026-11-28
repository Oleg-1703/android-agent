package com.oleg.androidagent

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

internal class PendingPcJobs(private val store: StateStore, scope: String) {
    private val key = "pending_pc_$scope"
    fun rows(): List<JSONObject> = JSONArray(store.get(key).ifBlank { "[]" }).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    fun contains(id: String) = rows().any { it.getString("id") == id }
    fun add(id: String, phone: Boolean) {
        if (!contains(id)) store.put(key, JSONArray(rows() + JSONObject().put("id", id).put("phone", phone).put("date", LocalDate.now().toString())).toString())
    }
    fun cancelAll() = store.put(key, JSONArray(rows().map { it.put("cancel", true) }).toString())
    fun remove(id: String) = store.put(key, JSONArray(rows().filterNot { it.getString("id") == id }).toString())
}
