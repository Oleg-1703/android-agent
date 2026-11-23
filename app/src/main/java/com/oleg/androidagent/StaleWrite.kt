package com.oleg.androidagent

import org.json.JSONObject

/**
 * A leftover write journal never blocks a new request: a duplicate entry is cheap, a blocked bot is not.
 * The same request id is still never retried (PhoneMealRunner claim); only the journal gate is gone.
 */
object StaleWrite {
    /** Clears the journal of an earlier unverified write and returns a note for the receipt of the new request. */
    fun clear(store: StateStore): String? {
        val raw = store.get("write_intent")
        if (raw.isBlank()) return null
        val preview = try {
            val journal = JSONObject(raw)
            journal.optString("preview").ifBlank { journal.optJSONObject("draft")?.optString("preview").orEmpty() }
        } catch (_: Exception) { "" }
        store.put("write_intent", "")
        return "Предыдущая запись осталась не сверена" + (if (preview.isNotBlank()) " (${preview.substringBefore('\n').take(200)})" else "") +
            ". Это не мешает новой записи; её я не повторял. Проверь дневник FatSecret."
    }
}
