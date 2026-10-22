package jp.jrabot.domain.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** テスト用の固定時計。`:domain` のテストの中だけで使う（他モジュールで必要になったら testFixtures へ移す）。 */
class FixedClock(
    private var instant: Instant,
    override val zone: ZoneId = Zones.JST,
) : Clock {
    override fun now(): Instant = instant

    fun set(newInstant: Instant) {
        instant = newInstant
    }
}

/** [Clock.zone] を上書きしない実装（既定の地域を確認する）。 */
private object DefaultZoneClock : Clock {
    override fun now(): Instant = Instant.EPOCH
}

class ClockTest {
    @Test
    fun `既定の地域は Asia Tokyo で 保存の基準は UTC`() {
        assertEquals("Asia/Tokyo", Zones.JST.id)
        assertEquals(ZoneId.of("Asia/Tokyo"), DefaultZoneClock.zone) // zone を上書きしない実装の既定値
        assertEquals("UTC", Zones.UTC.id)
    }

    @Test
    fun `固定時計は常に同じ瞬間を返す`() {
        val clock = FixedClock(Instant.parse("2026-11-01T01:23:45.123456Z"))
        assertEquals(clock.now(), clock.now())
        clock.set(Instant.parse("2026-11-01T02:00:00Z"))
        assertEquals(Instant.parse("2026-11-01T02:00:00Z"), clock.now())
    }

    @Test
    fun `UTC の 15 時は JST の翌日 0 時になり 開催日の判定は JST`() {
        val before = FixedClock(Instant.parse("2026-10-31T14:59:59.999999Z"))
        val after = FixedClock(Instant.parse("2026-10-31T15:00:00Z"))
        assertEquals(LocalDate.of(2026, 10, 31), before.today())
        assertEquals(LocalDate.of(2026, 11, 1), after.today())
        // 同じ瞬間の UTC の暦日は 1 日遅い
        assertEquals(LocalDate.of(2026, 10, 31), after.now().atZone(Zones.UTC).toLocalDate())
        assertEquals(0, after.nowInZone().hour)
        assertEquals(9 * 3600, after.nowInZone().offset.totalSeconds)
    }

    @Test
    fun `年またぎも JST で判定する`() {
        assertEquals(LocalDate.of(2027, 1, 1), FixedClock(Instant.parse("2026-12-31T15:00:00Z")).today())
        assertEquals(LocalDate.of(2026, 12, 31), FixedClock(Instant.parse("2026-12-31T14:59:59Z")).today())
    }

    @Test
    fun `JST の現地時刻を UTC の瞬間へ戻すと 9 時間前`() {
        val jst = LocalDate.of(2026, 11, 1).atTime(15, 35).atZone(Zones.JST)
        assertEquals(Instant.parse("2026-11-01T06:35:00Z"), jst.toInstant())
    }

    @Test
    fun `別の地域を指定した時計は その地域で暦日を出す`() {
        val utc = FixedClock(Instant.parse("2026-10-31T15:00:00Z"), Zones.UTC)
        assertEquals(LocalDate.of(2026, 10, 31), utc.today())
    }

    @Test
    fun `システム時計はマイクロ秒に切り捨てた現在時刻を返す`() {
        val before = Instant.now()
        val now = SystemClock.now()
        val after = Instant.now()
        assertEquals(0, now.nano % 1000, "DATETIME(6) の精度（マイクロ秒）")
        assertTrue(!now.isBefore(before.minusNanos(1000)) && !now.isAfter(after))
        assertEquals(Zones.JST, SystemClock.zone)
    }
}
