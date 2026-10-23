package jp.jrabot.domain.decision

import kotlin.math.abs
import kotlin.math.pow
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Plackett–Luce の確率。期待値の数値は旧 Python（固定コミット f7dbf69）の `suggest_multiple_bet_strategies` を実際に実行した値
 * （チケットの `prob`＝生の確率。ゴールデン GD05-PL-005 と同じ式）。恒等式（合計・対称性）は式から導いた検算。
 */
class PlackettLuceTest {
    // 旧 Python で odds=[3.0, 4.5, 7.0, 12.0, 25.0, 40.0] から作った市場確率
    private val p6 =
        listOf(
            0.3936638860249321,
            0.262442590683288,
            0.16871309401068516,
            0.09841597150623302,
            0.04723966632299185,
            0.029524791451869908,
        )
    private val u6 = listOf(1, 2, 3, 4, 5, 6)

    private fun pl6(alpha: Double = 1.0) = PlackettLuce.of(u6, p6, alpha)

    private val p5 = listOf(0.35, 0.25, 0.2, 0.12, 0.08)

    // ---- 旧 Python の値との一致 ------------------------------------------------------------------------------------

    @Test
    fun `ワイド - 旧 Python と同じ値（6 頭、alpha=1）`() {
        val pl = pl6()
        assertEquals(0.6619251876555018, pl.widePairProb(1, 2))
        assertEquals(0.5150032480689302, pl.widePairProb(1, 3))
        assertEquals(0.43111221543546524, pl.widePairProb(2, 3))
        assertEquals(0.3197348836375546, pl.widePairProb(1, 4))
        assertEquals(0.15765715516794343, pl.widePairProb(1, 5))
        assertEquals(0.09922943288252153, pl.widePairProb(1, 6))
    }

    @Test
    fun `三連複 - 旧 Python と同じ値（6 頭、alpha=1）`() {
        val pl = pl6()
        assertEquals(0.34432282037424844, pl.top3SetProb(1, 2, 3))
        assertEquals(0.18328310951822588, pl.top3SetProb(1, 2, 4))
        assertEquals(0.09872056767950663, pl.top3SetProb(1, 3, 4))
        assertEquals(0.006438131916220219, pl.top3SetProb(1, 5, 6))
    }

    @Test
    fun `alpha の違い - 旧 Python と同じ値（2_0 と 0_5）`() {
        assertEquals(0.9110831072422481, pl6(2.0).widePairProb(1, 2))
        assertEquals(0.6442556431424905, pl6(2.0).top3SetProb(1, 2, 3))
        assertEquals(0.4283714606729744, pl6(0.5).widePairProb(1, 2))
        assertEquals(0.16759562942539052, pl6(0.5).top3SetProb(1, 2, 3))
    }

    @Test
    fun `5 頭 - 旧 Python と同じ値`() {
        val pl = PlackettLuce.of(listOf(1, 2, 3, 4, 5), p5, 1.0)
        assertEquals(0.5936012141860946, pl.widePairProb(1, 2))
        assertEquals(0.22822723647586204, pl.widePairProb(1, 5))
        assertEquals(0.3162781662781662, pl.top3SetProb(1, 2, 3))
        assertEquals(0.04177147546982614, pl.top3SetProb(1, 4, 5))
    }

    // ---- 式から導ける恒等式 -----------------------------------------------------------------------------------------

    @Test
    fun `3 頭 - 6 通りの着順の和は 1 で、0_99 に収める（ワイドも 0_99）`() {
        val pl = PlackettLuce.of(listOf(1, 2, 3), listOf(0.5, 0.3, 0.2), 1.0)
        assertEquals(0.99, pl.top3SetProb(1, 2, 3))
        assertEquals(0.99, pl.widePairProb(1, 2))
        assertEquals(0.99, pl.widePairProb(2, 3))
    }

