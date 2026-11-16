package com.oleg.androidagent

interface MealScreen {
    fun screenStamp(): String
    fun searchContext(): String
    fun search(meal: String, query: String): List<FatUi.Row>
    fun choose(index: Int, expected: List<FatUi.Row>, grams: String): String
    fun preview(): String
    fun snapshot(meal: String): DiarySnapshot
    fun save(expectedPreview: String, expectedStamp: String, expectedContext: String)
    fun leaveDraft()
}
