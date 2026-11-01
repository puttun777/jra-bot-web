package jp.jrabot.domain.performance

import jp.jrabot.domain.ingest.ResultEntry
import jp.jrabot.domain.ingest.StoredPayout
import jp.jrabot.domain.race.RaceId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PerformanceTest {
    private val race = RaceId.parse("202610300511")
    private val post = Instant.parse("2026-10-30T06:40:00Z")
    private val res = listOf(ResultEntry(1, 2), ResultEntry(2, 1), ResultEntry(3, null))

    private fun sig(
        umaban: Int,
        odds: Double = 3.5,
        stake: Double = 100.0,
    ) = WinSignal(race, umaban, post.minusSeconds(300), odds, stake)

    @Test
    fun `1 着は stake × odds 外れは 0 損益は払戻 − stake`() {
        val hit = PaperSettlement.settleWin(sig(2), res, emptyList()) as PaperOutcome.Settled
        assertTrue(hit.hit)
        assertEquals(350.0, hit.paperPayout)
        assertEquals(250.0, hit.paperProfit)
        assertNull(hit.actualPayout)
        val miss = PaperSettlement.settleWin(sig(1), res, emptyList()) as PaperOutcome.Settled
        assertEquals(0.0, miss.paperPayout)
        assertEquals(-100.0, miss.paperProfit)
    }

    @Test
    fun `実払戻は 100 円あたりの値から計算する 予測オッズとは別の値`() {
        val pay = listOf(StoredPayout("02", 420.0, false))
        val hit = PaperSettlement.settleWin(sig(2), res, pay) as PaperOutcome.Settled
        assertEquals(350.0, hit.paperPayout)
        assertEquals(420.0, hit.actualPayout)
        assertEquals(320.0, hit.actualProfit)
        val miss = PaperSettlement.settleWin(sig(1), res, pay) as PaperOutcome.Settled
        assertEquals(0.0, miss.actualPayout)
    }

    @Test
    fun `結果が無い 馬が結果に無い 着順が無い は未確定で外れにしない`() {
        assertEquals(PaperOutcome.Unsettled(UnsettledReason.NO_RESULT), PaperSettlement.settleWin(sig(1), emptyList(), emptyList()))
        assertEquals(PaperOutcome.Unsettled(UnsettledReason.NO_RESULT), PaperSettlement.settleWin(sig(1), listOf(ResultEntry(1, null)), emptyList()))
        assertEquals(PaperOutcome.Unsettled(UnsettledReason.HORSE_NOT_IN_RESULT), PaperSettlement.settleWin(sig(9), res, emptyList()))
        assertEquals(PaperOutcome.Unsettled(UnsettledReason.NO_FINISH), PaperSettlement.settleWin(sig(3), res, emptyList()))
    }

    @Test
    fun `見送り stake 0 は的中率に数えない 着順 0 の取消は外れにしない 速報の払戻は実払戻に使わない`() {
        assertEquals(PaperOutcome.Unsettled(UnsettledReason.NOT_A_BET), PaperSettlement.settleWin(sig(2, stake = 0.0), res, emptyList()))
        assertEquals(PaperOutcome.Unsettled(UnsettledReason.NOT_A_BET), PaperSettlement.settleWin(sig(2, odds = 0.0), res, emptyList()))
        val zero = listOf(ResultEntry(1, 1), ResultEntry(2, 0))
        assertEquals(PaperOutcome.Unsettled(UnsettledReason.NO_FINISH), PaperSettlement.settleWin(sig(2), zero, emptyList()))
        assertEquals(PaperOutcome.Unsettled(UnsettledReason.NO_RESULT), PaperSettlement.settleWin(sig(2), listOf(ResultEntry(2, 0)), emptyList()))
        val prov = listOf(StoredPayout("02", 999.0, true))
        val hit = PaperSettlement.settleWin(sig(2), res, prov) as PaperOutcome.Settled
        assertNull(hit.actualPayout, "速報だけなら実払戻は不明")
        assertTrue(hit.hit)
    }

    @Test
    fun `同着の 1 着は両方的中`() {
        val tie = listOf(ResultEntry(4, 1), ResultEntry(5, 1))
        assertTrue((PaperSettlement.settleWin(sig(4), tie, emptyList()) as PaperOutcome.Settled).hit)
        assertTrue((PaperSettlement.settleWin(sig(5), tie, emptyList()) as PaperOutcome.Settled).hit)
    }

    private fun settled(
        umaban: Int,
        before: Long,
        stake: Double,
        payout: Double,
        hit: Boolean,
        actual: Double? = null,
    ) = SettledSignal(race, umaban, post.minusSeconds(before), post, PaperOutcome.Settled(hit, stake, payout, actual))

    @Test
    fun `代表集計は同じ馬の複数タイミングを二重計上せず 発走前の最新だけを数える`() {
        val signals =
            listOf(
                settled(2, 1800, 100.0, 300.0, true), // 30 分前
                settled(2, 300, 100.0, 350.0, true), // 5 分前（代表）
                settled(2, -60, 100.0, 999.0, true), // 発走後は数えない
                settled(1, 300, 50.0, 0.0, false),
            )
        val s = PerformanceAggregator.representative(signals)
        assertEquals(2, s.count)
        assertEquals(1, s.hits)
        assertEquals(150.0, s.totalStake)
        assertEquals(350.0, s.paperPayout)
        assertEquals(200.0, s.paperProfit)
        assertEquals(0.5, s.hitRate)
        assertEquals(350.0 / 150.0, s.paperRoi)
        assertEquals(PerformanceAggregator.representative(signals.reversed()), s)
    }

    @Test
    fun `タイミング別は別の見出し 代表集計と足さない`() {
        val signals = listOf(settled(2, 1800, 100.0, 300.0, true), settled(2, 300, 100.0, 350.0, true))
        val by = PerformanceAggregator.byTiming(signals)
        assertEquals(setOf(5L, 30L), by.keys)
        assertEquals(1, by.getValue(30L).count)
        assertEquals(1, PerformanceAggregator.representative(signals).count)
    }

    @Test
    fun `0 件の指標は null 実払戻は全件そろったときだけ`() {
        val empty = PerformanceAggregator.representative(emptyList())
        assertEquals(0, empty.count)
        assertNull(empty.hitRate)
        assertNull(empty.paperRoi)
        assertNull(empty.actualPayout)
        val partial =
            PerformanceAggregator.representative(
                listOf(settled(1, 300, 100.0, 0.0, false, actual = 0.0), settled(2, 300, 100.0, 350.0, true, actual = null)),
            )
        assertNull(partial.actualPayout)
        val full =
            PerformanceAggregator.representative(
                listOf(settled(1, 300, 100.0, 0.0, false, actual = 0.0), settled(2, 300, 100.0, 350.0, true, actual = 420.0)),
            )
        assertEquals(420.0, full.actualPayout)
        assertEquals(420.0 / 200.0, full.actualRoi)
    }
}
