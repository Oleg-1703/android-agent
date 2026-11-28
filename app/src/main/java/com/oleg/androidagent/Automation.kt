package com.oleg.androidagent

import android.app.KeyguardManager
import android.os.Bundle
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo

/** Local, deterministic primitives only. No arbitrary model/Telegram action execution. */
class Automation(private val service: FatAccessibilityService) {
    data class Action(val kind: Kind, val viewId: String, val text: String = "")
    enum class Kind { CLICK, SET_TEXT, SCROLL_FORWARD, SCROLL_BACKWARD }
    fun execute(actions: List<Action>, reviewedLocally: Boolean): List<Boolean> {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Run Accessibility on main thread" }
        require(reviewedLocally) { "Explicit local action review required" }
        require(actions.size in 1..12) { "Action budget exceeded" }
        return actions.map { action ->
            check(!service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Unlock phone first" }
            val root = service.rootInActiveWindow ?: error("No active window")
            check(root.packageName?.toString() == FatAccessibilityService.FATSECRET_PACKAGE) { "App not allowlisted" }
            require(action.viewId.startsWith(FatAccessibilityService.FATSECRET_PACKAGE + ":id/")) { "Use an allowlisted resource ID" }
            val nodes = root.findAccessibilityNodeInfosByViewId(action.viewId).filter { it.isVisibleToUser && it.isEnabled }
            check(nodes.size == 1) { "Missing or ambiguous target; stop and inspect UI" }
            val node = nodes.single()
            check(!node.isPassword) { "Password fields are excluded" }
            val ok = when(action.kind) {
                Kind.CLICK -> node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Kind.SET_TEXT -> {
                    require(action.text.length <= 500)
                    node.isEditable && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,action.text) })
                }
                Kind.SCROLL_FORWARD -> node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                Kind.SCROLL_BACKWARD -> node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            }
            check(ok) { "Action rejected; stopped without retry" }
            ok
        }
    }
}
