package jp.jrabot.persistence.integration

import jp.jrabot.domain.bot.BotRunTrigger
import jp.jrabot.domain.bot.LeaseRequest
import jp.jrabot.domain.bot.RunId
import jp.jrabot.domain.calendar.CalendarOverride
import jp.jrabot.domain.calendar.EventJson
import jp.jrabot.domain.calendar.OverrideAction
import jp.jrabot.domain.calendar.OverrideScope
import jp.jrabot.domain.calendar.OverrideValue
import jp.jrabot.domain.calendar.RaceDay
import jp.jrabot.domain.calendar.RaceDayStatus
import jp.jrabot.domain.calendar.ScheduleEvent
import jp.jrabot.domain.calendar.ScheduleEventSource
import jp.jrabot.domain.calendar.ScheduleEventType
import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.odds.Odds
import jp.jrabot.domain.odds.OddsEntry
import jp.jrabot.domain.odds.OddsSnapshot
import jp.jrabot.domain.race.HorseNumber
import jp.jrabot.domain.race.Race
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.race.RaceSchedule
import jp.jrabot.domain.race.RaceScheduleStatus
import jp.jrabot.domain.race.Venue
import jp.jrabot.domain.signal.Signal
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.value.Probability
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** 合成データ（実データではない）。 */
object Fixtures {
    val DATE: LocalDate = LocalDate.of(2026, 10, 24)
    val OBSERVED: Instant = Instant.parse("2026-10-24T00:30:00.250000Z")

    fun raceId(
        venue: Venue = Venue.TOKYO,
        no: Int = 11,
        date: LocalDate = DATE,
    ): RaceId = RaceId.of(date, venue, no)

    fun race(
        tenantId: TenantId = T1,
        id: RaceId = raceId(),
        name: String? = "テストレース",
        postTime: LocalTime? = LocalTime.of(15, 40),
        distance: Int? = 2000,
    ) = Race(tenantId, id, kai = 4, nichime = 8, postTime = postTime, name = name, distanceMeters = distance)

    fun odds(
        tenantId: TenantId = T1,
        raceId: RaceId = raceId(),
        market: Market = Market.WIN,
        observedAt: Instant = OBSERVED,
        values: Array<Pair<Int, Double>> = arrayOf(1 to 2.5, 2 to 10.0, 3 to 3.3),
    ) = OddsSnapshot(
        tenantId,
        raceId,
        market,
        observedAt,
        values.mapIndexed { i, (n, o) -> OddsEntry(HorseNumber.of(n), Odds.of(o), i + 1) },
    )

    val NOW: Instant = Instant.parse("2026-10-24T00:00:00.500000Z")

    fun lease(
        tenantId: TenantId = T1,
        runDate: LocalDate = DATE,
        runId: RunId = RunId.of(UUID.randomUUID()),
        owner: String = "owner-1",
        now: Instant = NOW,
        duration: Duration = Duration.ofMinutes(5),
        trigger: BotRunTrigger = BotRunTrigger.SCHEDULER,
    ) = LeaseRequest(tenantId, runId, runDate, trigger, owner, now, duration)

    fun schedule(
        tenantId: TenantId = T1,
        raceId: RaceId = raceId(),
        postAt: Instant? = Instant.parse("2026-10-24T06:40:00Z"),
        status: RaceScheduleStatus = RaceScheduleStatus.ANNOUNCED,
    ) = RaceSchedule(
        tenantId = tenantId,
        raceId = raceId,
        scheduledPostAt = postAt,
        postTimeUnknown = postAt == null,
        status = status,
        dataKubun = '1',
        sourceVersion = "202610231",
        raceName = "予定レース",
        lastChangedAt = Instant.parse("2026-10-23T03:00:00Z"),
    )

    /** [schedule] の変更に付ける履歴（種類・グループを変えれば別の履歴になる）。 */
    fun event(
        schedule: RaceSchedule,
        type: ScheduleEventType = ScheduleEventType.ADDED,
        group: String = "g1",
        batchId: String? = null,
    ) = ScheduleEvent.create(
        tenantId = schedule.tenantId,
        raceId = schedule.raceId,
        raceDate = schedule.raceId.date,
        type = type,
        oldValue = null,
        newValue = EventJson.obj("status" to schedule.status.name),
        source = ScheduleEventSource.RACE,
        observedAt = Instant.parse("2026-10-23T02:59:59.123456Z"),
        receivedAt = Instant.parse("2026-10-23T03:00:00.000001Z"),
        dedupGroup = group,
        batchId = batchId,
    )

