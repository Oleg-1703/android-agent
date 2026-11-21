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
        val date = requireNotNull(runCatching { LocalDate.parse(json.getString("date")) }.getOrNull()) { "Укажи дату в формате ГГГГ-ММ-ДД" }
        require(date.year in 2020..2100) { "Укажи корректную дату" }
        require(json.getString("meal") in types) { "Приём пищи: breakfast, lunch, dinner или other" }
        val items = json.getJSONArray("items")
        require(items.length() in 1..10) { "В запросе допустимо от 1 до 10 продуктов" }
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            require(item.getString("query").trim().length in 2..150) { "Укажи продукт и способ приготовления" }
            if (item.optString("source") in setOf("existing", "saved") && item.optString("grams").isBlank()) {
                val count = requireNotNull(item.optString("count", "1").toBigDecimalOrNull())
                require(count > BigDecimal.ZERO && count <= BigDecimal("100"))
                require(item.optString("unit", "servings") in setOf("servings", "pieces"))
            } else {
                val grams = requireNotNull(item.getString("grams").toBigDecimalOrNull()) { "Укажи вес числом в граммах, например 150 или 150,5" }
                require(grams > BigDecimal.ZERO && grams <= BigDecimal("5000")) { "Вес должен быть больше 0 и не больше 5000 г" }
            }
            item.optJSONArray("search_queries")?.let { queries ->
                require(queries.length() <= 5)
                for (index in 0 until queries.length()) require(queries.getString(index).length in 2..150)
            }
        }
        return json
    }
    fun parse(text: String): JSONObject {
        val parts = text.trim().split(Regex("\\s+"), limit=4)
        require(parts.size == 4 && parts[0] == "/meal") { "Пример: /meal 2026-11-15 lunch курица=200;рис варёный=150" }
        val items = JSONArray()
        parts[3].split(';').forEach {
            val pair = it.trim().split('=')
            require(pair.size == 2) { "Формат: продукт=граммы; другой продукт=граммы" }
            items.put(JSONObject().put("query", pair[0].trim()).put("grams", pair[1].trim().replace(',', '.')))
        }
        return validate(JSONObject().put("date", parts[1]).put("meal", parts[2]).put("items", items))
    }
}

