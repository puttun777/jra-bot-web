package jp.jrabot.persistence.integration

import jp.jrabot.domain.bot.BotRun
import jp.jrabot.domain.bot.BotRunState
import jp.jrabot.domain.bot.BotRunTrigger
import jp.jrabot.domain.bot.RunId
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

    fun run(
        tenantId: TenantId = T1,
        runDate: LocalDate = DATE,
        state: BotRunState = BotRunState.STARTING,
        runId: RunId = RunId.of(UUID.randomUUID()),
        startedAt: Instant = Instant.parse("2026-10-24T00:00:00.500000Z"),
    ) = BotRun(
        tenantId = tenantId,
        runId = runId,
        runDate = runDate,
        trigger = BotRunTrigger.SCHEDULER,
        state = state,
        leaseOwner = "owner-1",
        leaseExpiresAt = startedAt.plusSeconds(300),
        heartbeatAt = startedAt,
        checkpoint = null,
        startedAt = startedAt,
        finishedAt = null,
        exitReason = null,
    )

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
}
