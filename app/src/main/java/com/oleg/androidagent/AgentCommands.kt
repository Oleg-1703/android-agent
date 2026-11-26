package com.oleg.androidagent

import okhttp3.OkHttpClient
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

data class CommandReply(val text: String, val stop: Boolean = false, val confirm: Boolean = false, val html: Boolean = false) {
    companion object {
        /** Wraps a PcBrain string: HTML-marked text is sent with parse_mode HTML, anything else stays plain. */
        fun of(text: String, confirm: Boolean = false) =
            if (MealTable.isMarked(text)) CommandReply(MealTable.unmark(text), confirm = confirm, html = true) else CommandReply(text, confirm = confirm)
    }
}

class AgentCommands(
    private val store: StateStore,
    private val config: JSONObject,
    private val diary: DiaryApi,
    private val client: OkHttpClient,
    private val ui: FatUi? = null
) {
    init {
        if (config.optBoolean("pc_consent")) ui?.candidateResolver = { item, rows, saved ->
            PcBrain(config, client, store).resolveFood(item, rows, saved)
        }
    }
    fun hasPendingPhone() = config.optBoolean("pc_consent") && PcBrain(config, client, store).hasPendingPhone()
    private fun progress(telegram: TelegramApi): (String) -> Unit = { status: String ->
        try { telegram.progress(config.getLong("chat_id"), status) }
        catch (_: java.io.IOException) { /* A lost progress message must not interrupt a diary write. */ }
    }.also { ui?.searchProgress = it }
    fun resumePhone(telegram: TelegramApi): List<CommandReply> {
        if (!hasPendingPhone()) return emptyList()
        val screen = requireNotNull(ui)
        return PcBrain(config, client, store).resumePhoneRendered({ screen.canExecute() },
            { screen.searchState + " " + screen.diaryRangeState }, { screen.withExecutionScreen(it) }) { plan ->
            CustomMealEngine(store, screen, progress = progress(telegram)).log(plan)
        }.map { CommandReply.of(it.text, it.confirm) }
    }
    private val engine = MealEngine(store, diary)
    private val apiConnected: Boolean get() = config.optString("fs_token").isNotBlank() && config.optString("fs_token_secret").isNotBlank()
    private fun requireDiary() {
        require(apiConnected) {
            "Telegram работает. Управление установленным FatSecret через экран ещё в разработке и недоступно в этой сборке. Developer-ключи для этого сценария не нужны. Подключение через API - отдельный экспериментальный режим."
        }
    }
    fun handle(message: JSONObject, telegram: TelegramApi): CommandReply {
        val text = message.optString("text", message.optString("caption")).trim()
        val command = text.takeWhile { !it.isWhitespace() }.substringBefore('@').lowercase(Locale.ROOT)
        val natural = text.lowercase(Locale.ROOT).trimEnd('?', '.', '!')
        if (ui != null && !config.optBoolean("legacy_api_mode")) {
            return handleUi(message, telegram, text, command, natural)
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
                    "Последняя запись не сверена: проверь дневник. Новые запросы это не блокирует. " else "Непроверенных записей нет. ") +
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

    private fun handleUi(message: JSONObject, telegram: TelegramApi, text: String, command: String, natural: String): CommandReply {
        val screen = requireNotNull(ui)
        val brain = PcBrain(config, client, store)
        val batch = if (message.has("agent_batch")) message.objects("agent_batch") else listOf(message)
        if (config.optBoolean("pc_consent") && MealBatch.eligible(message)) return try {
            val submitted = brain.submit(batch, telegram, phoneStage = { screen.searchState + " " + screen.diaryRangeState }, progress = progress(telegram),
                phoneReady = { screen.canExecute() }, phoneSession = { screen.withExecutionScreen(it) }) { plan ->
                CustomMealEngine(store, screen, progress = progress(telegram)).log(plan)
            }
            // Any meal answer that is not a new draft (receipt, question, "ок") retires the buttons of the previous draft.
            if (!brain.lastConfirm) store.put("confirm_msg", "")
            CommandReply.of(submitted, brain.lastConfirm)
        }
            catch (_: java.io.IOException) { CommandReply("Не получил результат ПК. Запрос мог сохраниться на ПК. Не отправляй ту же еду повторно; проверь /status.") }
        if (config.optBoolean("pc_consent") && (command == "/status" || natural == "статус")) return CommandReply.of(brain.jobStatus())
        val meals = UiMealEngine(store, screen)
        return when {
            command in setOf("/start", "/help") || natural in setOf("помощь", "привет") -> CommandReply(
                "Записываю свою еду в твой FatSecret " + (if (config.optBoolean("pc_execute_on_phone")) "на этом телефоне" else "на эмуляторе ПК") + ". Claude работает по твоей подписке на ПК, платного API нет.\n\n" +
                    "1. Текст/голос с названием, весом и точными КБЖУ.\n2. Фото этикетки + голос, сколько съел.\n3. Фото блюда, с пояснением или без: КБЖУ будут оценены и помечены.\n4. Фото/цифры штрихкода + порция: проверю интернет-источник; если полного КБЖУ нет, не запишу.\n\n" +
                    "Можно без /meal. Фото и голос объединяются в один запрос. Точные данные (цифры или этикетка и сколько съел) записываю сразу. Оценённый приём (фото без цифр) сначала покажу по позициям: нажми «✅ Записать» или поправь голосом/текстом.\n" +
                    "Для упаковки КБЖУ без указанной основы считаю на 100 г и отмечаю это в ответе. Если это итог всей порции, скажи «всего» или «на всю порцию».\n\nУже есть карточка? «На ужин найди существующую еду Ролл с курицей small, 200 г». Использую точное совпадение с его КБЖУ, новый продукт не создаю. Без однозначного совпадения ничего не запишу.\n\n/status - результат последнего запроса\n/stop - остановка приёма сообщений.\n" +
                    (if (config.optBoolean("pc_execute_on_phone")) "ПК должен работать. Во время распознавания экран можно выключить. Готовая еда дождётся разблокировки, затем запишется автоматически. На время ввода удержу экран сам, максимум 2 минуты. Управление Android Agent должно быть включено."
                    else "ПК и эмулятор должны работать; открывать FatSecret на телефоне не нужно. Второй способ - запись на этом телефоне, включается на вкладке ИИ.")
            )
            command == "/status" || natural == "статус" -> CommandReply(
                "Telegram на связи. FatSecret: управление экраном. " +
                    (if (FatAccessibilityService.instance != null) "Специальные возможности включены. " else "Включи специальные возможности Android Agent. ") +
                    (if (store.get("write_intent").isNotBlank()) "Последняя запись не сверена: проверь дневник. Новые запросы это не блокирует." else "Непроверенных записей нет.")
            )
            command == "/stop" || natural == "останови агента" -> CommandReply("Агент остановлен. Запуск - в приложении.", stop = true)
            command == "/cancel" || natural in setOf("отмена", "отмени", "отмени черновик") -> {
                store.put("pc_dialogue", "")
                store.put("confirm_msg", "")
                if (config.optBoolean("pc_consent")) brain.cancelDraft()
                if (brain.hasPendingPhone()) CommandReply(brain.cancelPendingPhone().joinToString("\n\n"))
                else CommandReply(meals.cancel())
            }
            command == "/open" || natural in setOf("открой fatsecret", "открой фэтсекрет") -> {
                screen.diary()
                CommandReply("Открыл дневник FatSecret на телефоне.")
            }
            command == "/today" || natural in setOf("сегодня", "дневник", "что я сегодня ел", "что я ел сегодня", "покажи дневник", "сколько калорий сегодня") -> CommandReply(screen.today())
            command == "/meal" -> {
                store.put("pc_dialogue", "")
                val description = text.split(Regex("\\s+"), limit = 2).getOrNull(1).orEmpty().trim()
                if (description.isBlank()) CommandReply("Напиши одним сообщением: «На завтрак панини с моцареллой 200 граммов, на 100 граммов 209 ккал, белки 9,5, жиры 11, углеводы 18». С подключённым ПК можно без /meal, в том числе голосом. Без ПК: /meal завтрак панини с моцареллой=200")
                else if (config.optBoolean("pc_consent")) {
                    requireUiPlanning(screen)
                    CommandReply(meals.prepare(brain.parse(description)))
                } else CommandReply(meals.prepare(UiMeals.parse(text)))
            }
            command == "/pick" -> {
                val parts = text.split(Regex("\\s+"))
                require(parts.size == 3) { "Выбор: /pick 1 НОМЕР" }
                CommandReply(meals.pick(requireNotNull(parts[1].toIntOrNull()), requireNotNull(parts[2].toIntOrNull())))
            }
            command == "/confirm" -> CommandReply(meals.confirm(text.split(Regex("\\s+"), limit = 2).getOrNull(1).orEmpty().trim()))
            text.startsWith("/") -> CommandReply("Не знаю эту команду. Пришли /help.")
            message.has("voice") -> {
                requireUiPlanning(screen)
                require(config.optBoolean("pc_consent")) { "Включи согласие на ИИ по подписке в приложении. Платный API не нужен." }
                val voice = message.getJSONObject("voice")
                require(voice.optInt("duration") in 1..120) { "Голосовое сообщение - до 120 секунд." }
                val bytes = telegram.download(voice.getString("file_id"), voice.optLong("file_size"), 4_000_000)
                val draft = PcBrain(config, client, store).parse(text.ifBlank { "Разбери голосовое описание еды." }, voice = bytes)
                CommandReply("Распознал: ${draft.optString("transcript").take(500)}\n\n" + meals.prepare(draft))
            }
            message.has("photo") -> {
                requireUiPlanning(screen)
                require(config.optBoolean("pc_consent")) { "Включи согласие на ИИ по подписке в приложении. Платный API не нужен." }
                val photo = message.objects("photo").filter { it.optLong("file_size") in 1..4_000_000 }.lastOrNull()
                require(photo != null) { "Размер фотографии - до 4 МБ." }
                val bytes = telegram.download(photo.getString("file_id"), photo.optLong("file_size"), 4_000_000)
                CommandReply(meals.prepare(brain.parse(text.ifBlank { "Определи еду на фото. Если вес неизвестен, оцени его и явно отметь оценку." }, photo = bytes)))
            }
            text.isNotBlank() -> {
                requireUiPlanning(screen)
                CommandReply(meals.prepare(brain.parse(text)))
            }
            else -> CommandReply("Пришли текст, фото с подписью о весе и приёме пищи или голос. API-ключ не нужен.")
        }
    }
    private fun requireUiPlanning(screen: FatUi) {
        screen.requireReady()
    }
}
