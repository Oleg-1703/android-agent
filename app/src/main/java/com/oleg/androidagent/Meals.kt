package com.oleg.androidagent

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID
import java.math.BigDecimal

object Meals {
    val types = setOf("breakfast", "lunch", "dinner", "other")
    fun validate(json: JSONObject): JSONObject {
        require(json.optString("question").isBlank()) { json.optString("question").take(500) }
        val date = requireNotNull(runCatching { LocalDate.parse(json.getString("date")) }.getOrNull()) { "Specify a valid YYYY-MM-DD date" }
        require(date.year in 2020..2100) { "Specify a valid date" }
        require(json.getString("meal") in types) { "Specify breakfast, lunch, dinner or other" }
        val items = json.getJSONArray("items")
        require(items.length() in 1..10) { "Use 1–10 food items" }
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            require(item.getString("query").trim().length in 2..150) { "Specify the food and preparation" }
            val grams = item.getString("grams").toBigDecimal()
            require(grams > BigDecimal.ZERO && grams <= BigDecimal("5000")) { "Specify grams between 0 and 5000" }
        }
        return json
    }
    fun parse(text: String): JSONObject {
        val parts = text.trim().split(Regex("\\s+"), limit=4)
        require(parts.size == 4 && parts[0] == "/meal") { "Example: /meal 2026-11-12 lunch chicken cooked=200;rice cooked=150" }
        val items = JSONArray()
        parts[3].split(';').forEach {
            val pair = it.trim().split('=')
            require(pair.size == 2) { "Use food=grams, separated by semicolons" }
            items.put(JSONObject().put("query", pair[0].trim()).put("grams", pair[1].trim().replace(',', '.')))
        }
        return validate(JSONObject().put("date", parts[1]).put("meal", parts[2]).put("items", items))
    }
}

