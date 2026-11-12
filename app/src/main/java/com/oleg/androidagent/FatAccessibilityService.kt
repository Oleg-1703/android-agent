package com.oleg.androidagent

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class FatAccessibilityService : AccessibilityService() {
    companion object {
        const val FATSECRET_PACKAGE = "com.fatsecret.android"
        @Volatile var instance: FatAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Local-only bounded inspection. No sensitive screen data is logged or uploaded. */
    fun inspectFatSecret(): String {
        val root = rootInActiveWindow ?: return "No active window"
        if (root.packageName?.toString() != FATSECRET_PACKAGE) return "Refused: app not allowlisted"
        val result = StringBuilder()
        var visited = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 16 || visited++ >= 150 || result.length > 10000) return
            result.append(" ".repeat(depth))
                .append(node.className?.toString()?.take(80))
                .append(" clickable=").append(node.isClickable).append('\n')
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return result.toString()
    }
}
