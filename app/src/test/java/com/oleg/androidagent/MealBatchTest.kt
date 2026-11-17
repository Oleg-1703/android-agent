package com.oleg.androidagent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MealBatchTest {
    @Test fun voiceAndPhotosCanFormOneRequest() {
        val photo = JSONObject().put("photo", org.json.JSONArray())
        val voice = JSONObject().put("voice", JSONObject())
        assertTrue(MealBatch.eligible(photo)); assertTrue(MealBatch.eligible(voice))
        assertEquals(150000L, MealBatch.graceMillis(listOf(photo)))
        assertEquals(8000L, MealBatch.graceMillis(listOf(photo, voice)))
    }
    @Test fun commandsCannotAccidentallyBecomeMeals() {
        for (text in listOf("/stop", "/status", "Отмени черновик", "Помощь", "/meal"))
            assertFalse(text, MealBatch.eligible(JSONObject().put("text", text)))
        assertTrue(MealBatch.eligible(JSONObject().put("text", "/meal@fixture\tзавтрак панини 200 г")))
    }
}
