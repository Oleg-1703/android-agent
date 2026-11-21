package com.oleg.androidagent

import okhttp3.Call

/** Cancels only the idle Telegram poll, including an unlock racing with poll registration. */
internal class UnlockSignal {
    private var pending = false
    private var idleCall: Call? = null
    @Synchronized fun request() { pending = true; idleCall?.cancel() }
    @Synchronized fun begin(call: Call) { idleCall = call; if (pending) call.cancel() }
    @Synchronized fun end(call: Call) { if (idleCall === call) idleCall = null }
    @Synchronized fun consume(): Boolean = pending.also { pending = false }
}
