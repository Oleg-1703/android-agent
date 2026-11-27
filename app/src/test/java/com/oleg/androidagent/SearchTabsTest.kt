package com.oleg.androidagent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTabsTest {
    @Test fun onlyTheFoodTabMatches() {
        listOf("FOOD", " Еда ", "ПРОДУКТЫ").forEach { assertTrue(it, SearchTabs.isFood(it)) }
        listOf("RECIPES", "РЕЦЕПТЫ", "СОХРАНЕННАЯ ЕДА", "COOK BOOK", "", null).forEach { assertFalse("$it", SearchTabs.isFood(it)) }
    }
}
