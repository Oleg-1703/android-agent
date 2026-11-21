package com.oleg.androidagent

import java.util.Locale

object ExistingFood {
    private fun normalized(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    private fun words(value: String) = Regex("[\\p{L}\\p{N}]+").findAll(normalized(value)).map { it.value }.toSet()
    fun exactIndex(query: String, rows: List<FatUi.Row>): Int {
        val exact = rows.indices.filter { normalized(rows[it].name) == normalized(query) }
        val required = words(query)
        val matches = if (exact.isNotEmpty()) exact else rows.indices.filter { words(rows[it].name).containsAll(required) }
        require(matches.size == 1) {
            if (matches.isEmpty()) "Точного названия в FatSecret нет. Ничего не записано; похожий продукт не подставляю."
            else "Найдено несколько точных совпадений. Укажи бренд или другое отличие. Ничего не записано."
        }
        return matches.single()
    }
}
