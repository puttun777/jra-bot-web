package jp.jrabot.domain.settlement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 旧実装との一致は golden GD11（`tools/golden`）が正。ここでは境界と不変条件を固定する。 */
class SettlementCalculatorTest {
    private val fin = listOf(FinishRow(1, 3), FinishRow(2, 1), FinishRow(3, 2), FinishRow(4, 4))

    @Test
    fun `当たりのキーは着順の上位から作り 馬番は数値の昇順`() {
        assertEquals(WinningKeys("2-3", "1-2-3"), SettlementCalculator.winningKeys(fin))
        val big = SettlementCalculator.winningKeys(listOf(FinishRow(18, 1), FinishRow(2, 2), FinishRow(10, 3)))
        assertEquals("2-18", big.wide)
        assertEquals("2-10-18", big.sanrenpuku)
    }

    @Test
    fun `着順が無い 0 の馬は数えず 足りなければ null`() {
        assertEquals(WinningKeys(null, null), SettlementCalculator.winningKeys(listOf(FinishRow(1, null), FinishRow(2, 0), FinishRow(3, 1))))
        assertEquals(WinningKeys("3-4", null), SettlementCalculator.winningKeys(listOf(FinishRow(3, 1), FinishRow(4, 2))))
    }

    @Test
    fun `入力の並びに依存しない`() {
        assertEquals(SettlementCalculator.winningKeys(fin), SettlementCalculator.winningKeys(fin.reversed()))
    }

    @Test
    fun `的中は確定払戻を 100 で割る 外れは 0 確定`() {
        val w = SettlementCalculator.winningKeys(fin)
        val hit = SettlementCalculator.settleEvaluation(ResolvedCombo(ComboMarket.WIDE, "2-3"), w, { _, _ -> 350.0 }, { emptyMap() })
        assertEquals(EvaluationSettlement(SettleStatus.SETTLED, true, 3.5, false), hit)
        val miss = SettlementCalculator.settleEvaluation(ResolvedCombo(ComboMarket.WIDE, "1-4"), w, { _, _ -> 350.0 }, { emptyMap() })
        assertEquals(EvaluationSettlement(SettleStatus.SETTLED, false, 0.0, false), miss)
    }

    @Test
    fun `確定払戻が無い的中は 最新の組み合わせオッズを暫定にし 無ければ null`() {
        val w = SettlementCalculator.winningKeys(fin)
        val c = ResolvedCombo(ComboMarket.WIDE, "2-3")
        assertEquals(
            EvaluationSettlement(SettleStatus.SETTLED, true, 4.8, true),
            SettlementCalculator.settleEvaluation(c, w, { _, _ -> 0.0 }, {
                mapOf("2-3" to 4.8)
            }),
        )
        assertEquals(
            EvaluationSettlement(SettleStatus.SETTLED, true, null, false),
            SettlementCalculator.settleEvaluation(c, w, { _, _ -> null }, { emptyMap() }),
        )
    }

    @Test
    fun `買い目が決まらない 当たりが決まらない は no_ticket で値を更新しない`() {
        val w = SettlementCalculator.winningKeys(fin)
        val none = EvaluationSettlement(SettleStatus.NO_TICKET, null, null, false)
        assertEquals(none, SettlementCalculator.settleEvaluation(null, w, { _, _ -> 1.0 }, { emptyMap() }))
        assertEquals(
            none,
            SettlementCalculator.settleEvaluation(ResolvedCombo(ComboMarket.SANRENPUKU, "1-2-3"), WinningKeys("1-2", null), { _, _ -> 1.0 }, {
                emptyMap()
            }),
        )
    }

    @Test
    fun `買い目は採用済みを優先し 英語表記の賭式は買い目表では無視する`() {
        val t =
            listOf(
                TicketRef("ワイド", "1-4", 1, 0),
                TicketRef("ワイド", "3-2", 2, 1),
                TicketRef("wide", "9-9", 1, 1),
            )
        assertEquals(ResolvedCombo(ComboMarket.WIDE, "2-3"), SettlementCalculator.resolveBestCombo(t, null))
    }

    @Test
    fun `買い目の先頭のキーが空なら best_candidate を使う`() {
        val t = listOf(TicketRef("ワイド", "", 1, 1))
        val c = CandidateView(betType = "三連複", comboKey = "3-1-2")
        assertEquals(ResolvedCombo(ComboMarket.SANRENPUKU, "1-2-3"), SettlementCalculator.resolveBestCombo(t, c))
        assertNull(SettlementCalculator.resolveBestCombo(emptyList(), CandidateView(betType = "単勝", comboKey = "3")))
    }

    @Test
    fun `代表評価は発走前の最新 同値は created_at と id の降順 発走前が無ければ全体の最新`() {
        val start = SettlementCalculator.raceStartIso("20260301", "15:40")
        assertEquals("2026-03-01T15:40:00", start)
        val e =
            listOf(
                EvaluationRef(1, "2026-03-01T14:00:00", "2026-03-01T14:00:00"),
                EvaluationRef(2, "2026-03-01T15:30:00", "2026-03-01T15:30:00"),
                EvaluationRef(3, "2026-03-01T16:00:00", "2026-03-01T16:00:00"),
            )
        assertEquals(2L, SettlementCalculator.selectRepresentative(e, start)?.id)
        assertEquals(3L, SettlementCalculator.selectRepresentative(e, "2026-03-01T10:00:00")?.id)
        assertEquals(3L, SettlementCalculator.selectRepresentative(e, null)?.id)
        assertNull(SettlementCalculator.selectRepresentative(emptyList(), start))
        assertNull(SettlementCalculator.raceStartIso("2026-03-01", "15:40"))
    }

    @Test
    fun `同じ入力で何度計算しても同じ結果になる`() {
        val w = SettlementCalculator.winningKeys(fin)
        val c = ResolvedCombo(ComboMarket.SANRENPUKU, "1-2-3")
        val a = SettlementCalculator.settleEvaluation(c, w, { _, _ -> 1280.0 }, { emptyMap() })
        repeat(5) { assertEquals(a, SettlementCalculator.settleEvaluation(c, w, { _, _ -> 1280.0 }, { emptyMap() })) }
    }

    @Test
    fun `単勝 bets は 1 着だけ stake × odds 着順が無ければ未確定`() {
        assertEquals(WinBetSettlement(350.0, 250.0), SettlementCalculator.settleWinBet(100.0, 3.5, 1))
        assertEquals(WinBetSettlement(0.0, -100.0), SettlementCalculator.settleWinBet(100.0, 3.5, 2))
        assertEquals(WinBetSettlement(0.0, 0.0), SettlementCalculator.settleWinBet(0.0, 3.5, 1))
        assertEquals(WinBetSettlement(0.0, -100.0), SettlementCalculator.settleWinBet(100.0, 0.0, 1))
        assertNull(SettlementCalculator.settleWinBet(100.0, 3.5, null))
        assertNotNull(SettlementCalculator.settleWinBet(null, null, 0))
    }
}
