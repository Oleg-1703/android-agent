package com.oleg.androidagent

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiFailure(val status: Int = 0, val retrySeconds: Long = 0) : IOException("Remote request failed ($status); check connection and account configuration")
object Http {
    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(65, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS).retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false)
        // OkHttp can otherwise repeat POST on HTTP 503 + Retry-After: 0 even with
        // retryOnConnectionFailure(false). Prevent that implicit write replay.
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code == 503 && response.header("Retry-After")?.toLongOrNull() == 0L)
                response.newBuilder().header("Retry-After", "1").build()
            else response
        }.build()
    fun text(client: OkHttpClient, request: Request, limit: Long = 2_000_000): String {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                var retry = response.header("Retry-After")?.toLongOrNull() ?: 0
                try {
                    val source = response.body?.source()
                    if (source != null && !source.request(8193)) {
                        val error = JSONObject(source.buffer.readUtf8())
                        retry = maxOf(retry, error.optJSONObject("parameters")?.optLong("retry_after") ?: 0)
                    }
                } catch (_: Exception) { /* Never expose raw remote error bodies. */ }
                throw ApiFailure(response.code, retry)
            }
            val body = response.body ?: throw ApiFailure()
            require(body.contentLength() <= limit) { "Response too large" }
            val source = body.source()
            source.request(limit + 1)
            val bytes = source.buffer.readByteArray()
            require(bytes.size <= limit) { "Response too large" }
            return String(bytes, Charsets.UTF_8)
        }
    }
    fun json(client: OkHttpClient, request: Request): JSONObject = JSONObject(text(client, request))
}
fun JSONObject.objects(key: String): List<JSONObject> = when (val v = opt(key)) {
    is JSONArray -> (0 until v.length()).map { v.getJSONObject(it) }
    is JSONObject -> listOf(v)
    else -> emptyList()
}