    @Test
    fun `全馬が同確率 - 三連複は 6 割る n(n-1)(n-2)、ワイドは 6 割る n(n-1)`() {
        for (n in listOf(5, 8, 18)) {
            val pl = PlackettLuce.of((1..n).toList(), List(n) { 1.0 / n }, 1.0)
            assertEquals(6.0 / (n * (n - 1.0) * (n - 2.0)), pl.top3SetProb(1, 2, 3), 1e-12, "n=$n 三連複")
            assertEquals(6.0 / (n * (n - 1.0)), pl.widePairProb(1, 2), 1e-12, "n=$n ワイド")
        }
    }

    @Test
    fun `4 頭 - 4 つの三連複の確率の和は 1、6 つのワイドの確率の和は 3`() {
        val pl = PlackettLuce.of(listOf(1, 2, 3, 4), listOf(0.4, 0.3, 0.2, 0.1), 1.0)
        val tri = listOf(listOf(1, 2, 3), listOf(1, 2, 4), listOf(1, 3, 4), listOf(2, 3, 4)).sumOf { (a, b, c) -> pl.top3SetProb(a, b, c) }
        assertEquals(1.0, tri, 1e-12)
        val wide = listOf(1 to 2, 1 to 3, 1 to 4, 2 to 3, 2 to 4, 3 to 4).sumOf { (a, b) -> pl.widePairProb(a, b) }
        assertEquals(3.0, wide, 1e-12)
    }

    @Test
    fun `馬番の順序を入れ替えても確率は 1e-12 以内で変わらない`() {
        val pl = pl6()
        val perms = listOf(listOf(1, 3, 5), listOf(1, 5, 3), listOf(3, 1, 5), listOf(3, 5, 1), listOf(5, 1, 3), listOf(5, 3, 1))
        val base = pl.top3SetProb(1, 3, 5)
        for ((a, b, c) in perms) assertEquals(base, pl.top3SetProb(a, b, c), 1e-12)
        assertEquals(pl.widePairProb(2, 6), pl.widePairProb(6, 2), 1e-12)
    }

    @Test
    fun `一頭の確率を上げても他の 2 頭の組合せのワイドは上がらない - 単調性の検算`() {
        val low = PlackettLuce.of(listOf(1, 2, 3, 4), listOf(0.1, 0.3, 0.3, 0.3), 1.0)
        val high = PlackettLuce.of(listOf(1, 2, 3, 4), listOf(0.7, 0.1, 0.1, 0.1), 1.0)
        assertTrue(low.widePairProb(2, 3) > high.widePairProb(2, 3))
    }

    // ---- 重み・alpha・確率の下限/上限 -------------------------------------------------------------------------------

    @Test
    fun `alpha は 0_1 から 5 に収める - 0_0 は旧実装の or で 1_0 になる`() {
        assertEquals(1.0, PlackettLuce.effectiveAlpha(0.0))
        assertEquals(0.1, PlackettLuce.effectiveAlpha(-1.0))
        assertEquals(0.1, PlackettLuce.effectiveAlpha(0.05))
        assertEquals(0.1, PlackettLuce.effectiveAlpha(0.1))
        assertEquals(2.5, PlackettLuce.effectiveAlpha(2.5))
        assertEquals(5.0, PlackettLuce.effectiveAlpha(5.0))
        assertEquals(5.0, PlackettLuce.effectiveAlpha(5.5))
        assertEquals(5.0, PlackettLuce.effectiveAlpha(100.0))
    }

    @Test
    fun `alpha の上下限の外は端と同じ確率になる`() {
        val over = pl6(5.5).widePairProb(1, 2)
        assertEquals(pl6(5.0).widePairProb(1, 2), over)
        assertEquals(pl6(0.1).widePairProb(1, 2), pl6(0.05).widePairProb(1, 2))
        assertEquals(pl6(1.0).widePairProb(1, 2), pl6(0.0).widePairProb(1, 2))
        assertTrue(pl6(5.0).widePairProb(1, 2) > pl6(1.0).widePairProb(1, 2), "alpha が大きいほど人気馬に集中する")
    }

