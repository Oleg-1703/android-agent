package com.oleg.androidagent

import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Protocol
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.URLDecoder

class TelegramSetupTest {
    private class Memory : StateStore {
        val data = mutableMapOf<String, String>()
        override fun get(key: String) = data[key].orEmpty()
        override fun put(key: String, value: String) { data[key] = value }
    }
    @Test fun menuAndKeyboardAreScopedAndGreetingIsOncePerToken() {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = okio.Buffer()
            request.body!!.writeTo(buffer)
            val fields = buffer.readUtf8().split('&').associate {
                val pair = it.split('=', limit = 2)
                URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
            }
            requests.add(request.url.pathSegments.last() to fields)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"ok\":true,\"result\":{\"message_id\":1}}".toResponseBody()).build()
        }.build()
        val api = TelegramApi("123456:" + "x".repeat(30), client)
        val store = Memory()
        api.setupOwner(42, "hash1", store)
        api.setupOwner(42, "hash1", store)
        api.setupOwner(42, "hash2", store)
        assertEquals(3, requests.count { it.first == "setMyCommands" })
        assertEquals(2, requests.count { it.first == "sendMessage" })
        val scope = JSONObject(requests.first().second.getValue("scope"))
        assertEquals("chat", scope.getString("type"))
        assertEquals(42L, scope.getLong("chat_id"))
        val commands = JSONArray(requests.first().second.getValue("commands"))
        assertEquals(listOf("look", "today", "status", "help", "cancel", "stop"), (0 until commands.length()).map { commands.getJSONObject(it).getString("command") })
        val sent = requests.first { it.first == "sendMessage" }.second
        assertEquals("42", sent["chat_id"])
        assertEquals("Android Agent подключён к этому боту. Кнопки внизу, команды - в меню.", sent["text"])
        assertEquals(TelegramApi.keyboard().toString(), sent["reply_markup"])
    }
    @Test fun failuresDoNotEscapeOrMarkGreetingAndNextStartRetries() {
        for (io in listOf(true, false)) {
            var fail = true
            var calls = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                if (fail && io) throw IOException("offline")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body((if (fail) "{\"ok\":false,\"error_code\":403}" else "{\"ok\":true}").toResponseBody()).build()
            }.build()
            val api = TelegramApi("123456:" + "x".repeat(30), client)
            val store = Memory()
            api.setupOwner(42, "hash", store)
            assertEquals("", store.get("telegram_connected_hash"))
            assertEquals(2, calls)
            fail = false
            api.setupOwner(42, "hash", store)
            assertEquals("true", store.get("telegram_connected_hash"))
            assertEquals(4, calls)
        }
    }
}
