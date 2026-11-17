package com.oleg.androidagent

/** Detailed view hides the count; its expanded footer closes the fully indexed section. */
object DiarySectionProof {
    fun count(title: String): Int? {
        if (title.isBlank()) return null
        val counter = Regex("^([0-9]+)[\\s\\p{Zs}]+(?:items?|продукт[а-я]*)\\b", RegexOption.IGNORE_CASE).find(title)
        if (counter != null) return counter.groupValues[1].toIntOrNull()
            ?: throw DiaryReadFailure("Неизвестный формат счётчика продуктов FatSecret.")
        // The same nutrition-info field can contain a labelled nutrient summary instead of a count.
        if (Regex("^\\p{L}[^:]*:").containsMatchIn(title)) return null
        throw DiaryReadFailure("Неизвестный формат счётчика продуктов FatSecret.")
    }
    fun complete(count: Int?, entries: Int, expandedFooter: Boolean, unknownRows: Boolean): Boolean {
        if (unknownRows || entries < 0) return false
        return if (count != null) count >= 0 && entries == count else expandedFooter && entries > 0
    }
}
