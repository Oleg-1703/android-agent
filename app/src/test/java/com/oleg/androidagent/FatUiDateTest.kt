package com.oleg.androidagent

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class FatUiDateTest {
    @Test fun acceptsObservedEnglishAndRussianDateLabels() {
        val day = LocalDate.of(2026, 11, 15)
        assertTrue(FatUiDate.matches("Sunday, Nov 15", day, Locale.ENGLISH))
        assertTrue(FatUiDate.matches("воскресенье, 15 нояб.", day, Locale.ENGLISH))
    }
    @Test fun rejectsStaleDayAndExplicitWrongYear() {
        val day = LocalDate.of(2026, 11, 16)
        assertFalse(FatUiDate.matches("Sunday, Nov 15", day))
        assertFalse(FatUiDate.matches("Nov 16, 2025", day))
        assertFalse(FatUiDate.matches("Today", day))
    }
}
