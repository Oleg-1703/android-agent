package com.oleg.androidagent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WholeMealTest {
    private fun food(name: String, grams: String, basis: String, calories: String, source: String = "user") =
        JSONObject().put("query", name).put("grams", grams).put("source", source).put("nutrition",
            JSONObject().put("basis_grams", basis).put("calories", calories).put("protein", "10").put("fat", "5").put("carbs", "20"))
    private fun plan(vararg items: JSONObject) = JSONObject().put("meal", "lunch").put("items", JSONArray(items.toList()))
    @Test fun consumedTotalsUseEachStatedBasisOnce() {
        val request = plan(food("Sandwich", "200", "100", "209"), food("Salad", "150", "150", "120"))
        val original = request.toString()
        val result = WholeMeal.items(request).single()
        assertEquals("350", result.getString("grams"))
        val nutrition = result.getJSONObject("nutrition")
        assertEquals("350", nutrition.getString("basis_grams"))
        assertEquals("538", nutrition.getString("calories"))
        assertEquals("30", nutrition.getString("protein"))
        assertEquals("15", nutrition.getString("fat"))
        assertEquals("60", nutrition.getString("carbs"))
        assertEquals(original, request.toString())
    }
    @Test fun estimatesRemainEstimatesAndComponentsRemainInTheReceipt() {
        val request = plan(food("Rice", "100", "100", "130"), food("Fish", "75", "100", "160", "estimate"))
        val result = WholeMeal.items(request).single()
        assertEquals("estimate", result.getString("source"))
        assertEquals("250", result.getJSONObject("nutrition").getString("calories"))
        assertTrue(WholeMeal.composition(request).contains("Fish 75 г"))
    }
    @Test fun existingCardsAreNeverCombinedOrGivenEstimatedNutrition() {
        val existing = JSONObject().put("query", "Exact existing card").put("grams", "200").put("source", "existing")
        val result = WholeMeal.items(plan(existing, food("Rice", "100", "100", "130"), food("Fish", "100", "100", "160")))
        assertEquals(2, result.size)
        assertSame(existing, result.first())
        assertFalse(result.first().has("nutrition"))
        assertEquals("290", result.last().getJSONObject("nutrition").getString("calories"))
    }
    @Test fun singleItemKeepsItsExactBasisAndName() {
        val item = food("Label food", "200", "100", "209")
        assertSame(item, WholeMeal.items(plan(item)).single())
    }
    @Test fun combinedPortionMustFitTheSupportedWeight() {
        try { WholeMeal.items(plan(food("A", "3000", "100", "1"), food("B", "3000", "100", "1"))); fail() }
        catch (_: IllegalArgumentException) {}
    }
}
