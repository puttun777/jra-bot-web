package jp.jrabot.persistence.integration

import jp.jrabot.domain.calendar.ChangeResult
import jp.jrabot.domain.calendar.RaceDay
import jp.jrabot.domain.calendar.RaceDayStatus
import jp.jrabot.domain.calendar.ScheduleEventType
import jp.jrabot.domain.race.Venue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

@Tag("integration")
class RaceDayRepositoryIntegrationTest {
    companion object {
        private val db = MySqlSupport.migratedDatabase()
        private val h = RepositoryHarness(db, poolMax = 6)

        @JvmStatic
        @AfterAll
        fun close() = h.close()
    }

    @BeforeEach
    fun reset() = db.resetData()

    private fun applied(r: ChangeResult<RaceDay>) = assertIs<ChangeResult.Applied<RaceDay>>(r)

    @Test
    fun `挿入すると開催日と日単位の履歴が同時に残り、読み戻せる`() {
        val day = Fixtures.day()
        val r = applied(h.raceDays.insertIfAbsent(day, Fixtures.dayEvent()))
        assertEquals(day, r.value)
        assertEquals(day, h.raceDays.find(T1, Fixtures.DATE, Venue.TOKYO))
        assertEquals(listOf(day), h.raceDays.listByDate(T1, Fixtures.DATE))
        assertEquals(listOf(r.event), h.events.eventsOfDate(T1, Fixtures.DATE))
        assertNull(r.event.raceId)
        assertEquals("2026-10-24 00:00:00.500000", db.scalar("SELECT DATE_FORMAT(last_confirmed_at, '%Y-%m-%d %H:%i:%s.%f') FROM race_day"))
    }

    @Test
    fun `同じ開催日の再挿入は AlreadyExists で何も変えず、同じ履歴は Duplicate`() {
        val day = Fixtures.day()
        h.raceDays.insertIfAbsent(day, Fixtures.dayEvent())
        assertEquals(ChangeResult.AlreadyExists, h.raceDays.insertIfAbsent(day.copy(status = RaceDayStatus.CANCELLED), Fixtures.dayEvent(group = "d2")))
        assertEquals(ChangeResult.Duplicate, h.raceDays.insertIfAbsent(Fixtures.day(venue = Venue.KYOTO), Fixtures.dayEvent()))
        assertEquals(1, db.count("SELECT COUNT(*) FROM race_day"))
        assertEquals(1, db.count("SELECT COUNT(*) FROM race_schedule_event"))
        assertEquals(RaceDayStatus.PLANNED, h.raceDays.find(T1, Fixtures.DATE, Venue.TOKYO)!!.status)
    }

    @Test
    fun `更新は version の一致が条件で、履歴と同じトランザクション（競合・不在では履歴が残らない）`() {
        val day = Fixtures.day()
        h.raceDays.insertIfAbsent(day, Fixtures.dayEvent())
        val v1 = applied(h.raceDays.update(day.copy(status = RaceDayStatus.CONFIRMED), Fixtures.dayEvent(group = "d2"))).value
        assertEquals(1, v1.version)
        assertEquals(RaceDayStatus.CONFIRMED, v1.status)
        assertEquals(ChangeResult.Conflict, h.raceDays.update(day.copy(status = RaceDayStatus.CANCELLED), Fixtures.dayEvent(group = "d3")))
        assertEquals(ChangeResult.NotFound, h.raceDays.update(Fixtures.day(venue = Venue.KYOTO), Fixtures.dayEvent(group = "d4")))
        assertEquals(2, db.count("SELECT COUNT(*) FROM race_schedule_event"))
        // 同じ履歴の再送は Duplicate（更新しない）
        assertEquals(ChangeResult.Duplicate, h.raceDays.update(v1.copy(status = RaceDayStatus.CANCELLED), Fixtures.dayEvent(group = "d2")))
        assertEquals(RaceDayStatus.CONFIRMED, h.raceDays.find(T1, Fixtures.DATE, Venue.TOKYO)!!.status)
    }

    @Test
    fun `履歴の追記が失敗したら開催日の変更も戻る`() {
        val day = Fixtures.day()
        val bad = Fixtures.dayEvent(group = "bad").copy(batchId = "00000000-0000-7000-8000-00000000dead")
        assertSqlError(MySqlError.FK_CHILD) { h.raceDays.insertIfAbsent(day, bad) }
        assertEquals(0, db.count("SELECT COUNT(*) FROM race_day"))
        h.raceDays.insertIfAbsent(day, Fixtures.dayEvent())
        assertSqlError(MySqlError.FK_CHILD) { h.raceDays.update(day.copy(status = RaceDayStatus.CONFIRMED), bad) }
        assertEquals(RaceDayStatus.PLANNED, h.raceDays.find(T1, Fixtures.DATE, Venue.TOKYO)!!.status)
        assertEquals(0, db.scalar("SELECT version FROM race_day")!!.toInt())
    }

