package jp.jrabot.domain.decision

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 期待値は旧 Python（固定コミット f7dbf69）の `ev_calc.py` を実際に実行した値。 */
class EvMathTest {
    @ParameterizedTest
    @CsvSource(
        "0.25, 10.0, 1.5, 2.5",
        "0.5, 2.0, 0.0, 1.0",
        "0.4, 3.0, 0.20000000000000018, 1.2000000000000002", // 2 進の誤差を含めて旧実装と同じ
        "0.3333333333333333, 3.0, 0.0, 1.0",
        "0.99, 1.01, -9.999999999998899E-5, 0.9999",
        "1.0E-6, 999.0, -0.999001, 0.0009989999999999999",
    )
    fun `正常系は旧実装と同じ値`(
        prob: Double,
        odds: Double,
        ev: Double,
        expectedReturn: Double,
    ) {
        assertEquals(ev, EvMath.calcEv(prob, odds))
        assertEquals(expectedReturn, EvMath.calcExpectedReturn(prob, odds))
    }

    @ParameterizedTest
    @CsvSource("0.0, 2.0", "-0.1, 2.0", "0.5, 0.0", "0.5, -1.0", "0.0, 0.0", "-0.1, -1.0")
    fun `確率またはオッズが 0 以下なら EV は -1 で期待回収は 0`(
        prob: Double,
        odds: Double,
    ) {
        assertEquals(-1.0, EvMath.calcEv(prob, odds))
        assertEquals(0.0, EvMath.calcExpectedReturn(prob, odds))
    }

    @Test
    fun `境界のちょうど - 確率1オッズ1は EV ゼロ（0 以下の分岐ではなく通常式）`() {
        assertEquals(0.0, EvMath.calcEv(1.0, 1.0))
        assertEquals(1.0, EvMath.calcExpectedReturn(1.0, 1.0))
        assertEquals(-1.0, EvMath.calcEv(Double.MIN_VALUE, 1.0)) // 正の極小値は通常式（結果が丸めで -1 になる）
        assertEquals(Double.MIN_VALUE, EvMath.calcExpectedReturn(Double.MIN_VALUE, 1.0))
    }

    @Test
    fun `期待利益は stake 掛ける EV で、旧実装と同じ誤差を含む`() {
        assertEquals(2.0000000000000018, EvMath.calcExpectedProfit(10.0, 0.4, 3.0))
        assertEquals(-10.0, EvMath.calcExpectedProfit(10.0, 0.0, 3.0)) // EV が -1 の分岐
        assertEquals(0.0, EvMath.calcExpectedProfit(0.0, 0.4, 3.0))
    }

    @Test
    fun `範囲外の入力も補正せず通常式に流す - 確率が1を超える`() {
        assertEquals(1.0, EvMath.calcEv(2.0, 1.0)) // 2.0 * 1.0 - 1.0。旧実装も検査しない
    }

    @Test
    fun `NaN は旧実装と同じく比較が偽になり演算に流れる`() {
        assertTrue(EvMath.calcEv(Double.NaN, 2.0).isNaN())
        assertTrue(EvMath.calcExpectedReturn(0.5, Double.NaN).isNaN())
    }
}
