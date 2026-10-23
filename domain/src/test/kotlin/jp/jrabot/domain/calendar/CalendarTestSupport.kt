package jp.jrabot.domain.calendar

import jp.jrabot.domain.bot.BotRun
import jp.jrabot.domain.bot.BotRunReader
import jp.jrabot.domain.bot.BotRunState
import jp.jrabot.domain.bot.BotRunTrigger
import jp.jrabot.domain.bot.RunId
import jp.jrabot.domain.ops.ModeProvider
import jp.jrabot.domain.ops.OpsMode
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.race.RaceSchedule
import jp.jrabot.domain.race.RaceScheduleRepository
import jp.jrabot.domain.race.RaceScheduleStatus
import jp.jrabot.domain.race.Venue
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Clock
import jp.jrabot.domain.time.Zones
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** 合成データ（実データではない）。設定値はテスト用の固定値で、運用の既定値ではない（D-016・D-122）。 */
object Cal {
    val T1: TenantId = TenantId.of(1)
    val T2: TenantId = TenantId.of(2)

    /** 2026-10-24 は土曜。曜日・祝日は判定に使わないので、日付は何でもよい。 */
    val DATE: LocalDate = LocalDate.of(2026, 10, 24)

    val WATCH = WatchSettings(watchStart = Duration.ofMinutes(120), prestart = Duration.ofMinutes(10), settleDeadline = Duration.ofMinutes(90))
    val SETTINGS = CalendarSettings(WATCH, staleAfter = Duration.ofHours(2))

    fun jst(
        date: LocalDate,
        h: Int,
        m: Int,
        s: Int = 0,
    ): Instant = date.atTime(h, m, s).atZone(Zones.JST).toInstant()

    fun race(
        venue: Venue = Venue.TOKYO,
        no: Int = 1,
        date: LocalDate = DATE,
        status: RaceScheduleStatus = RaceScheduleStatus.CARD,
        post: LocalTime? = LocalTime.of(9, 55).plusMinutes(25L * (no - 1)),
        tenant: TenantId = T1,
        sourceVersion: String = "202610232",
        kubun: Char = '2',
    ) = RaceSchedule(
        tenantId = tenant,
        raceId = RaceId.of(date, venue, no),
        scheduledPostAt = post?.let { date.atTime(it).atZone(Zones.JST).toInstant() },
        postTimeUnknown = post == null,
        status = status,
        dataKubun = kubun,
        sourceVersion = sourceVersion,
        raceName = "R$no",
        lastChangedAt = Instant.parse("2026-10-23T03:00:00Z"),
    )

    fun races(
        venue: Venue = Venue.TOKYO,
        date: LocalDate = DATE,
        count: Int = 12,
        status: RaceScheduleStatus = RaceScheduleStatus.CARD,
    ) = (1..count).map { race(venue, it, date, status) }

    fun day(
        venue: Venue = Venue.TOKYO,
        date: LocalDate = DATE,
        status: RaceDayStatus = RaceDayStatus.CONFIRMED,
        confirmedAt: Instant? = jst(date, 6, 0),
        replacedBy: LocalDate? = null,
        tenant: TenantId = T1,
    ) = RaceDay(tenant, date, venue, 4, 8, status, replacedBy, firstSeenAt = jst(date.minusDays(3), 12, 0), lastConfirmedAt = confirmedAt)

    private var seq = 0L

    fun override(
        action: OverrideAction,
        scope: OverrideScope = if (action == OverrideAction.SET_POST_TIME) OverrideScope.RACE else OverrideScope.DAY,
        date: LocalDate = DATE,
        raceNo: Int? = null,
        venue: Venue = Venue.TOKYO,
        value: OverrideValue? =
            when (action) {
                OverrideAction.SET_POST_TIME -> OverrideValue.PostAt(jst(date, 10, 0))
                OverrideAction.POSTPONE_TO -> OverrideValue.PostponedTo(date.plusDays(1))
                else -> null
            },
        createdAt: Instant = jst(date, 5, 0),
        validUntil: Instant? = null,
        revokedAt: Instant? = null,
        id: Long? = ++seq,
        tenant: TenantId = T1,
    ) = CalendarOverride(
        tenantId = tenant,
        id = id,
        scope = scope,
        raceDate = date,
        raceId = if (scope == OverrideScope.RACE) RaceId.of(date, venue, raceNo ?: 1) else null,
        action = action,
        value = value,
        reason = "テスト",
        createdBy = "admin@example.com",
        createdAt = createdAt,
        validUntil = validUntil,
        revokedAt = revokedAt,
        revokedBy = if (revokedAt != null) "admin@example.com" else null,
    )

    fun derive(
        now: Instant,
        days: List<RaceDay>,
        races: List<RaceSchedule>,
        overrides: List<CalendarOverride> = emptyList(),
        fact: ScheduleFetchFact? = null,
        date: LocalDate = DATE,
        settings: CalendarSettings = SETTINGS,
    ) = EffectiveCalendar.derive(T1, date, now, settings, days, races, overrides, fact)

    fun run(
        date: LocalDate = DATE,
        state: BotRunState = BotRunState.RUNNING,
        expiresAt: Instant?,
        tenant: TenantId = T1,
    ) = BotRun(
        tenantId = tenant,
        runId = RunId.of(UUID.randomUUID()),
        runDate = date,
        trigger = BotRunTrigger.SCHEDULER,
        state = state,
        leaseOwner = "owner",
        leaseExpiresAt = expiresAt,
        heartbeatAt = jst(date, 6, 0),
        checkpoint = null,
        startedAt = jst(date, 6, 0),
        finishedAt = null,
        exitReason = null,
    )
}

