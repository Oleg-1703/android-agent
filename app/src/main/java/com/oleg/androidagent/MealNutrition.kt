package com.oleg.androidagent

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/** User label values must not be silently replaced with a different catalog product. */
object MealNutrition {
    private val fields = listOf("calories", "protein", "fat", "carbs")
    private val labels = listOf("ккал", "Б", "Ж", "У")
    fun validate(item: JSONObject) {
        if (!item.has("nutrition")) return
        val n = item.getJSONObject("nutrition")
        for (key in fields + "basis_grams") {
            val value = n.optString(key).toBigDecimalOrNull()
            require(value != null && value >= BigDecimal.ZERO && value <= BigDecimal("10000")) { "Некорректные КБЖУ. Пришли значения с этикетки заново." }
        }
        require(n.getString("basis_grams").toBigDecimal() > BigDecimal.ZERO) { "Основа КБЖУ должна быть больше 0 г." }
    }
    fun expected(item: JSONObject): List<BigDecimal>? {
        validate(item)
        val n = item.optJSONObject("nutrition") ?: return null
        val factor = item.getString("grams").toBigDecimal().divide(n.getString("basis_grams").toBigDecimal(), 8, RoundingMode.HALF_UP)
        return fields.map { n.getString(it).toBigDecimal() * factor }
    }
    fun summary(item: JSONObject): String {
        val values = expected(item) ?: return ""
        return "По твоим данным на ${item.getString("grams")} г: " + values.mapIndexed { i, v ->
            "${labels[i]} ${v.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()}"
        }.joinToString(", ")
    }
    fun mismatch(item: JSONObject, preview: String): String? {
        val expected = expected(item) ?: return null
        val actual = Regex("\\n([0-9]+(?:[.,][0-9]+)?)(?: \\([0-9]+%\\))? ккал; Б ([0-9]+(?:[.,][0-9]+)?)\\s*[gг]?, Ж ([0-9]+(?:[.,][0-9]+)?)\\s*[gг]?, У ([0-9]+(?:[.,][0-9]+)?)\\s*[gг]?$")
            .find(preview)?.groupValues?.drop(1)?.map { it.replace(',', '.').toBigDecimal() }
            ?: return "Не удалось прочитать КБЖУ выбранного продукта. Ничего не сохранено."
        if (actual.indices.all { (actual[it] - expected[it]).abs() <= if (it == 0) BigDecimal.ONE else BigDecimal("0.15") }) return null
        return summary(item) + "\nКБЖУ на экране FatSecret отличаются от твоих. Порция не сохранена. Проверь карточку; автоматического повтора нет."
    }
}
