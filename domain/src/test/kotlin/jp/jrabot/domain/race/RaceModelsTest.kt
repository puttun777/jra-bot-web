package jp.jrabot.domain.race

import jp.jrabot.domain.tenant.TenantId
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RaceModelsTest {
    private val tenant = TenantId.of(1)
    private val raceId = RaceId.parse("202401010611")

    @Test
    fun `HorseNumber は 1 から 99`() {
        assertEquals(1, HorseNumber.of(1).value)
        assertEquals(99, HorseNumber.of(99).value)
        for (bad in listOf(0, -1, 100, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertNull(HorseNumber.ofOrNull(bad), "$bad")
            assertFailsWith<IllegalArgumentException> { HorseNumber.of(bad) }
        }
    }

    @Test
    fun `Race は旧データのように回と日目と発走時刻が無くても作れ RaceId から日付などを得る`() {
        val race = Race(tenant, raceId, kai = null, nichime = null, postTime = null, name = null, distanceMeters = null)
        assertEquals(LocalDate.of(2024, 1, 1), race.date)
        assertEquals(Venue.NAKAYAMA, race.venue)
        assertEquals(11, race.raceNo)
        assertNull(race.postTime)
    }

    @Test
    fun `Race の不正な項目は拒否する`() {
        fun race(
            kai: Int? = null,
            nichime: Int? = null,
            distance: Int? = null,
        ) = Race(tenant, raceId, kai, nichime, LocalTime.of(15, 35), "テスト", distance)
        assertFailsWith<IllegalArgumentException> { race(kai = 0) }
        assertFailsWith<IllegalArgumentException> { race(nichime = 100) }
        assertFailsWith<IllegalArgumentException> { race(distance = 0) }
        assertEquals(1200, race(distance = 1200).distanceMeters)
    }

    @Test
    fun `RaceSchedule は発走時刻が不明なら時刻を持てない`() {
        val now = Instant.parse("2026-11-01T00:00:00Z")

        fun schedule(
            at: Instant?,
            unknown: Boolean,
            version: Int = 0,
        ) = RaceSchedule(tenant, raceId, at, unknown, RaceScheduleStatus.ANNOUNCED, '1', "20261031" + "1", null, now, version)
        assertEquals(now, schedule(now, false).scheduledPostAt)
        assertNull(schedule(null, true).scheduledPostAt)
        assertNull(schedule(null, false).scheduledPostAt) // 未確定（不明フラグなし）
        assertFailsWith<IllegalArgumentException> { schedule(now, true) }
        assertFailsWith<IllegalArgumentException> { schedule(now, false, version = -1) }
    }
}