    @Test
    fun `確率は 1e-12 から 0_99 に収めてから alpha 乗する`() {
        assertEquals(1e-12.pow(2.0), PlackettLuce.weight(0.0, 2.0))
        assertEquals(1e-12.pow(2.0), PlackettLuce.weight(-0.2, 2.0))
        assertEquals(1e-12.pow(2.0), PlackettLuce.weight(1e-15, 2.0))
        assertEquals(0.99.pow(2.0), PlackettLuce.weight(1.5, 2.0))
        assertEquals(0.99.pow(2.0), PlackettLuce.weight(1.0, 2.0))
        assertEquals(0.5, PlackettLuce.weight(0.5, 1.0))
    }

    @Test
    fun `確率 0 の馬でも重みは正（1e-12）なので、確率は 0 ではないがほぼ 0`() {
        val pl = PlackettLuce.of(listOf(1, 2, 3, 4), listOf(0.6, 0.3, 0.1, 0.0), 1.0)
        assertEquals(1e-12, pl.weightOf(4))
        val w = pl.widePairProb(1, 4)
        assertTrue(w > 0.0 && w < 1e-9, "確率 0 の馬を含むワイド: $w")
    }

    @Test
    fun `極端に低い確率や人気集中でも 0 から 0_99 の範囲に収まり有限`() {
        val pl = PlackettLuce.of(listOf(1, 2, 3, 4, 5), listOf(0.99, 1e-6, 1e-9, 1e-12, 1e-15), 5.0)
        for ((a, b) in listOf(1 to 2, 1 to 5, 2 to 3, 4 to 5)) {
            val p = pl.widePairProb(a, b)
            assertTrue(p.isFinite() && p in 0.0..0.99, "($a,$b)=$p")
        }
        val top = pl.top3SetProb(1, 2, 3)
        assertTrue(top.isFinite() && top in 0.0..0.99)
    }

    // ---- 入力の検査（旧実装は黙って流す箇所の境界）------------------------------------------------------------------

    @Test
    fun `馬番の重複・0 以下・数の不一致・非有限の確率や alpha は拒否する`() {
        assertFailsWith<IllegalArgumentException> { PlackettLuce.of(listOf(1, 2, 2), listOf(0.3, 0.3, 0.4), 1.0) }
        assertFailsWith<IllegalArgumentException> { PlackettLuce.of(listOf(0, 1, 2), listOf(0.3, 0.3, 0.4), 1.0) }
        assertFailsWith<IllegalArgumentException> { PlackettLuce.of(listOf(-1, 1, 2), listOf(0.3, 0.3, 0.4), 1.0) }
        assertFailsWith<IllegalArgumentException> { PlackettLuce.of(listOf(1, 2, 3), listOf(0.3, 0.3), 1.0) }
        assertFailsWith<IllegalArgumentException> { PlackettLuce.of(listOf(1, 2), listOf(0.3, Double.NaN), 1.0) }
        assertFailsWith<IllegalArgumentException> { PlackettLuce.of(listOf(1, 2), listOf(0.3, Double.POSITIVE_INFINITY), 1.0) }
        assertFailsWith<IllegalArgumentException> { PlackettLuce.of(listOf(1, 2), listOf(0.3, 0.3), Double.NaN) }
    }

    @Test
    fun `同じ馬番の組合せは拒否する`() {
        val pl = pl6()
        assertFailsWith<IllegalArgumentException> { pl.top3SetProb(1, 1, 2) }
        assertFailsWith<IllegalArgumentException> { pl.top3SetProb(1, 2, 2) }
        assertFailsWith<IllegalArgumentException> { pl.top3SetProb(3, 2, 3) }
        assertFailsWith<IllegalArgumentException> { pl.widePairProb(4, 4) }
    }

    @Test
    fun `出走していない馬番は重み 0 として確率 0（旧実装のまま）`() {
        val pl = pl6()
        assertEquals(0.0, pl.top3SetProb(1, 2, 99))
        assertEquals(0.0, pl.orderProb(1, 2, 99))
        assertEquals(0.0, pl.widePairProb(1, 99))
    }

