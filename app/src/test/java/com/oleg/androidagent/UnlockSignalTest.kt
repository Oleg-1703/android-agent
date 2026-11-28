package com.oleg.androidagent

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class UnlockSignalTest {
    private fun call(path: String) = OkHttpClient().newCall(Request.Builder().url("https://pc.example.invalid/$path").build())
    @Test fun unlockCancelsOnlyTheRegisteredIdleCall() {
        val signal = UnlockSignal()
        val poll = call("getUpdates")
        val write = call("claim")
        signal.begin(poll)
        signal.request()
        assertTrue(poll.isCanceled())
        assertFalse(write.isCanceled())
        assertTrue(signal.consume())
        assertFalse(signal.consume())
    }
    @Test fun unlockBeforePollRegistrationIsNotLost() {
        val signal = UnlockSignal()
        signal.request()
        val poll = call("getUpdates")
        signal.begin(poll)
        assertTrue(poll.isCanceled())
        signal.end(poll)
        signal.consume()
        val next = call("getUpdates")
        signal.begin(next)
        assertFalse(next.isCanceled())
    }
}
