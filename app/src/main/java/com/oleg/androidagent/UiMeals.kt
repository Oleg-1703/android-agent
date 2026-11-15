package com.oleg.androidagent

import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

object UiMeals {
    private val aliases = mapOf("breakfast" to "breakfast", "завтрак" to "breakfast", "lunch" to "lunch", "обед" to "lunch",
        "dinner" to "dinner", "ужин" to "dinner", "other" to "other", "перекус" to "other")

    fun parse(text: String, today: LocalDate = LocalDate.now()): JSONObject {
        val command = text.trim().split(Regex("\\s+"), limit = 2)
        require(command.size == 2 && command[0].substringBefore('@').lowercase(Locale.ROOT) == "/meal") { "Пример: /meal обед банан=100" }
        val first = command[1].split(Regex("\\s+"), limit = 2)
        require(first.size == 2) { "Пример: /meal обед банан=100" }
        val kind = aliases[first[0].lowercase(Locale.ROOT)]
        if (kind != null) return Meals.parse("/meal $today $kind ${first[1]}")
        val date = if (first[0].lowercase(Locale.ROOT) in setOf("сегодня", "today")) today.toString() else first[0]
        val remaining = first[1].split(Regex("\\s+"), limit = 2)
        require(remaining.size == 2) { "Пример: /meal сегодня обед банан=100" }
        val meal = aliases[remaining[0].lowercase(Locale.ROOT)] ?: remaining[0]
        return Meals.parse("/meal $date $meal ${remaining[1]}")
    }
}
