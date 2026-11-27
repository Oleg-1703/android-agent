package com.oleg.androidagent

import org.json.JSONArray
import org.json.JSONObject

internal class AcceptedPcMessages(private val store: StateStore, scope: String) {
    private val key = "accepted_pc_$scope"
    fun rows(): List<JSONObject> = JSONArray(store.get(key).ifBlank { "[]" }).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun save(rows: List<JSONObject>) = store.put(key, JSONArray(rows).toString())
    fun accept(messages: List<JSONObject>, nextOffset: Long) {
        require(messages.isNotEmpty() && messages.all(MealBatch::eligible))
        val id = messages.first().getLong("message_id")
        val rows = rows()
        val row = rows.firstOrNull { it.getLong("id") == id } ?: JSONObject().put("id", id).put("attempt", 0)
        row.put("messages", JSONArray(messages)).put("offset", nextOffset)
        save(rows.filterNot { it.getLong("id") == id } + row)
    }
    fun clear() = save(emptyList())
    fun remove(id: Long) = save(rows().filterNot { it.getLong("id") == id })
    fun recover(row: JSONObject, serverNow: Long?): JSONObject? {
        val messages = row.objects("messages")
        if (row.optInt("attempt") >= 1 || TelegramFreshness.rejection(messages.maxBy { it.optLong("date") }, serverNow) != null) return null
        row.put("attempt", row.optInt("attempt") + 1)
        save(rows().map { if (it.getLong("id") == row.getLong("id")) row else it })
        return JSONObject(messages.first().toString()).put("agent_batch", JSONArray(messages))
    }
}
