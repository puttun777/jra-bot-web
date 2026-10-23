package jp.jrabot.domain.calendar

import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.race.RaceSchedule
import jp.jrabot.domain.race.RaceScheduleReader
import jp.jrabot.domain.race.Venue
import jp.jrabot.domain.tenant.TenantId
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarOverrideTest {
    private val d = Cal.DATE
    private val raceId = RaceId.of(d, Venue.TOKYO, 1)

    private fun make(
        scope: OverrideScope = OverrideScope.DAY,
        race: RaceId? = null,
        action: OverrideAction = OverrideAction.DENY_RUN,
        value: OverrideValue? = null,
        reason: String = "理由",
        by: String = "admin@example.com",
        validUntil: Instant? = null,
        revokedAt: Instant? = null,
        revokedBy: String? = null,
        createdAt: Instant = Cal.jst(d, 5, 0),
        date: LocalDate = d,
    ) = CalendarOverride(Cal.T1, null, scope, date, race, action, value, reason, by, createdAt, validUntil, revokedAt, revokedBy)

    @Test
    fun `操作と対象の組合せ`() {
        assertEquals(setOf(OverrideScope.DAY, OverrideScope.RACE), OverrideAction.CANCEL.scopes)
        assertEquals(setOf(OverrideScope.RACE), OverrideAction.SET_POST_TIME.scopes)
        for (a in listOf(
            OverrideAction.MARK_RACE_DAY,
            OverrideAction.MARK_NO_RACE,
            OverrideAction.ALLOW_RUN,
            OverrideAction.DENY_RUN,
            OverrideAction.POSTPONE_TO,
        )) {
            assertEquals(setOf(OverrideScope.DAY), a.scopes, "$a")
        }
        assertFailsWith<IllegalArgumentException> { make(OverrideScope.RACE, raceId, OverrideAction.DENY_RUN) }
        assertFailsWith<IllegalArgumentException> { make(OverrideScope.DAY, null, OverrideAction.SET_POST_TIME, OverrideValue.PostAt(Cal.jst(d, 10, 0))) }
    }

    @Test
    fun `scope と raceId の整合`() {
        make(OverrideScope.RACE, raceId, OverrideAction.CANCEL)
        assertFailsWith<IllegalArgumentException> { make(OverrideScope.RACE, null, OverrideAction.CANCEL) }
        assertFailsWith<IllegalArgumentException> { make(OverrideScope.DAY, raceId, OverrideAction.CANCEL) }
        assertFailsWith<IllegalArgumentException> { make(OverrideScope.RACE, raceId, OverrideAction.CANCEL, date = d.plusDays(1)) }
    }

    @Test
    fun `値の検証`() {
        make(OverrideScope.RACE, raceId, OverrideAction.SET_POST_TIME, OverrideValue.PostAt(Cal.jst(d, 10, 0)))
        assertFailsWith<IllegalArgumentException> { make(OverrideScope.RACE, raceId, OverrideAction.SET_POST_TIME, null) }
        // JST の暦日が開催日と違う時刻は拒否（UTC では同じ日でも JST では翌日）
        assertFailsWith<IllegalArgumentException> {
            make(
                OverrideScope.RACE,
                raceId,
                OverrideAction.SET_POST_TIME,
                OverrideValue.PostAt(Cal.jst(d.plusDays(1), 0, 0)),
            )
        }
        make(OverrideScope.RACE, raceId, OverrideAction.SET_POST_TIME, OverrideValue.PostAt(Cal.jst(d, 0, 0)))
        make(OverrideScope.DAY, null, OverrideAction.POSTPONE_TO, OverrideValue.PostponedTo(d.plusDays(1)))
        assertFailsWith<IllegalArgumentException> { make(OverrideScope.DAY, null, OverrideAction.POSTPONE_TO, OverrideValue.PostponedTo(d)) }
        assertFailsWith<IllegalArgumentException> { make(OverrideScope.DAY, null, OverrideAction.POSTPONE_TO, null) }
        assertFailsWith<IllegalArgumentException> { make(OverrideScope.DAY, null, OverrideAction.DENY_RUN, OverrideValue.PostponedTo(d.plusDays(1))) }
    }

    @Test
    fun `監査情報は必須`() {
        assertFailsWith<IllegalArgumentException> { make(reason = "") }
        assertFailsWith<IllegalArgumentException> { make(reason = "   ") }
        assertFailsWith<IllegalArgumentException> { make(reason = "x".repeat(501)) }
        make(reason = "x".repeat(500))
        assertFailsWith<IllegalArgumentException> { make(by = "") }
        assertFailsWith<IllegalArgumentException> { make(by = "a\nb") }
        assertFailsWith<IllegalArgumentException> { make(by = "x".repeat(255)) }
    }

    @Test
    fun `期限と取消の検証`() {
        assertFailsWith<IllegalArgumentException> { make(validUntil = Cal.jst(d, 5, 0)) }
        assertFailsWith<IllegalArgumentException> { make(validUntil = Cal.jst(d, 4, 0)) }
        assertFailsWith<IllegalArgumentException> { make(revokedAt = Cal.jst(d, 6, 0)) }
        assertFailsWith<IllegalArgumentException> { make(revokedBy = "a@example.com") }
        assertFailsWith<IllegalArgumentException> { make(revokedAt = Cal.jst(d, 4, 0), revokedBy = "a@example.com") }
        make(revokedAt = Cal.jst(d, 6, 0), revokedBy = "a@example.com")
    }

    @Test
    fun `適用中の判定（期限ちょうどは期限切れ、取消済みは適用しない）`() {
        val until = Cal.jst(d, 12, 0)
        val o = make(validUntil = until)
        assertTrue(o.isActiveAt(until.minusNanos(1000)))
        assertFalse(o.isActiveAt(until))
        assertFalse(o.isActiveAt(until.plusNanos(1000)))
        assertTrue(make().isActiveAt(Instant.parse("2099-01-01T00:00:00Z")))
        assertFalse(make(revokedAt = Cal.jst(d, 6, 0), revokedBy = "a@example.com").isActiveAt(Cal.jst(d, 7, 0)))
    }

    @Test
    fun `JSON の値を書いて読み戻せる（MySQL が整形した形も読める）`() {
        val at = Instant.parse("2026-10-24T06:35:00.123456Z")
        val post = OverrideValue.PostAt(at)
        assertEquals("""{"post_at":"2026-10-24T06:35:00.123456Z"}""", post.toJson())
        assertEquals(post, OverrideValue.parse(OverrideAction.SET_POST_TIME, post.toJson()))
        assertEquals(post, OverrideValue.parse(OverrideAction.SET_POST_TIME, """{"post_at": "2026-10-24T06:35:00.123456Z"}"""))
        val to = OverrideValue.PostponedTo(LocalDate.of(2026, 10, 25))
        assertEquals("""{"to":"2026-10-25"}""", to.toJson())
        assertEquals(to, OverrideValue.parse(OverrideAction.POSTPONE_TO, """{"to": "2026-10-25"}"""))
        assertNull(OverrideValue.parse(OverrideAction.DENY_RUN, null))
    }

    @Test
    fun `形の違う JSON は黙って無視せず例外`() {
        assertFailsWith<IllegalStateException> { OverrideValue.parse(OverrideAction.SET_POST_TIME, null) }
        assertFailsWith<IllegalStateException> { OverrideValue.parse(OverrideAction.SET_POST_TIME, """{"to":"2026-10-25"}""") }
        assertFailsWith<IllegalStateException> { OverrideValue.parse(OverrideAction.SET_POST_TIME, """{"post_at":"bad"}""") }
        assertFailsWith<IllegalStateException> { OverrideValue.parse(OverrideAction.POSTPONE_TO, """{"to":"2026-13-40"}""") }
        assertFailsWith<IllegalStateException> { OverrideValue.parse(OverrideAction.DENY_RUN, """{"x":1}""") }
    }

    // --- サービス ---

    private class FakeOverrides : CalendarOverrideRepository {
        val rows = mutableListOf<CalendarOverride>()
        val events = mutableListOf<ScheduleEvent>()

        override fun find(
            tenantId: TenantId,
            id: Long,
        ) = rows.firstOrNull { it.tenantId == tenantId && it.id == id }

        override fun listByDate(
            tenantId: TenantId,
            date: LocalDate,
        ) = rows.filter { it.tenantId == tenantId && it.raceDate == date }

        override fun create(
            override: CalendarOverride,
            event: ScheduleEvent,
        ): OverrideCreateResult {
            if (events.any { it.tenantId == event.tenantId && it.dedupHash == event.dedupHash }) return OverrideCreateResult.Duplicate
            val saved = override.copy(id = rows.size + 1L)
            rows += saved
            events += event
            return OverrideCreateResult.Created(saved, event)
        }

        override fun revoke(
            tenantId: TenantId,
            id: Long,
            revokedBy: String,
            revokedAt: Instant,
            event: ScheduleEvent,
        ): OverrideRevokeResult {
            val i = rows.indexOfFirst { it.tenantId == tenantId && it.id == id }
            if (i < 0) return OverrideRevokeResult.NotFound
            if (rows[i].revokedAt != null) return OverrideRevokeResult.AlreadyRevoked
            if (events.any { it.tenantId == tenantId && it.dedupHash == event.dedupHash }) return OverrideRevokeResult.AlreadyRevoked
            rows[i] = rows[i].copy(revokedAt = revokedAt, revokedBy = revokedBy)
            events += event
            return OverrideRevokeResult.Revoked(rows[i], event)
        }
    }

    private class Races(
        private val existing: Set<RaceId>,
    ) : RaceScheduleReader {
        override fun find(
            tenantId: TenantId,
            raceId: RaceId,
        ): RaceSchedule? =
            if (raceId in
                existing
            ) {
                Cal.race(raceId.venue, raceId.raceNo, raceId.date)
            } else {
                null
            }

        override fun listByDate(
            tenantId: TenantId,
            date: LocalDate,
        ): List<RaceSchedule> = emptyList()
    }

    private val clock = FixedClock(Cal.jst(d, 5, 0))
    private val repo = FakeOverrides()
    private val service = CalendarOverrideService(repo, Races(setOf(raceId)), clock)

    @Test
    fun `作成すると監査情報つきで保存され MANUAL_OVERRIDE が追記される`() {
        val out =
            assertIs<CreateOverrideOutcome.Created>(
                service.create(Cal.T1, OverrideScope.DAY, d, null, OverrideAction.DENY_RUN, null, "  雨で中止の可能性  ", "admin@example.com", Cal.jst(d, 12, 0)),
            )
        assertEquals("雨で中止の可能性", out.override.reason)
        assertEquals(clock.instant, out.override.createdAt)
        assertEquals(1, repo.events.size)
        val e = repo.events.single()
        assertEquals(ScheduleEventType.MANUAL_OVERRIDE, e.type)
        assertEquals(ScheduleEventSource.MANUAL, e.source)
        assertNull(e.raceId)
        assertTrue(e.newValue!!.contains("\"action\":\"DENY_RUN\""))
    }

    @Test
    fun `レース単位の補正は対象のレースが予定に無ければ作らない`() {
        val ok =
            service.create(
                Cal.T1,
                OverrideScope.RACE,
                d,
                raceId,
                OverrideAction.SET_POST_TIME,
                OverrideValue.PostAt(Cal.jst(d, 10, 0)),
                "時刻確認",
                "admin@example.com",
            )
        assertIs<CreateOverrideOutcome.Created>(ok)
        assertEquals(raceId, repo.events.single().raceId)
        val missing = RaceId.of(d, Venue.TOKYO, 2)
        assertEquals(
            CreateOverrideOutcome.RaceNotFound,
            service.create(Cal.T1, OverrideScope.RACE, d, missing, OverrideAction.CANCEL, null, "中止", "admin@example.com"),
        )
        assertEquals(1, repo.rows.size)
    }

    @Test
    fun `同じ作成者・内容・時刻の二重送信は Duplicate で増えない`() {
        service.create(Cal.T1, OverrideScope.DAY, d, null, OverrideAction.DENY_RUN, null, "x", "admin@example.com")
        assertEquals(
            CreateOverrideOutcome.Duplicate,
            service.create(Cal.T1, OverrideScope.DAY, d, null, OverrideAction.DENY_RUN, null, "x", "admin@example.com"),
        )
        assertEquals(1, repo.rows.size)
        clock.instant = clock.instant.plusNanos(1000)
        assertIs<CreateOverrideOutcome.Created>(service.create(Cal.T1, OverrideScope.DAY, d, null, OverrideAction.DENY_RUN, null, "x", "admin@example.com"))
    }

    @Test
    fun `不正な入力は作る前に拒否する`() {
        assertFailsWith<IllegalArgumentException> { service.create(Cal.T1, OverrideScope.DAY, d, null, OverrideAction.DENY_RUN, null, "", "admin@example.com") }
        assertFailsWith<IllegalArgumentException> { service.create(Cal.T1, OverrideScope.DAY, d, null, OverrideAction.DENY_RUN, null, "x", "") }
        assertTrue(repo.rows.isEmpty())
    }

    @Test
    fun `取消は MANUAL_REVOKE を追記し、二度目は AlreadyRevoked`() {
        service.create(Cal.T1, OverrideScope.DAY, d, null, OverrideAction.DENY_RUN, null, "x", "admin@example.com")
        clock.instant = Cal.jst(d, 6, 0)
        val r = assertIs<OverrideRevokeResult.Revoked>(service.revoke(Cal.T1, 1, "boss@example.com"))
        assertEquals("boss@example.com", r.override.revokedBy)
        assertEquals(ScheduleEventType.MANUAL_REVOKE, r.event.type)
        assertEquals(OverrideRevokeResult.AlreadyRevoked, service.revoke(Cal.T1, 1, "boss@example.com"))
        assertEquals(2, repo.events.size)
        assertEquals(OverrideRevokeResult.NotFound, service.revoke(Cal.T1, 99, "boss@example.com"))
        assertEquals(OverrideRevokeResult.NotFound, service.revoke(Cal.T2, 1, "boss@example.com"))
        assertFailsWith<IllegalArgumentException> { service.revoke(Cal.T1, 1, "") }
    }
}
