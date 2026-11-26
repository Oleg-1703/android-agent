package com.oleg.androidagent

/** The expected screen did not open in time. Only navigation before any save may be retried on this signal. */
class ScreenTimeout(message: String) : IllegalArgumentException(message)

/** A write failed while nothing was saved: the journal is already cleared, the whole plan may be offered again. */
class PreCommitFailure(message: String, val stage: String, cause: Throwable) : IllegalArgumentException(message, cause)

/**
 * Progress-aware wait for a slow FatSecret screen: the base timeout is extended up to [hardMillis] while the screen still
 * changes or shows a loading indicator, and ends at the base timeout once it is idle. Pure and clock-free for tests.
 */
class ScreenPatience(private val baseMillis: Long, private val hardMillis: Long = 30_000, private val idleMillis: Long = 3_000) {
    private var lastChange = 0L
    private var lastStamp: String? = null
    /** [stamp] identifies the current screen content (changes while the UI loads); [loading] is a visible progress indicator. */
    fun shouldStop(elapsed: Long, stamp: String?, loading: Boolean): Boolean {
        if (stamp != lastStamp) { lastStamp = stamp; lastChange = elapsed }
        if (elapsed < baseMillis) return false
        if (elapsed >= maxOf(baseMillis, hardMillis)) return true
        return !(loading || elapsed - lastChange < idleMillis)
    }
}
