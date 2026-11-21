package com.oleg.androidagent

import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/** A server timestamp anchored to elapsed time; never to the phone's adjustable wall clock. */
class TelegramClock(private val elapsedMillis: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) }) {
    private data class Sample(val epochSeconds: Long, val elapsedMillis: Long)
    @Volatile private var sample: Sample? = null

    fun observe(httpDate: String?) {
        if (httpDate == null) return
        val parsed = try { ZonedDateTime.parse(httpDate, DateTimeFormatter.RFC_1123_DATE_TIME) }
        catch (_: java.time.DateTimeException) { return }
        if (parsed.year !in 2020..2100) return
        sample = Sample(parsed.toEpochSecond(), elapsedMillis())
    }

    fun nowSeconds(): Long? {
        val anchor = sample ?: return null
        val elapsed = elapsedMillis() - anchor.elapsedMillis
        if (elapsed !in 0..120_000) return null
        return anchor.epochSeconds + elapsed / 1000
    }
}

object TelegramFreshness {
    enum class Rejection(val reply: String) {
        UNKNOWN_TIME("Не удалось проверить время Telegram. Команда с действием не выполнена. Помощь, статус и /stop доступны; проверь соединение и пришли команду заново."),
        INVALID_DATE("У сообщения нет корректного времени Telegram. Команда с действием не выполнена; пришли её заново."),
        TOO_OLD("Этой команде больше пяти минут по времени Telegram. Я её не выполнял. Пришли команду ещё раз."),
        FUTURE_DATE("Время сообщения не согласуется с ответом Telegram. Команда с действием не выполнена; пришли её заново.")
    }

    fun rejection(message: JSONObject, serverNow: Long?): Rejection? {
        val text = message.optString("text").trim()
        val command = text.substringBefore(' ').substringBefore('@').lowercase(Locale.ROOT)
        val natural = text.lowercase(Locale.ROOT).trimEnd('?', '.', '!')
        // Recovery commands cannot create diary entries or drive Android actions.
        if (command in setOf("/help", "/start", "/status", "/stop", "/cancel") ||
            natural in setOf("помощь", "привет", "статус", "отмена")) return null
        if (serverNow == null) return Rejection.UNKNOWN_TIME
        val sent = message.optLong("date")
        if (sent <= 0) return Rejection.INVALID_DATE
        val age = serverNow - sent
        return when {
            age < -30 -> Rejection.FUTURE_DATE
            age > 300 -> Rejection.TOO_OLD
            else -> null
        }
    }
}
