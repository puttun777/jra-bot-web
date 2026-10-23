package jp.jrabot.domain.decision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 実オッズの注入・推定オッズ・旧の粗い近似・買い目の重複除去。推定オッズの期待値は旧 Python（f7dbf69）の値。 */
class ComboOddsTest {
    // ---- 推定オッズ（旧 estimate_wide_odds / estimate_trifecta_odds）------------------------------------------------

    @Test
    fun `ワイドの推定オッズ - 3_0 と 10_0 ちょうどは小さい側の係数（以下）`() {
        assertEquals(0.4, ComboOddsEstimate.wide(1.0))
        assertEquals(1.2000000000000002, ComboOddsEstimate.wide(3.0)) // 係数 0.4
        assertEquals(1.0534999999999999, ComboOddsEstimate.wide(3.01)) // 係数 0.35
        assertEquals(1.75, ComboOddsEstimate.wide(5.0))
        assertEquals(3.5, ComboOddsEstimate.wide(10.0)) // 係数 0.35
        assertEquals(3.0029999999999997, ComboOddsEstimate.wide(10.01)) // 係数 0.3
        assertEquals(30.0, ComboOddsEstimate.wide(100.0))
    }

    @Test
    fun `三連複の推定オッズ`() {
        assertEquals(0.03, ComboOddsEstimate.trifecta(1.0))
        assertEquals(0.09, ComboOddsEstimate.trifecta(3.0))
        assertEquals(0.0602, ComboOddsEstimate.trifecta(3.01))
        assertEquals(0.2, ComboOddsEstimate.trifecta(10.0))
        assertEquals(0.1001, ComboOddsEstimate.trifecta(10.01))
        assertEquals(1.0, ComboOddsEstimate.trifecta(100.0))
    }

    @Test
    fun `無効な単勝オッズ - 0 以下は 0_0 で、丸めも上下限もかけない`() {
        for (o in listOf(-1.0, -0.001, 0.0)) {
            assertEquals(0.0, ComboOddsEstimate.wide(o))
            assertEquals(0.0, ComboOddsEstimate.trifecta(o))
        }
        assertEquals(0.1 * 0.4, ComboOddsEstimate.wide(0.1)) // 極端に低いオッズも下限なし
        assertEquals(999.0 * 0.3, ComboOddsEstimate.wide(999.0)) // 極端に高いオッズも上限なし
    }

    @Test
    fun `推定オッズは実オッズとは別物 - 払戻率や頭数を使わない`() {
        // 旧実装は num_horses を受け取るが使わない。払戻率（0.775）も掛けない
        assertEquals(3.0 * 0.4, ComboOddsEstimate.wide(3.0))
        assertNotEquals(3.0 * 0.4 * 0.775, ComboOddsEstimate.wide(3.0))
    }

    // ---- 旧の粗い近似（判定には使われない）---------------------------------------------------------------------------

    @Test
    fun `旧 calculate_wide_prob - p の 1_5 倍、0_99 上限、空・p が 0 以下は 0`() {
        assertEquals(0.15000000000000002, LegacySimpleComboProbability.wide(0.1, raceIsEmpty = false))
        assertEquals(0.99, LegacySimpleComboProbability.wide(0.66, false))
        assertEquals(0.99, LegacySimpleComboProbability.wide(1.0, false))
        assertEquals(0.0, LegacySimpleComboProbability.wide(0.0, false))
        assertEquals(0.0, LegacySimpleComboProbability.wide(-0.1, false))
        assertEquals(0.0, LegacySimpleComboProbability.wide(0.3, raceIsEmpty = true))
    }

    @Test
    fun `旧 calculate_trifecta_prob - p の 1_8 倍、0_99 上限、空・p が 0 以下は 0`() {
        assertEquals(0.18000000000000002, LegacySimpleComboProbability.trifecta(0.1, raceIsEmpty = false))
        assertEquals(0.99, LegacySimpleComboProbability.trifecta(0.55, false))
        assertEquals(0.0, LegacySimpleComboProbability.trifecta(0.0, false))
        assertEquals(0.0, LegacySimpleComboProbability.trifecta(0.3, raceIsEmpty = true))
    }

    // ---- 実オッズの注入 --------------------------------------------------------------------------------------------

    @Test
    fun `実オッズ - 馬番の順序に依らず同じ買い目として引ける`() {
        val odds =
            ComboOdds(
                wide = mapOf(WideKey.of(3, 5) to WideOddsRange(2.1, 3.4)),
                sanrenpuku = mapOf(SanrenpukuKey.of(9, 2, 5) to 55.5),
            )
        assertEquals(WideOddsRange(2.1, 3.4), odds.wide(3, 5))
        assertEquals(WideOddsRange(2.1, 3.4), odds.wide(5, 3))
        assertEquals(55.5, odds.sanrenpuku(2, 5, 9))
        assertEquals(55.5, odds.sanrenpuku(9, 5, 2))
        assertEquals(55.5, odds.sanrenpuku(5, 9, 2))
        assertTrue(odds.hasWide && odds.hasSanrenpuku)
    }

