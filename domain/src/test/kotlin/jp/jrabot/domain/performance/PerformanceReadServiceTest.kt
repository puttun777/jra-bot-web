package jp.jrabot.domain.performance

import jp.jrabot.domain.botrun.TimingNotice
import jp.jrabot.domain.botrun.testing.InMemoryScheduleReader
import jp.jrabot.domain.botrun.testing.InMemorySignals
import jp.jrabot.domain.botrun.testing.InMemoryTimingNotices
import jp.jrabot.domain.botrun.testing.ManualClock
import jp.jrabot.domain.calendar.Cal
import jp.jrabot.domain.ingest.PayoutRow
import jp.jrabot.domain.ingest.PayoutStore
import jp.jrabot.domain.ingest.ResultEntry
import jp.jrabot.domain.ingest.ResultStore
import jp.jrabot.domain.ingest.StoredPayout
import jp.jrabot.domain.notify.BundleView
import jp.jrabot.domain.notify.NoticeItem
import jp.jrabot.domain.notify.NoticeSnapshot
import jp.jrabot.domain.notify.RaceLabel
import jp.jrabot.domain.notify.SignalView
import jp.jrabot.domain.notify.StrategyView
import jp.jrabot.domain.notify.TicketView
import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.odds.Odds
import jp.jrabot.domain.race.HorseNumber
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.repository.UpsertOutcome
import jp.jrabot.domain.signal.Signal
import jp.jrabot.domain.signal.SignalId
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.value.Probability
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PerformanceReadServiceTest {
    private val t1 = Cal.T1
    private val races = Cal.races(count = 2)
    private val r1 = races[0].raceId
    private val r2 = races[1].raceId
    private val post1 = races[0].scheduledPostAt!!
    private val clock = ManualClock(post1)
    private val signals = InMemorySignals(clock)
    private val timings = InMemoryTimingNotices()

    private class Pay :
        ResultStore,
        PayoutStore {
        val res = HashMap<RaceId, List<ResultEntry>>()
        val pay = HashMap<Pair<RaceId, Market>, List<StoredPayout>>()

        override fun upsertAll(
            tenantId: TenantId,
            raceId: RaceId,
            entries: List<ResultEntry>,
        ) = UpsertOutcome.CREATED

        override fun list(
            tenantId: TenantId,
            raceId: RaceId,
        ) = res[raceId].orEmpty()

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
        ) = pay[raceId to market].orEmpty()
    }

    private val store = Pay()
    private val service = PerformanceReadService(InMemoryScheduleReader(races), signals, timings, store, store)

    private fun sentSignal(
        race: RaceId,
        umaban: Int,
        at: Instant,
        odds: Double = 8.0,
        stake: Double = 3.0,
        send: Boolean = true,
    ) {
        val s =
            signals
                .insertIfAbsent(
                    Signal(t1, null, race, Market.WIN, HorseNumber.of(umaban), at, Odds.of(odds), Probability.of(0.2), 0.6, true, stake, "market"),
                ).signal
        if (send) {
            val id = checkNotNull(s.id)
            signals.claim(t1, id)
            signals.markSent(t1, id)
        }
    }

    private fun notice(
        race: RaceId,
        at: Instant,
        centi: Int,
        kumi: String,
        stake: Double,
    ) {
        val view =
            SignalView(
                "ワイド",
                5,
                3.0,
                0.2,
                0.1,
                bundle =
                    BundleView(
                        listOf(StrategyView("ワイド", stake, 1.0, 3.0, 0.2, 0.1, null, listOf(TicketView(kumi, "3.0〜4.0", 0.2, 0.1, stake)), null)),
                        null,
                        stake,
                        1.0,
                    ),
            )
        val snap = NoticeSnapshot(RaceLabel("東京", 1, "R1"), listOf(NoticeItem(SignalId.of(1), view)))
        timings.claim(listOf(TimingNotice(t1, race, centi, "run", 1L, at, NoticeSnapshot.SCHEMA, snap.canonicalJson(), snap.sha256(), at)))
    }

    @Test
    fun `通知済みの単勝とワイドの紙上成績を計算し 未通知・結果なしは数えない`() {
        val at = post1.minusSeconds(300)
        sentSignal(r1, 2, at) // 的中
        sentSignal(r1, 4, at, odds = 15.0, stake = 1.0) // 外れ
        sentSignal(r1, 6, at, send = false) // 未通知は対象外
        store.res[r1] = listOf(ResultEntry(2, 1), ResultEntry(4, 2), ResultEntry(5, 3))
        store.pay[r1 to Market.WIN] = listOf(StoredPayout("02", 800.0, false))
        notice(r1, at, 500, "5-7", 300.0)
        store.pay[r1 to Market.WIDE] = listOf(StoredPayout("05-07", 1200.0, false))
        sentSignal(r2, 2, post1.plusSeconds(1500)) // 結果がまだ無いレース
        val r = service.report(t1, Cal.DATE, Cal.DATE)
        assertEquals(2, r.win.count)
        assertEquals(1, r.win.hits)
        assertEquals(4.0, r.win.totalStake)
        assertEquals(3.0 * 800.0 / 100.0, r.win.actualPayout)
        assertEquals(1, r.winUnsettled) // r2 の結果なし
        val wide = r.combos.single()
        assertEquals(Market.WIDE, wide.market)
        assertEquals(3600.0, wide.actualPayout)
        assertEquals(0, r.unreadableNotices)
    }

    @Test
    fun `壊れた通知内容は数えず件数で報告する`() {
        val at = post1.minusSeconds(300)
        timings.claim(listOf(TimingNotice(t1, r1, 500, "run", 1L, at, NoticeSnapshot.SCHEMA, "{}", "0".repeat(64), at)))
        assertEquals(1, service.report(t1, Cal.DATE, Cal.DATE).unreadableNotices)
    }

    @Test
    fun `期間は検査する`() {
        assertFailsWith<IllegalArgumentException> { service.report(t1, Cal.DATE, Cal.DATE.minusDays(1)) }
        assertFailsWith<IllegalArgumentException> { service.report(t1, Cal.DATE, Cal.DATE.plusDays(400)) }
    }
}
