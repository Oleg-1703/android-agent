package com.oleg.androidagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

internal class PhoneUnlockEvents(private val context: Context, onUnlock: () -> Unit) : AutoCloseable {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action in setOf(Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON)) onUnlock()
        }
    }
    init {
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT).apply { addAction(Intent.ACTION_SCREEN_ON) }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
    }
    override fun close() { context.unregisterReceiver(receiver) }
}
