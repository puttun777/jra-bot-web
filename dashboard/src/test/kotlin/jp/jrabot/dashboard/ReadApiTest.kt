package jp.jrabot.dashboard

import jp.jrabot.dashboard.api.ApiAuthFilter
import jp.jrabot.dashboard.api.ReadApi
import jp.jrabot.domain.botrun.testing.InMemoryBotRunRepository
import jp.jrabot.domain.botrun.testing.InMemoryRaceDays
import jp.jrabot.domain.botrun.testing.InMemoryScheduleReader
import jp.jrabot.domain.botrun.testing.InMemorySignals
import jp.jrabot.domain.botrun.testing.InMemoryTimingNotices
import jp.jrabot.domain.botrun.testing.ManualClock
import jp.jrabot.domain.calendar.RaceDay
import jp.jrabot.domain.calendar.RaceDayStatus
import jp.jrabot.domain.ingest.PayoutRow
import jp.jrabot.domain.ingest.PayoutStore
import jp.jrabot.domain.ingest.ResultEntry
import jp.jrabot.domain.ingest.ResultStore
import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.odds.Odds
import jp.jrabot.domain.ops.InspectableModeProvider
import jp.jrabot.domain.ops.ModeSnapshot
import jp.jrabot.domain.ops.OpsMode
import jp.jrabot.domain.performance.PerformanceReadService
import jp.jrabot.domain.race.HorseNumber
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.race.RaceSchedule
import jp.jrabot.domain.race.RaceScheduleStatus
import jp.jrabot.domain.race.Venue
import jp.jrabot.domain.repository.UpsertOutcome
import jp.jrabot.domain.signal.Signal
import jp.jrabot.domain.tenant.AppUserRole
import jp.jrabot.domain.tenant.EmailAddress
import jp.jrabot.domain.tenant.Principal
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.value.Probability
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 読取り API の単体試験（本物の MySQL・認証フィルタは別の試験）。テナント分離・入力検査・ページング・『未更新』の判定を確かめる。 */
class ReadApiTest {
    private val t1 = TenantId.of(1)
    private val t2 = TenantId.of(2)
    private val date = LocalDate.of(2026, 10, 24)
    private val now = Instant.parse("2026-10-24T01:00:00Z") // JST 10:00
    private val clock = ManualClock(now)

    private fun race(
        tenant: TenantId,
        no: Int,
        postOffsetMin: Long,
    ) = RaceSchedule(
        tenant,
        RaceId.of(date, Venue.TOKYO, no),
        now.plusSeconds(postOffsetMin * 60),
        false,
        RaceScheduleStatus.CARD,
        '2',
        "202610232",
        "R$no",
        now.minusSeconds(3600),
    )

    private val schedules = InMemoryScheduleReader(listOf(race(t1, 1, 30), race(t1, 2, 60), race(t2, 1, 30)))
    private val days =
        InMemoryRaceDays(listOf(RaceDay(t1, date, Venue.TOKYO, 4, 8, RaceDayStatus.RUNNING, null, now.minusSeconds(86400), now.minusSeconds(600))))
    private val signals = InMemorySignals(clock)
    private val runs = InMemoryBotRunRepository()

    private val mode =
        object : InspectableModeProvider {
            override fun current() = OpsMode.NORMAL

            override fun snapshot() = ModeSnapshot(OpsMode.NORMAL, OpsMode.NORMAL, 1, 0, "test")
        }

    private val store =
        object : ResultStore, PayoutStore {
            override fun upsertAll(
                tenantId: TenantId,
                raceId: RaceId,
                entries: List<ResultEntry>,
            ) = UpsertOutcome.CREATED

            override fun list(
                tenantId: TenantId,
                raceId: RaceId,
            ) = emptyList<ResultEntry>()

            override fun upsertAll(
                tenantId: TenantId,
                raceId: RaceId,
                market: Market,
                rows: List<PayoutRow>,
                provisional: Boolean,
            ) = UpsertOutcome.CREATED

            override fun list(
                tenantId: TenantId,
                raceId: RaceId,
                market: Market,
            ) = emptyList<jp.jrabot.domain.ingest.StoredPayout>()
        }

