package com.oleg.androidagent

import org.json.JSONArray
import org.json.JSONObject

data class DiaryEntry(val name: String, val portion: String, val calories: String)

data class DiarySnapshot(val date: String, val meal: String, val entries: List<DiaryEntry>, val complete: Boolean) {
    fun json(): JSONObject = JSONObject().put("date", date).put("meal", meal).put("complete", complete)
        .put("entries", JSONArray(entries.map { JSONObject().put("name", it.name).put("portion", it.portion).put("calories", it.calories) }))

    companion object {
        fun parse(value: JSONObject) = DiarySnapshot(value.getString("date"), value.getString("meal"),
            value.objects("entries").map { DiaryEntry(it.getString("name"), it.getString("portion"), it.getString("calories")) },
            value.getBoolean("complete"))
    }
}

/** A semantic multiset delta, never an acknowledgement of an uncertain click. */
object DiaryVerification {
    fun addedExactly(before: DiarySnapshot, after: DiarySnapshot, preview: String): Boolean {
        if (!before.complete || !after.complete || before.date != after.date || before.meal != after.meal) return false
        val remaining = after.entries.toMutableList()
        for (entry in before.entries) if (!remaining.remove(entry)) return false
        if (remaining.size != 1) return false
        val approved = Regex("^(.+): ([0-9]+(?:[.,][0-9]+)?) [gг]\\n([0-9]+)(?: \\([0-9]+%\\))? ккал;").find(preview) ?: return false
        val row = remaining.single()
        val portion = Regex("^([0-9]+(?:[.,][0-9]+)?)\\s*[gг]$").matchEntire(row.portion) ?: return false
        return row.name == approved.groupValues[1] && row.calories == approved.groupValues[3] &&
            portion.groupValues[1].replace(',', '.').toBigDecimal().compareTo(approved.groupValues[2].replace(',', '.').toBigDecimal()) == 0
    }
}
