package com.oleg.androidagent

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import org.junit.Assert.*

class HttpTest {
    @Test fun shortBodiesReadWithoutEofFailure() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"ok\":true}"));server.start()
            assertTrue(Http.json(Http.client(),Request.Builder().url(server.url("/")).build()).getBoolean("ok"))
        }
    }
    @Test fun oversizedChunkedBodiesRefused() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody("a".repeat(100),8));server.start()
            assertThrows(IllegalArgumentException::class.java) {Http.text(Http.client(),Request.Builder().url(server.url("/")).build(),32)}
        }
    }
    @Test fun redirectsAreNotFollowedAndErrorsAreRedacted() {
        MockWebServer().use { server ->
            server.start();server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",server.url("/secret")))
            val error=assertThrows(ApiFailure::class.java) {Http.text(Http.client(),Request.Builder().url(server.url("/token-secret")).build())}
            assertEquals(302,error.status);assertFalse(error.message!!.contains("token-secret"));assertEquals(1,server.requestCount)
        }
    }
    @Test fun serviceUnavailableWithZeroRetryAfterCannotReplayPost() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After","0"))
            server.enqueue(MockResponse().setBody("unexpected second request"));server.start()
            val request=Request.Builder().url(server.url("/")).post(okhttp3.FormBody.Builder().add("method","food_entry.create").build()).build()
            val error=assertThrows(ApiFailure::class.java) {Http.text(Http.client(),request)}
            assertEquals(503,error.status);assertEquals(1,server.requestCount)
        }
    }
    @Test fun telegramJsonRateLimitPreserved() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setBody("{\"ok\":false,\"parameters\":{\"retry_after\":42}}"));server.start()
            val error=assertThrows(ApiFailure::class.java) {Http.text(Http.client(),Request.Builder().url(server.url("/")).build())}
            assertEquals(42L,error.retrySeconds)
        }
    }
    @Test fun rateLimitRetryAfterPreserved() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","30"));server.start()
            val error=assertThrows(ApiFailure::class.java) {Http.text(Http.client(),Request.Builder().url(server.url("/")).build())}
            assertEquals(30L,error.retrySeconds)
        }
    }
}