class FixedClock(
    var instant: Instant,
) : Clock {
    override fun now(): Instant = instant
}

class FixedMode(
    var mode: OpsMode,
) : ModeProvider {
    var calls = 0

    override fun current(): OpsMode {
        calls++
        return mode
    }
}

/** 永続化層の意味（原子性・冪等性・楽観ロック）を写したメモリ実装。ドメインのロジックのテスト用。 */
class InMemorySchedules :
    RaceScheduleRepository,
    ScheduleEventRepository {
    val rows = linkedMapOf<Pair<TenantId, RaceId>, RaceSchedule>()
    val events = mutableListOf<ScheduleEvent>()

    /** 次の update を競合させる回数（他の更新が先に入った状況の再現）。 */
    var injectConflicts = 0

    override fun find(
        tenantId: TenantId,
        raceId: RaceId,
    ) = rows[tenantId to raceId]

    override fun listByDate(
        tenantId: TenantId,
        date: LocalDate,
    ) = rows.values.filter { it.tenantId == tenantId && it.raceId.date == date }.sortedBy { it.raceId }

    private fun duplicate(e: ScheduleEvent) = events.any { it.tenantId == e.tenantId && it.dedupHash == e.dedupHash }

    private fun stored(e: ScheduleEvent) = e.copy(id = events.size + 1L).also { events += it }

    override fun insertIfAbsent(
        schedule: RaceSchedule,
        event: ScheduleEvent,
    ): ChangeResult<RaceSchedule> {
        if (rows.containsKey(schedule.tenantId to schedule.raceId)) return ChangeResult.AlreadyExists
        if (duplicate(event)) return ChangeResult.Duplicate
        rows[schedule.tenantId to schedule.raceId] = schedule
        return ChangeResult.Applied(schedule, stored(event))
    }

    override fun update(
        schedule: RaceSchedule,
        event: ScheduleEvent,
    ): ChangeResult<RaceSchedule> {
        val current = rows[schedule.tenantId to schedule.raceId] ?: return ChangeResult.NotFound
        if (injectConflicts > 0) {
            injectConflicts--
            return ChangeResult.Conflict
        }
        if (current.version != schedule.version) return ChangeResult.Conflict
        if (duplicate(event)) return ChangeResult.Duplicate
        val next = schedule.copy(version = schedule.version + 1)
        rows[schedule.tenantId to schedule.raceId] = next
        return ChangeResult.Applied(next, stored(event))
    }

    override fun eventsOfRace(
        tenantId: TenantId,
        raceId: RaceId,
    ) = events.filter { it.tenantId == tenantId && it.raceId == raceId }

    override fun eventsOfDate(
        tenantId: TenantId,
        date: LocalDate,
    ) = events.filter { it.tenantId == tenantId && it.raceDate == date }

    override fun append(event: ScheduleEvent): AppendResult = if (duplicate(event)) AppendResult.Duplicate else AppendResult.Appended(stored(event))
}

class InMemoryDays : RaceDayRepository {
    val rows = linkedMapOf<Triple<TenantId, LocalDate, Venue>, RaceDay>()
    val events = mutableListOf<ScheduleEvent>()

    override fun find(
        tenantId: TenantId,
        date: LocalDate,
        venue: Venue,
    ) = rows[Triple(tenantId, date, venue)]

    override fun listByDate(
        tenantId: TenantId,
        date: LocalDate,
    ) = rows.values.filter { it.tenantId == tenantId && it.raceDate == date }.sortedBy { it.venue }

    private fun duplicate(e: ScheduleEvent) = events.any { it.dedupHash == e.dedupHash }

    override fun insertIfAbsent(
        day: RaceDay,
        event: ScheduleEvent,
    ): ChangeResult<RaceDay> {
        if (rows.containsKey(Triple(day.tenantId, day.raceDate, day.venue))) return ChangeResult.AlreadyExists
        if (duplicate(event)) return ChangeResult.Duplicate
        rows[Triple(day.tenantId, day.raceDate, day.venue)] = day
        events += event.copy(id = events.size + 1L)
        return ChangeResult.Applied(day, events.last())
    }

    override fun update(
        day: RaceDay,
        event: ScheduleEvent,
    ): ChangeResult<RaceDay> {
        val key = Triple(day.tenantId, day.raceDate, day.venue)
        val current = rows[key] ?: return ChangeResult.NotFound
        if (current.version != day.version) return ChangeResult.Conflict
        if (duplicate(event)) return ChangeResult.Duplicate
        val next = day.copy(version = day.version + 1)
        rows[key] = next
        events += event.copy(id = events.size + 1L)
        return ChangeResult.Applied(next, events.last())
    }

    override fun confirm(
        tenantId: TenantId,
        date: LocalDate,
        venue: Venue,
        observedAt: Instant,
    ): Boolean {
        val key = Triple(tenantId, date, venue)
        val current = rows[key] ?: return false
        if (current.lastConfirmedAt == null ||
            current.lastConfirmedAt.isBefore(observedAt)
        ) {
            rows[key] = current.copy(lastConfirmedAt = observedAt, version = current.version + 1)
        }
        return true
    }
}
