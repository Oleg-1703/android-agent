package com.oleg.androidagent

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class FatAccessibilityService : AccessibilityService() {
    private val incarnation = java.util.UUID.randomUUID().toString()
    @Volatile private var windowEpoch = 0L
    val screenStamp: String get() = "$incarnation:$windowEpoch"
    data class ScrollState(val sourceId: String, val from: Int, val to: Int, val count: Int, val y: Int, val maxY: Int,
        val deltaY: Int, val sequence: Long, val rootHash: Int, val window: Int)
    @Volatile var lastScroll: ScrollState? = null
        private set
    @Volatile var resultScroll: ScrollState? = null
        private set
    companion object {
        const val FATSECRET_PACKAGE = "com.fatsecret.android"
        @Volatile var instance: FatAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        syncKeyboardPackage()
    }

    fun syncKeyboardPackage() {
        val selected = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
        val keyboard = android.content.ComponentName.unflattenFromString(selected.orEmpty())?.packageName.orEmpty()
        val packages = listOf(FATSECRET_PACKAGE) + listOf(keyboard).filter { it.isNotBlank() }
        val info = serviceInfo ?: return
        if (info.packageNames?.toList() != packages) {
            info.packageNames = packages.toTypedArray()
            serviceInfo = info
        }
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.packageName?.toString() == FATSECRET_PACKAGE) windowEpoch++
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && event.packageName?.toString() == FATSECRET_PACKAGE) {
            val source = event.source
            if (source?.viewIdResourceName == "$FATSECRET_PACKAGE:id/food_journal_new_body") {
                lastScroll = ScrollState(source.viewIdResourceName, event.fromIndex, event.toIndex, event.itemCount,
                    event.scrollY, event.maxScrollY, event.scrollDeltaY, (lastScroll?.sequence ?: 0) + 1, source.hashCode(), event.windowId)
            }
            if (source?.viewIdResourceName == "android:id/list") {
                resultScroll = ScrollState(source.viewIdResourceName, event.fromIndex, event.toIndex, event.itemCount,
                    event.scrollY, event.maxScrollY, event.scrollDeltaY, (resultScroll?.sequence ?: 0) + 1, source.hashCode(), event.windowId)
            }
        }
    }
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