/** Single foreground worker owns this executor. Persist BEFORE a write; never replay uncertain writes. */
class MealEngine(private val store: StateStore, private val api: DiaryApi, private val now: () -> Long = { System.currentTimeMillis() }) {
    private fun pending(): JSONObject {
        val raw = store.get("pending")
        require(raw.isNotBlank()) { "Нет подготовленной еды. Сначала отправь /meal." }
        val p = JSONObject(raw)
        require(now() - p.getLong("created") in 0..900_000) { "Время подтверждения истекло. Отправь еду заново." }
        return p
    }
    fun prepare(meal: JSONObject): String {
        Meals.validate(meal)
        require(store.get("write_intent").isBlank()) { "Предыдущая запись не проверена. Проверь дневник FatSecret и сними блокировку в приложении." }
        store.put("pending", "")
        val items = meal.getJSONArray("items")
        val lines = mutableListOf("Выбери точный продукт для каждой позиции:")
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val choices = api.search(item.getString("query"))
            require(choices.isNotEmpty()) { "Продукт ${i+1} не найден. Уточни название, при необходимости на английском." }
            item.put("choices", JSONArray(choices)).remove("selected")
            lines += "${i+1}. ${item.getString("query")} ${item.getString("grams")} г"
            choices.forEachIndexed { n,c -> lines += "  ${n+1}: ${c.optString("food_name").take(120)} ${c.optString("brand_name").take(80)} ${c.optString("food_description").take(180)}" }
        }
        meal.put("created", now()).put("nonce", UUID.randomUUID().toString().take(8))
        store.put("pending", meal.toString())
        return lines.joinToString("\n") + "\nВыбор: /pick НОМЕР_ПРОДУКТА НОМЕР_ВАРИАНТА, например /pick 1 2. Отмена: /cancel."
    }
    fun pick(itemIndex: Int, choiceIndex: Int): String {
        val p = pending()
        val items = p.getJSONArray("items")
        require(itemIndex in 1..items.length()) { "Неверный номер продукта" }
        val item = items.getJSONObject(itemIndex-1)
        val choices = item.getJSONArray("choices")
        require(choiceIndex in 1..choices.length()) { "Неверный номер варианта" }
        val food = api.food(choices.getJSONObject(choiceIndex-1).getString("food_id"))
        val serving = food.getJSONObject("servings").objects("serving").firstOrNull {
            it.optString("metric_serving_unit") == "g" && (it.optString("metric_serving_amount").toBigDecimalOrNull() ?: BigDecimal.ZERO) > BigDecimal.ZERO
        } ?: throw IllegalArgumentException("Нет порции в граммах. Выбери другой продукт.")
        val selected = JSONObject().put("food_id", food.getString("food_id")).put("name", food.getString("food_name").take(150))
            .put("serving_id", serving.getString("serving_id")).put("serving", serving.getString("serving_description"))
            .put("units", Portions.units(item.getString("grams").toBigDecimal(), serving).toPlainString())
        item.put("selected", selected)
        p.put("nonce", UUID.randomUUID().toString().take(8))
        store.put("pending", p.toString())
        if ((0 until items.length()).any { !items.getJSONObject(it).has("selected") }) return "Продукт $itemIndex выбран. Выбери остальные командой /pick."
        val entries = api.entries(p.getString("date"))
        val duplicate = entries.any { entry -> (0 until items.length()).any { items.getJSONObject(it).getJSONObject("selected").getString("food_id") == entry.optString("food_id") } }
        val summary = (0 until items.length()).joinToString("\n") {
            val row = items.getJSONObject(it); val s = row.getJSONObject("selected")
            "${s.getString("name")}: ${row.getString("grams")} г (${s.getString("units")} порций, ${s.getString("serving")})"
        }
        return "Проверь ${p.getString("date")} / ${p.getString("meal")}:\n$summary\n" +
            (if (duplicate) "ВНИМАНИЕ: этот продукт уже есть на этой дате. Подтверждение может создать дубль.\n" else "") +
            "Записать в существующий дневник: /confirm ${p.getString("nonce")}\nОтмена: /cancel. Код действует 15 минут."
    }
    fun cancel(): String { store.put("pending", ""); return "Подготовленная еда отменена. Существующие и непроверенные записи в дневнике сохранены." }
    fun confirm(nonce: String): String {
        require(store.get("write_intent").isBlank()) { "Есть непроверенная запись. Сначала проверь дневник и сними блокировку в приложении." }
        val p = pending()
        require(p.getString("nonce") == nonce) { "Неверный или устаревший код подтверждения" }
        val items = p.getJSONArray("items")
        require((0 until items.length()).all { items.getJSONObject(it).has("selected") }) { "Сначала выбери все продукты" }
        // Consume review durably before any side effect, including process death / redelivered updates.
        store.put("pending", "")
        var verified = 0
        for (i in 0 until items.length()) {
            if (Thread.currentThread().isInterrupted) return "Остановлено после $verified проверенных записей. Отправь новой командой только недостающие продукты."
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
                store.put("last_result", "Проверено $verified/${items.length()} записей за ${p.getString("date")}; ID последней записи $id")
                store.put("write_intent", "")
            } catch (_: Exception) {
                return "Остановлено: проверено $verified/${items.length()} записей. Продукт ${i+1} мог сохраниться. Повтора не будет. Проверь дневник FatSecret и сними блокировку в приложении. Отправь заново только недостающие продукты."
            }
        }
        return "Сохранено и проверено $verified записей в существующем дневнике FatSecret (${p.getString("date")}, ${p.getString("meal")})."
    }
}
