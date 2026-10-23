package jp.jrabot.domain.decision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MarketBlendTest {
    private val odds = listOf(3.0, 4.5, 7.0, 12.0, 25.0, 40.0)
    private val probs = MarketProbability.estimateProbNormalized(odds)

    @Test
    fun `alpha が 0_999 以上はブレンドもクリップも再正規化もしない（入力のまま）`() {
        assertEquals(probs, MarketBlend.apply(probs, odds, 0.999, true, 0.35))
        assertEquals(probs, MarketBlend.apply(probs, odds, 1.0, true, 0.01))
        assertEquals(probs, MarketBlend.apply(probs, odds, 1.5, true, 0.01)) // 1.0 に収められる
    }

    @Test
    fun `alpha が 0 以下の側は市場確率そのもの（1 割る odds）を使い、合計で再正規化する`() {
        val r = MarketBlend.apply(probs, odds, 0.0, false, null)
        val mkt = odds.map { 1.0 / it }
        val total = PythonCompat.sum(mkt)
        r.zip(mkt).forEach { (p, m) -> assertEquals(m / total, p, 1e-15) }
        assertEquals(1.0, PythonCompat.sum(r), 1e-12)
    }

    @Test
    fun `alpha が 0_001 ちょうどは市場確率側（0_001 を超えたときだけ logit 混合）で、0_0011 は混合`() {
        val atBoundary = MarketBlend.apply(probs, odds, 0.001, false, null)
        val market = MarketBlend.apply(probs, odds, 0.0, false, null)
        assertEquals(market, atBoundary)
        assertTrue(MarketBlend.apply(probs, odds, 0.0011, false, null) != market)
    }

    @Test
    fun `logit 混合の一頭分は旧 Python と同じ値`() {
        // 旧: evaluate_odds の市場ブレンド（alpha=0.25）。odds=3.0 の馬の最終確率（再正規化前）。numpy の log/exp と最後の桁が違い得る
        // 正規化後 0.3936638860249321、市場 1/3 → numpy の log/exp で 0.3479994200061943（ビット一致）
        assertEquals(0.3936638860249321, probs[0])
        assertEquals(0.3479994200061943, MarketBlend.blendOne(probs[0], 3.0, 0.25, true, 0.35)) // 上限 0.35 未満なのでクリップされない
        assertEquals(0.3, MarketBlend.blendOne(probs[0], 3.0, 0.25, true, 0.30)) // 上限 0.30 でクリップ
        assertEquals(0.3479994200061943, MarketBlend.blendOne(probs[0], 3.0, 0.25, false, 0.30)) // クリップ無効
    }

    @Test
    fun `クリップは馬ごとに上限を掛けた後で全体を再正規化するので、最終値が上限を超え得る`() {
        val strong = listOf(1.2, 40.0, 40.0)
        val p = MarketProbability.estimateProbNormalized(strong)
        val r = MarketBlend.apply(p, strong, 0.25, true, 0.35)
        assertEquals(1.0, PythonCompat.sum(r), 1e-12)
        assertTrue(r[0] > 0.35, "再正規化後は上限 0.35 を超え得る: ${r[0]}") // 旧欠陥の仕様として固定
    }

    @Test
    fun `クリップ上限が 0 以下か未設定、または無効指定ならクリップしない`() {
        val a = MarketBlend.apply(probs, odds, 0.25, true, 0.0)
        val b = MarketBlend.apply(probs, odds, 0.25, true, null)
        val c = MarketBlend.apply(probs, odds, 0.25, false, 0.01)
        assertEquals(a, b)
        assertEquals(b, c)
    }

    @Test
    fun `空は空で、件数が違う入力は拒否する`() {
        assertEquals(emptyList(), MarketBlend.apply(emptyList(), emptyList(), 0.25, true, 0.35))
        assertFailsWith<IllegalArgumentException> { MarketBlend.apply(listOf(0.5), listOf(2.0, 3.0), 0.25, true, 0.35) }
    }
}