    fun signal(
        tenantId: TenantId = T1,
        raceId: RaceId = raceId(),
        umaban: Int = 3,
        market: Market = Market.WIN,
        observedAt: Instant = OBSERVED,
    ) = Signal(
        tenantId = tenantId,
        id = null,
        raceId = raceId,
        market = market,
        umaban = HorseNumber.of(umaban),
        observedAt = observedAt,
        odds = Odds.of(3.3),
        prob = Probability.of(0.4),
        ev = 0.32,
        isPaper = true,
        stake = 1.0,
        probSource = "market",
    )

    // --- 開催日・手動補正・履歴 ---

    fun day(
        tenantId: TenantId = T1,
        status: RaceDayStatus = RaceDayStatus.PLANNED,
        venue: Venue = Venue.TOKYO,
        date: LocalDate = DATE,
        confirmedAt: Instant? = NOW,
        replacedBy: LocalDate? = null,
    ) = RaceDay(tenantId, date, venue, 4, 8, status, replacedBy, Instant.parse("2026-10-20T03:00:00.000001Z"), confirmedAt)

    /** 日単位の履歴（レースを指さない）。[group] を変えれば別の履歴になる。 */
    fun dayEvent(
        tenantId: TenantId = T1,
        group: String = "d1",
        type: ScheduleEventType = ScheduleEventType.DAY_STATUS,
    ) = ScheduleEvent.create(
        tenantId,
        null,
        DATE,
        type,
        null,
        EventJson.obj("status" to "PLANNED", "venue" to "05"),
        ScheduleEventSource.YSCH,
        Instant.parse("2026-10-23T02:59:59.123456Z"),
        Instant.parse("2026-10-23T03:00:00.000001Z"),
        group,
    )

    fun staleEvent(
        tenantId: TenantId = T1,
        group: String = "stale",
    ) = ScheduleEvent.create(
        tenantId,
        null,
        DATE,
        ScheduleEventType.STALE,
        null,
        EventJson.obj("reason" to "AGENT_SILENT"),
        ScheduleEventSource.SYSTEM,
        Instant.parse("2026-10-24T03:00:00Z"),
        Instant.parse("2026-10-24T03:00:00Z"),
        group,
    )

    fun manual(
        tenantId: TenantId = T1,
        by: String = "admin@example.com",
        action: OverrideAction = OverrideAction.DENY_RUN,
        scope: OverrideScope = OverrideScope.DAY,
        race: RaceId? = null,
        value: OverrideValue? = null,
        createdAt: Instant = NOW,
        validUntil: Instant? = null,
    ) = CalendarOverride(tenantId, null, scope, DATE, race, action, value, "理由（合成）", by, createdAt, validUntil)

    fun overrideEvent(
        tenantId: TenantId = T1,
        by: String = "admin@example.com",
        race: RaceId? = null,
        group: String = "o1",
    ) = ScheduleEvent.create(
        tenantId,
        race,
        DATE,
        ScheduleEventType.MANUAL_OVERRIDE,
        null,
        EventJson.obj("action" to "X", "created_by" to by),
        ScheduleEventSource.MANUAL,
        NOW,
        NOW,
        group,
    )

    fun revokeEvent(override: CalendarOverride) =
        ScheduleEvent.create(
            override.tenantId,
            override.raceId,
            override.raceDate,
            ScheduleEventType.MANUAL_REVOKE,
            null,
            EventJson.obj("override_id" to checkNotNull(override.id)),
            ScheduleEventSource.MANUAL,
            NOW,
            NOW,
            "revoke:${override.id}",
        )
}

/** [user]（本番と同じ最小権限のユーザー）で生の JDBC 接続を開いて [block] を実行する。 */
fun <T> TestDb.asUser(
    user: String,
    block: (java.sql.Connection) -> T,
): T =
    java.sql.DriverManager
        .getConnection(MySqlSupport.jdbcUrl(name), user, MySqlSupport.PASSWORDS.getValue(user))
        .use(block)
