package jp.jrabot.persistence.integration

import jp.jrabot.domain.calendar.ChangeResult
import jp.jrabot.domain.calendar.ScheduleEventType
import jp.jrabot.domain.race.RaceSchedule
import jp.jrabot.domain.race.RaceScheduleStatus
import jp.jrabot.domain.race.Venue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    private fun applied(result: ChangeResult<RaceSchedule>): RaceSchedule = assertIs<ChangeResult.Applied<RaceSchedule>>(result).value

    private fun insert(
        schedule: RaceSchedule,
        group: String = "g1",
    ) = h.schedules.insertIfAbsent(schedule, Fixtures.event(schedule, group = group))

    private fun update(
        schedule: RaceSchedule,
        type: ScheduleEventType = ScheduleEventType.TIME_CHANGE,
        group: String = "u-${schedule.version}-${schedule.raceName}-${schedule.status}",
    ) = h.schedules.update(schedule, Fixtures.event(schedule, type, group))

    @Test
    fun `races が無くても予定を挿入でき、履歴 ADDED が同時に残る`() {
        val schedule = Fixtures.schedule()
        assertEquals(0, db.count("SELECT COUNT(*) FROM races"))
        val r = assertIs<ChangeResult.Applied<RaceSchedule>>(insert(schedule))
        assertEquals(schedule, r.value)
        assertEquals(schedule, h.schedules.find(T1, schedule.raceId))
        assertEquals(1, db.count("SELECT COUNT(*) FROM race_schedule_event"))
        assertEquals(ScheduleEventType.ADDED, r.event.type)
        assertEquals(listOf(r.event), h.events.eventsOfRace(T1, schedule.raceId))
    }

    @Test
    fun `自然キーの再実行は AlreadyExists で、既存の行も履歴も変更しない`() {
        val schedule = Fixtures.schedule()
        insert(schedule)
        assertEquals(ChangeResult.AlreadyExists, insert(schedule.copy(status = RaceScheduleStatus.CANCELLED, raceName = "別名"), group = "g2"))
        assertEquals(schedule, h.schedules.find(T1, schedule.raceId))
        assertEquals(1, db.count("SELECT COUNT(*) FROM race_schedule"))
        assertEquals(1, db.count("SELECT COUNT(*) FROM race_schedule_event"))
    }

    @Test
    fun `挿入の再実行（同じ履歴）は、行が無くても履歴が重複なら Duplicate で何も残らない`() {
        val a = Fixtures.schedule(raceId = Fixtures.raceId(no = 1))
        val b = Fixtures.schedule(raceId = Fixtures.raceId(no = 2))
        val shared = Fixtures.event(a, group = "same")
        assertIs<ChangeResult.Applied<RaceSchedule>>(h.schedules.insertIfAbsent(a, shared))
        // b の挿入に、同じ dedup_hash を持つ履歴（a の履歴を b のレースに付け替えたもの）を付ける = 履歴は反映済みと判断される
        assertEquals(ChangeResult.Duplicate, h.schedules.insertIfAbsent(b, shared.copy(id = null, raceId = b.raceId)))
        assertNull(h.schedules.find(T1, b.raceId))
        assertEquals(1, db.count("SELECT COUNT(*) FROM race_schedule"))
    }

    @Test
    fun `更新と履歴は 1 つのトランザクション（履歴の追記が失敗したら更新も戻る）`() {
        val schedule = Fixtures.schedule()
        insert(schedule)
        // 存在しない取込バッチを指す履歴 = FK 違反で履歴の追記が失敗する
        val badEvent = Fixtures.event(schedule, ScheduleEventType.TIME_CHANGE, "bad", batchId = "00000000-0000-7000-8000-00000000dead")
        assertSqlError(MySqlError.FK_CHILD) { h.schedules.update(schedule.copy(status = RaceScheduleStatus.CARD, raceName = "更新"), badEvent) }
        assertEquals(schedule, h.schedules.find(T1, schedule.raceId), "履歴が失敗したのに予定が更新された")
        assertEquals(1, db.count("SELECT COUNT(*) FROM race_schedule_event"))
        assertEquals("0", db.scalar("SELECT version FROM race_schedule"))
    }

    @Test
    fun `挿入と履歴も 1 つのトランザクション（履歴が失敗したら挿入も戻る）`() {
        val schedule = Fixtures.schedule()
        val badEvent = Fixtures.event(schedule, batchId = "00000000-0000-7000-8000-00000000dead")
        assertSqlError(MySqlError.FK_CHILD) { h.schedules.insertIfAbsent(schedule, badEvent) }
        assertNull(h.schedules.find(T1, schedule.raceId))
        assertEquals(0, db.count("SELECT COUNT(*) FROM race_schedule"))
    }

    @Test
    fun `履歴がこの予定のものでなければ拒否する（テナント・レースの食い違い）`() {
        val schedule = Fixtures.schedule()
        val other = Fixtures.schedule(raceId = Fixtures.raceId(no = 1))
        assertFailsWith<IllegalArgumentException> { h.schedules.insertIfAbsent(schedule, Fixtures.event(other)) }
        assertFailsWith<IllegalArgumentException> { h.schedules.insertIfAbsent(schedule, Fixtures.event(schedule.copy(tenantId = T2))) }
        assertEquals(0, db.count("SELECT COUNT(*) FROM race_schedule"))
    }

    @Test
    fun `楽観ロック、一致する version なら更新して version が進み、古い version は Conflict で履歴も増えない`() {
        val schedule = Fixtures.schedule()
        insert(schedule)
        val v1 =
            applied(update(schedule.copy(status = RaceScheduleStatus.CARD, raceName = "更新1", lastChangedAt = Instant.parse("2026-10-23T04:00:00.000001Z"))))
        assertEquals(1, v1.version)
        assertEquals(RaceScheduleStatus.CARD, v1.status)
        assertEquals(Instant.parse("2026-10-23T04:00:00.000001Z"), v1.lastChangedAt)
        assertEquals(2, db.count("SELECT COUNT(*) FROM race_schedule_event"))

        assertEquals(ChangeResult.Conflict, update(schedule.copy(status = RaceScheduleStatus.CANCELLED)))
        assertEquals(v1, h.schedules.find(T1, schedule.raceId))
        assertEquals(2, db.count("SELECT COUNT(*) FROM race_schedule_event"), "競合したのに履歴が増えた")
        assertEquals(2, applied(update(v1.copy(status = RaceScheduleStatus.MONITORING))).version)
    }

    @Test
    fun `更新の再実行（同じ履歴）は Duplicate で、更新も履歴の追加もしない`() {
        val schedule = Fixtures.schedule()
        insert(schedule)
        val changed = schedule.copy(status = RaceScheduleStatus.CARD)
        val first = assertIs<ChangeResult.Applied<RaceSchedule>>(update(changed, group = "fixed"))
        // 同じ履歴で、まだ古い version のまま再送（再実行・リトライ）
        assertEquals(ChangeResult.Duplicate, update(changed, group = "fixed"))
        // 新しい version で同じ履歴を送っても Duplicate（履歴が反映済みの証拠）
        assertEquals(ChangeResult.Duplicate, update(first.value.copy(raceName = "別名"), group = "fixed"))
        assertEquals(1, h.schedules.find(T1, schedule.raceId)!!.version)
        assertEquals(2, db.count("SELECT COUNT(*) FROM race_schedule_event"))
    }

    @Test
    fun `発走時刻の不明と UTC の予定時刻を往復できる`() {
        val unknown = Fixtures.schedule(postAt = null)
        insert(unknown)
        assertEquals(unknown, h.schedules.find(T1, unknown.raceId))
        val known = applied(update(unknown.copy(postTimeUnknown = false, scheduledPostAt = Instant.parse("2026-10-24T06:40:00.000123Z"))))
        assertEquals(Instant.parse("2026-10-24T06:40:00.000123Z"), known.scheduledPostAt)
        assertEquals("2026-10-24 06:40:00.000123", db.scalar("SELECT DATE_FORMAT(scheduled_post_at, '%Y-%m-%d %H:%i:%s.%f') FROM race_schedule"))
    }

    @Test
    fun `存在しない予定と他テナントの予定の更新は NotFound で履歴も残らない`() {
        val schedule = Fixtures.schedule()
        insert(schedule)
        val missing = Fixtures.schedule(raceId = Fixtures.raceId(no = 1))
        assertEquals(ChangeResult.NotFound, update(missing))
        val foreign = schedule.copy(tenantId = T2, status = RaceScheduleStatus.DELETED)
        assertEquals(ChangeResult.NotFound, update(foreign))
        assertEquals(schedule, h.schedules.find(T1, schedule.raceId))
        assertNull(h.schedules.find(T2, schedule.raceId))
        assertEquals(emptyList(), h.schedules.listByDate(T2, Fixtures.DATE))
        assertEquals(1, db.count("SELECT COUNT(*) FROM race_schedule_event"))
        assertIs<ChangeResult.Applied<RaceSchedule>>(insert(schedule.copy(tenantId = T2)))
    }

    @Test
    fun `日付の一覧は発走時刻の昇順で、不明は最後`() {
        insert(Fixtures.schedule(raceId = Fixtures.raceId(Venue.TOKYO, 1), postAt = Instant.parse("2026-10-24T01:00:00Z")))
        insert(Fixtures.schedule(raceId = Fixtures.raceId(Venue.KYOTO, 1), postAt = null))
        insert(Fixtures.schedule(raceId = Fixtures.raceId(Venue.NAKAYAMA, 1), postAt = Instant.parse("2026-10-24T00:30:00Z")))
        assertEquals(listOf("202610240601", "202610240501", "202610240801"), h.schedules.listByDate(T1, Fixtures.DATE).map { it.raceId.value })
    }

    @Test
    fun `新規の version は 0 でなければならない`() {
        val s = Fixtures.schedule().copy(version = 3)
        assertFailsWith<IllegalArgumentException> { h.schedules.insertIfAbsent(s, Fixtures.event(s)) }
    }

    @Test
    fun `同じ version から同時に更新すると、成功するのは 1 つだけで履歴も 1 つ`() {
        repeat(8) { round ->
            val schedule = Fixtures.schedule(raceId = Fixtures.raceId(no = round + 1))
            insert(schedule)
            val results =
                runConcurrently(
                    2,
                ) { i -> h.schedules.update(schedule.copy(raceName = "writer-$i"), Fixtures.event(schedule, ScheduleEventType.TIME_CHANGE, "w-$i")) }.map {
                    it.getOrThrow()
                }
            assertEquals(1, results.count { it is ChangeResult.Applied<*> }, "$results")
            assertEquals(1, results.count { it == ChangeResult.Conflict }, "$results")
            assertEquals(2, h.events.eventsOfRace(T1, schedule.raceId).size)
        }
    }

    @Test
    fun `同じ履歴を同時に追記しても反映されるのは 1 回だけ`() {
        val schedule = Fixtures.schedule()
        insert(schedule)
        val changed = schedule.copy(status = RaceScheduleStatus.CARD)
        val results =
            runConcurrently(
                5,
            ) { h.schedules.update(changed, Fixtures.event(schedule, ScheduleEventType.TIME_CHANGE, "same-group")) }.map { it.getOrThrow() }
        assertEquals(1, results.count { it is ChangeResult.Applied<*> }, "$results")
        assertTrue(results.all { it is ChangeResult.Applied<*> || it == ChangeResult.Duplicate || it == ChangeResult.Conflict }, "$results")
        assertEquals(1, h.schedules.find(T1, schedule.raceId)!!.version)
        assertEquals(2, db.count("SELECT COUNT(*) FROM race_schedule_event"))
    }
}