    @Test
    fun `実オッズが無い、一部だけの組合せ - 無い買い目は null`() {
        val odds = ComboOdds(wide = mapOf(WideKey.of(1, 2) to WideOddsRange(1.5, 2.0)))
        assertNull(odds.wide(1, 3))
        assertNull(odds.sanrenpuku(1, 2, 3))
        assertTrue(odds.hasWide)
        assertFalse(odds.hasSanrenpuku)
        assertFalse(ComboOdds.EMPTY.hasWide)
    }

    @Test
    fun `実オッズの検索に不正な馬番を渡しても例外にせず null（旧実装の辞書検索は一致しないだけ）`() {
        val odds = ComboOdds(wide = mapOf(WideKey.of(1, 2) to WideOddsRange(1.5, 2.0)))
        assertNull(odds.wide(0, 2))
        assertNull(odds.wide(-1, 2))
        assertNull(odds.wide(2, 2))
        assertNull(odds.sanrenpuku(1, 1, 2))
        assertNull(odds.sanrenpuku(0, 1, 2))
    }

    @Test
    fun `キーの作成は不正な馬番を拒否する`() {
        assertFailsWith<IllegalArgumentException> { WideKey.of(1, 1) }
        assertFailsWith<IllegalArgumentException> { WideKey.of(0, 1) }
        assertFailsWith<IllegalArgumentException> { WideKey.of(-3, 1) }
        assertFailsWith<IllegalArgumentException> { SanrenpukuKey.of(1, 2, 2) }
        assertFailsWith<IllegalArgumentException> { SanrenpukuKey.of(1, 0, 2) }
        assertEquals(WideKey.of(2, 7), WideKey.of(7, 2))
        assertEquals(SanrenpukuKey.of(3, 1, 2), SanrenpukuKey.of(2, 3, 1))
        assertEquals("1-2-3", SanrenpukuKey.of(3, 1, 2).toString())
    }

    @Test
    fun `実オッズの値の検査 - 非有限・負・下限が上限を超えるものは拒否、三連複の 0 と ワイドの下限 0 は受け付ける`() {
        assertFailsWith<IllegalArgumentException> { WideOddsRange(Double.NaN, 2.0) }
        assertFailsWith<IllegalArgumentException> { WideOddsRange(1.0, Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { WideOddsRange(-0.1, 2.0) }
        assertFailsWith<IllegalArgumentException> { WideOddsRange(3.0, 2.0) }
        WideOddsRange(0.0, 0.0) // 旧実装は受け付ける（EV が −1 になるだけ）
        assertFailsWith<IllegalArgumentException> { ComboOdds(sanrenpuku = mapOf(SanrenpukuKey.of(1, 2, 3) to Double.NaN)) }
        assertFailsWith<IllegalArgumentException> { ComboOdds(sanrenpuku = mapOf(SanrenpukuKey.of(1, 2, 3) to -1.0)) }
        ComboOdds(sanrenpuku = mapOf(SanrenpukuKey.of(1, 2, 3) to 0.0))
    }

    // ---- 買い目の重複除去（旧 notify の _deduplicate_tickets）-------------------------------------------------------

    private fun ticket(
        kumi: String,
        ev: Double,
    ) = ComboTicket(kumi, 10.0, "10.0", ProbRaw(0.2), ProbUsed(0.2), Ev(ev), OddsSource.REAL)

    @Test
    fun `重複除去 - BOX の有無を無視して同じ組合せを 1 つにし、EV が厳密に大きい方を残す（順序は最初に現れた位置）`() {
        val result =
            ComboTickets.deduplicated(
                listOf(
                    ticket("01-02", 1.0),
                    ticket("[BOX]01-02", 1.0), // 同値 → 先のものを残す
                    ticket("01-03", 0.5),
                    ticket("[BOX]01-03", 0.7), // 大きい → 置き換え（位置は 01-03 のまま）
                    ticket("[BOX]02-03", 0.6),
                ),
            )
        assertEquals(listOf("01-02", "[BOX]01-03", "[BOX]02-03"), result.map { it.kumi })
        assertEquals(listOf(1.0, 0.7, 0.6), result.map { it.ev.value })
    }

    @Test
    fun `重複除去 - 空と重複なしは変わらない`() {
        assertEquals(emptyList(), ComboTickets.deduplicated(emptyList()))
        val t = listOf(ticket("01-02", 1.0), ticket("01-03", 0.5))
        assertEquals(t, ComboTickets.deduplicated(t))
        assertTrue(ticket("[BOX]01-02", 1.0).isBox)
        assertFalse(ticket("01-02", 1.0).isBox)
    }
}
