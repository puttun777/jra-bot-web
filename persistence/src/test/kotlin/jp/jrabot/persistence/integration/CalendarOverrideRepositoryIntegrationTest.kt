package jp.jrabot.persistence.integration

import jp.jrabot.domain.calendar.CalendarOverride
import jp.jrabot.domain.calendar.CalendarOverrideService
import jp.jrabot.domain.calendar.CreateOverrideOutcome
import jp.jrabot.domain.calendar.OverrideAction
import jp.jrabot.domain.calendar.OverrideCreateResult
import jp.jrabot.domain.calendar.OverrideRevokeResult
import jp.jrabot.domain.calendar.OverrideScope
import jp.jrabot.domain.calendar.OverrideValue
import jp.jrabot.domain.calendar.ScheduleEventType
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.race.Venue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 手動補正の永続化（`app` ユーザー = 管理者の書込み権限）。補正と履歴は同じトランザクション、取消は 1 回だけ。 */
@Tag("integration")
class CalendarOverrideRepositoryIntegrationTest {
    companion object {
        private val db = MySqlSupport.migratedDatabase()
        private val clock = MutableClock(Instant.parse("2026-10-24T01:00:00.123456Z"))
        private val h = RepositoryHarness(db, user = "app", clock = clock, poolMax = 6)
        private val admin = RepositoryHarness(db, clock = clock) // 準備用（予定の挿入）

        @JvmStatic
        @AfterAll
        fun close() {
            h.close()
            admin.close()
        }
    }

    private val raceId = RaceId.of(Fixtures.DATE, Venue.TOKYO, 11)
    private val service get() = CalendarOverrideService(h.overrides, h.schedules, clock)

    @BeforeEach
    fun reset() {
        db.resetData()
        clock.instant = Instant.parse("2026-10-24T01:00:00.123456Z")
        val schedule = Fixtures.schedule(raceId = raceId)
        admin.schedules.insertIfAbsent(schedule, Fixtures.event(schedule))
    }

    private fun created(o: CreateOverrideOutcome) = assertIs<CreateOverrideOutcome.Created>(o).override

    @Test
    fun `日単位の補正を追記して読み戻せ、MANUAL_OVERRIDE の履歴が同時に残る`() {
        val o =
            created(
                service.create(
                    T1,
                    OverrideScope.DAY,
                    Fixtures.DATE,
                    null,
                    OverrideAction.DENY_RUN,
                    null,
                    "雨天のため（合成）",
                    "admin@example.com",
                    Instant.parse("2026-10-24T09:00:00.000001Z"),
                ),
            )
        assertNotNull(o.id)
        assertEquals(clock.instant, o.createdAt)
        assertEquals(Instant.parse("2026-10-24T09:00:00.000001Z"), o.validUntil)
        assertEquals(o, h.overrides.find(T1, checkNotNull(o.id)))
        assertEquals(listOf(o), h.overrides.listByDate(T1, Fixtures.DATE))
        val events = h.events.eventsOfDate(T1, Fixtures.DATE).filter { it.type == ScheduleEventType.MANUAL_OVERRIDE }
        assertEquals(1, events.size)
        assertNull(events.single().raceId)
        assertTrue(events.single().newValue!!.contains("DENY_RUN"))
        assertEquals("MANUAL", db.scalar("SELECT source FROM race_schedule_event WHERE event_type = 'MANUAL_OVERRIDE'"))
    }

    @Test
    fun `レース単位の補正（SET_POST_TIME・CANCEL）の値が JSON で往復し、履歴にレースが付く`() {
        val at = Instant.parse("2026-10-24T06:35:00.000123Z")
        val o =
            created(
                service.create(
                    T1,
                    OverrideScope.RACE,
                    Fixtures.DATE,
                    raceId,
                    OverrideAction.SET_POST_TIME,
                    OverrideValue.PostAt(at),
                    "時刻確認",
                    "admin@example.com",
                ),
            )
        assertEquals(OverrideValue.PostAt(at), h.overrides.find(T1, checkNotNull(o.id))!!.value)
        assertEquals(
            raceId,
            h.events
                .eventsOfRace(T1, raceId)
                .single { it.type == ScheduleEventType.MANUAL_OVERRIDE }
                .raceId,
        )
        val c = created(service.create(T1, OverrideScope.RACE, Fixtures.DATE, raceId, OverrideAction.CANCEL, null, "中止", "admin@example.com"))
        assertNull(c.value)
        val p =
            created(
                service.create(
                    T1,
                    OverrideScope.DAY,
                    Fixtures.DATE,
                    null,
                    OverrideAction.POSTPONE_TO,
                    OverrideValue.PostponedTo(Fixtures.DATE.plusDays(1)),
                    "延期",
                    "admin@example.com",
                ),
            )
        assertEquals(OverrideValue.PostponedTo(Fixtures.DATE.plusDays(1)), h.overrides.find(T1, checkNotNull(p.id))!!.value)
        assertEquals(listOf(o.id, c.id, p.id), h.overrides.listByDate(T1, Fixtures.DATE).map { it.id })
    }

