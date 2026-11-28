package com.oleg.androidagent

import org.junit.Assert.*
import org.junit.Test

class DiaryVerificationTest {
    private val old = DiaryEntry("Existing", "200 g", "300")
    private val banana = DiaryEntry("Banana", "1 g", "1")
    private val before = DiarySnapshot("2026-11-16", "lunch", listOf(old), true)
    private val preview = "Banana: 1 g\n1 ккал; Б 0, Ж 0, У 0"
    private fun verify(entries: List<DiaryEntry>, complete: Boolean = true) = DiaryVerification.addedExactly(
        before, before.copy(entries = entries, complete = complete), preview)
    @Test fun exactDeltaIsVerified() { assertTrue(verify(listOf(old, banana))) }
    @Test fun duplicatesAreCounted() {
        assertTrue(DiaryVerification.addedExactly(before.copy(entries = listOf(banana)), before.copy(entries = listOf(banana, banana)), preview))
    }
    @Test fun extraOrMissingRowsFail() {
        assertFalse(verify(listOf(old)))
        assertFalse(verify(listOf(banana)))
        assertFalse(verify(listOf(old, banana, banana)))
    }
    @Test fun ChangedExistingRowFails() { assertFalse(verify(listOf(old.copy(calories = "301"), banana))) }
    @Test fun wrongQuantityNameOrCaloriesFail() {
        assertFalse(verify(listOf(old, banana.copy(portion = "2 g"))))
        assertFalse(verify(listOf(old, banana.copy(name = "Other"))))
        assertFalse(verify(listOf(old, banana.copy(calories = "2"))))
    }
    @Test fun incompleteOrDifferentContextFails() {
        assertFalse(verify(listOf(old, banana), false))
        assertFalse(DiaryVerification.addedExactly(before.copy(complete = false), before.copy(entries = listOf(old, banana)), preview))
        assertFalse(DiaryVerification.addedExactly(before, before.copy(meal = "dinner", entries = listOf(old, banana)), preview))
        assertFalse(DiaryVerification.addedExactly(before, before.copy(date = "2026-11-17", entries = listOf(old, banana)), preview))
    }
    @Test fun decimalCommaAndEquivalentGramsAreAccepted() { assertTrue(verify(listOf(old, banana.copy(portion = "1,0 г")))) }
    @Test fun observedCalorieTargetPercentageIsAccepted() {
        assertTrue(DiaryVerification.addedExactly(before, before.copy(entries = listOf(old, banana)), preview.replace("1 ккал", "1 (0%) ккал")))
    }
    @Test fun displayFormatAmbiguityFailsClosed() {
        assertFalse(DiaryVerification.addedExactly(before, before.copy(entries = listOf(old, banana)), "Banana: 1 g"))
        assertFalse(verify(listOf(old, banana.copy(portion = "1 g, serving"))))
    }
}
