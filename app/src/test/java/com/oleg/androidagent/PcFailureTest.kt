package com.oleg.androidagent

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PcFailureTest {
    @Test fun subscriptionLimitCarriesOnlySafeResetHint() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"subscription_limit","reset":"00:40"}"""))
            try {
                Http.text(Http.client(), Request.Builder().url(server.url("/")).build())
                fail()
            } catch (e: ApiFailure) {
                assertEquals("subscription_limit", e.reason)
                assertEquals("00:40", e.resetHint)
            }
        }
    }
    @Test fun untrustedErrorBodyNeverBecomesUserFacingCredentials() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"private-provider-key","reset":"private-provider-key"}"""))
            try {
                Http.text(Http.client(), Request.Builder().url(server.url("/")).build())
                fail()
            } catch (e: ApiFailure) {
                assertEquals("", e.reason)
                assertEquals("", e.resetHint)
                assertFalse(e.message.orEmpty().contains("private-provider-key"))
            }
        }
    }
}