    @Test
    fun `存在しないレースへの補正は作らない（サービスと FK）`() {
        val missing = RaceId.of(Fixtures.DATE, Venue.TOKYO, 12)
        assertEquals(
            CreateOverrideOutcome.RaceNotFound,
            service.create(T1, OverrideScope.RACE, Fixtures.DATE, missing, OverrideAction.CANCEL, null, "x", "admin@example.com"),
        )
        // サービスを介さなくても DB の複合 FK が拒否する
        val bad = Fixtures.manual(action = OverrideAction.CANCEL, scope = OverrideScope.RACE, race = missing)
        assertSqlError(MySqlError.FK_CHILD) { h.overrides.create(bad, Fixtures.overrideEvent(race = missing)) }
        assertEquals(0, db.count("SELECT COUNT(*) FROM calendar_override"))
    }

    @Test
    fun `補正と履歴は 1 つのトランザクション（履歴が失敗したら補正も戻る）`() {
        val badEvent = Fixtures.overrideEvent().copy(batchId = "00000000-0000-7000-8000-00000000dead")
        assertSqlError(MySqlError.FK_CHILD) { h.overrides.create(Fixtures.manual(), badEvent) }
        assertEquals(0, db.count("SELECT COUNT(*) FROM calendar_override"))
        assertEquals(0, db.count("SELECT COUNT(*) FROM race_schedule_event WHERE event_type = 'MANUAL_OVERRIDE'"))
    }

    @Test
    fun `同じ補正の二重送信（同じ作成者・内容・時刻）は Duplicate で 1 件だけ`() {
        created(service.create(T1, OverrideScope.DAY, Fixtures.DATE, null, OverrideAction.DENY_RUN, null, "x", "admin@example.com"))
        assertEquals(
            CreateOverrideOutcome.Duplicate,
            service.create(T1, OverrideScope.DAY, Fixtures.DATE, null, OverrideAction.DENY_RUN, null, "x", "admin@example.com"),
        )
        assertEquals(1, db.count("SELECT COUNT(*) FROM calendar_override"))
        val results =
            runConcurrently(5) {
                service.create(T1, OverrideScope.DAY, Fixtures.DATE, null, OverrideAction.ALLOW_RUN, null, "y", "admin@example.com")
            }.map { it.getOrThrow() }
        assertEquals(1, results.count { it is CreateOverrideOutcome.Created }, "$results")
        assertEquals(2, db.count("SELECT COUNT(*) FROM calendar_override"))
    }

    @Test
    fun `取消は MANUAL_REVOKE を追記して revoked を記録し、二度目は AlreadyRevoked で履歴も増えない`() {
        val o = created(service.create(T1, OverrideScope.DAY, Fixtures.DATE, null, OverrideAction.MARK_NO_RACE, null, "x", "admin@example.com"))
        clock.instant = clock.instant.plusSeconds(60)
        val r = assertIs<OverrideRevokeResult.Revoked>(service.revoke(T1, checkNotNull(o.id), "boss@example.com"))
        assertEquals("boss@example.com", r.override.revokedBy)
        assertEquals(clock.instant, r.override.revokedAt)
        assertEquals(ScheduleEventType.MANUAL_REVOKE, r.event.type)
        assertEquals(false, r.override.isActiveAt(clock.instant))
        assertEquals(OverrideRevokeResult.AlreadyRevoked, service.revoke(T1, checkNotNull(o.id), "other@example.com"))
        assertEquals("boss@example.com", h.overrides.find(T1, checkNotNull(o.id))!!.revokedBy)
        assertEquals(1, db.count("SELECT COUNT(*) FROM race_schedule_event WHERE event_type = 'MANUAL_REVOKE'"))
        assertEquals(OverrideRevokeResult.NotFound, service.revoke(T1, 99999, "boss@example.com"))
    }

