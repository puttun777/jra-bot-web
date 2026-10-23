package jp.jrabot.persistence.integration

import jp.jrabot.domain.race.RaceSchedule
import jp.jrabot.domain.race.RaceScheduleStatus
import jp.jrabot.domain.race.Venue
import jp.jrabot.domain.repository.InsertOutcome
import jp.jrabot.domain.repository.UpdateResult
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

@Tag("integration")
class RaceScheduleRepositoryIntegrationTest {
    companion object {
        private val db = MySqlSupport.migratedDatabase()
        private val h = RepositoryHarness(db, poolMax = 6)

        @JvmStatic
        @AfterAll
        fun close() = h.close()
    }

    @BeforeEach
    fun reset() = db.resetData()

    private fun updated(result: UpdateResult<RaceSchedule>): RaceSchedule = assertIs<UpdateResult.Updated<RaceSchedule>>(result).value

    @Test
    fun `races が無くても予定を挿入できる（races への FK を持たない）`() {
        val schedule = Fixtures.schedule()
        assertEquals(0, db.count("SELECT COUNT(*) FROM races"))
        assertEquals(InsertOutcome.INSERTED, h.schedules.insertIfAbsent(schedule))
        assertEquals(schedule, h.schedules.find(T1, schedule.raceId))
    }

    @Test
    fun `自然キーの再実行は ALREADY_EXISTS で、既存の行を変更しない`() {
        val schedule = Fixtures.schedule()
        h.schedules.insertIfAbsent(schedule)
        assertEquals(InsertOutcome.ALREADY_EXISTS, h.schedules.insertIfAbsent(schedule.copy(status = RaceScheduleStatus.CANCELLED, raceName = "別名")))
        assertEquals(schedule, h.schedules.find(T1, schedule.raceId))
        assertEquals(1, db.count("SELECT COUNT(*) FROM race_schedule"))
    }

    @Test
    fun `楽観ロック、一致する version なら更新して version が進み、古い version は Conflict`() {
        val schedule = Fixtures.schedule()
        h.schedules.insertIfAbsent(schedule)
        val v1 =
            updated(
                h.schedules.update(
                    schedule.copy(status = RaceScheduleStatus.CARD, raceName = "更新1", lastChangedAt = Instant.parse("2026-10-23T04:00:00.000001Z")),
                ),
            )
        assertEquals(1, v1.version)
        assertEquals(RaceScheduleStatus.CARD, v1.status)
        assertEquals(Instant.parse("2026-10-23T04:00:00.000001Z"), v1.lastChangedAt)

        assertEquals(UpdateResult.Conflict, h.schedules.update(schedule.copy(status = RaceScheduleStatus.CANCELLED)))
        assertEquals(v1, h.schedules.find(T1, schedule.raceId))
        assertEquals(2, updated(h.schedules.update(v1.copy(status = RaceScheduleStatus.MONITORING))).version)
    }

    @Test
    fun `発走時刻の不明と UTC の予定時刻を往復できる`() {
        val unknown = Fixtures.schedule(postAt = null)
        h.schedules.insertIfAbsent(unknown)
        assertEquals(unknown, h.schedules.find(T1, unknown.raceId))
        val known = updated(h.schedules.update(unknown.copy(postTimeUnknown = false, scheduledPostAt = Instant.parse("2026-10-24T06:40:00.000123Z"))))
        assertEquals(Instant.parse("2026-10-24T06:40:00.000123Z"), known.scheduledPostAt)
    }

    @Test
    fun `存在しない予定と他テナントの予定の更新は NotFound`() {
        val schedule = Fixtures.schedule()
        h.schedules.insertIfAbsent(schedule)
        assertEquals(UpdateResult.NotFound, h.schedules.update(Fixtures.schedule(raceId = Fixtures.raceId(no = 1))))
        assertEquals(UpdateResult.NotFound, h.schedules.update(schedule.copy(tenantId = T2, status = RaceScheduleStatus.DELETED)))
        assertEquals(schedule, h.schedules.find(T1, schedule.raceId))
        assertNull(h.schedules.find(T2, schedule.raceId))
        assertEquals(emptyList(), h.schedules.listByDate(T2, Fixtures.DATE))
        assertEquals(InsertOutcome.INSERTED, h.schedules.insertIfAbsent(schedule.copy(tenantId = T2)))
    }

    @Test
    fun `日付の一覧は発走時刻の昇順で、不明は最後`() {
        h.schedules.insertIfAbsent(Fixtures.schedule(raceId = Fixtures.raceId(Venue.TOKYO, 1), postAt = Instant.parse("2026-10-24T01:00:00Z")))
        h.schedules.insertIfAbsent(Fixtures.schedule(raceId = Fixtures.raceId(Venue.KYOTO, 1), postAt = null))
        h.schedules.insertIfAbsent(Fixtures.schedule(raceId = Fixtures.raceId(Venue.NAKAYAMA, 1), postAt = Instant.parse("2026-10-24T00:30:00Z")))
        assertEquals(listOf("202610240601", "202610240501", "202610240801"), h.schedules.listByDate(T1, Fixtures.DATE).map { it.raceId.value })
    }

    @Test
    fun `新規の version は 0 でなければならない`() {
        assertFailsWith<IllegalArgumentException> { h.schedules.insertIfAbsent(Fixtures.schedule().copy(version = 3)) }
    }

    @Test
    fun `同じ version から同時に更新すると、成功するのは 1 つだけ`() {
        repeat(8) { round ->
            val schedule = Fixtures.schedule(raceId = Fixtures.raceId(no = round + 1))
            h.schedules.insertIfAbsent(schedule)
            val results = runConcurrently(2) { i -> h.schedules.update(schedule.copy(raceName = "writer-$i")) }.map { it.getOrThrow() }
            assertEquals(1, results.count { it is UpdateResult.Updated<*> }, "$results")
            assertEquals(1, results.count { it == UpdateResult.Conflict }, "$results")
        }
    }
}
