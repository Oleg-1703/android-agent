package com.oleg.androidagent

import okhttp3.OkHttpClient
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

data class CommandReply(val text: String, val stop: Boolean = false)

class AgentCommands(
    private val store: StateStore,
    private val config: JSONObject,
    private val diary: DiaryApi,
    private val client: OkHttpClient,
    private val ui: FatUi? = null
) {
    private val engine = MealEngine(store, diary)
    private val apiConnected: Boolean get() = config.optString("fs_token").isNotBlank() && config.optString("fs_token_secret").isNotBlank()
    private fun requireDiary() {
        require(apiConnected) {
            "Telegram работает. Управление установленным FatSecret через экран ещё в разработке и недоступно в этой сборке. Developer-ключи для этого сценария не нужны. Подключение через API - отдельный экспериментальный режим."
        }
    }
    fun handle(message: JSONObject, telegram: TelegramApi): CommandReply {
        val text = message.optString("text", message.optString("caption")).trim()
        val command = text.substringBefore(' ').substringBefore('@').lowercase(Locale.ROOT)
        val natural = text.lowercase(Locale.ROOT).trimEnd('?', '.', '!')
        if (ui != null && !config.optBoolean("legacy_api_mode")) {
            return handleUi(text, command, natural)
        }
        when {
            command in setOf("/start", "/help") || natural in setOf("помощь", "привет") -> return CommandReply(
                "Я на связи. Управление идёт прямо с Android.\n\n" +
                    "/today - дневник за сегодня\n/status - состояние\n/cancel - отменить подготовленную еду\n/stop - остановить агента\n\n" +
                    if (!apiConnected) "Управление установленным FatSecret через экран ещё в разработке и недоступно в этой сборке. Developer-ключи для него не нужны. Сейчас можно проверить связь и управление агентом командами выше."
                    else "Экспериментальный API-режим. Добавить еду без ИИ:\n/meal ${LocalDate.now()} lunch рис варёный=150;курица=200\n" +
                    "Приём пищи: breakfast (завтрак), lunch (обед), dinner (ужин), other (перекус).\n" +
                    "Я предложу продукты для /pick, затем попрошу /confirm с кодом. Без подтверждения запись не появится. " +
                    "Фото и голос доступны после отдельной настройки ИИ на телефоне."
            )
            command == "/status" || natural == "статус" -> return CommandReply(
                "Агент работает. " + (if (store.get("write_intent").isNotBlank())
                    "Есть непроверенная запись: проверь дневник и сними блокировку в приложении. " else "Непроверенных записей нет. ") +
                    (if (!apiConnected) "Управление экраном FatSecret ещё в разработке. " else "Экспериментальный API FatSecret настроен. ") + store.get("last_result")
            )
            command == "/stop" -> return CommandReply("Агент остановлен. Запустить снова можно в приложении на телефоне.", stop = true)
            command == "/cancel" || natural == "отмена" -> return CommandReply(engine.cancel())
            command == "/today" || natural in setOf("сегодня", "дневник", "что я сегодня ел", "что я ел сегодня") -> {
                requireDiary()
                val today = LocalDate.now().toString()
                val entries = diary.entries(today)
                if (entries.isEmpty()) return CommandReply("В дневнике за $today пока нет записей.")
                val lines = entries.take(60).map { entry ->
                    val name = entry.optString("food_entry_name", entry.optString("food_name", "Продукт")).take(120)
                    val calories = entry.optString("calories").takeIf { it.toBigDecimalOrNull() != null }
                    "• $name" + (calories?.let { " - $it ккал" } ?: "")
                }
                return CommandReply("Дневник за $today (${entries.size}):\n" + lines.joinToString("\n") +
                    if (entries.size > 60) "\nОстальные записи - в FatSecret." else "")
            }
            command == "/pick" -> {
                requireDiary()
                val parts = text.split(Regex("\\s+"))
                require(parts.size == 3 && parts[1].toIntOrNull() != null && parts[2].toIntOrNull() != null) { "Выбери продукт: /pick НОМЕР_ПРОДУКТА НОМЕР_ВАРИАНТА, например /pick 1 2" }
                return CommandReply(engine.pick(parts[1].toInt(), parts[2].toInt()))
            }
            command == "/confirm" -> {
                requireDiary()
                return CommandReply(engine.confirm(text.substringAfter(' ', "").trim()))
            }
            command == "/meal" -> {
                requireDiary()
                return CommandReply(engine.prepare(Meals.parse("/meal " + text.substringAfter(' ', ""))))
            }
            text.startsWith("/") -> return CommandReply("Не знаю эту команду. Нажми «Помощь» или отправь /help.")
        }
        requireDiary()
        require(config.optBoolean("ai_consent")) {
            "Фото, голос и свободный текст требуют отдельной настройки ИИ в приложении. Без ИИ можно так:\n/meal ${LocalDate.now()} lunch рис варёный=150"
        }
        val ai = AiMeals(config, client)
        val voice = message.optJSONObject("voice")
        if (voice != null) {
            require(voice.optInt("duration") in 1..120) { "Голосовое сообщение - до 120 секунд." }
            val bytes = telegram.download(voice.getString("file_id"), voice.optLong("file_size"), 8_000_000)
            return CommandReply(engine.prepare(ai.parse(ai.transcribe(bytes))))
        }
        val photo = message.objects("photo").filter { it.optLong("file_size") in 1..4_000_000 }.lastOrNull()
        if (message.has("photo")) {
            require(photo != null) { "Размер фотографии - до 4 МБ." }
            val bytes = telegram.download(photo.getString("file_id"), photo.optLong("file_size"), 4_000_000)
            return CommandReply(engine.prepare(ai.parse(text, bytes)))
        }
        require(text.isNotBlank()) { "Пришли текст, фото с весом продуктов или голосовое сообщение." }
        return CommandReply(engine.prepare(ai.parse(text)))
    }

    private fun handleUi(text: String, command: String, natural: String): CommandReply {
        val screen = requireNotNull(ui)
        val meals = UiMealEngine(store, screen)
        return when {
            command in setOf("/start", "/help") || natural in setOf("помощь", "привет") -> CommandReply(
                "Управляю установленным FatSecret. Developer-ключи не нужны. Телефон должен быть разблокирован, специальные возможности Android Agent включены.\n\n" +
                    "/today - сводка дневника\n/meal ${LocalDate.now()} lunch banana=100 - найти продукт и подготовить порцию\n" +
                    "/pick 1 НОМЕР - выбрать продукт\n/confirm КОД - сохранить после проверки\n/cancel - отмена\n/status - состояние\n/stop - остановка\n\n" +
                    "По одному продукту, только сегодня. breakfast - завтрак, lunch - обед, dinner - ужин, other - перекус. ИИ по подписке на ПК подключается отдельно; API-ключ не требуется."
            )
            command == "/status" || natural == "статус" -> CommandReply(
                "Telegram на связи. FatSecret: управление экраном. " +
                    (if (FatAccessibilityService.instance != null) "Специальные возможности включены. " else "Включи специальные возможности Android Agent. ") +
                    (if (store.get("write_intent").isNotBlank()) "Есть непроверенная запись. Проверь дневник и сними блокировку в приложении." else "Непроверенных записей нет.")
            )
            command == "/stop" -> CommandReply("Агент остановлен. Запуск - в приложении.", stop = true)
            command == "/cancel" || natural == "отмена" -> CommandReply(meals.cancel())
            command == "/today" || natural in setOf("сегодня", "дневник", "что я сегодня ел", "что я ел сегодня") -> CommandReply(screen.today())
            command == "/meal" -> CommandReply(meals.prepare(Meals.parse(text)))
            command == "/pick" -> {
                val parts = text.split(Regex("\\s+"))
                require(parts.size == 3) { "Выбор: /pick 1 НОМЕР" }
                CommandReply(meals.pick(requireNotNull(parts[1].toIntOrNull()), requireNotNull(parts[2].toIntOrNull())))
            }
            command == "/confirm" -> CommandReply(meals.confirm(text.substringAfter(' ', "").trim()))
            text.startsWith("/") -> CommandReply("Не знаю эту команду. Пришли /help.")
            text.isNotBlank() -> CommandReply(meals.prepare(PcBrain(config, client).parse(text)))
            else -> CommandReply("Пришли текст еды с весом и приёмом пищи. Фото и голос пока не подключены; API-ключ покупать не нужно.")
        }
    }
}