    private val api = ReadApi(clock, mode, days, schedules, signals, runs, PerformanceReadService(schedules, signals, InMemoryTimingNotices(), store, store))

    private fun req(tenant: TenantId = t1): MockHttpServletRequest =
        MockHttpServletRequest().also {
            it.setAttribute(
                ApiAuthFilter.PRINCIPAL_ATTRIBUTE,
                Principal(tenant, EmailAddress.of("me@example.com"), AppUserRole.VIEWER),
            )
        }

    private fun addSignal(
        tenant: TenantId,
        race: RaceId,
        umaban: Int,
        at: Instant,
    ) {
        signals.insertIfAbsent(Signal(tenant, null, race, Market.WIN, HorseNumber.of(umaban), at, Odds.of(8.0), Probability.of(0.2), 0.6, true, 3.0, "market"))
    }

    @Test
    fun `status は自分のテナントの開催日・次の発走・モードを返し 確認が新しければ未更新にしない`() {
        val s = assertNotNull(api.status(req()).body)
        assertEquals("NORMAL", s.mode)
        assertEquals(date, s.today)
        assertEquals(1, s.days.size)
        assertEquals(now.plusSeconds(1800), s.nextRaceAt)
        assertEquals(false, s.scheduleStale)
    }

    @Test
    fun `status は最終確認が古ければ未更新を示し 他テナントの開催日は見えない`() {
        days.rows = listOf(days.rows.single().copy(lastConfirmedAt = now.minusSeconds(3 * 3600)))
        assertTrue(assertNotNull(api.status(req()).body).scheduleStale)
        val other = assertNotNull(api.status(req(t2)).body)
        assertEquals(0, other.days.size)
        assertEquals(false, other.scheduleStale, "開催日が無いテナントは『未更新』ではなく空")
    }

    @Test
    fun `races と signals はテナントで分離され signals はページングできる`() {
        val r1 = RaceId.of(date, Venue.TOKYO, 1)
        for (u in 1..5) addSignal(t1, r1, u, now.minusSeconds(60L * u))
        addSignal(t2, r1, 9, now)
        assertEquals(2, assertNotNull(api.races(req(), null).body).size)
        assertEquals(1, assertNotNull(api.races(req(t2), null).body).size)
        val p0 = assertNotNull(api.signals(req(), null, 0, 2).body)
        assertEquals(5, p0.total)
        assertEquals(2, p0.items.size)
        assertEquals(listOf(1, 2), p0.items.map { it.umaban }, "観測の新しい順")
        val p2 = assertNotNull(api.signals(req(), null, 2, 2).body)
        assertEquals(listOf(5), p2.items.map { it.umaban })
        assertEquals(listOf(9), assertNotNull(api.signals(req(t2), null, 0, 50).body).items.map { it.umaban })
    }

    @Test
    fun `不正な日付・ページ・期間は 400 で 値を含めない`() {
        for (call in listOf<() -> Any?>(
            { api.races(req(), "2026-13-45") },
            { api.signals(req(), null, -1, 10) },
            { api.signals(req(), null, 0, 0) },
            { api.signals(req(), null, 0, 1000) },
            { api.performance(req(), "2026-10-24", "2026-10-01") },
            { api.performance(req(), "2020-01-01", "2026-10-24") },
        )) {
            val e = assertFailsWith<ResponseStatusException> { call() }
            assertEquals(400, e.statusCode.value())
            assertEquals("invalid_request", e.reason)
        }
    }

    @Test
    fun `performance は結果が無ければ空の集計と未決済の件数を返す`() {
        val r1 = RaceId.of(date, Venue.TOKYO, 1)
        addSignal(t1, r1, 2, now)
        val sent = signals.all().single()
        signals.claim(t1, checkNotNull(sent.id))
        signals.markSent(t1, checkNotNull(sent.id))
        val p = assertNotNull(api.performance(req(), "2026-10-24", "2026-10-24").body)
        assertEquals(0, p.winCount)
        assertEquals(1, p.winUnsettled)
        assertEquals(null, p.winHitRate)
    }

    @Test
    fun `応答は no-store`() {
        assertEquals("no-store", api.status(req()).headers.getFirst("Cache-Control"))
    }
}
