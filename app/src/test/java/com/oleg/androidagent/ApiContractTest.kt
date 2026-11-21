package com.oleg.androidagent

import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

class ApiContractTest {
    private fun config() = JSONObject().put("fs_key","test-consumer").put("fs_secret","test-secret").put("fs_token","test-access").put("fs_token_secret","test-access-secret")
    private fun client(server: MockWebServer) = Http.client().newBuilder().addInterceptor { chain ->
        val request=chain.request()
        chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: ""))).build())
    }.build()
    private fun form(body: String) = body.split('&').associate {
        val p=it.split('=',limit=2); URLDecoder.decode(p[0],"UTF-8") to URLDecoder.decode(p[1],"UTF-8")
    }
    @Test fun requestTokenUsesSignedPostAndOutOfBandCallback() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("oauth_token=request&oauth_token_secret=request-secret&oauth_callback_confirmed=true"));server.start()
            val token=FatSecretApi(config(),client(server)).requestToken()
            assertEquals("request",token.getString("oauth_token"))
            val r=server.takeRequest(2,TimeUnit.SECONDS)!!;assertEquals("POST",r.method);assertEquals("/oauth/request_token",r.path)
            val fields=form(r.body.readUtf8());assertEquals("oob",fields["oauth_callback"]);assertFalse(fields.containsKey("oauth_token"))
            assertEquals(OAuth1.signature("POST","https://authentication.fatsecret.com/oauth/request_token",fields,"test-secret",""),fields["oauth_signature"])
        }
    }
    @Test fun accessTokenUsesSignedGetAndRequestTokenSecret() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("oauth_token=access&oauth_token_secret=access-secret"));server.start()
            FatSecretApi(config(),client(server)).accessToken(JSONObject().put("oauth_token","request").put("oauth_token_secret","request-secret"),"verifier")
            val r=server.takeRequest(2,TimeUnit.SECONDS)!!;assertEquals("GET",r.method)
            val url=r.requestUrl!!;val fields=url.queryParameterNames.associateWith { url.queryParameter(it)!! }
            assertEquals("verifier",fields["oauth_verifier"]);assertEquals("request",fields["oauth_token"])
            assertEquals(OAuth1.signature("GET","https://authentication.fatsecret.com/oauth/access_token",fields,"test-secret","request-secret"),fields["oauth_signature"])
        }
    }
    @Test fun diaryWritesContainReviewedParametersAndUseExistingToken() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"food_entry_id\":\"99\"}"));server.start()
            val api=FatSecretApi(config(),client(server))
            val item=JSONObject().put("food_id","42").put("serving_id","7").put("name","Chicken").put("units","200")
            assertEquals("99",api.create("2026-11-12","lunch",item))
            val r=server.takeRequest()!!;val fields=form(r.body.readUtf8())
            assertEquals("food_entry.create",fields["method"]);assertEquals("test-access",fields["oauth_token"])
            assertEquals("20769",fields["date"]);assertEquals("200",fields["number_of_units"])
            assertEquals("7",fields["serving_id"]);assertEquals("lunch",fields["meal"])
            assertEquals(OAuth1.signature("POST","https://platform.fatsecret.com/rest/server.api",fields,"test-secret","test-access-secret"),fields["oauth_signature"])
        }
    }
    @Test fun connectionLostAfterWriteIsNeverRetriedByHttpClient() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));server.start()
            val api=FatSecretApi(config(),client(server))
            val item=JSONObject().put("food_id","42").put("serving_id","7").put("name","Chicken").put("units","200")
            assertThrows(Exception::class.java) {api.create("2026-11-12","lunch",item)}
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun apiErrorsDoNotExposeRawRemoteDescriptions() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"error\":{\"code\":9,\"message\":\"invalid token sensitive-value\"}}"));server.start()
            val error=assertThrows(ApiFailure::class.java) {FatSecretApi(config(),client(server)).entries("2026-11-12")}
            assertFalse(error.message!!.contains("sensitive-value"));assertEquals(9,error.status)
        }
    }
    @Test fun noDiaryAccessWithoutUserAuthorization() {
        MockWebServer().use { server ->
            server.start();val c=config();c.remove("fs_token")
            assertThrows(IllegalArgumentException::class.java) {FatSecretApi(c,client(server)).entries("2026-11-12")};assertEquals(0,server.requestCount)
        }
    }
    @Test fun aiRequiresExplicitConsentBeforeSendingData() {
        MockWebServer().use { server ->
            server.start();val c=JSONObject().put("llm_url","https://model.example/v1").put("llm_key","test")
            assertThrows(IllegalArgumentException::class.java) {AiMeals(c,client(server)).parse("food")};assertEquals(0,server.requestCount)
        }
    }
    @Test fun aiResponseIsValidatedAndQuestionCannotExecute() {
        MockWebServer().use { server ->
            val answer=JSONObject().put("choices",org.json.JSONArray().put(JSONObject().put("message",JSONObject().put("content","{\"question\":\"How many grams?\"}"))))
            server.enqueue(MockResponse().setBody(answer.toString()));server.start()
            val c=JSONObject().put("llm_url","https://model.example/v1").put("llm_key","test").put("ai_consent",true)
            assertThrows(IllegalArgumentException::class.java) {AiMeals(c,client(server)).parse("rice")}
            val r=server.takeRequest();assertEquals("Bearer test",r.getHeader("Authorization"));assertEquals("/v1/chat/completions",r.path)
        }
    }
}
