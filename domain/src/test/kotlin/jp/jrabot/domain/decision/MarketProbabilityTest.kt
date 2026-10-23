package jp.jrabot.domain.decision

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 期待値は旧 Python（固定コミット f7dbf69）の `probability.py`（`use_ml=False`）を実際に実行した値。 */
class MarketProbabilityTest {
    @ParameterizedTest
    @CsvSource("-1.0, 0.0", "0.0, 0.0", "0.5, 2.0", "1.0, 1.0", "2.0, 0.5", "100.0, 0.01")
    fun `生の暗黙確率は 1 割る odds で、0 以下は 0`(
        odds: Double,
        expected: Double,
    ) {
        assertEquals(expected, MarketProbability.estimateProbRaw(odds))
    }

    @ParameterizedTest
    @CsvSource("-1.0, 0.0", "0.0, 0.0", "0.5, 0.99", "1.0, 0.99", "1.01, 0.99", "1.02, 0.9803921568627451", "2.0, 0.5", "999.0, 0.001001001001001001")
    fun `estimate_prob は 0_99 が上限`(
        odds: Double,
        expected: Double,
    ) {
        assertEquals(expected, MarketProbability.estimateProb(odds))
    }

    @Test
    fun `正規化 - 空は空、1 頭は 1_0、2 頭は 0_6 と 0_4`() {
        assertEquals(emptyList(), MarketProbability.estimateProbNormalized(emptyList()))
        assertEquals(listOf(1.0), MarketProbability.estimateProbNormalized(listOf(5.0)))
        assertEquals(listOf(0.6, 0.4), MarketProbability.estimateProbNormalized(listOf(2.0, 3.0)))
    }

    @Test
    fun `正規化 - 18 頭で全馬同じオッズは均等で、合計は 1`() {
        val p = MarketProbability.estimateProbNormalized(List(18) { 4.0 })
        assertEquals(18, p.size)
        p.forEach { assertEquals(0.05555555555555556, it) }
        assertEquals(1.0, PythonCompat.sum(p))
    }

    @Test
    fun `正規化 - 通常のレース`() {
        assertEquals(
            listOf(0.4263959390862944, 0.28426395939086296, 0.18274111675126903, 0.1065989847715736),
            MarketProbability.estimateProbNormalized(listOf(3.0, 4.5, 7.0, 12.0)),
        )
    }

    @Test
    fun `正規化 - オッズ 0 の馬は確率 0 で、他の馬で正規化する`() {
        assertEquals(listOf(0.7142857142857143, 0.0, 0.28571428571428575), MarketProbability.estimateProbNormalized(listOf(2.0, 0.0, 5.0)))
    }

    @Test
    fun `正規化 - 全馬が使えない（合計 0）なら均等配分`() {
        assertEquals(listOf(0.5, 0.5), MarketProbability.estimateProbNormalized(listOf(0.0, 0.0)))
        assertEquals(listOf(0.5, 0.5), MarketProbability.estimateProbNormalized(listOf(-1.0, -4.0)))
    }

    @Test
    fun `補正制限 - 補正の比は全馬で同じ 1 割る 合計なので、再正規化後は ±limit の値によらず同じ（旧欠陥 E13 の実測）`() {
        val odds = listOf(1.5, 3.0, 6.0, 12.0, 24.0, 48.0) // 逆数の合計 > 1 → 比 < 1
        val base = MarketProbability.estimateProbNormalized(odds, correctionLimit = 10.0) // 実質無制限
        for (limit in listOf(0.0, 0.01, 0.10, 0.5)) {
            val p = MarketProbability.estimateProbNormalized(odds, correctionLimit = limit)
            p.zip(base).forEach { (a, b) -> assertTrue(abs(a - b) < 1e-15, "limit=$limit: $a vs $b") }
        }
    }

    @Test
    fun `補正制限の境界 - 比がちょうど 1 + limit のときは補正しない（超えたときだけ）`() {
        // 逆数の合計がちょうど 0.5 → 比 = 2.0。limit = 1.0 なら 1 + limit = 2.0 ちょうど（補正なし）、0.99 なら超過（補正あり）
        val odds = List(8) { 16.0 }
        assertEquals(0.5, PythonCompat.sum(odds.map { 1.0 / it }))
        val atBoundary = MarketProbability.estimateProbNormalized(odds, correctionLimit = 1.0)
        val beyond = MarketProbability.estimateProbNormalized(odds, correctionLimit = 0.99)
        assertEquals(1.0, PythonCompat.sum(atBoundary), 1e-12)
        assertEquals(1.0, PythonCompat.sum(beyond), 1e-12)
        atBoundary.zip(beyond).forEach { (a, b) -> assertEquals(a, b, 1e-15) } // どちらも結果は同じ（補正は再正規化で消える）
    }

    @Test
    fun `負のオッズは 0 として扱われ、他の馬が全確率を持つ`() {
        assertEquals(listOf(0.0, 1.0), MarketProbability.estimateProbNormalized(listOf(-1.0, 4.0)))
    }
}
