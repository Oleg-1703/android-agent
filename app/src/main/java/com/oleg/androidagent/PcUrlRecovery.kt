package com.oleg.androidagent

import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException

/** Finds the PC's current quick-tunnel address in the pinned Telegram message `AA-URL https://<x>.trycloudflare.com` sent by the PC helper. */
object PcUrl {
    private val PINNED = Regex("AA-URL (https://[a-z0-9-]+\\.trycloudflare\\.com)")
    /** The address in a pinned-message text, or null unless the whole text has exactly that shape. */
    fun parse(text: String?): String? = text?.let { PINNED.matchEntire(it)?.groupValues?.get(1) }
    /** Reads getChat's result: only a message sent by this very bot counts; anyone else able to pin in the chat is ignored. */
    fun fromChat(chat: JSONObject, botId: Long): String? {
        val pinned = chat.optJSONObject("pinned_message") ?: return null
        val sender = pinned.optJSONObject("from") ?: return null
        if (!sender.optBoolean("is_bot") || botId <= 0 || sender.optLong("id") != botId) return null
        return parse(pinned.optString("text").takeIf { pinned.has("text") })
    }
}

/**
 * When the PC address stops answering, asks Telegram for the pinned address (at most one getChat a minute), saves it as pc_url
 * and tells the caller to retry once. The pairing token still authorizes every request; the address is not a secret.
 */
class PcUrlRecovery(
    private val config: JSONObject,
    private val store: StateStore,
    private val chatOf: (Long) -> JSONObject,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    /** The new address when it differs from the saved one, else null (also when rate-limited or Telegram is unreachable). */
    fun recover(): String? {
        val botId = config.optString("bot_token").substringBefore(':').toLongOrNull() ?: return null
        val owner = config.optString("chat_id").toLongOrNull()?.takeIf { it > 0 } ?: return null
        synchronized(lock) {
            val time = now()
            if (lastAttempt != Long.MIN_VALUE && time - lastAttempt < MIN_INTERVAL_MILLIS) return null
            lastAttempt = time
        }
        val url = try { PcUrl.fromChat(chatOf(owner), botId) } catch (e: Exception) {
            Telemetry.log("url_recovery", ok = false, errClass = Telemetry.errClass(e)); null
        } ?: run { Telemetry.log("url_recovery", ok = false, errClass = "no_pinned_url"); return null }
        val current = try { PcBrain.endpoint(config.optString("pc_url")).host } catch (_: IllegalArgumentException) { "" }
        val found = try { PcBrain.endpoint(url) } catch (_: IllegalArgumentException) { return null }
        if (found.host == current) { Telemetry.log("url_recovery", ok = true, flags = mapOf("changed" to false)); return null }
        config.put("pc_url", url)
        val saved = store.get("config")
        store.put("config", (if (saved.isBlank()) JSONObject() else JSONObject(saved)).put("pc_url", url).toString())
        Telemetry.log("url_recovery", ok = true, flags = mapOf("changed" to true))
        return url
    }
    companion object {
        const val MIN_INTERVAL_MILLIS = 60_000L
        private val lock = Any()
        private var lastAttempt = Long.MIN_VALUE
        internal fun resetRateLimit() = synchronized(lock) { lastAttempt = Long.MIN_VALUE }
    }
}

/** Application interceptor for requests to the PC: on a dead address (DNS/connect failure, 502/530; a plain 404 is not treated as dead) retries the same request once at the recovered address. */
class PcUrlInterceptor(private val config: JSONObject, private val recovery: PcUrlRecovery) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val pcHost = try { PcBrain.endpoint(config.optString("pc_url")).host } catch (_: IllegalArgumentException) { return chain.proceed(request) }
        if (request.url.host != pcHost) return chain.proceed(request)
        fun retryAt(): Response? {
            val url = recovery.recover() ?: return null
            val host = PcBrain.endpoint(url).host
            return chain.proceed(request.newBuilder().url(request.url.newBuilder().host(host).build()).build())
        }
        val response = try { chain.proceed(request) }
        catch (e: IOException) {
            if (e is UnknownHostException || e is ConnectException || e is NoRouteToHostException) return retryAt() ?: throw e
            throw e
        }
        if (response.code in DEAD_STATUS) retryAt()?.let { response.close(); return it }
        return response
    }
    companion object { val DEAD_STATUS = setOf(502, 530) }
}
