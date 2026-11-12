package com.oleg.androidagent

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.math.BigDecimal
import java.math.RoundingMode

interface DiaryApi {
    fun search(query: String): List<JSONObject>
    fun food(id: String): JSONObject
    fun entries(date: String): List<JSONObject>
    fun create(date: String, meal: String, item: JSONObject): String
}
object Portions {
    fun units(grams: BigDecimal, serving: JSONObject): BigDecimal {
        require(grams > BigDecimal.ZERO && grams <= BigDecimal("5000"))
        require(serving.getString("metric_serving_unit") == "g") { "A gram-based serving is required" }
        val metric = serving.getString("metric_serving_amount").toBigDecimal()
        val units = serving.getString("number_of_units").toBigDecimal()
        require(metric > BigDecimal.ZERO && units > BigDecimal.ZERO)
        return grams.multiply(units).divide(metric, 6, RoundingMode.HALF_UP).stripTrailingZeros()
    }
}
class FatSecretApi(private val config: JSONObject, private val client: OkHttpClient) : DiaryApi {
    private fun request(method: String, url: String, fields: Map<String,String>, token: String, secret: String): String {
        val p = OAuth1.signed(method, url, fields, config.optString("fs_key"), config.optString("fs_secret"), token, secret)
        val builder = Request.Builder()
        if (method == "GET") builder.url(url.toHttpUrl().newBuilder().apply { p.forEach { (k,v) -> addQueryParameter(k,v) } }.build())
        else builder.url(url).post(FormBody.Builder().apply { p.forEach { (k,v) -> add(k,v) } }.build())
        return Http.text(client, builder.build())
    }
    private fun tokens(text: String): JSONObject {
        val result = JSONObject()
        text.split("&").forEach { piece ->
            val pair = piece.split("=", limit=2)
            if (pair.size == 2) result.put(URLDecoder.decode(pair[0], "UTF-8"), URLDecoder.decode(pair[1], "UTF-8"))
        }
        require(result.optString("oauth_token").isNotBlank() && result.optString("oauth_token_secret").isNotBlank()) { "FatSecret authorization failed" }
        return result
    }
    fun requestToken(): JSONObject = tokens(request("POST", "https://authentication.fatsecret.com/oauth/request_token", mapOf("oauth_callback" to "oob"), "", ""))
    fun accessToken(pending: JSONObject, verifier: String): JSONObject {
        require(verifier.matches(Regex("[A-Za-z0-9_-]{1,200}"))) { "Invalid verifier" }
        return tokens(request("GET", "https://authentication.fatsecret.com/oauth/access_token", mapOf("oauth_verifier" to verifier), pending.getString("oauth_token"), pending.getString("oauth_token_secret")))
    }
    private fun api(method: String, fields: Map<String,String>): JSONObject {
        require(config.optString("fs_token").isNotBlank() && config.optString("fs_token_secret").isNotBlank()) { "Authorize the existing FatSecret account in the app first" }
        val r = JSONObject(request("POST", "https://platform.fatsecret.com/rest/server.api", fields + mapOf("method" to method, "format" to "json"), config.getString("fs_token"), config.getString("fs_token_secret")))
        if (r.has("error")) throw ApiFailure(r.getJSONObject("error").optInt("code"))
        return r
    }
    override fun search(query: String): List<JSONObject> = api("foods.search", mapOf("search_expression" to query.take(150), "max_results" to "5", "page_number" to "0")).optJSONObject("foods")?.objects("food") ?: emptyList()
    override fun food(id: String): JSONObject = api("food.get.v4", mapOf("food_id" to id)).getJSONObject("food")
    override fun entries(date: String): List<JSONObject> = api("food_entries.get", mapOf("date" to java.time.LocalDate.parse(date).toEpochDay().toString())).optJSONObject("food_entries")?.objects("food_entry") ?: emptyList()
    override fun create(date: String, meal: String, item: JSONObject): String = api("food_entry.create", mapOf(
        "food_id" to item.getString("food_id"), "serving_id" to item.getString("serving_id"),
        "food_entry_name" to item.getString("name").take(100), "number_of_units" to item.getString("units"),
        "meal" to meal, "date" to java.time.LocalDate.parse(date).toEpochDay().toString()
    )).getString("food_entry_id").also { require(it.matches(Regex("[0-9]+"))) }
}