    @Test
    fun `延期の振替先を往復できる（POSTPONED だけが持てる）`() {
        val next = LocalDate.of(2026, 10, 25)
        val day = Fixtures.day()
        h.raceDays.insertIfAbsent(day, Fixtures.dayEvent())
        val postponed =
            applied(
                h.raceDays.update(
                    day.copy(status = RaceDayStatus.POSTPONED, replacedByDate = next),
                    Fixtures.dayEvent(group = "p", type = ScheduleEventType.POSTPONED),
                ),
            ).value
        assertEquals(next, postponed.replacedByDate)
        assertEquals(postponed, h.raceDays.find(T1, Fixtures.DATE, Venue.TOKYO))
        assertFailsWith<IllegalArgumentException> { Fixtures.day().copy(replacedByDate = next) }
    }

    @Test
    fun `履歴の種類が開催日向けでない・レースを指す履歴は拒否する`() {
        val day = Fixtures.day()
        assertFailsWith<IllegalArgumentException> { h.raceDays.insertIfAbsent(day, Fixtures.dayEvent(type = ScheduleEventType.STALE)) }
        val schedule = Fixtures.schedule()
        assertFailsWith<IllegalArgumentException> { h.raceDays.insertIfAbsent(day, Fixtures.event(schedule)) }
        assertFailsWith<IllegalArgumentException> { h.raceDays.insertIfAbsent(day, Fixtures.dayEvent(T2)) }
        assertEquals(0, db.count("SELECT COUNT(*) FROM race_day"))
    }

    @Test
    fun `confirm は確認の時刻を前進させるだけで、古い時刻で戻さず、履歴も残さない`() {
        val day = Fixtures.day(confirmedAt = Instant.parse("2026-10-24T01:00:00Z"))
        h.raceDays.insertIfAbsent(day, Fixtures.dayEvent())
        val later = Instant.parse("2026-10-24T02:00:00.000123Z")
        assertEquals(true, h.raceDays.confirm(T1, Fixtures.DATE, Venue.TOKYO, later))
        assertEquals(later, h.raceDays.find(T1, Fixtures.DATE, Venue.TOKYO)!!.lastConfirmedAt)
        assertEquals(1, h.raceDays.find(T1, Fixtures.DATE, Venue.TOKYO)!!.version)
        // 古い時刻・同じ時刻は変えない（それでも行はあるので true。version も進まない）
        assertEquals(true, h.raceDays.confirm(T1, Fixtures.DATE, Venue.TOKYO, Instant.parse("2026-10-24T00:00:00Z")))
        assertEquals(true, h.raceDays.confirm(T1, Fixtures.DATE, Venue.TOKYO, later))
        assertEquals(later, h.raceDays.find(T1, Fixtures.DATE, Venue.TOKYO)!!.lastConfirmedAt)
        assertEquals(1, h.raceDays.find(T1, Fixtures.DATE, Venue.TOKYO)!!.version)
        assertEquals(false, h.raceDays.confirm(T1, Fixtures.DATE, Venue.KYOTO, later))
        assertEquals(1, db.count("SELECT COUNT(*) FROM race_schedule_event"))
        // 確認の時刻が無い行にも設定できる
        val none = Fixtures.day(venue = Venue.NAKAYAMA, confirmedAt = null)
        h.raceDays.insertIfAbsent(none, Fixtures.dayEvent(group = "n"))
        assertEquals(true, h.raceDays.confirm(T1, Fixtures.DATE, Venue.NAKAYAMA, later))
        assertEquals(later, h.raceDays.find(T1, Fixtures.DATE, Venue.NAKAYAMA)!!.lastConfirmedAt)
    }

    @Test
    fun `一覧は場コードの昇順で、日付とテナントで限定される`() {
        h.raceDays.insertIfAbsent(Fixtures.day(venue = Venue.KYOTO), Fixtures.dayEvent(group = "1").copy())
        h.raceDays.insertIfAbsent(Fixtures.day(venue = Venue.NAKAYAMA), Fixtures.dayEvent(group = "2"))
        h.raceDays.insertIfAbsent(Fixtures.day(venue = Venue.SAPPORO), Fixtures.dayEvent(group = "3"))
        h.raceDays.insertIfAbsent(
            Fixtures.day(venue = Venue.TOKYO, date = LocalDate.of(2026, 10, 25)),
            Fixtures.dayEvent(group = "4").copy(raceDate = LocalDate.of(2026, 10, 25)),
        )
        assertEquals(listOf(Venue.SAPPORO, Venue.NAKAYAMA, Venue.KYOTO), h.raceDays.listByDate(T1, Fixtures.DATE).map { it.venue })
        assertEquals(emptyList(), h.raceDays.listByDate(T2, Fixtures.DATE))
    }

    @Test
    fun `同じ version から同時に更新すると、成功するのは 1 つだけ`() {
        repeat(5) { round ->
            val day = Fixtures.day(venue = Venue.entries[round])
            h.raceDays.insertIfAbsent(day, Fixtures.dayEvent(group = "i$round"))
            val results =
                runConcurrently(
                    2,
                ) { i -> h.raceDays.update(day.copy(status = RaceDayStatus.CONFIRMED), Fixtures.dayEvent(group = "w$round-$i")) }.map {
                    it.getOrThrow()
                }
            assertEquals(1, results.count { it is ChangeResult.Applied<*> }, "$results")
            assertEquals(1, results.count { it == ChangeResult.Conflict }, "$results")
        }
    }
}
