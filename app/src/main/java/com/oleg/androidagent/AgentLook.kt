package com.oleg.androidagent

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Observation data only. No model action is ever executed. */
internal object AgentLook {
    const val DESCRIPTION = "посмотреть экран FatSecret и назвать следующий шаг, без нажатий"
    const val USAGE = "Например: /look Как добавить еду на ужин?"
    fun task(text: String): String? {
        val parts = text.trim().split(Regex("\\s+"), limit = 2)
        if (parts[0].substringBefore('@').lowercase(Locale.ROOT) != "/look") return null
        return parts.getOrNull(1).orEmpty().trim()
    }

    data class Node(val cls: String, val text: String, val desc: String, val id: String,
        val flags: List<String>, val bounds: List<Int>, val password: Boolean = false) {
        fun json(index: Int) = JSONObject().put("index", index).put("cls", cls.substringAfterLast('.').take(80))
            .put("text", if (password) "" else text.take(1000)).put("desc", if (password) "" else desc.take(1000))
            .put("id", id.substringAfterLast('/').take(120)).put("flags", JSONArray(flags)).put("bounds", JSONArray(bounds))
    }

    fun screen(width: Int, height: Int, nodes: Sequence<Node>): JSONObject {
        val result = JSONArray()
        var chars = 0
        var truncated = false
        for (node in nodes) {
            val json = node.json(result.length())
            val length = json.toString().length
            if (result.length() >= 300 || chars + length > 39000) { truncated = true; break }
            result.put(json)
            chars += length
            if (!node.password && (node.text.length > 1000 || node.desc.length > 1000)) truncated = true
        }
        return JSONObject().put("package", FatAccessibilityService.FATSECRET_PACKAGE)
            .put("size", JSONArray(listOf(width, height))).put("nodes", result).put("truncated", truncated)
    }

    fun reply(result: JSONObject, totalMs: Long): String {
        val note = if (result.optBoolean("truncated")) " (экран сокращён)" else ""
        return "Вижу ${result.getInt("nodes")} элементов$note. Следующий шаг: ${result.getString("step")}.\n" +
            result.getString("reason").replace(Regex("\\s+"), " ").take(200) + "\n" +
            String.format(Locale.ROOT, "Всего %.1f с, модель %.1f с. Без нажатий.", totalMs / 1000.0, result.getLong("model_ms") / 1000.0)
    }
}
