package com.oleg.androidagent

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/** Sum consumed portions before creating one custom card; existing-card requests remain separate. */
object WholeMeal {
    fun items(plan: JSONObject): List<JSONObject> {
        val items = plan.objects("items")
        val custom = items.filter { it.optString("source") != "existing" }
        if (custom.size < 2) return items
        val grams = custom.sumOf { it.getString("grams").toBigDecimal() }
        require(grams > BigDecimal.ZERO && grams <= BigDecimal("5000")) { "Общий вес приёма пищи должен быть не больше 5000 г." }
        val nutrition = JSONObject().put("basis_grams", grams.stripTrailingZeros().toPlainString())
        for (key in listOf("calories", "protein", "fat", "carbs")) {
            val total = custom.sumOf { item ->
                MealNutrition.validate(item)
                val n = item.getJSONObject("nutrition")
                n.getString(key).toBigDecimal().multiply(item.getString("grams").toBigDecimal())
                    .divide(n.getString("basis_grams").toBigDecimal(), 12, RoundingMode.HALF_UP)
            }
            nutrition.put(key, total.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString())
        }
        val meal = when (plan.getString("meal")) {
            "breakfast" -> "Завтрак"
            "lunch" -> "Обед"
            "dinner" -> "Ужин"
            else -> "Перекус"
        }
        val name = "$meal: " + custom.joinToString(" + ") { it.getString("query") }
        val combined = JSONObject().put("query", name.take(150)).put("grams", grams.stripTrailingZeros().toPlainString())
            .put("source", if (custom.any { it.optString("source") == "estimate" }) "estimate" else "user")
            .put("nutrition", nutrition)
        MealNutrition.validate(combined)
        var inserted = false
        return items.mapNotNull {
            if (it.optString("source") == "existing") it
            else if (!inserted) { inserted = true; combined } else null
        }
    }
    fun composition(plan: JSONObject): String {
        val custom = plan.objects("items").filter { it.optString("source") != "existing" }
        return if (custom.size < 2) "" else "Состав: " + custom.joinToString(", ") { "${it.getString("query")} ${it.getString("grams")} г" }
    }
}
