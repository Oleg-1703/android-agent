package com.oleg.androidagent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MealNutritionTest {
    private fun panini() = JSONObject("""{"query":"Панини с моцареллой","grams":"200","nutrition":{"basis_grams":"100","calories":"209","protein":"9.5","fat":"11","carbs":"18"}}""")
    @Test fun userLabelIsScaledToActualWeight() {
        assertEquals("По твоим данным на 200 г: ккал 418, Б 19, Ж 22, У 36", MealNutrition.summary(panini()))
        assertNull(MealNutrition.mismatch(panini(), "Панини: 200 г\n418 ккал; Б 19, Ж 22, У 36"))
    }
    @Test fun statedWholePortionIsNotDoubled() {
        val food = panini()
        food.getJSONObject("nutrition").put("basis_grams", "200")
        assertNull(MealNutrition.mismatch(food, "Панини: 200 г\n209 ккал; Б 9,5, Ж 11, У 18"))
        assertNull(MealNutrition.mismatch(food, "Панини: 200 g\n209 (8%) ккал; Б 9.5g, Ж 11g, У 18g"))
    }
    @Test fun missingOrWrongCatalogValuesCannotPassLabelCheck() {
        assertNotNull(MealNutrition.mismatch(panini(), "Панини: 200 г\n209 ккал; Б 19, Ж 22, У 36"))
        assertNotNull(MealNutrition.mismatch(panini(), "Панини: 200 г\n ккал; Б , Ж , У "))
    }
    @Test fun invalidLabelBasisIsRejected() {
        val food = panini()
        food.getJSONObject("nutrition").put("basis_grams", "0")
        try { MealNutrition.validate(food); fail() } catch (_: IllegalArgumentException) {}
    }
}
