package com.oleg.androidagent

import org.junit.Assert.*
import org.junit.Test

class AccessibilityGuardTest {
    private class Store : StateStore {
        val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key].orEmpty()
        override fun put(key: String, value: String) { values[key] = value }
    }
    private val pkg = "com.oleg.androidagent"
    private val cls = "com.oleg.androidagent.FatAccessibilityService"

    @Test fun addingOurServiceKeepsOtherServicesAndOrder() {
        val other = "com.other/com.other.Svc:com.third/.Third"
        assertEquals("$other:$pkg/$cls", AccessibilityList.with(other, pkg, cls))
        assertEquals("$pkg/$cls", AccessibilityList.with(null, pkg, cls))
        assertEquals("$pkg/$cls", AccessibilityList.with("", pkg, cls))
    }
    @Test fun shortAndFullComponentNamesAreTheSameServiceAndNeverDuplicated() {
        val short = "com.other/.Svc:$pkg/.FatAccessibilityService"
        assertTrue(AccessibilityList.contains(short, pkg, cls))
        assertEquals("com.other/.Svc:$pkg/$cls", AccessibilityList.with(short, pkg, cls))
        assertEquals("com.other/.Svc", AccessibilityList.without(short, pkg, cls))
        assertFalse(AccessibilityList.contains("$pkg.evil/$cls:com.other/.Svc", pkg, cls))
        assertFalse(AccessibilityList.contains("$pkg/com.oleg.androidagent.Other", pkg, cls))
    }
    @Test fun outageIsReportedOncePerOutageAndRearmedAfterRecovery() {
        val store = Store(); var up = false; val sent = mutableListOf<String>()
        val guard = AccessibilityGuard(store, { up }, { false }, { sent.add(it) }, now = { 1000 }, settle = {})
        assertFalse(guard.ensure()); assertFalse(guard.ensure()); assertFalse(guard.ensure())
        assertEquals(1, sent.size)
        assertTrue(sent[0].contains("Android Agent") && sent[0].contains("автовключение"))
        up = true
        assertTrue(guard.ensure())
        up = false
        assertFalse(guard.ensure())
        assertEquals(2, sent.size)
    }
    @Test fun outageStaysSilentAfterAnAppRestartWhileStillOff() {
        val store = Store(); val sent = mutableListOf<String>()
        AccessibilityGuard(store, { false }, { false }, { sent.add(it) }, settle = {}).ensure()
        AccessibilityGuard(store, { false }, { false }, { sent.add(it) }, settle = {}).ensure()
        assertEquals(1, sent.size)
    }
    @Test fun selfEnableRestoresTheServiceWithoutAnyMessage() {
        val store = Store(); var up = false; var enables = 0; val sent = mutableListOf<String>()
        val guard = AccessibilityGuard(store, { up }, { enables++; up = true; true }, { sent.add(it) }, settle = {})
        assertTrue(guard.ensure())
        assertEquals(1, enables); assertTrue(sent.isEmpty())
    }
    @Test fun failedSelfEnableFallsBackToTheMessageAndIsRetriedOnlyAfterTheInterval() {
        val store = Store(); var time = 0L; var enables = 0; val sent = mutableListOf<String>()
        val guard = AccessibilityGuard(store, { false }, { enables++; true }, { sent.add(it) }, now = { time }, settle = {})
        assertFalse(guard.ensure()); assertFalse(guard.ensure())
        assertEquals(1, enables); assertEquals(1, sent.size)
        time = AccessibilityGuard.RETRY_MILLIS + 1
        assertFalse(guard.ensure())
        assertEquals(2, enables); assertEquals(1, sent.size)
    }
    @Test fun lostTelegramMessageIsRetriedAtTheNextCheck() {
        val store = Store(); var fail = true; var sent = 0
        val guard = AccessibilityGuard(store, { false }, { false }, { if (fail) throw java.io.IOException("offline"); sent++ }, settle = {})
        assertFalse(guard.ensure()); assertEquals(0, sent)
        fail = false
        assertFalse(guard.ensure()); assertEquals(1, sent)
        assertFalse(guard.ensure()); assertEquals(1, sent)
    }
}
