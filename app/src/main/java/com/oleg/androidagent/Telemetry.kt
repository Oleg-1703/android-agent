package com.oleg.androidagent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Metadata-only event schema shared with the PC (`pc/telemetry.py`). Anything outside the whitelist is dropped here and again on the
 * PC: no food names, macros, chat text, photos, transcripts or tokens ever enter an event. Screen fingerprints are view resource ids only.
 */
object EventSchema {
    val ALLOWED = setOf("eid", "ts", "type", "req", "ok", "ms", "err_class", "err", "n", "flags", "ids")
    private val TYPE = Regex("[a-z][a-z0-9_]{1,39}")
    private val CLASS = Regex("[A-Za-z][A-Za-z0-9_.-]{0,59}")
    private val KEY = Regex("[a-z][a-z0-9_]{0,31}")
    private val FLAG_TEXT = Regex("[A-Za-z0-9_.+-]{1,32}")
    private val ID = Regex("[A-Za-z0-9_.:/$-]{1,120}")
    private val REQ = Regex("[a-f0-9]{8,16}")
    private val SECRET = Regex("\\d{6,}:[A-Za-z0-9_-]{20,}|[A-Za-z0-9_+/=-]{24,}|https?://\\S+")

    fun text(value: String, limit: Int = 200): String = SECRET.replace(value.trim().split(Regex("\\s+")).joinToString(" "), "<x>").take(limit)

    /** The event reduced to allowed fields and shapes, or null when it has no valid type. */
    fun clean(raw: JSONObject): JSONObject? {
        val type = raw.optString("type")
        if (!TYPE.matches(type)) return null
        val out = JSONObject().put("type", type)
        raw.optString("eid").takeIf { it.matches(Regex("[A-Za-z0-9]{6,32}")) }?.let { out.put("eid", it) }
        (raw.opt("ts") as? Number)?.let { out.put("ts", it.toLong()) }
        raw.optString("req").takeIf { REQ.matches(it) }?.let { out.put("req", it) }
        (raw.opt("ok") as? Boolean)?.let { out.put("ok", it) }
        (raw.opt("ms") as? Number)?.toLong()?.takeIf { it in 0..86_400_000 }?.let { out.put("ms", it) }
        raw.optString("err_class").takeIf { CLASS.matches(it) }?.let { out.put("err_class", it) }
        raw.optString("err").takeIf { it.isNotBlank() }?.let { out.put("err", text(it)) }
        raw.optJSONObject("n")?.let { source ->
            val kept = JSONObject()
            for (key in source.keys()) {
                val value = source.opt(key)
                if (KEY.matches(key) && value is Number && kept.length() < 12) kept.put(key, value.toLong())
            }
            if (kept.length() > 0) out.put("n", kept)
        }
        raw.optJSONObject("flags")?.let { source ->
            val kept = JSONObject()
            for (key in source.keys()) {
                val value = source.opt(key)
                val fine = value is Boolean || value is Int || value is Long || (value is String && FLAG_TEXT.matches(value))
                if (KEY.matches(key) && fine && kept.length() < 12) kept.put(key, value)
            }
            if (kept.length() > 0) out.put("flags", kept)
        }
        raw.optJSONArray("ids")?.let { source ->
            val kept = JSONArray()
            for (i in 0 until source.length()) {
                val value = source.opt(i)
                if (value is String && ID.matches(value) && kept.length() < 60) kept.put(value)
            }
            if (kept.length() > 0) out.put("ids", kept)
        }
        return out
    }
}

/** Ring buffer of the last [cap] events, persisted in the encrypted store; removed only after the PC acknowledged them. */
class EventBuffer(private val store: StateStore, val cap: Int = 300, private val clock: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { java.util.UUID.randomUUID().toString().replace("-", "").take(12) }) {
    private val key = "events_buf"
    private fun load(): MutableList<JSONObject> = try {
        val raw = store.get(key)
        if (raw.isBlank()) mutableListOf() else JSONArray(raw).let { array -> (0 until array.length()).map { array.getJSONObject(it) }.toMutableList() }
    } catch (_: Exception) { mutableListOf() }
    private fun save(events: List<JSONObject>) = store.put(key, JSONArray(events).toString())

