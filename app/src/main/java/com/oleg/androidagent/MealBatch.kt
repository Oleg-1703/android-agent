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
        if (messages.any { it.has("photo") } && messages.none { it.has("voice") } &&
            messages.none { it.optString("caption", it.optString("text")).isNotBlank() }) 150_000 else 8_000

    fun waitingForVoice(messages: List<JSONObject>) = graceMillis(messages) == 150_000L

    /** Acknowledgement for a batch; text gets none (it may be a correction, a question or a confirmation) and nothing promises a diary write. */
    fun ack(messages: List<JSONObject>): String? = when {
        messages.none { it.has("photo") || it.has("voice") } -> null
        waitingForVoice(messages) -> "Жду голосовой комментарий к фото (до 2,5 мин) или напиши подпись."
        messages.any { it.has("photo") } -> "Принял фото, разбираю."
        else -> "Принял голосовое, разбираю."
    }
}
