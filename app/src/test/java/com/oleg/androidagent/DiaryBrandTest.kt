package com.oleg.androidagent

import org.junit.Assert.*
import org.junit.Test

class DiaryBrandTest {
    @Test fun onlyTheVerifiedScreenNameCanOmitTheBrandInDiary() {
        val before = DiarySnapshot("2026-11-17", "dinner", emptyList(), true)
        val after = before.copy(entries = listOf(DiaryEntry("Chicken roll", "200 g", "280")))
        val preview = "Chicken roll (SMALL): 200 g\n280 ккал; Б 18g, Ж 8g, У 34g"
        assertFalse(DiaryVerification.addedExactly(before, after, preview))
        assertTrue(DiaryVerification.addedExactly(before, after, preview, "Chicken roll"))
        assertFalse(DiaryVerification.addedExactly(before, after, preview, "Other roll"))
        assertFalse(DiaryVerification.addedExactly(before, after.copy(entries = listOf(DiaryEntry("Chicken roll (LARGE)", "200 g", "280"))), preview, "Chicken roll"))
    }
}