    @Synchronized fun add(raw: JSONObject): Boolean {
        val event = EventSchema.clean(raw) ?: return false
        if (!event.has("ts")) event.put("ts", clock())
        event.put("eid", newId())
        val events = load().apply { add(event) }
        while (events.size > cap) events.removeAt(0)
        save(events)
        return true
    }
    @Synchronized fun size() = load().size
    /** The oldest [limit] events, ready to send (already clean). */
    @Synchronized fun pending(limit: Int = 100): List<JSONObject> = load().take(limit)
    @Synchronized fun acknowledge(eids: Collection<String>) {
        val drop = eids.toSet()
        save(load().filter { it.optString("eid") !in drop })
    }
}

/** Sends the buffer to the PC in batches of at most [batch] events; a failure pauses further attempts for [backoffMillis]. */
class EventFlusher(private val buffer: EventBuffer, private val clock: () -> Long = { System.currentTimeMillis() },
    private val minIntervalMillis: Long = 20_000, private val backoffMillis: Long = 300_000,
    private val batch: Int = 100, private val maxBatches: Int = 3, private val send: (JSONObject) -> Boolean) {
    private var nextAllowed = 0L
    /** Number of events delivered in this call; never throws. */
    fun flush(): Int {
        val now = clock()
        if (now < nextAllowed || buffer.size() == 0) return 0
        nextAllowed = now + minIntervalMillis
        var sent = 0
        try {
            repeat(maxBatches) {
                val events = buffer.pending(batch)
                if (events.isEmpty()) return sent
                if (!send(JSONObject().put("events", JSONArray(events)))) { nextAllowed = now + backoffMillis; return sent }
                buffer.acknowledge(events.map { it.getString("eid") })
                sent += events.size
            }
        } catch (_: Exception) { nextAllowed = now + backoffMillis }
        return sent
    }
}

/** Process-wide entry point. A no-op until the service installs a buffer; never throws into the caller. */
object Telemetry {
    @Volatile var buffer: EventBuffer? = null
    fun requestHash(id: String): String? = id.takeIf { it.isNotBlank() }?.let {
        java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }.take(8)
    }
    fun errClass(error: Throwable): String = if (error is ApiFailure) "http_${error.status}" else error.javaClass.simpleName.ifBlank { "Error" }
    fun log(type: String, req: String? = null, ok: Boolean? = null, ms: Long? = null, errClass: String? = null,
        n: Map<String, Int> = emptyMap(), flags: Map<String, Any> = emptyMap(), ids: List<String> = emptyList()) {
        try {
            val target = buffer ?: return
            val event = JSONObject().put("type", type)
            req?.let { event.put("req", it) }
            ok?.let { event.put("ok", it) }
            ms?.let { event.put("ms", it) }
            errClass?.let { event.put("err_class", it) }
            if (n.isNotEmpty()) event.put("n", JSONObject(n))
            if (flags.isNotEmpty()) event.put("flags", JSONObject(flags))
            if (ids.isNotEmpty()) event.put("ids", JSONArray(ids))
            target.add(event)
        } catch (_: Throwable) { /* telemetry must never break the meal flow */ }
    }
    /** Result of one executor stage: its name, success, duration and the exception class only (never a message). */
    inline fun <T> stage(req: String?, name: String, block: () -> T): T {
        val started = System.nanoTime()
        try {
            val result = block()
            log("stage", req, true, (System.nanoTime() - started) / 1_000_000, flags = mapOf("stage" to name))
            return result
        } catch (error: Throwable) {
            log("stage", req, false, (System.nanoTime() - started) / 1_000_000, errClass(error), flags = mapOf("stage" to name))
            throw error
        }
    }
}
