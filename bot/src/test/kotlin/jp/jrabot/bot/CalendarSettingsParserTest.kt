package jp.jrabot.bot

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarSettingsParserTest {
    private val ok =
        mapOf(
            "CALENDAR_WATCH_START_MINUTES" to "120",
            "CALENDAR_PRESTART_MINUTES" to "10",
            "CALENDAR_SETTLE_DEADLINE_MINUTES" to "90",
            "CALENDAR_STALE_AFTER_MINUTES" to "120",
        )

    @Test
    fun `環境変数から分単位で読む`() {
        val s = CalendarSettingsParser.fromEnv(ok)
        assertEquals(Duration.ofMinutes(120), s.watch.watchStart)
        assertEquals(Duration.ofMinutes(10), s.watch.prestart)
        assertEquals(Duration.ofMinutes(90), s.watch.settleDeadline)
        assertEquals(Duration.ofMinutes(120), s.staleAfter)
    }

    @Test
    fun `運用の既定値は無く、1 つでも欠ければ（名前だけを示して）拒否する`() {
        for (name in ok.keys) {
            val e = assertFailsWith<IllegalStateException>(name) { CalendarSettingsParser.fromEnv(ok - name) }
            assertTrue(e.message!!.contains(name), e.message)
            assertFailsWith<IllegalStateException>(name) { CalendarSettingsParser.fromEnv(ok + (name to "  ")) }
        }
    }

    @Test
    fun `不正な値は拒否し、メッセージに値を含めない`() {
        for (bad in listOf("abc", "-1", "1.5", "0x10", "１２０", "1440000", "99999999999999999999")) {
            val e = assertFailsWith<IllegalStateException>(bad) { CalendarSettingsParser.fromEnv(ok + ("CALENDAR_WATCH_START_MINUTES" to bad)) }
            assertFalse(e.message!!.contains(bad), e.message)
        }
        assertFailsWith<IllegalStateException> { CalendarSettingsParser.fromEnv(ok + ("CALENDAR_WATCH_START_MINUTES" to "0")) }
        assertFailsWith<IllegalStateException> { CalendarSettingsParser.fromEnv(ok + ("CALENDAR_SETTLE_DEADLINE_MINUTES" to "0")) }
        assertFailsWith<IllegalStateException> { CalendarSettingsParser.fromEnv(ok + ("CALENDAR_WATCH_START_MINUTES" to "1441")) }
        assertEquals(Duration.ZERO, CalendarSettingsParser.fromEnv(ok + ("CALENDAR_PRESTART_MINUTES" to "0")).watch.prestart)
    }

    @Test
    fun `calendar-check だけが設定を必須にする（他のタスクは無くても通り、設定は null）`() {
        val base = baseEnv("/dev/null", *ok.keys.map { it to null }.toTypedArray())
        assertNull(BotSettings.from(base, BotTask.RECONCILE).calendar)
        // bot-run も監視ウィンドウを必須にする（T3.8。設定は botRun の中にあり、calendar-check 用の calendar は null）
        assertFailsWith<IllegalStateException> { BotSettings.from(base, BotTask.BOT_RUN) }
        assertNull(BotSettings.from(baseEnv("/dev/null"), BotTask.BOT_RUN).calendar)
        assertFailsWith<IllegalStateException> { BotSettings.from(base, BotTask.CALENDAR_CHECK) }
        assertEquals(
            Duration.ofMinutes(120),
            BotSettings
                .from(baseEnv("/dev/null"), BotTask.CALENDAR_CHECK)
                .calendar!!
                .watch.watchStart,
        )
    }
}
