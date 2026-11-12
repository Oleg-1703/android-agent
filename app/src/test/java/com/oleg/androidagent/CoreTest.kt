package com.oleg.androidagent

import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import org.json.JSONArray
import java.math.BigDecimal
import java.io.IOException

private class MemoryStore : StateStore {
    val values = mutableMapOf<String,String>()
    var rejectWriteIntent = false
    override fun get(key: String) = values[key] ?: ""
    override fun put(key: String,value: String) {
        if(key == "write_intent" && rejectWriteIntent) throw IOException("Disk full")
        values[key] = value
    }
}
private class FakeDiary : DiaryApi {
    var writes = 0
    var failAfterSave = false
    var failVerification = false
    val saved = mutableListOf<JSONObject>()
    override fun search(query: String) = listOf(JSONObject("""{"food_id":"42","food_name":"Cooked chicken"}"""))
    override fun food(id: String) = JSONObject("""{"food_id":"42","food_name":"Cooked chicken","servings":{"serving":{"serving_id":"7","serving_description":"100 g","metric_serving_unit":"g","metric_serving_amount":"100","number_of_units":"100"}}}""")
    override fun entries(date: String): List<JSONObject> = if(failVerification) emptyList() else saved
    override fun create(date: String, meal: String, item: JSONObject): String {
        writes++
        saved += JSONObject().put("food_entry_id",writes.toString()).put("food_id",item.getString("food_id"))
            .put("serving_id",item.getString("serving_id")).put("number_of_units",item.getString("units"))
            .put("meal",meal).put("date_int",java.time.LocalDate.parse(date).toEpochDay().toString())
        if(failAfterSave) throw IOException("Connection lost after server commit")
        return writes.toString()
    }
}
class CoreTest {
    @Test fun oauthMatchesPublishedPhotoExample() {
        val p=mapOf("file" to "vacation.jpg","size" to "original","oauth_consumer_key" to "dpf43f3p2l4k3l03",
            "oauth_token" to "nnch734d00sl2jdk","oauth_nonce" to "kllo9940pd9333jh","oauth_timestamp" to "1191242096",
            "oauth_signature_method" to "HMAC-SHA1","oauth_version" to "1.0")
        assertEquals("tR3+Ty81lMeYAr/Fid0kMTYa/WM=",OAuth1.signature("GET","http://photos.example.net/photos",p,"kd94hf93k423kf44","pfkkdhi9sl3r4s00"))
    }
    @Test fun oauthEncodingIsRfc3986() { assertEquals("%D0%B3%20%2B%2A~",OAuth1.encode("г +*~")) }
    private fun message(chat:Long=123, sender:Long=123, type:String="private") = JSONObject().put("chat",JSONObject().put("id",chat).put("type",type))
        .put("from",JSONObject().put("id",sender).put("is_bot",false))
    @Test fun onlyMatchingPrivateOwnerAllowed() { assertTrue(TelegramAccess.allowed(message(),123)) }
    @Test fun strangersGroupsAndBotsDenied() {
        assertFalse(TelegramAccess.allowed(message(sender=9),123))
        assertFalse(TelegramAccess.allowed(message(chat=9),123))
        assertFalse(TelegramAccess.allowed(message(type="group"),123))
        assertFalse(TelegramAccess.allowed(message(),0))
        assertFalse(TelegramAccess.allowed(message().put("from",JSONObject().put("id",123).put("is_bot",true)),123))
        assertFalse(TelegramAccess.allowed(message().put("forward_origin",JSONObject()),123))
        assertFalse(TelegramAccess.allowed(message().put("via_bot",JSONObject()),123))
    }
    @Test fun structuredMealPreservesDateAndGrams() {
        val meal=Meals.parse("/meal 2026-11-12 lunch chicken cooked=200;rice cooked=150,5")
        assertEquals("2026-11-12",meal.getString("date"));assertEquals("150.5",meal.getJSONArray("items").getJSONObject(1).getString("grams"))
    }
    @Test fun invalidMealsRejected() {
        listOf("/meal 2026-02-30 lunch rice=100","/meal 2026-11-12 unknown rice=100", "/meal 2026-11-12 lunch rice=NaN", "/meal 2026-11-12 lunch rice=-10", "/meal 2026-11-12 lunch rice=5001", "/meal 2026-11-12 lunch rice").forEach { text ->
            assertThrows(Exception::class.java) { Meals.parse(text) }
        }
    }
    @Test fun aiQuestionsBlockPreparation() {
        assertThrows(IllegalArgumentException::class.java) { Meals.validate(JSONObject().put("question","Which portion?")) }
    }
    @Test fun portionsUseServingUnitsNotServingCount() {
        val gramServing=JSONObject("""{"metric_serving_unit":"g","metric_serving_amount":"100","number_of_units":"100"}""")
        assertEquals(0,BigDecimal("200").compareTo(Portions.units(BigDecimal("200"),gramServing)))
        val cup=JSONObject("""{"metric_serving_unit":"g","metric_serving_amount":"150","number_of_units":"1"}""")
        assertEquals(0,BigDecimal("2").compareTo(Portions.units(BigDecimal("300"),cup)))
        cup.put("metric_serving_unit","ml")
        assertThrows(IllegalArgumentException::class.java) { Portions.units(BigDecimal("200"),cup) }
    }
    @Test fun singletonAndArrayApiShapesSupported() {
        assertEquals(1,JSONObject("""{"food":{"food_id":"1"}}""").objects("food").size)
        assertEquals(2,JSONObject("""{"food":[{"food_id":"1"},{"food_id":"2"}]}""").objects("food").size)
        assertTrue(JSONObject().objects("food").isEmpty())
    }
    private fun ready(store: MemoryStore, api: FakeDiary, now:()->Long = {1000L}): Pair<MealEngine,String> {
        val engine=MealEngine(store,api,now)
        engine.prepare(Meals.parse("/meal 2026-11-12 lunch chicken cooked=200"));engine.pick(1,1)
        return engine to JSONObject(store.get("pending")).getString("nonce")
    }
    @Test fun noWriteWithoutConfirmationOrAllSelections() {
        val s=MemoryStore();val api=FakeDiary();val e=MealEngine(s,api)
        e.prepare(Meals.parse("/meal 2026-11-12 lunch chicken=200"))
        assertThrows(IllegalArgumentException::class.java) {e.confirm(JSONObject(s.get("pending")).getString("nonce"))}
        e.pick(1,1);assertThrows(IllegalArgumentException::class.java) {e.confirm("wrong")};assertEquals(0,api.writes)
    }
    @Test fun successfulWriteIsVerifiedAndConsumed() {
        val s=MemoryStore();val api=FakeDiary();val(e,code)=ready(s,api)
        assertTrue(e.confirm(code).contains("Saved and verified 1"));assertEquals(1,api.writes);assertEquals("",s.get("write_intent"))
        assertThrows(IllegalArgumentException::class.java) {e.confirm(code)};assertEquals(1,api.writes)
    }
    @Test fun timeoutAfterServerCommitCannotBeRetriedEvenAfterRestart() {
        val s=MemoryStore();val api=FakeDiary();val(e,code)=ready(s,api);api.failAfterSave=true
        assertTrue(e.confirm(code).contains("may have been saved"));assertEquals(1,api.writes);assertTrue(s.get("write_intent").isNotBlank())
        val restarted=MealEngine(s,api)
        assertThrows(IllegalArgumentException::class.java) {restarted.confirm(code)}
        assertThrows(IllegalArgumentException::class.java) {restarted.prepare(Meals.parse("/meal 2026-11-12 lunch chicken=200"))}
        restarted.cancel();assertTrue(s.get("write_intent").isNotBlank());assertEquals(1,api.writes)
    }
    @Test fun verificationMismatchRetainsBlock() {
        val s=MemoryStore();val api=FakeDiary();val(e,code)=ready(s,api);api.failVerification=true
        assertTrue(e.confirm(code).contains("0/1"));assertTrue(s.get("write_intent").contains("entry_id"))
    }
    @Test fun noWriteIfDurableIntentCannotBeSaved() {
        val s=MemoryStore();val api=FakeDiary();val(e,code)=ready(s,api);s.rejectWriteIntent=true
        assertThrows(IOException::class.java) {e.confirm(code)};assertEquals(0,api.writes)
    }
    @Test fun expiredReviewCannotWrite() {
        var now=1000L;val s=MemoryStore();val api=FakeDiary();val(e,code)=ready(s,api,{now});now+=900001
        assertThrows(IllegalArgumentException::class.java) {e.confirm(code)};assertEquals(0,api.writes)
    }
    @Test fun duplicateRiskIsShown() {
        val s=MemoryStore();val api=FakeDiary();val(e,code)=ready(s,api);e.confirm(code)
        e.prepare(Meals.parse("/meal 2026-11-12 lunch chicken=200"))
        assertTrue(e.pick(1,1).contains("WARNING"));assertEquals(1,api.writes)
    }
    @Test fun partialMealStopsAfterUncertainFirstWrite() {
        val s=MemoryStore();val api=FakeDiary();val e=MealEngine(s,api)
        e.prepare(Meals.parse("/meal 2026-11-12 lunch chicken=200;rice=150"));e.pick(1,1);e.pick(2,1);api.failAfterSave=true
        e.confirm(JSONObject(s.get("pending")).getString("nonce"));assertEquals(1,api.writes)
    }
}