    @Test
    fun `同時に取り消しても勝者は 1 つで履歴も 1 行`() {
        val o = created(service.create(T1, OverrideScope.DAY, Fixtures.DATE, null, OverrideAction.DENY_RUN, null, "x", "admin@example.com"))
        val results = runConcurrently(6) { i -> service.revoke(T1, checkNotNull(o.id), "boss-$i@example.com") }.map { it.getOrThrow() }
        assertEquals(1, results.count { it is OverrideRevokeResult.Revoked }, "$results")
        assertEquals(5, results.count { it == OverrideRevokeResult.AlreadyRevoked }, "$results")
        assertEquals(1, db.count("SELECT COUNT(*) FROM race_schedule_event WHERE event_type = 'MANUAL_REVOKE'"))
    }

    @Test
    fun `期限切れの補正は DB に残るが適用中ではない（取消とは別）`() {
        val until = Instant.parse("2026-10-24T02:00:00Z")
        val o = created(service.create(T1, OverrideScope.DAY, Fixtures.DATE, null, OverrideAction.DENY_RUN, null, "x", "admin@example.com", until))
        val read = h.overrides.find(T1, checkNotNull(o.id))!!
        assertEquals(true, read.isActiveAt(until.minusNanos(1000)))
        assertEquals(false, read.isActiveAt(until))
        assertNull(read.revokedAt)
    }

    @Test
    fun `DB の制約が不正な補正を拒否する（scope と race_id、取消の組、理由）`() {
        fun insert(sql: String) =
            db.exec(
                "INSERT INTO calendar_override (tenant_id, scope, race_date, race_id, action, reason, created_by, created_at, revoked_at, revoked_by) VALUES $sql",
            )
        assertSqlError(MySqlError.CHECK_VIOLATED) { insert("(1, 'DAY', '2026-10-24', '${raceId.value}', 'CANCEL', 'r', 'u', NOW(6), NULL, NULL)") }
        assertSqlError(MySqlError.CHECK_VIOLATED) { insert("(1, 'RACE', '2026-10-24', NULL, 'CANCEL', 'r', 'u', NOW(6), NULL, NULL)") }
        assertSqlError(MySqlError.CHECK_VIOLATED) { insert("(1, 'DAY', '2026-10-24', NULL, 'DENY_RUN', '', 'u', NOW(6), NULL, NULL)") }
        assertSqlError(MySqlError.CHECK_VIOLATED) { insert("(1, 'DAY', '2026-10-24', NULL, 'DENY_RUN', 'r', 'u', NOW(6), NOW(6), NULL)") }
        assertEquals(0, db.count("SELECT COUNT(*) FROM calendar_override"))
    }

    @Test
    fun `新規の補正は id なし・未取消でなければならず、履歴の種類・対象が合わなければ拒否する`() {
        val base = Fixtures.manual()
        assertFailsWith<IllegalArgumentException> { h.overrides.create(base.copy(id = 5), Fixtures.overrideEvent()) }
        assertFailsWith<IllegalArgumentException> {
            h.overrides.create(
                base.copy(revokedAt = clock.instant, revokedBy = "x@example.com"),
                Fixtures.overrideEvent(),
            )
        }
        assertFailsWith<IllegalArgumentException> { h.overrides.create(base, Fixtures.dayEvent()) } // 種類が MANUAL_OVERRIDE ではない
        assertFailsWith<IllegalArgumentException> { h.overrides.create(base, Fixtures.overrideEvent(T2)) } // テナント違い
        assertFailsWith<IllegalArgumentException> { h.overrides.create(base, Fixtures.overrideEvent(race = raceId)) } // 対象違い
        assertEquals(0, db.count("SELECT COUNT(*) FROM calendar_override"))
        assertIs<OverrideCreateResult.Created>(h.overrides.create(base, Fixtures.overrideEvent()))
    }
}
