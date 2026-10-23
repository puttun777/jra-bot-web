package jp.jrabot.persistence.integration

import jp.jrabot.domain.calendar.AppendResult
import jp.jrabot.domain.calendar.EventJson
import jp.jrabot.domain.calendar.ScheduleEvent
import jp.jrabot.domain.calendar.ScheduleEventSource
import jp.jrabot.domain.calendar.ScheduleEventType
import jp.jrabot.domain.race.Venue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 履歴（`race_schedule_event`）は追記専用で、`(tenant_id, dedup_hash)` で冪等。 */
@Tag("integration")
class ScheduleEventRepositoryIntegrationTest {
    companion object {
        private val db = MySqlSupport.migratedDatabase()
        private val h = RepositoryHarness(db, poolMax = 8)

        @JvmStatic
        @AfterAll
        fun close() = h.close()
    }

    @BeforeEach
    fun reset() = db.resetData()

    @Test
    fun `追記して読み戻せる（JSON・マイクロ秒の時刻・id の昇順）`() {
        val schedule = Fixtures.schedule()
        h.schedules.insertIfAbsent(schedule, Fixtures.event(schedule))
        val e =
            ScheduleEvent.create(
                T1,
                schedule.raceId,
                Fixtures.DATE,
                ScheduleEventType.TIME_CHANGE,
                EventJson.obj("post_at" to "2026-10-24T06:35:00Z"),
                EventJson.obj("post_at" to "2026-10-24T06:50:00Z"),
                ScheduleEventSource.B14,
                Instant.parse("2026-10-24T05:00:00.123456Z"),
                Instant.parse("2026-10-24T05:00:01.000001Z"),
                "snap-1",
            )
        val stored = assertIs<AppendResult.Appended>(h.events.append(e)).event
        assertTrue(stored.id!! > 0)
        assertEquals(e.copy(id = stored.id, oldValue = stored.oldValue, newValue = stored.newValue), stored)
        // MySQL の JSON は整形して返す（値は同じ）
        assertTrue(stored.newValue!!.contains("2026-10-24T06:50:00Z"))
        assertEquals(listOf("ADDED", "TIME_CHANGE"), h.events.eventsOfRace(T1, schedule.raceId).map { it.type.name })
        assertEquals("0B14", db.scalar("SELECT source FROM race_schedule_event WHERE event_type = 'TIME_CHANGE'"))
        assertEquals(
            "2026-10-24 05:00:00.123456",
            db.scalar("SELECT DATE_FORMAT(observed_at, '%Y-%m-%d %H:%i:%s.%f') FROM race_schedule_event WHERE event_type = 'TIME_CHANGE'"),
        )
        assertEquals(2, h.events.eventsOfDate(T1, Fixtures.DATE).size)
        assertEquals(emptyList(), h.events.eventsOfDate(T1, LocalDate.of(2026, 10, 25)))
    }

    @Test
    fun `同じ履歴の再実行は Duplicate で増えない（冪等）`() {
        val e = Fixtures.staleEvent()
        assertIs<AppendResult.Appended>(h.events.append(e))
        repeat(3) { assertEquals(AppendResult.Duplicate, h.events.append(e)) }
        assertEquals(1, db.count("SELECT COUNT(*) FROM race_schedule_event"))
        // 取得時刻・受信時刻が違っても、内容と dedupGroup が同じなら同じ履歴
        assertEquals(AppendResult.Duplicate, h.events.append(e.copy(observedAt = e.observedAt.plusSeconds(60), receivedAt = e.receivedAt.plusSeconds(61))))
        // dedupGroup が違えば別の履歴
        assertIs<AppendResult.Appended>(h.events.append(Fixtures.staleEvent(group = "stale-2")))
        assertEquals(2, db.count("SELECT COUNT(*) FROM race_schedule_event"))
    }

    @Test
    fun `同じ履歴を同時に追記しても 1 行だけ`() {
        val e = Fixtures.staleEvent()
        val results = runConcurrently(8) { h.events.append(e) }.map { it.getOrThrow() }
        assertEquals(1, results.count { it is AppendResult.Appended }, "$results")
        assertEquals(7, results.count { it == AppendResult.Duplicate }, "$results")
        assertEquals(1, db.count("SELECT COUNT(*) FROM race_schedule_event"))
    }

    @Test
    fun `同じ履歴でもテナントが違えば別の行（dedup はテナントごと）`() {
        assertIs<AppendResult.Appended>(h.events.append(Fixtures.staleEvent(T1)))
        assertIs<AppendResult.Appended>(h.events.append(Fixtures.staleEvent(T2)))
        assertEquals(2, db.count("SELECT COUNT(*) FROM race_schedule_event"))
    }

    @Test
    fun `日単位と手動補正のイベントの race_id の扱い（FK と種類の整合）`() {
        // レース単位のイベントは予定が無ければ FK で拒否される
        val schedule = Fixtures.schedule()
        assertSqlError(MySqlError.FK_CHILD) { h.events.append(Fixtures.event(schedule, ScheduleEventType.TIME_CHANGE, "x")) }
        // 日単位は race_id を持てない（種類の規則。ドメインが拒否する）
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            ScheduleEvent.create(
                T1,
                schedule.raceId,
                Fixtures.DATE,
                ScheduleEventType.STALE,
                null,
                null,
                ScheduleEventSource.SYSTEM,
                Fixtures.NOW,
                Fixtures.NOW,
                "g",
            )
        }
        assertEquals(0, db.count("SELECT COUNT(*) FROM race_schedule_event"))
        assertEquals(setOf(Venue.TOKYO), setOf(schedule.raceId.venue))
    }

    @Test
    fun `履歴の表には更新・削除の操作が無く、app と ingest は追記だけ（DB の権限）`() {
        h.events.append(Fixtures.staleEvent())
        for (user in listOf("app", "ingest", "bot")) {
            assertDenied { db.asUser(user) { it.createStatement().executeUpdate("UPDATE race_schedule_event SET source = 'MANUAL'") } }
        }
        for (user in listOf("app", "ingest")) {
            assertDenied { db.asUser(user) { it.createStatement().executeUpdate("DELETE FROM race_schedule_event") } }
        }
        assertEquals("SYSTEM", db.scalar("SELECT source FROM race_schedule_event"))
    }
}
