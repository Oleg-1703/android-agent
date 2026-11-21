package com.oleg.androidagent

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class UiMealsTest {
    private val today = LocalDate.of(2026, 11, 16)
    @Test fun russianShortCommandUsesTheDeviceDate() {
        val meal = UiMeals.parse("/meal обед банан=100,5", today)
        assertEquals("2026-11-16", meal.getString("date"))
        assertEquals("lunch", meal.getString("meal"))
        assertEquals("100.5", meal.getJSONArray("items").getJSONObject(0).getString("grams"))
    }
    @Test fun explicitDateIsPreservedForTheEngineToValidate() {
        assertEquals("2026-11-15", UiMeals.parse("/meal 2026-11-15 ужин рис=150", today).getString("date"))
    }
    @Test fun botSuffixWhitespaceAndTodayAliasAreAccepted() {
        val meal = UiMeals.parse("/meal@fixture\tсегодня\tперекус банан=100", today)
        assertEquals("2026-11-16", meal.getString("date"))
        assertEquals("other", meal.getString("meal"))
    }
}
