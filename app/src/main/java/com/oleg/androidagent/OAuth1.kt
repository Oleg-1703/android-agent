package com.oleg.androidagent

import java.net.URLEncoder
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object OAuth1 {
    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")
    fun signature(method: String, url: String, parameters: Map<String, String>, consumerSecret: String, tokenSecret: String): String {
        val normalized = parameters.filterKeys { it != "oauth_signature" }.map { encode(it.key) to encode(it.value) }
            .sortedWith(compareBy<Pair<String,String>> { it.first }.thenBy { it.second })
            .joinToString("&") { "${it.first}=${it.second}" }
        val base = method.uppercase() + "&" + encode(url) + "&" + encode(normalized)
        val key = encode(consumerSecret) + "&" + encode(tokenSecret)
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1")) }
        return Base64.getEncoder().encodeToString(mac.doFinal(base.toByteArray(Charsets.UTF_8)))
    }
    fun signed(method: String, url: String, fields: Map<String,String>, consumer: String, secret: String,
               token: String = "", tokenSecret: String = ""): Map<String,String> {
        require(consumer.isNotBlank() && secret.isNotBlank()) { "Configure FatSecret consumer credentials on phone" }
        val p = fields + mapOf("oauth_consumer_key" to consumer, "oauth_nonce" to UUID.randomUUID().toString(),
            "oauth_timestamp" to (System.currentTimeMillis()/1000).toString(), "oauth_signature_method" to "HMAC-SHA1", "oauth_version" to "1.0") +
            if (token.isNotBlank()) mapOf("oauth_token" to token) else emptyMap()
        return p + ("oauth_signature" to signature(method, url, p, secret, tokenSecret))
    }
}