    @Test
    fun `頭数が 1 や 2 のとき - 三連複は成立せず、ワイドは他の馬がいないので 0`() {
        val two = PlackettLuce.of(listOf(1, 2), listOf(0.6, 0.4), 1.0)
        assertEquals(0.0, two.widePairProb(1, 2))
        assertEquals(0.0, two.orderProb(1, 2, 3))
        val one = PlackettLuce.of(listOf(1), listOf(1.0), 1.0)
        assertEquals(0.0, one.orderProb(1, 2, 3))
    }

    // ---- 旧実装の素朴な計算との一致（E10: 合計の前計算・メモ化は値を変えない）----------------------------------------------

    /** 旧実装をそのまま写した参照: 着順確率ごとに全馬の重みの合計を取り直し、メモ化しない。 */
    private fun referenceTop3(
        weights: Map<Int, Double>,
        a: Int,
        b: Int,
        c: Int,
    ): Double {
        fun order(vararg o: Int): Double {
            var remaining = PythonCompat.sum(weights.values.toList())
            if (remaining <= 0) return 0.0
            var p = 1.0
            for (u in o) {
                val w = weights[u] ?: 0.0
                if (w <= 0 || remaining <= 0) return 0.0
                p *= w / remaining
                remaining -= w
            }
            return PythonCompat.clamp(p, 0.0, 0.99)
        }
        var s = 0.0
        for (perm in listOf(intArrayOf(a, b, c), intArrayOf(a, c, b), intArrayOf(b, a, c), intArrayOf(b, c, a), intArrayOf(c, a, b), intArrayOf(c, b, a))) {
            s += order(*perm)
        }
        return PythonCompat.clamp(s, 0.0, 0.99)
    }

    @Test
    fun `前計算とメモ化は素朴な旧実装の計算とビット単位で同じ（18 頭の全組合せ）`() {
        val odds = listOf(4.2, 6.8, 9.6, 13.0, 16.0, 21.0, 26.0, 32.0, 40.0, 52.0, 66.0, 84.0, 110.0, 140.0, 180.0, 240.0, 360.0, 500.0)
        val inv = odds.map { 1.0 / it }
        val total = PythonCompat.sum(inv)
        val probs = inv.map { it / total }
        for (alpha in listOf(1.0, 0.7, 1.8)) {
            val umabans = (1..18).toList()
            val pl = PlackettLuce.of(umabans, probs, alpha)
            val weights = LinkedHashMap<Int, Double>().also { m -> umabans.forEachIndexed { i, u -> m[u] = PlackettLuce.weight(probs[i], alpha) } }
            var maxDiff = 0.0
            for (a in 1..16) {
                for (b in a + 1..17) {
                    for (c in b + 1..18) {
                        maxDiff = maxOf(maxDiff, abs(pl.top3SetProb(a, b, c) - referenceTop3(weights, a, b, c)))
                        // 2 回目はキャッシュから。同じ値
                        assertEquals(pl.top3SetProb(a, b, c), pl.top3SetProb(a, b, c))
                    }
                }
            }
            assertEquals(0.0, maxDiff, "alpha=$alpha: 前計算した合計と毎回の合計が一致しなければならない")
        }
    }

    // ---- 性能（18 頭で現実的な時間）----------------------------------------------------------------------------------

    @Test
    fun `18 頭の全ワイド 153 通りと全三連複 816 通りが短時間で計算できる`() {
        val n = 18
        val pl = PlackettLuce.of((1..n).toList(), List(n) { 1.0 / n }, 1.0)
        var sink = 0.0
        val nanos =
            measureNanoTime {
                for (a in 1..n) for (b in a + 1..n) sink += pl.widePairProb(a, b)
                for (a in 1..n) for (b in a + 1..n) for (c in b + 1..n) sink += pl.top3SetProb(a, b, c)
            }
        assertTrue(sink > 0)
        println("PlackettLuce 18 頭 全ワイド 153 + 全三連複 816: ${nanos / 1_000_000.0} ms（ウォームアップなしの 1 回目）")
        assertTrue(nanos < 2_000_000_000L, "18 頭の全組合せが 2 秒以内（実測 ${nanos / 1_000_000} ms）")
    }
}
