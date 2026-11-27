package com.oleg.androidagent

import android.content.Context
import android.os.PowerManager

/** A bounded dim lock spans FatSecret windows; no display lock is held during inference. */
@Suppress("DEPRECATION")
internal class ScreenLease(context: Context, timeoutMillis: Long = 120_000) : AutoCloseable {
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "androidagent:meal-write").apply {
            setReferenceCounted(false)
            acquire(timeoutMillis)
        }
    val active: Boolean get() = lock.isHeld
    override fun close() { if (lock.isHeld) lock.release() }
}

internal fun closeScreenLease(lease: AutoCloseable, reset: () -> Unit) {
    try { lease.close() } finally { reset() }
}
