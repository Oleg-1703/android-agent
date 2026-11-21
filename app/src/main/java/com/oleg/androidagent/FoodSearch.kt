package com.oleg.androidagent

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

object FoodSearch {
    fun words(value: String): Set<String> {
        val normalized = value.lowercase(Locale.ROOT).replace('ё', 'е')
            .replace(Regex("поп[ -]?(?:айс|айз|аис|аиз|айес)|popeyes"), " popeyes ")
            .replace(Regex("чик[ -]?(?:ен|н)?[ -]?ролл?|chicken[ -]?roll"), " chicken roll ")
            .replace(Regex("наг+етс[а-я]*|nuggets?"), " nuggets ")
            .replace(Regex("(?:смолл?|small)"), " small ")
        return Regex("[\\p{L}\\p{N}]+").findAll(normalized).map { it.value }.toSet()
    }
    fun matches(query: String, name: String) = words(query).let { it.isNotEmpty() && words(name).containsAll(it) }
    fun queries(item: JSONObject): List<String> {
        val proposed = item.optJSONArray("search_queries")
        return (listOf(item.getString("query")) + (0 until (proposed?.length() ?: 0)).map { proposed!!.getString(it) })
            .map(String::trim).filter { it.length in 2..150 }.distinct().take(5)
    }
    fun grams(item: JSONObject, detail: String): String {
        item.optString("grams").takeIf { it.isNotBlank() }?.let { return it }
        val mass = Regex("([0-9]+(?:[.,][0-9]+)?)[\\s\\p{Zs}]*[gг]\\b", RegexOption.IGNORE_CASE)
            .findAll(detail).toList()
        require(mass.size == 1) { "В карточке не удалось однозначно прочитать вес порции." }
        val baseGrams = mass.single().groupValues[1].replace(',', '.').toBigDecimal()
        val amount = item.optString("count", "1").toBigDecimal()
        val units = if (item.optString("unit", "servings") == "pieces") {
            val count = Regex("^([0-9]+(?:[.,][0-9]+)?)\\s*[\\p{L}]").find(detail.trim())
            requireNotNull(count) { "В карточке не указано количество штук в порции." }
            count.groupValues[1].replace(',', '.').toBigDecimal()
        } else BigDecimal.ONE
        require(units > BigDecimal.ZERO && amount > BigDecimal.ZERO)
        val grams = baseGrams.multiply(amount).divide(units, 4, RoundingMode.HALF_UP).stripTrailingZeros()
        require(grams > BigDecimal.ZERO && grams <= BigDecimal("5000"))
        return grams.toPlainString()
    }
}

data class SavedIngredient(val name: String, val grams: String, val portion: String = "")
data class SavedMealDraft(val name: String, val entries: List<SavedIngredient>, val calories: String,
    val protein: String, val fat: String, val carbs: String) {
    fun preview() = "$name (сохранённая еда): ${entries.sumOf { it.grams.toBigDecimal() }.stripTrailingZeros().toPlainString()} г\n$calories ккал; Б $protein, Ж $fat, У $carbs"
    fun json() = JSONObject().put("name", name).put("calories", calories).put("protein", protein).put("fat", fat).put("carbs", carbs)
        .put("entries", org.json.JSONArray(entries.map { JSONObject().put("name", it.name).put("grams", it.grams).put("portion", it.portion) }))
    fun verified(before: DiarySnapshot, after: DiarySnapshot): Boolean {
        if (!before.complete || !after.complete || before.date != after.date || before.meal != after.meal) return false
        val remaining = after.entries.toMutableList()
        for (entry in before.entries) if (!remaining.remove(entry)) return false
        if (remaining.size != entries.size) return false
        val total = remaining.sumOf { it.calories.toBigDecimalOrNull() ?: return false }
        if ((total - calories.toBigDecimal()).abs() > BigDecimal.ONE) return false
        for (expected in entries) {
            val index = remaining.indexOfFirst { row ->
                val nativePortion = expected.portion.substringBefore(',').trim().lowercase().replace(Regex("[\\s\\p{Zs}]+"), " ")
                val shownPortion = row.portion.trim().lowercase().replace(Regex("[\\s\\p{Zs}]+"), " ")
                FoodSearch.words(row.name) == FoodSearch.words(expected.name) && ((nativePortion.isNotBlank() && shownPortion == nativePortion) || runCatching {
                    FoodSearch.grams(JSONObject(), row.portion).toBigDecimal().compareTo(expected.grams.toBigDecimal()) == 0
                }.getOrDefault(false))
            }
            if (index < 0) return false
            remaining.removeAt(index)
        }
        return true
    }
    companion object {
        fun parse(value: JSONObject) = SavedMealDraft(value.getString("name"), value.objects("entries").map {
            SavedIngredient(it.getString("name"), it.getString("grams"), it.optString("portion"))
        }, value.getString("calories"), value.getString("protein"), value.getString("fat"), value.getString("carbs"))
    }
}
