package com.oleg.androidagent

import org.json.JSONObject
import java.util.Locale

object MealBatch {
    fun eligible(message: JSONObject): Boolean {
        if (message.has("photo") || message.has("voice")) return true
        val text = message.optString("text", message.optString("caption")).trim()
        val command = text.takeWhile { !it.isWhitespace() }.substringBefore('@').lowercase(Locale.ROOT)
        if (text.startsWith("/")) return command == "/meal" && text.any { it.isWhitespace() }
        return text.isNotBlank() && text.lowercase(Locale.ROOT).trimEnd('?', '.', '!') !in setOf(
            "помощь", "привет", "статус", "сегодня", "дневник", "что я сегодня ел", "что я ел сегодня", "покажи дневник", "сколько калорий сегодня",
            "отмена", "отмени", "отмени черновик", "останови агента", "открой fatsecret", "открой фэтсекрет")
    }
    fun graceMillis(messages: List<JSONObject>): Long =
        if (messages.any { it.has("photo") } && messages.none { it.has("voice") }) 150_000 else 8_000
}