/** Single foreground worker owns this executor. Persist BEFORE a write; never replay uncertain writes. */
class MealEngine(private val store: StateStore, private val api: DiaryApi, private val now: () -> Long = { System.currentTimeMillis() }) {
    private fun pending(): JSONObject {
        val raw = store.get("pending")
        require(raw.isNotBlank()) { "No pending meal. Send /meal first." }
        val p = JSONObject(raw)
        require(now() - p.getLong("created") in 0..900_000) { "Review expired; send a new meal." }
        return p
    }
    fun prepare(meal: JSONObject): String {
        Meals.validate(meal)
        require(store.get("write_intent").isBlank()) { "An earlier write is uncertain. Inspect the FatSecret diary, then acknowledge it in the app." }
        store.put("pending", "")
        val items = meal.getJSONArray("items")
        val lines = mutableListOf("Choose the exact product for each item:")
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val choices = api.search(item.getString("query"))
            require(choices.isNotEmpty()) { "No results for item ${i+1}; refine the product name (English may be required)." }
            item.put("choices", JSONArray(choices)).remove("selected")
            lines += "${i+1}. ${item.getString("query")} ${item.getString("grams")} g"
            choices.forEachIndexed { n,c -> lines += "  ${n+1}: ${c.optString("food_name").take(120)} ${c.optString("brand_name").take(80)} ${c.optString("food_description").take(180)}" }
        }
        meal.put("created", now()).put("nonce", UUID.randomUUID().toString().take(8))
        store.put("pending", meal.toString())
        return lines.joinToString("\n") + "\n/pick ITEM CHOICE (e.g. /pick 1 2). /cancel to discard."
    }
    fun pick(itemIndex: Int, choiceIndex: Int): String {
        val p = pending()
        val items = p.getJSONArray("items")
        require(itemIndex in 1..items.length()) { "Invalid item" }
        val item = items.getJSONObject(itemIndex-1)
        val choices = item.getJSONArray("choices")
        require(choiceIndex in 1..choices.length()) { "Invalid choice" }
        val food = api.food(choices.getJSONObject(choiceIndex-1).getString("food_id"))
        val serving = food.getJSONObject("servings").objects("serving").firstOrNull {
            it.optString("metric_serving_unit") == "g" && (it.optString("metric_serving_amount").toBigDecimalOrNull() ?: BigDecimal.ZERO) > BigDecimal.ZERO
        } ?: throw IllegalArgumentException("No gram-based serving. Choose a different product.")
        val selected = JSONObject().put("food_id", food.getString("food_id")).put("name", food.getString("food_name").take(150))
            .put("serving_id", serving.getString("serving_id")).put("serving", serving.getString("serving_description"))
            .put("units", Portions.units(item.getString("grams").toBigDecimal(), serving).toPlainString())
        item.put("selected", selected)
        p.put("nonce", UUID.randomUUID().toString().take(8))
        store.put("pending", p.toString())
        if ((0 until items.length()).any { !items.getJSONObject(it).has("selected") }) return "Selected item $itemIndex. Choose the remaining items with /pick."
        val entries = api.entries(p.getString("date"))
        val duplicate = entries.any { entry -> (0 until items.length()).any { items.getJSONObject(it).getJSONObject("selected").getString("food_id") == entry.optString("food_id") } }
        val summary = (0 until items.length()).joinToString("\n") {
            val row = items.getJSONObject(it); val s = row.getJSONObject("selected")
            "${s.getString("name")}: ${row.getString("grams")} g (${s.getString("units")} units, ${s.getString("serving")})"
        }
        return "Review ${p.getString("date")} / ${p.getString("meal")}:\n$summary\n" +
            (if (duplicate) "WARNING: this food already appears on this date; confirmation may create a duplicate.\n" else "") +
            "Write to existing diary: /confirm ${p.getString("nonce")}\n/cancel to discard. Valid for 15 minutes."
    }
    fun cancel(): String { store.put("pending", ""); return "Pending meal discarded. Existing/uncertain diary writes are unchanged." }
    fun confirm(nonce: String): String {
        require(store.get("write_intent").isBlank()) { "Uncertain earlier write: inspect diary and acknowledge in app first." }
        val p = pending()
        require(p.getString("nonce") == nonce) { "Invalid or stale confirmation code" }
        val items = p.getJSONArray("items")
        require((0 until items.length()).all { items.getJSONObject(it).has("selected") }) { "Choose every product first" }
        // Consume review durably before any side effect, including process death / redelivered updates.
        store.put("pending", "")
        var verified = 0
        for (i in 0 until items.length()) {
            if (Thread.currentThread().isInterrupted) return "Stopped after $verified verified entries. Send only missing items in a new command."
            val item = items.getJSONObject(i).getJSONObject("selected")
            val intent = JSONObject().put("date", p.getString("date")).put("meal", p.getString("meal"))
                .put("item", item).put("verified_before", verified).put("created", now())
            store.put("write_intent", intent.toString())
            try {
                val id = api.create(p.getString("date"), p.getString("meal"), item)
                intent.put("entry_id", id); store.put("write_intent", intent.toString())
                val entry = api.entries(p.getString("date")).firstOrNull { it.optString("food_entry_id") == id }
                require(entry != null && entry.optString("food_id") == item.getString("food_id") &&
                    entry.optString("serving_id") == item.getString("serving_id") &&
                    entry.optString("meal") == p.getString("meal") &&
                    entry.optString("date_int") == LocalDate.parse(p.getString("date")).toEpochDay().toString() &&
                    entry.optString("number_of_units").toBigDecimal().compareTo(item.getString("units").toBigDecimal()) == 0) { "Diary verification failed" }
                verified++
                store.put("last_result", "Verified $verified/${items.length()} entries for ${p.getString("date")}; last entry ID $id")
                store.put("write_intent", "")
            } catch (_: Exception) {
                return "Stopped: $verified/${items.length()} entries verified. Item ${i+1} may have been saved. No retry will run. Inspect FatSecret, then acknowledge uncertain write in the app. Unsent items need a new command."
            }
        }
        return "Saved and verified $verified entries in the existing FatSecret diary (${p.getString("date")}, ${p.getString("meal")})."
    }
}
