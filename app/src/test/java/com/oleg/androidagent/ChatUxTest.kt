package com.oleg.androidagent

import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatUxTest {
    private fun row(name: String, grams: String, kcal: Int, p: Double, f: Double, c: Double, estimate: Boolean = false) =
        JSONObject().put("name", name).put("grams", grams).put("kcal", kcal).put("protein", p).put("fat", f).put("carbs", c)
            .put("estimate", estimate).put("catalog", false)
    private fun table(vararg rows: JSONObject, total: JSONObject = JSONObject().put("grams", "300").put("kcal", 430).put("protein", 19.0).put("fat", 24.0).put("carbs", 33.0)) =
        JSONObject().put("rows", JSONArray(rows.toList())).put("total", total).put("skipped_catalog", 0)
    private val sample = table(row("Пюре", "200", 180, 4.0, 6.0, 28.0, true), row("Котлета", "100", 250, 15.0, 18.0, 5.0, true))

    // --- table formatting ---
    @Test fun draftMatchesTheAgreedLayout() {
        val html = MealTable.html(sample, "Обед — проверь расчёт", "заметка", MealTable.QUESTION)
        val expected = "<b>Обед — проверь расчёт</b>\n<pre>" + listOf(
            "Позиция       г  ккал    Б    Ж    У",
            "Пюре*       200   180  4.0  6.0 28.0",
            "Котлета*    100   250 15.0 18.0  5.0",
            "─".repeat(36),
            "Итого       300   430 19.0 24.0 33.0").joinToString("\n") +
            "</pre>\n* оценка\n<i>заметка</i>\n\nЗаписать так? Нажми ✅ или скажи, что поправить."
        assertEquals(expected, html)
    }

    @Test fun longNamesAreTruncatedAndAlignmentHolds() {
        val html = MealTable.html(table(row("Куриная грудка на гриле", "150", 247, 46.5, 5.4, 0.0, true)), "t")
        val line = html.lines().first { it.startsWith("Куриная г") }
        assertTrue(line, line.startsWith("Куриная г…*"))
        assertEquals(11, line.indexOf("…*") + 2)
        val widths = html.substringAfter("<pre>").substringBefore("</pre>").lines().map { it.length }
        assertEquals(widths.first(), widths.last())
    }

    @Test fun everyDynamicStringIsEscaped() {
        val html = MealTable.html(table(row("<u>Т&Т</u>", "100", 50, 1.0, 1.0, 1.0)), "Обед <script>", "a < b && c > d", "q?", listOf("http://x.test/?a=1&b=<2>"))
        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("<u>"))
        assertTrue(html.contains("&lt;u&gt;Т&amp;Т&lt;/u&gt;"))
        assertTrue(html.contains("<i>a &lt; b &amp;&amp; c &gt; d</i>"))
        assertTrue(html.contains("http://x.test/?a=1&amp;b=&lt;2&gt;"))
        assertEquals(1, Regex("<pre>").findAll(html).count())
    }

    @Test fun catalogRowsShowCardAndTotalIsAlwaysPresent() {
        val catalog = JSONObject().put("name", "Ролл").put("grams", JSONObject.NULL).put("kcal", JSONObject.NULL).put("protein", JSONObject.NULL)
            .put("fat", JSONObject.NULL).put("carbs", JSONObject.NULL).put("estimate", false).put("catalog", true)
        val html = MealTable.html(table(catalog, total = JSONObject().put("grams", "0").put("kcal", 0).put("protein", 0.0).put("fat", 0.0).put("carbs", 0.0)), "t")
        assertTrue(html.lines().first { it.startsWith("Ролл") }.contains("карт."))
        assertTrue(html.lines().any { it.startsWith("Итого") })
        assertTrue(html.contains("карт. —"))
        assertFalse(html.contains("* оценка"))
    }

    @Test fun oversizedNotesKeepTheTableAndStayInOneMessage() {
        val html = MealTable.html(sample, "t", "я".repeat(9000), MealTable.QUESTION)
        assertTrue(html.length <= 4000)
        assertTrue(html.contains("<pre>") && html.contains("</pre>"))
        assertTrue(html.endsWith(MealTable.QUESTION))
    }

    @Test fun draftAndReceiptBuildersNeedATableAndMarkTheirOutput() {
        assertNull(MealTable.draft(JSONObject().put("message", "x")))
        val draft = MealTable.draft(JSONObject().put("table", sample).put("meal", "dinner").put("message", "n"))!!
        assertTrue(MealTable.isMarked(draft))
        assertTrue(MealTable.unmark(draft).startsWith("<b>Ужин — проверь расчёт</b>"))
        val receipt = MealTable.receipt(JSONObject().put("table", sample).put("meal", "lunch").put("notes", "n").put("sources", JSONArray().put("https://a.test/x")))!!
        assertTrue(MealTable.unmark(receipt).startsWith("<b>✅ Записано в FatSecret: Обед</b>"))
        assertTrue(receipt.endsWith("https://a.test/x"))
    }

    @Test fun joiningEscapesPlainPartsOfAnHtmlMessage() {
        val joined = MealTable.join(listOf(MealTable.mark("<b>x</b>"), "ждёт <экран> & ключ"))
        assertEquals(MealTable.mark("<b>x</b>\n\nждёт &lt;экран&gt; &amp; ключ"), joined)
        assertEquals("a\n\nb", MealTable.join(listOf("a", "b")))
    }

    // --- callbacks ---
    private val owner = 777L
    private fun callback(data: String = "meal:confirm", from: Long = owner, chat: Long = owner, date: Long = 10_000, id: String = "cb1", bot: Boolean = false, type: String = "private") =
        JSONObject().put("id", id).put("data", data).put("from", JSONObject().put("id", from).put("is_bot", bot))
            .put("message", JSONObject().put("message_id", 55).put("date", date).put("chat", JSONObject().put("id", chat).put("type", type)))

    @Test fun ownerPressInTheOwnerChatIsAccepted() {
        assertEquals(TelegramCallbacks.Decision.Accept(true), TelegramCallbacks.decide(callback(), owner, 10_100))
        assertEquals(TelegramCallbacks.Decision.Accept(false), TelegramCallbacks.decide(callback("meal:cancel"), owner, 10_100))
    }

    @Test fun strangersOtherChatsBotsAndUnknownDataAreIgnored() {
        listOf(callback(from = 1), callback(chat = 1), callback(bot = true), callback(type = "group"), callback("meal:other"), callback("")).forEach {
            assertEquals(TelegramCallbacks.Decision.Ignore, TelegramCallbacks.decide(it, owner, 10_100))
        }
        assertEquals(TelegramCallbacks.Decision.Ignore, TelegramCallbacks.decide(callback().apply { remove("message") }, owner, 10_100))
        assertEquals(TelegramCallbacks.Decision.Ignore, TelegramCallbacks.decide(callback(), 0, 10_100))
    }

    @Test fun confirmOnAMessageOlderThanTwoHoursOrOfUnknownAgeIsStale() {
        assertEquals(TelegramCallbacks.Decision.Accept(true), TelegramCallbacks.decide(callback(), owner, 10_000 + 7200))
        assertEquals(TelegramCallbacks.Decision.Stale, TelegramCallbacks.decide(callback(), owner, 10_000 + 7201))
        assertEquals(TelegramCallbacks.Decision.Stale, TelegramCallbacks.decide(callback(), owner, null))
        assertEquals(TelegramCallbacks.Decision.Stale, TelegramCallbacks.decide(callback(date = 0), owner, 10_000))
        assertEquals(TelegramCallbacks.Decision.Accept(false), TelegramCallbacks.decide(callback("meal:cancel"), owner, 10_000 + 99_999))
    }

    @Test fun onlyTheNewestUnusedDraftMessageIsConfirmable() {
        assertTrue(TelegramCallbacks.isCurrentDraft(55, "55"))
        assertFalse(TelegramCallbacks.isCurrentDraft(55, "56"))   // newer draft exists
        assertFalse(TelegramCallbacks.isCurrentDraft(55, ""))     // already used, cancelled or answered
        assertFalse(TelegramCallbacks.isCurrentDraft(0, "0"))
    }

    @Test fun plainFallbackOfFormatterHtmlKeepsTheTextAndDropsTheMarkup() {
        val html = MealTable.html(table(row("A<b>&Co", "10", 1, 1.0, 1.0, 1.0)), "Обед — проверь расчёт", "n & m", MealTable.QUESTION)
        val plain = MealTable.plain(html)
        assertFalse(plain.contains("<pre>") || plain.contains("</i>") || plain.contains("&amp;"))
        assertTrue(plain.contains("A<b>&Co"))
        assertTrue(plain.contains("n & m"))
    }

    @Test fun pressedConfirmIsTheOwnerWriteTextWithAStableUniqueIdentity() {
        val a = TelegramCallbacks.asMessage(callback(id = "A"), true)
        assertEquals(TelegramApi.CONFIRM_BUTTON, a.getString("text"))
        assertTrue(MealBatch.eligible(a))
        assertEquals(a.getLong("message_id"), TelegramCallbacks.asMessage(callback(id = "A"), true).getLong("message_id"))
        assertNotEquals(a.getLong("message_id"), TelegramCallbacks.asMessage(callback(id = "B"), true).getLong("message_id"))
        assertTrue(a.getLong("message_id") > 1L shl 32)
        val cancel = TelegramCallbacks.asMessage(callback("meal:cancel"), false)
        assertEquals("Отмена", cancel.getString("text"))
        assertFalse(MealBatch.eligible(cancel))
    }

    @Test fun confirmPressReachesThePcAsTheWriteTextWithRichTableCapability() {
        val config = JSONObject().put("pc_url", "https://pc.example.invalid/").put("pc_pairing", "fixture-pairing-".repeat(3))
            .put("pc_consent", true).put("bot_token", "1:" + "x".repeat(24)).put("chat_id", "777")
        val bodies = mutableListOf<JSONObject>()
        val progress = mutableListOf<String>()
        val client = Http.client().newBuilder().addInterceptor { chain ->
            val buffer = okio.Buffer()
            chain.request().body?.writeTo(buffer)
            bodies += JSONObject(buffer.readUtf8())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(null, """{"status":"failed","message":"готово"}""")).build()
        }.build()
        val api = TelegramApi("1:" + "x".repeat(24), client)
        fun press(id: String): String {
            val message = TelegramCallbacks.asMessage(callback(id = id), true)
            val batch = listOf(JSONObject(message.toString()))
            return PcBrain(config, client).submit(batch, api, progress = { progress += it }) { JSONObject() }
        }
        assertEquals("готово", press("A"))
        press("A"); press("B")
        assertEquals("✅ Записать", bodies[0].getString("text"))
        assertEquals("rich_table", bodies[0].getJSONArray("capabilities").getString(0))
        assertEquals(bodies[0].getString("request_id"), bodies[1].getString("request_id"))
        assertNotEquals(bodies[0].getString("request_id"), bodies[2].getString("request_id"))
        assertTrue("a finished answer needs no progress", progress.isEmpty())
    }

    // --- quiet acknowledgements ---
    @Test fun textGetsNoAckAndMediaGetsOneThatNeverPromisesAWrite() {
        assertNull(MealBatch.ack(listOf(JSONObject().put("text", "ок"))))
        assertNull(MealBatch.ack(listOf(JSONObject().put("text", "✅ Записать"))))
        assertNull(MealBatch.ack(listOf(JSONObject().put("text", "поправь: 150 г"))))
        assertEquals("Жду голосовой комментарий к фото (до 2,5 мин) или напиши подпись.", MealBatch.ack(listOf(JSONObject().put("photo", JSONArray()))))
        assertEquals("Принял фото, разбираю.", MealBatch.ack(listOf(JSONObject().put("photo", JSONArray()).put("caption", "обед"))))
        assertEquals("Принял голосовое, разбираю.", MealBatch.ack(listOf(JSONObject().put("voice", JSONObject()))))
        listOf("photo", "voice").forEach {
            val text = MealBatch.ack(listOf(JSONObject().put(it, if (it == "photo") JSONArray() else JSONObject()).put("caption", "c")))!!
            assertFalse(text.contains("запишу", ignoreCase = true) || text.contains("FatSecret"))
        }
    }

    @Test fun progressIsSilentUntilSixSecondsThenNeutralAndSparse() {
        assertNull(PcBrain.Progress.message(0))
        assertEquals("Думаю…", PcBrain.Progress.message(1))
        assertEquals((2..19).mapNotNull { PcBrain.Progress.message(it) }, emptyList<String>())
        assertEquals("Всё ещё считаю, отвечу здесь.", PcBrain.Progress.message(20))
        assertNull(PcBrain.Progress.message(30))
        assertEquals("Всё ещё считаю, отвечу здесь.", PcBrain.Progress.message(40))
        assertEquals(60_000L, 20 * PcBrain.Progress.POLL_MILLIS)
    }
}
