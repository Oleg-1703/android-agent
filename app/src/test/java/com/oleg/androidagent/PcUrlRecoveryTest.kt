package com.oleg.androidagent

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.UnknownHostException

class PcUrlRecoveryTest {
    private class Store : StateStore {
        val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key].orEmpty()
        override fun put(key: String, value: String) { values[key] = value }
    }
    private val bot = 123456789L
    private fun pinned(text: String, id: Long = bot, isBot: Boolean = true) = JSONObject().put("pinned_message",
        JSONObject().put("text", text).put("from", JSONObject().put("id", id).put("is_bot", isBot)))

    @Before fun fresh() = PcUrlRecovery.resetRateLimit()

    @Test fun acceptsOnlyTheExactPinnedShape() {
        assertEquals("https://a-b1.trycloudflare.com", PcUrl.parse("AA-URL https://a-b1.trycloudflare.com"))
        listOf("AA-URL http://a.trycloudflare.com", "AA-URL https://a.evil.com", "AA-URL https://a.trycloudflare.com.evil.com",
            "AA-URL https://x.trycloudflare.com@evil.com", "AA-URL https://evil.com@x.trycloudflare.com", "AA-URL https://a.trycloudflare.com/",
            "AA-URL https://a.trycloudflare.com:8443", "AA-URL https://A.trycloudflare.com", "AA-URL  https://a.trycloudflare.com",
            "https://a.trycloudflare.com", "AA-URL https://a.trycloudflare.com\nAA-URL https://b.trycloudflare.com", "AA-URL https://a.trycloudflare.com\n",
            "AA-URL https://.trycloudflare.com", "AA-URL https://a.b.trycloudflare.com", "").forEach { assertNull(it, PcUrl.parse(it)) }
        assertNull(PcUrl.parse(null))
    }
    @Test fun chatMustBePinnedByThisBot() {
        val text = "AA-URL https://new-one.trycloudflare.com"
        assertEquals("https://new-one.trycloudflare.com", PcUrl.fromChat(pinned(text), bot))
        assertNull("another bot", PcUrl.fromChat(pinned(text, id = 999), bot))
        assertNull("a person", PcUrl.fromChat(pinned(text, isBot = false), bot))
        assertNull("a person with the bot id", PcUrl.fromChat(pinned(text, isBot = false), bot))
        assertNull("no sender", PcUrl.fromChat(JSONObject().put("pinned_message", JSONObject().put("text", text)), bot))
        assertNull("no pin", PcUrl.fromChat(JSONObject(), bot))
        assertNull("not text", PcUrl.fromChat(JSONObject().put("pinned_message", JSONObject().put("caption", text)
            .put("from", JSONObject().put("id", bot).put("is_bot", true))), bot))
        assertNull("unknown bot", PcUrl.fromChat(pinned(text), 0))
    }

    private fun config() = JSONObject().put("pc_url", "https://old.trycloudflare.com").put("bot_token", "$bot:AAAAAAAAAAAAAAAAAAAAAAAA").put("chat_id", "42")
    private class Net(val chatCalls: MutableList<Long>) {
        val hosts = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        var oldStatus = 0 // 0 = DNS failure
    }
    private fun client(config: JSONObject, store: Store, net: Net, chat: JSONObject, now: () -> Long = { 1_000_000L }): OkHttpClient {
        val recovery = PcUrlRecovery(config, store, { net.chatCalls.add(it); chat }, now)
        return OkHttpClient.Builder().addInterceptor(PcUrlInterceptor(config, recovery)).addInterceptor(Interceptor { chain ->
            val request = chain.request()
            net.hosts.add(request.url.host)
            val buffer = okio.Buffer(); request.body?.writeTo(buffer); net.bodies.add(buffer.readUtf8())
            if (request.url.host == "old.trycloudflare.com" && net.oldStatus == 0) throw UnknownHostException("old")
            val code = if (request.url.host == "old.trycloudflare.com") net.oldStatus else 200
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }).build()
    }
    private fun post(client: OkHttpClient, url: String = "https://old.trycloudflare.com/v1/log") =
        client.newCall(Request.Builder().url(url).post("""{"request_id":"abc"}""".toRequestBody("application/json".toMediaType())).build()).execute()

    @Test fun deadAddressIsReplacedAndTheSameRequestRetriedOnce() {
        val config = config(); val store = Store(); val net = Net(mutableListOf())
        store.put("config", config.toString())
        val response = post(client(config, store, net, pinned("AA-URL https://new-one.trycloudflare.com")))
        assertEquals(200, response.code)
        assertEquals(listOf("old.trycloudflare.com", "new-one.trycloudflare.com"), net.hosts)
        assertEquals(listOf("""{"request_id":"abc"}""", """{"request_id":"abc"}"""), net.bodies)
        assertEquals("https://new-one.trycloudflare.com", config.getString("pc_url"))
        assertEquals("https://new-one.trycloudflare.com", JSONObject(store.get("config")).getString("pc_url"))
        assertEquals("the stored config keeps its other fields", "42", JSONObject(store.get("config")).getString("chat_id"))
        assertEquals(listOf(42L), net.chatCalls)
    }
    @Test fun notFoundAndCloudflareErrorsAlsoRecoverButOtherStatusesDoNot() {
        for (code in listOf(502, 530)) {
            PcUrlRecovery.resetRateLimit()
            val config = config(); val net = Net(mutableListOf()); net.oldStatus = code
            val response = post(client(config, Store(), net, pinned("AA-URL https://new-one.trycloudflare.com")))
            assertEquals("$code", 200, response.code)
            assertEquals(2, net.hosts.size)
        }
        for (code in listOf(401, 410, 429, 503)) {
            PcUrlRecovery.resetRateLimit()
            val net = Net(mutableListOf()); net.oldStatus = code
            assertEquals(code, post(client(config(), Store(), net, pinned("AA-URL https://new-one.trycloudflare.com"))).code)
            assertTrue(net.chatCalls.isEmpty())
        }
    }
    @Test fun atMostOneLookupPerMinute() {
        var clock = 1_000_000L
        val config = config(); val net = Net(mutableListOf())
        val client = client(config, Store(), net, JSONObject(), { clock }) // nothing pinned
        repeat(2) { try { post(client); fail() } catch (_: UnknownHostException) {} }
        assertEquals(1, net.chatCalls.size)
        clock += 59_000
        try { post(client); fail() } catch (_: UnknownHostException) {}
        assertEquals(1, net.chatCalls.size)
        clock += 2_000
        try { post(client); fail() } catch (_: UnknownHostException) {}
        assertEquals(2, net.chatCalls.size)
    }
    @Test fun rejectedPinKeepsTheFailureAndTheSavedAddress() {
        for (chat in listOf(pinned("AA-URL https://x.trycloudflare.com@evil.com"), pinned("AA-URL https://new-one.trycloudflare.com", id = 5), JSONObject(),
            pinned("AA-URL https://old.trycloudflare.com"))) {
            PcUrlRecovery.resetRateLimit()
            val config = config(); val net = Net(mutableListOf())
            try { post(client(config, Store(), net, chat)); fail() } catch (_: UnknownHostException) {}
            assertEquals("https://old.trycloudflare.com", config.getString("pc_url"))
            assertEquals(listOf("old.trycloudflare.com"), net.hosts)
        }
    }
    @Test fun telegramFailureIsNotFatalAndOtherHostsAreUntouched() {
        val config = config(); val net = Net(mutableListOf())
        val failing = PcUrlRecovery(config, Store(), { throw java.io.IOException("offline") })
        assertNull(failing.recover())
        PcUrlRecovery.resetRateLimit()
        val client = client(config, Store(), net, pinned("AA-URL https://new-one.trycloudflare.com"))
        assertEquals(200, post(client, "https://other.example.com/x").code)
        assertTrue(net.chatCalls.isEmpty())
    }
}
