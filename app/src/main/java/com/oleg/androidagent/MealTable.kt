package com.oleg.androidagent

import org.json.JSONObject

/** Telegram-HTML view of the PC's structured per-item table. Pure: no I/O, every dynamic string is escaped. */
object MealTable {
    private const val NAME = 11
    private const val LIMIT = 4000
    /** Prefix of a message string whose body is Telegram HTML; stripped by [unmark] right before sending. */
    private const val MARK = "\u0001html\u0001"
    private val meals = mapOf("breakfast" to "Завтрак", "lunch" to "Обед", "dinner" to "Ужин", "other" to "Перекус")
    const val QUESTION = "Записать так? Нажми ✅ или скажи, что поправить."

    fun mealName(code: String) = meals[code] ?: "Приём пищи"
    fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    /** Plain-text twin of formatter HTML, for the fallback when Telegram rejects the markup. */
    fun plain(html: String) = html.replace(Regex("<[^>]*>"), "").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    fun mark(html: String) = MARK + html
    fun isMarked(text: String) = text.startsWith(MARK)
    fun unmark(text: String) = text.removePrefix(MARK)
    /** Joins reply parts; if one is HTML the plain ones are escaped so the whole message parses. */
    fun join(parts: List<String>): String =
        if (parts.none(::isMarked)) parts.joinToString("\n\n")
        else mark(parts.joinToString("\n\n") { if (isMarked(it)) unmark(it) else escape(it) })

    private fun cut(name: String, star: Boolean): String {
        val tail = if (star) "*" else ""
        return if (name.length + tail.length <= NAME) name + tail else name.take(NAME - tail.length - 1) + "…" + tail
    }
    private fun number(value: Any?, decimals: Int): String = when {
        value == null || value == JSONObject.NULL -> ""
        decimals == 0 -> (value as Number).toLong().toString()
        else -> "%.1f".format(java.util.Locale.ROOT, (value as Number).toDouble())
    }

    /** The monospace block: header, items, rule, total. */
    fun pre(table: JSONObject): String {
        data class Line(val name: String, val grams: String, val kcal: String, val p: String, val f: String, val c: String)
        val lines = mutableListOf<Line>()
        val rows = table.optJSONArray("rows")
        for (i in 0 until (rows?.length() ?: 0)) {
            val row = rows!!.getJSONObject(i)
            val name = cut(row.optString("name"), row.optBoolean("estimate"))
            val grams = if (row.isNull("grams")) "" else row.optString("grams")
            if (row.optBoolean("catalog")) lines += Line(name, grams, "карт.", "", "", "")
            else lines += Line(name, grams, number(row.opt("kcal"), 0), number(row.opt("protein"), 1), number(row.opt("fat"), 1), number(row.opt("carbs"), 1))
        }
        val total = table.optJSONObject("total") ?: JSONObject()
        val sum = Line("Итого", total.optString("grams"), number(total.opt("kcal"), 0), number(total.opt("protein"), 1), number(total.opt("fat"), 1), number(total.opt("carbs"), 1))
        val head = Line("Позиция", "г", "ккал", "Б", "Ж", "У")
        val all = lines + sum + head
        val gw = maxOf(3, all.maxOf { it.grams.length })
        val kw = maxOf(5, all.maxOf { it.kcal.length })
        val pw = maxOf(4, all.maxOf { it.p.length })
        val fw = maxOf(4, all.maxOf { it.f.length })
        val cw = maxOf(4, all.maxOf { it.c.length })
        fun render(l: Line) = (l.name.padEnd(NAME) + " " + l.grams.padStart(gw) + " " + l.kcal.padStart(kw) + " " + l.p.padStart(pw) + " " +
            l.f.padStart(fw) + " " + l.c.padStart(cw)).trimEnd()
        val width = NAME + 1 + gw + 1 + kw + 1 + pw + 1 + fw + 1 + cw
        return (listOf(render(head)) + lines.map(::render) + "─".repeat(width) + render(sum)).joinToString("\n") { escape(it) }
    }

    fun html(table: JSONObject, title: String, notes: String = "", footer: String = "", sources: List<String> = emptyList()): String {
        val rows = table.optJSONArray("rows")
        val flags = (0 until (rows?.length() ?: 0)).map { rows!!.getJSONObject(it) }
        val legend = listOfNotNull(
            "* оценка".takeIf { flags.any { it.optBoolean("estimate") } },
            "карт. — значения возьму из карточки FatSecret, в итог не входят".takeIf { flags.any { it.optBoolean("catalog") } }
        ).joinToString("\n")
        val head = "<b>${escape(title)}</b>\n<pre>${pre(table)}</pre>" + (if (legend.isNotEmpty()) "\n" + escape(legend) else "")
        val tail = (if (sources.isNotEmpty()) "\n" + sources.joinToString("\n") { escape(it) } else "") +
            (if (footer.isNotBlank()) "\n\n" + escape(footer) else "")
        var budget = notes.trim().length
        while (true) {
            val body = notes.trim().take(budget).let { if (it.length < notes.trim().length) "$it…" else it }
            val text = head + (if (body.isNotEmpty()) "\n<i>${escape(body)}</i>" else "") + tail
            if (text.length <= LIMIT || budget == 0) return if (text.length <= LIMIT) text else head.take(LIMIT)
            budget = (budget - maxOf(50, text.length - LIMIT)).coerceAtLeast(0)
        }
    }

    /** Draft reply of a rich PC answer, or null when the PC sent no table. */
    fun draft(result: JSONObject): String? {
        val table = result.optJSONObject("table") ?: return null
        return mark(html(table, "${mealName(result.optString("meal"))} — проверь расчёт", result.optString("message"), QUESTION))
    }

    /** Receipt of a verified write, or null when the plan carried no table. */
    fun receipt(result: JSONObject): String? {
        val table = result.optJSONObject("table") ?: return null
        val sources = result.optJSONArray("sources")?.let { values -> (0 until values.length()).map { values.getString(it) }.filter { it.isNotBlank() } }.orEmpty()
        return mark(html(table, "✅ Записано в FatSecret: ${mealName(result.optString("meal"))}", result.optString("notes"), "", sources))
    }
}
