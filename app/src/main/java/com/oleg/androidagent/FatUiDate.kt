package com.oleg.androidagent

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Compare the visible meal-search date, including the year when FatSecret displays it. */
object FatUiDate {
    private val formats = listOf("EEEE, MMM d", "EEE, MMM d", "EEEE, d MMM", "EEE, d MMM", "EEEE, d MMMM",
        "MMM d", "d MMM", "d MMMM", "MMM d, yyyy", "d MMM yyyy", "d MMMM yyyy", "EEEE, MMM d, yyyy", "EEEE, d MMM yyyy")
    private fun normalized(value: String) = value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    fun matches(value: String, date: LocalDate, locale: Locale = Locale.getDefault()): Boolean {
        val text = normalized(value)
        if (text.isBlank()) return false
        return setOf(locale, Locale.ENGLISH, Locale.forLanguageTag("ru")).any { language ->
            formats.any { normalized(date.format(DateTimeFormatter.ofPattern(it, language))) == text }
        }
    }
}
