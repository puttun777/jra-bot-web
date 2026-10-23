package jp.jrabot.domain.decision

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 期待値は旧 Python（固定コミット f7dbf69）の `ev_calc.py` を実際に実行した値。 */
class KellyMathTest {
    @ParameterizedTest
    @CsvSource(
        "0.4, 3.0, 0.10000000000000003",
        "0.6, 2.0, 0.19999999999999996",
        "0.9, 3.0, 0.25", // 上限 0.25 で打ち切り
        "0.5, 1.01, 0.0", // b = 0.01 で期待値が負 → 0
    )
    fun `Kelly の正常系`(
        prob: Double,
        odds: Double,
        expected: Double,
    ) {
        assertEquals(expected, KellyMath.calcKellyFraction(prob, odds))
    }

    @ParameterizedTest
    @CsvSource("0.5, 2.0", "0.3, 2.0", "0.1, 5.0")
    fun `Kelly のゼロ境界 - 期待値がちょうどゼロまたは負なら 0`(
        prob: Double,
        odds: Double,
    ) {
        assertEquals(0.0, KellyMath.calcKellyFraction(prob, odds)) // 0.5 × 2.0: kelly == 0 は「0 以下」で 0
    }

    @ParameterizedTest
    @CsvSource("0.0, 3.0", "-0.2, 3.0", "0.5, 0.0", "0.5, -2.0")
    fun `確率またはオッズが 0 以下は 0`(
        prob: Double,
        odds: Double,
    ) {
        assertEquals(0.0, KellyMath.calcKellyFraction(prob, odds))
    }

    @Test
    fun `max_fraction は上限で、判定側の 1_0 では打ち切られない（E12 の背景）`() {
        assertEquals(0.25, KellyMath.calcKellyFraction(0.9, 3.0, maxFraction = 0.25))
        assertEquals(0.8500000000000001, KellyMath.calcKellyFraction(0.9, 3.0, maxFraction = 1.0))
        assertEquals(0.05, KellyMath.calcKellyFraction(0.9, 3.0, maxFraction = 0.05))
    }

    @Test
    fun `odds が 1_0 ちょうどは 0 除算で、旧 Python の ZeroDivisionError を再現する（E5）`() {
        assertFailsWith<ArithmeticException> { KellyMath.calcKellyFraction(0.5, 1.0) } // LegacyZeroDivisionException は ArithmeticException
        assertFailsWith<LegacyZeroDivisionException> { KellyMath.calcKellyFraction(0.5, 1.0) }
        // 確率が 0 以下なら 0 除算の前に 0 を返す（旧実装の分岐順）
        assertEquals(0.0, KellyMath.calcKellyFraction(0.0, 1.0))
        assertEquals(0.0, KellyMath.calcKellyFraction(-0.1, 1.0))
        assertFailsWith<LegacyZeroDivisionException> { KellyMath.suggestBetAmount(0.5, 1.0) }
        assertFailsWith<LegacyZeroDivisionException> { KellyMath.calcKellyFraction(1.0, 1.0, maxFraction = 1.0) }
    }

    @Test
    fun `odds が 1 未満は旧実装では正の値になる - 補正せず再現する（取込側の入力検証は T3_4 の判断）`() {
        assertEquals(0.25, KellyMath.calcKellyFraction(0.5, 0.5)) // b < 0 で分子も分母も負 → 正。JRA のオッズは 1.0 以上なので実運用では起きない想定
        assertEquals(0.25, KellyMath.calcKellyFraction(0.2, 0.5))
    }

    @Test
    fun `推奨賭け額 - 通常は ケリー割合 掛ける 資金 で、min と max の間はそのまま`() {
        val r = KellyMath.suggestBetAmount(0.12, 10.0)
        assertEquals(0.02222222222222223, r.kellyFraction)
        assertEquals(2.222222222222223, r.suggestedBet)
        assertEquals(0.44444444444444453, r.expectedProfit)
        assertEquals(0.19999999999999996, r.ev)
        assertEquals("小額投資推奨（ケリー基準が低い）", r.strategy)
    }

    @Test
    fun `推奨賭け額 - 上限 max_bet で打ち切り、下限 min_bet に引き上げる（見送りにはしない）`() {
        assertEquals(10.0, KellyMath.suggestBetAmount(0.4, 3.0).suggestedBet) // 0.1 × 100 = 10.0 は max_bet ちょうど
        assertEquals(10.0, KellyMath.suggestBetAmount(0.3, 10.0).suggestedBet) // 22.2 → 10.0
        assertEquals("高額投資推奨（ケリー基準が高い）", KellyMath.suggestBetAmount(0.3, 10.0).strategy)
        assertEquals(1.0000000000000064, KellyMath.suggestBetAmount(0.34, 3.0, minBet = 1.0).suggestedBet)
        assertEquals(0.5, KellyMath.suggestBetAmount(0.34, 3.0, bankroll = 10.0, minBet = 0.5).suggestedBet) // 0.1 → min_bet 0.5 に引き上げ
        assertEquals(5.0, KellyMath.suggestBetAmount(0.9, 3.0, maxFraction = 0.05).suggestedBet)
        val r = KellyMath.suggestBetAmount(0.4, 3.0, bankroll = 100.0, minBet = 10.0, maxBet = 10.0)
        assertEquals(10.0, r.suggestedBet)
        assertEquals(2.0000000000000018, r.expectedProfit)
        assertEquals("中額投資推奨", r.strategy)
    }

    @Test
    fun `推奨賭け額 - EV が 0 以下なら 0 点で戦略は EV が負`() {
        val r = KellyMath.suggestBetAmount(0.2, 3.0)
        assertEquals(0.0, r.kellyFraction)
        assertEquals(0.0, r.suggestedBet)
        assertEquals(0.0, r.expectedProfit)
        assertEquals("投資推奨なし（EVが負）", r.strategy)
        assertEquals(-0.3999999999999999, r.ev)
    }

    @Test
    fun `推奨賭け額 - オッズ 0 は Kelly 0 と EV -1`() {
        val r = KellyMath.suggestBetAmount(0.5, 0.0)
        assertEquals(0.0, r.suggestedBet)
        assertEquals(-1.0, r.ev)
    }
}
