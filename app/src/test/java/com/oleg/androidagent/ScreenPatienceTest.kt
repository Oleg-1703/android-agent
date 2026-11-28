package com.oleg.androidagent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScreenPatienceTest {
    @Test fun idleScreenStopsAtTheBaseTimeout() {
        val p = ScreenPatience(10_000)
        assertFalse(p.shouldStop(0, "a", false)); assertFalse(p.shouldStop(9_000, "a", false))
        assertTrue(p.shouldStop(10_000, "a", false))
    }
    @Test fun loadingIndicatorExtendsUpToTheHardCap() {
        val p = ScreenPatience(10_000, 30_000)
        assertFalse(p.shouldStop(10_000, "a", true)); assertFalse(p.shouldStop(29_000, "a", true))
        assertTrue(p.shouldStop(30_000, "a", true))
    }
    @Test fun aChangingScreenKeepsWaitingButAFrozenOneDoesNot() {
        val p = ScreenPatience(10_000, 30_000, 3_000)
        assertFalse(p.shouldStop(0, "a", false))
        assertFalse(p.shouldStop(9_000, "b", false))
        assertFalse(p.shouldStop(11_000, "b", false))   // changed 2 s ago
        assertTrue(p.shouldStop(12_500, "b", false))    // frozen for 3.5 s
    }
    @Test fun aWrongScreenThatNeverChangesFailsAtTenSeconds() {
        val p = ScreenPatience(10_000)
        for (t in 0..9_800 step 200) assertFalse(p.shouldStop(t.toLong(), "same", false))
        assertTrue(p.shouldStop(10_000, "same", false))
    }
    @Test fun preCommitReceiptOffersAConfirmAndStatusDoesNot() {
        val brain = PcBrain(JSONObject(), Http.client())
        val failed = JSONObject().put("status", "failed").put("stage", "open_screen").put("receipts", org.json.JSONArray())
            .put("message", "FatSecret не открылся вовремя, ничего не записано.")
        assertTrue(brain.receipt(failed).contains(PcBrain.RETRY_FOOTER))
        assertTrue(brain.lastConfirm)
        val status = PcBrain(JSONObject(), Http.client())
        assertFalse(status.receipt(JSONObject(failed.toString()), offerRetry = false).contains(PcBrain.RETRY_FOOTER))
        assertFalse(status.lastConfirm)
        val uncertain = PcBrain(JSONObject(), Http.client())
        assertFalse(uncertain.receipt(JSONObject().put("status", "uncertain").put("stage", "after_commit").put("message", "m")).contains(PcBrain.RETRY_FOOTER))
        assertFalse(uncertain.lastConfirm)
        val partial = PcBrain(JSONObject(), Http.client())
        partial.receipt(JSONObject(failed.toString()).put("receipts", org.json.JSONArray().put(JSONObject().put("preview", "A"))))
        assertFalse(partial.lastConfirm)
    }
    @Test fun failureWhoseDraftWasNotRestoredHasNoConfirmButOnlyAPlainMessage() {
        val failed = JSONObject().put("status", "failed").put("stage", "open_screen").put("receipts", org.json.JSONArray())
            .put("message", "FatSecret не открылся вовремя, ничего не записано.").put("table", JSONObject()).put("meal", "lunch")
        val busy = PcBrain(JSONObject(), Http.client())
        val text = busy.receipt(JSONObject(failed.toString()).put("not_restored", "busy"))
        assertEquals("Не записалось: FatSecret не открылся вовремя, ничего не записано. " + PcBrain.BUSY_FOOTER, text)
        assertFalse(text.contains("✅"))
        assertFalse(MealTable.isMarked(text))
        assertFalse(busy.lastConfirm)
        val other = PcBrain(JSONObject(), Http.client())
        assertFalse(other.receipt(JSONObject(failed.toString()).put("not_restored", "no_plan")).contains(PcBrain.BUSY_FOOTER))
        assertFalse(other.lastConfirm)
    }
}
