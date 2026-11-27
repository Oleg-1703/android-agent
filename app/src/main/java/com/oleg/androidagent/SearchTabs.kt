package com.oleg.androidagent

object SearchTabs {
    fun isFood(label: CharSequence?): Boolean = label?.toString()?.trim()?.lowercase() in setOf("food", "еда", "продукты")
}
