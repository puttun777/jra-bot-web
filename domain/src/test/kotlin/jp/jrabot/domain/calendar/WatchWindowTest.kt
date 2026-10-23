package jp.jrabot.domain.calendar

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WatchWindowTest {
    private val first = Cal.jst(Cal.DATE, 9, 55)
    private val last = Cal.jst(Cal.DATE, 16, 25)
    private val w = WatchWindow(first, last, Cal.WATCH)

    private val opens = first - Duration.ofMinutes(130)
    private val closes = last + Duration.ofMinutes(90)

    @Test
    fun `起動してよい時刻・監視開始・期限の計算`() {
        assertEquals(opens, w.launchOpensAt)
        assertEquals(first - Duration.ofMinutes(120), w.watchStartsAt)
        assertEquals(closes, w.settleDeadlineAt)
    }

    @Test
    fun `開始の境界（1 秒前・ちょうど・1 秒後）`() {
        assertEquals(WindowPhase.BEFORE, w.phase(opens.minusSeconds(1)))
        assertEquals(WindowPhase.OPEN, w.phase(opens))
        assertEquals(WindowPhase.OPEN, w.phase(opens.plusSeconds(1)))
    }

    @Test
    fun `終了の境界（1 秒前・ちょうど・1 秒後）`() {
        assertEquals(WindowPhase.OPEN, w.phase(closes.minusSeconds(1)))
        assertEquals(WindowPhase.OPEN, w.phase(closes))
        assertEquals(WindowPhase.AFTER, w.phase(closes.plusSeconds(1)))
    }

    @Test
    fun `マイクロ秒単位でも境界が正しい`() {
        assertEquals(WindowPhase.BEFORE, w.phase(opens.minusNanos(1000)))
        assertEquals(WindowPhase.AFTER, w.phase(closes.plusNanos(1000)))
    }

    @Test
    fun `prestart が 0 なら監視開始と同じ時刻に起動できる`() {
        val z = WatchWindow(first, last, WatchSettings(Duration.ofMinutes(120), Duration.ZERO, Duration.ofMinutes(90)))
        assertEquals(z.watchStartsAt, z.launchOpensAt)
    }

    @Test
    fun `発走が 1 レースだけでも窓を作れる`() {
        val one = WatchWindow(first, first, Cal.WATCH)
        assertEquals(WindowPhase.OPEN, one.phase(first))
    }

    @Test
    fun `不正な設定と窓を拒否する`() {
        assertFailsWith<IllegalArgumentException> { WatchSettings(Duration.ZERO, Duration.ZERO, Duration.ofMinutes(1)) }
        assertFailsWith<IllegalArgumentException> { WatchSettings(Duration.ofMinutes(1), Duration.ofMinutes(-1), Duration.ofMinutes(1)) }
        assertFailsWith<IllegalArgumentException> { WatchSettings(Duration.ofMinutes(1), Duration.ZERO, Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { WatchWindow(last, first, Cal.WATCH) }
        assertFailsWith<IllegalArgumentException> { CalendarSettings(Cal.WATCH, Duration.ZERO) }
    }

    @Test
    fun `日付が変わる前後（JST 0 時）でも瞬間の比較で決まる`() {
        // JST 2026-10-25 00:00:00 = UTC 2026-10-24 15:00:00
        val midnight = Instant.parse("2026-10-24T15:00:00Z")
        assertEquals(midnight, Cal.jst(Cal.DATE.plusDays(1), 0, 0))
        val early =
            WatchWindow(
                Cal.jst(Cal.DATE.plusDays(1), 0, 30),
                Cal.jst(Cal.DATE.plusDays(1), 1, 0),
                WatchSettings(Duration.ofMinutes(60), Duration.ZERO, Duration.ofMinutes(30)),
            )
        assertEquals(WindowPhase.BEFORE, early.phase(midnight.minusSeconds(30 * 60 + 1)))
        assertEquals(WindowPhase.OPEN, early.phase(midnight.minusSeconds(30 * 60)))
    }
}
