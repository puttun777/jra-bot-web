package jp.jrabot.domain.decision

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ワイド・三連複の買い目生成（旧 `suggest_multiple_bet_strategies`）。分岐・境界・処理順序の単体テスト。
 * 数値そのものの旧 Python との一致はゴールデン（GD05-*・GD06-EVAL-011・GD07-COMBO-STAKE-010）が検証する。
 * ここは、比較演算子や処理順序を変えると壊れるように、式から導ける期待値（[PlackettLuce] を独立に使った検算）で書く。
 */
class ComboStrategyTest {
    private val odds8 = listOf(8.0, 3.0, 12.0, 5.0, 20.0, 2.5, 30.0, 15.0)
    private val pops8 = listOf(5, 2, 6, 3, 7, 1, 8, 4) // 人気順: 6, 2, 4, 1, 3, 5, 8, 7 の馬番

    private fun market(odds: List<Double>): List<Double> {
        val inv = odds.map { 1.0 / maxOf(it, 0.1) }
        val t = PythonCompat.sum(inv)
        return inv.map { it / t }
    }

    private fun wideAll(
        n: Int,
        low: Double = 200.0,
        high: Double = 260.0,
    ) = (1..n).flatMap { a -> (a + 1..n).map { b -> WideKey.of(a, b) to WideOddsRange(low, high) } }.toMap()

    private fun triAll(
        n: Int,
        odds: Double = 2000.0,
    ) = (1..n).flatMap { a -> (a + 1..n).flatMap { b -> (b + 1..n).map { c -> SanrenpukuKey.of(a, b, c) to odds } } }.toMap()

    private fun all(
        n: Int,
        wide: Double = 200.0,
        tri: Double = 2000.0,
    ) = ComboOdds(wideAll(n, wide, wide + 60.0), triAll(n, tri))

    /** 縮小・上限なし（生の確率がそのまま使われる）。 */
    private val raw =
        WinDecisionConfig.legacyFunctionDefaults().copy(
            evShrinkFactor = 1.0,
            combo = ComboSettings.legacyFunctionDefaults().copy(wideMaxProb = 0.0, sanrenpukuMaxProb = 0.0, maxTicketsPerType = 200),
        )

    private fun withCombo(
        base: WinDecisionConfig = raw,
        f: (ComboSettings) -> ComboSettings,
    ) = base.copy(combo = f(base.combo))

    private fun build(
        axis: Int,
        odds: List<Double>,
        config: WinDecisionConfig,
        real: ComboOdds = ComboOdds.EMPTY,
        pops: List<Int>? = null,
        probs: List<Double> = market(odds),
    ): BetStrategyBundle? {
        val horses = odds.mapIndexed { i, o -> DecisionHorse(i + 1, o, pops?.get(i) ?: (i + 1)) }
        val stake = WinStake(config.evShrinkFactor, config.kellyFraction, config.bankroll, config.minBet, config.maxBet, config.probSource)
        val race = ComboRaceContext(horses, probs, real, config.combo)
        return BetStrategyBuilder.build(axis, ProbRaw(probs[axis - 1]), odds[axis - 1], config, stake, race)
    }

    private fun BetStrategyBundle.entry(type: BetType) = strategies.single { it.type == type }

    private fun BetStrategyBundle.kumi(type: BetType) = entry(type).tickets.map { it.kumi }

    // ---- 流し: 相手選び -------------------------------------------------------------------------------------------

    @Test
    fun `ワイド流し - 相手は人気順の上位 top_k 頭（軸を除く）`() {
        val cfg = withCombo { it.copy(topKPartners = 3, boxSize = 3) }
        val b = build(1, odds8, cfg, all(8), pops8)!!
        // 人気順: 6(1) 2(2) 4(3) 1(4=軸) ... → 軸 1 の相手は 6, 2, 4。軸 1 は人気 5 位で BOX 候補（6, 2, 4）の外
        assertEquals(setOf("01-06", "01-02", "01-04"), b.kumi(BetType.WIDE).toSet())
        assertTrue(b.kumi(BetType.WIDE).none { it.startsWith("[BOX]") })
    }

    @Test
    fun `流しの相手の頭数 - ワイドは max(1, k)、三連複は max(3, k)`() {
        fun counts(k: Int): Pair<Int, Int> {
            val b = build(1, odds8, withCombo { it.copy(topKPartners = k, boxSize = 3) }, all(8), pops8)!!
            return b.entry(BetType.WIDE).tickets.size to b.entry(BetType.SANRENPUKU).tickets.size
        }
        assertEquals(1 to 3, counts(0)) // ワイド: 相手 max(1,0)=1、三連複: 相手 3 頭 → C(3,2)=3 通り
        assertEquals(1 to 3, counts(1))
        assertEquals(2 to 3, counts(2))
        assertEquals(3 to 3, counts(3))
        assertEquals(4 to 6, counts(4)) // 三連複: 相手 4 頭 → C(4,2)=6
        assertEquals(7 to 21, counts(100)) // 軸以外の 7 頭全部 → C(7,2)=21
    }

    @Test
    fun `人気 0 の馬は相手にならない`() {
        val pops = listOf(3, 0, 1, 0, 2, 4, 0, 5)
        val b = build(1, odds8, withCombo { it.copy(topKPartners = 2, boxSize = 3) }, all(8), pops)!!
        // 軸 1（人気 3）の相手は 人気 1 の 3 番と 人気 2 の 5 番（人気 0 の 2, 4, 7 番は、オッズが低くても対象外）
        assertEquals(setOf("01-03", "01-05"), b.entry(BetType.WIDE).tickets.filterNot { it.isBox }.map { it.kumi }.toSet())
    }

    @Test
    fun `人気の同順位は出走順を保つ`() {
        val pops = listOf(1, 1, 2, 2, 3, 3, 4, 4)
        val b = build(1, odds8, withCombo { it.copy(topKPartners = 3, boxSize = 3) }, all(8), pops)!!
        assertEquals(setOf("01-02", "01-03", "01-04"), b.entry(BetType.WIDE).tickets.filterNot { it.isBox }.map { it.kumi }.toSet())
    }

    @Test
    fun `人気が全て 0 - 流しの相手がおらず、BOX 候補だけがオッズ順になる`() {
        val odds = listOf(8.0, 3.0, 12.0, 5.0)
        val b = build(1, odds, withCombo { it.copy(topKPartners = 6, boxSize = 3) }, all(4), listOf(0, 0, 0, 0))!!
        // BOX 候補 = オッズ昇順の上位 3 頭 = 2(3.0), 4(5.0), 1(8.0)。軸 1 は含まれるので BOX が出る。流しは無し
        assertEquals(setOf("[BOX]02-04", "[BOX]01-02", "[BOX]01-04"), b.kumi(BetType.WIDE).toSet())
        assertEquals(listOf("[BOX]01-02-04"), b.kumi(BetType.SANRENPUKU))
    }

    @Test
    fun `オッズが 0 の馬は BOX 候補のオッズ順に入らない`() {
        val odds = listOf(8.0, 0.0, 12.0, 5.0)
        val b = build(1, odds, withCombo { it.copy(boxSize = 3) }, all(4), listOf(0, 0, 0, 0))!!
        // 候補は 4(5.0), 1(8.0), 3(12.0)
        assertEquals(setOf("[BOX]01-04", "[BOX]01-03", "[BOX]03-04"), b.kumi(BetType.WIDE).toSet())
    }

    // ---- BOX ------------------------------------------------------------------------------------------------------

    @Test
    fun `BOX - 軸が候補に入るときだけ。候補は人気上位 max(3, box_size) 頭`() {
        val odds = listOf(3.0, 4.5, 7.0, 12.0, 25.0, 40.0, 80.0)
        fun boxCount(
            axis: Int,
            boxSize: Int,
        ): Pair<Int, Int> {
            val b = build(axis, odds, withCombo { it.copy(topKPartners = 3, boxSize = boxSize) }, all(7, wide = 20_000.0, tri = 2_000_000.0))!!
            return b.entry(BetType.WIDE).tickets.count { it.isBox } to b.entry(BetType.SANRENPUKU).tickets.count { it.isBox }
        }
        assertEquals(3 to 1, boxCount(1, 0)) // max(3, 0) = 3 頭 → C(3,2)=3, C(3,3)=1
        assertEquals(3 to 1, boxCount(1, 3))
        assertEquals(6 to 4, boxCount(1, 4))
        assertEquals(10 to 10, boxCount(1, 5))
        assertEquals(21 to 35, boxCount(1, 7))
        assertEquals(21 to 35, boxCount(1, 10)) // 頭数を超える指定
        assertEquals(0 to 0, boxCount(5, 4)) // 軸（人気 5 位）が 4 頭の BOX 候補の外 → 追加しない
        assertEquals(10 to 10, boxCount(5, 5)) // 5 頭にすると候補に入る
    }

    @Test
    fun `頭数不足 - 2 頭では BOX も三連複も成立しない（ワイドは他の馬がいないので確率 0）`() {
        val real = all(2)
        val require = build(1, listOf(3.0, 4.5), raw, real)!!
        // ワイド: 相手 1 頭だが 3 着以内の組合せ確率 = 他に馬がいないので 0 → EV −1 で除外 → 実オッズ必須なら見送りエントリ
        assertTrue(require.entry(BetType.WIDE).isComboSkip)
        assertTrue(require.entry(BetType.SANRENPUKU).isComboSkip)
        val one = build(1, listOf(3.0), raw, ComboOdds.EMPTY)!!
        assertTrue(one.entry(BetType.WIDE).isComboSkip)
        val noRequire = build(1, listOf(3.0, 4.5), raw.copy(requireRealOddsForCombo = false), real)!!
        assertEquals(listOf(BetType.WIN), noRequire.strategies.map { it.type }, "実オッズ不要でも買い目が無ければ見送りエントリは作らない")
    }

    @Test
    fun `3 頭 - 流しと BOX が同じ組合せになり、重複を含んだまま保持する（除去は ComboTickets_deduplicated）`() {
        val odds = listOf(2.0, 3.3, 5.0)
        val b = build(1, odds, withCombo { it.copy(boxSize = 4) }, all(3), probs = listOf(0.5, 0.3, 0.2))!!
        // ワイド: 軸 1 の流し 01-02, 01-03、BOX は 3 頭全組合せ
        assertEquals(5, b.entry(BetType.WIDE).tickets.size)
        val dedup = ComboTickets.deduplicated(b.entry(BetType.WIDE).tickets)
        assertEquals(3, dedup.size)
        assertEquals(setOf("01-02", "01-03", "02-03"), dedup.map { it.kumi.removePrefix("[BOX]") }.toSet())
    }

    @Test
    fun `重複を点数上限の前に除くと数値が変わる - 生成段階では除かない（E9）`() {
        val odds = listOf(2.0, 3.3, 5.0)
        val probs = listOf(0.5, 0.3, 0.2)
        val real = ComboOdds(wideAll(3) + (WideKey.of(1, 2) to WideOddsRange(300.0, 360.0)), triAll(3))
        val two = build(1, odds, withCombo { it.copy(boxSize = 4, maxTicketsPerType = 2) }, real, probs = probs)!!
        // 上位 2 点は同じ組合せの流しと BOX（EV が同じ）。重複を先に除いていたら 2 点とも別の組合せになる
        val kumi = two.kumi(BetType.WIDE)
        assertEquals(2, kumi.size)
        assertEquals(kumi[0].removePrefix("[BOX]"), kumi[1].removePrefix("[BOX]"))
        assertFalse(kumi[0].startsWith("[BOX]"), "同値は生成順（流しが先）")
        assertTrue(kumi[1].startsWith("[BOX]"))
    }

    // ---- 実オッズと推定オッズ ---------------------------------------------------------------------------------------

    @Test
    fun `実オッズがあれば下限で EV を計算し、メモに範囲を表示する`() {
        val real = ComboOdds(wide = mapOf(WideKey.of(1, 2) to WideOddsRange(150.5, 180.25)))
        val b = build(1, listOf(3.0, 4.5, 7.0, 12.0), withCombo { it.copy(topKPartners = 1, boxSize = 3) }, real)!!
        val t = b.entry(BetType.WIDE).tickets.single { it.kumi == "01-02" }
        assertEquals(150.5, t.odds)
        assertEquals("150.5〜180.2", t.oddsNote) // 範囲の上限 180.25 は偶数丸め（Python の format と同じ）
        assertEquals(OddsSource.REAL, t.source)
        assertEquals(EvMath.calcEv(t.probUsed.value, 150.5), t.ev.value)
        assertEquals("※ 実オッズ優先（低い方でEV計算）", b.entry(BetType.WIDE).note)
    }

    @Test
    fun `実オッズが無く実オッズ必須なら買い目にせず、すべて無ければ見送りエントリ`() {
        val b = build(1, odds8, raw.copy(requireRealOddsForCombo = true), ComboOdds.EMPTY, pops8)!!
        for (type in listOf(BetType.WIDE, BetType.SANRENPUKU)) {
            val e = b.entry(type)
            assertTrue(e.isComboSkip)
            assertEquals(0.0, e.suggestedBet)
            assertEquals(0.0, e.ev.value)
            assertEquals("実オッズ未取得のため見送り（推定オッズは使用しない設定）", e.skipReason)
            assertEquals("${type.label}: 見送り", e.strategy)
            assertNull(e.kumi)
        }
    }

    @Test
    fun `一部の組合せだけ実オッズがあるとき - 実オッズ必須なら無い買い目は買い目にしない`() {
        val real = ComboOdds(wide = mapOf(WideKey.of(1, 2) to WideOddsRange(150.0, 160.0), WideKey.of(1, 3) to WideOddsRange(150.0, 160.0)))
        val b = build(1, listOf(3.0, 4.5, 7.0, 12.0, 25.0, 40.0), withCombo { it.copy(topKPartners = 6, boxSize = 3) }, real)!!
        assertEquals(setOf("01-02", "01-03"), b.kumi(BetType.WIDE).filterNot { it.startsWith("[BOX]") }.toSet())
        assertTrue(b.entry(BetType.SANRENPUKU).isComboSkip, "三連複は実オッズが 1 つも無い")
    }

    @Test
    fun `実オッズ必須でないとき - 無い買い目は単勝オッズからの推定オッズ。実オッズがあればそれが優先`() {
        val odds = listOf(3.0, 4.5, 7.0, 12.0, 25.0, 40.0)
        val real = ComboOdds(wide = mapOf(WideKey.of(6, 2) to WideOddsRange(33.0, 44.0)))
        val cfg = withCombo(raw.copy(requireRealOddsForCombo = false)) { it.copy(topKPartners = 2, boxSize = 3) }
        val b = build(6, odds, cfg, real)!!
        val tickets = b.entry(BetType.WIDE).tickets
        val realT = tickets.single { it.kumi == "02-06" }
        assertEquals(33.0, realT.odds)
        assertEquals(OddsSource.REAL, realT.source)
        val est = tickets.single { it.kumi == "01-06" } // 実オッズの無い組は、軸 6 の単勝 40.0 からの推定
        assertEquals(OddsSource.ESTIMATED, est.source)
        assertEquals(ComboOddsEstimate.wide(40.0), est.odds) // 12.0
        assertEquals("12.0（推定）", est.oddsNote)
        assertEquals("※ 実オッズ優先（低い方でEV計算）", b.entry(BetType.WIDE).note)
    }

    @Test
    fun `推定オッズのメモ - 実オッズの辞書が空なら『オッズは推定値』`() {
        val odds = listOf(3.0, 4.5, 7.0, 12.0, 25.0, 40.0)
        val b = build(6, odds, withCombo(raw.copy(requireRealOddsForCombo = false)) { it.copy(topKPartners = 2, boxSize = 3) })!!
        val e = b.entry(BetType.WIDE)
        assertTrue(e.tickets.isNotEmpty() && e.tickets.all { it.source == OddsSource.ESTIMATED })
        assertEquals("※ オッズは推定値", e.note)
    }

    @Test
    fun `BOX の推定オッズは 2 頭の単勝オッズの小さい方から`() {
        val odds = listOf(3.0, 4.5, 40.0, 60.0, 80.0, 90.0)
        val cfg = withCombo(raw.copy(requireRealOddsForCombo = false)) { it.copy(topKPartners = 1, boxSize = 6) }
        val b = build(3, odds, cfg, ComboOdds.EMPTY, listOf(1, 2, 3, 4, 5, 6))
        // 軸 3 は人気 3 位。BOX は全 6 頭。(3,4) は min(40, 60)=40 → 12.0、(5,6) は min(80, 90)=80 → 24.0
        val tickets = b!!.entry(BetType.WIDE).tickets
        assertTrue(tickets.any { it.isBox }, "BOX の買い目が少なくとも 1 点ある")
        tickets.filter { it.isBox }.forEach { t ->
            val (x, y) = t.kumi.removePrefix("[BOX]").split("-").map { it.toInt() }
            assertEquals(ComboOddsEstimate.wide(minOf(odds[x - 1], odds[y - 1])), t.odds, "${t.kumi}")
        }
    }

    @Test
    fun `三連複の実オッズ 0_0 は『無い』扱い - 必須なら見送り、不要なら推定に回る`() {
        val odds = listOf(3.0, 4.5, 7.0, 12.0, 25.0, 40.0)
        val zero = ComboOdds(sanrenpuku = mapOf(SanrenpukuKey.of(1, 2, 3) to 0.0, SanrenpukuKey.of(1, 2, 4) to 0.0))
        val required = build(1, odds, withCombo { it.copy(topKPartners = 3, boxSize = 3) }, zero)!!
        assertTrue(required.entry(BetType.SANRENPUKU).isComboSkip)
        val estimated = build(1, odds, withCombo(raw.copy(requireRealOddsForCombo = false)) { it.copy(topKPartners = 3, boxSize = 3) }, zero)!!
        val tri = estimated.strategies.firstOrNull { it.type == BetType.SANRENPUKU }
        // 推定の三連複オッズ（単勝 3.0 → 0.09）では EV が −1 に近く、買い目は無い → エントリなし
        assertNull(tri)
    }

    @Test
    fun `ワイドの実オッズ下限 0_0 はそのまま使われ（EV −1 で除外）、推定には戻らない`() {
        val zero = ComboOdds(wide = mapOf(WideKey.of(1, 2) to WideOddsRange(0.0, 0.0)))
        val b = build(1, listOf(3.0, 4.5, 7.0, 12.0, 25.0, 40.0), withCombo(raw.copy(requireRealOddsForCombo = false)) { it.copy(topKPartners = 1, boxSize = 3) }, zero)!!
        assertTrue(b.strategies.none { it.type == BetType.WIDE && it.tickets.any { t -> t.kumi == "01-02" } })
    }

    // ---- 確率の段階（E8）: 順序と境界 -------------------------------------------------------------------------------

    private val oddsF = listOf(3.0, 4.5, 7.0, 12.0, 25.0, 40.0)
    private val probsF = market(oddsF)
    private val plF = PlackettLuce.of((1..6).toList(), probsF, 1.0)

    /** 軸 1・相手 2 のワイド 1 点だけの戦略（BOX なし・相手 1 頭）。 */
    private fun oneWide(
        cfg: WinDecisionConfig,
        low: Double = 20.0,
    ): ComboTicket? {
        val real = ComboOdds(wide = mapOf(WideKey.of(1, 2) to WideOddsRange(low, low + 1.0)))
        val b = build(1, oddsF, withCombo(cfg) { it.copy(topKPartners = 1, boxSize = 3) }.copy(betTypes = setOf(BetType.WIDE)), real, pops = listOf(9, 1, 2, 3, 4, 5))
        return b?.strategies?.singleOrNull()?.tickets?.singleOrNull()
    }

    @Test
    fun `確率の縮小は上限より先 - 縮小後の値に上限をかける`() {
        val rawP = plF.widePairProb(1, 2)
        // 縮小 0.5 → 0.3309… は上限 0.35 に掛からない。上限を先に掛けると 0.35 × 0.5 = 0.175 になってしまう
        val t = oneWide(withCombo { it.copy(wideMaxProb = 0.35) }.copy(evShrinkFactor = 0.5))!!
        assertEquals(rawP, t.probRaw.value)
        assertEquals(rawP * 0.5, t.probUsed.value)
        // 縮小 1.0 で上限 0.2 に掛かる
        assertEquals(0.2, oneWide(withCombo { it.copy(wideMaxProb = 0.2) })!!.probUsed.value)
    }

    @Test
    fun `上限 max_prob は 0 以下で無効`() {
        val rawP = plF.widePairProb(1, 2)
        assertEquals(rawP, oneWide(withCombo { it.copy(wideMaxProb = 0.0) })!!.probUsed.value)
        assertEquals(rawP, oneWide(withCombo { it.copy(wideMaxProb = -0.1) })!!.probUsed.value)
    }

    @Test
    fun `確率は縮小で 0 から 0_99 に収まる - 縮小係数は 0 から 1 に収める`() {
        assertEquals(plF.widePairProb(1, 2), oneWide(raw.copy(evShrinkFactor = 1.5))!!.probUsed.value) // 1.5 → 1.0
        assertNull(oneWide(raw.copy(evShrinkFactor = -1.0)), "-1 → 0.0 → 確率 0 → EV −1")
        assertNull(oneWide(raw.copy(evShrinkFactor = 0.0)))
    }

    @Test
    fun `市場比上限は max_prob の後 - 払戻率 割る odds の倍率が上限`() {
        // 上限 0.2 のあと、市場比 1.5 × 0.775 / 20.0 = 0.058125（k = 1.0 だと EV = 払戻率 − 1 < 0 で必ず除外される）
        val cfg = withCombo { it.copy(wideMaxProb = 0.2, wideMaxOverMarket = 1.5) }.copy(widePayoutRate = 0.775)
        assertEquals(1.5 * (0.775 / 20.0), oneWide(cfg, low = 20.0)!!.probUsed.value)
        // 市場比上限が max_prob より緩いなら max_prob のまま
        val loose = withCombo { it.copy(wideMaxProb = 0.06, wideMaxOverMarket = 100.0) }.copy(widePayoutRate = 0.775)
        assertEquals(0.06, oneWide(loose, low = 20.0)!!.probUsed.value)
    }

    @Test
    fun `市場比上限 - 0 以下は無効、払戻率は 0 から 1 に収め、odds は 0_1 以上にする`() {
        val rawP = plF.widePairProb(1, 2)
        assertEquals(rawP, oneWide(withCombo { it.copy(wideMaxOverMarket = 0.0) })!!.probUsed.value)
        assertEquals(rawP, oneWide(withCombo { it.copy(wideMaxOverMarket = -2.0) })!!.probUsed.value)
        // 払戻率 1.5 → 1.0
        assertEquals(1.5 * (1.0 / 20.0), oneWide(withCombo { it.copy(wideMaxOverMarket = 1.5) }.copy(widePayoutRate = 1.5), low = 20.0)!!.probUsed.value)
        // 払戻率 −0.2 → 0.0 → 上限 0 → 確率 0 → EV −1 で除外
        assertNull(oneWide(withCombo { it.copy(wideMaxOverMarket = 1.5) }.copy(widePayoutRate = -0.2), low = 20.0))
        // odds が 0.1 未満でも 0.1 として扱う（上限の分母）。EV は odds そのもので計算するので除外される
        assertNull(oneWide(withCombo { it.copy(wideMaxOverMarket = 1.5) }, low = 0.05))
    }

    @Test
    fun `下限 min_prob は上限・市場比上限の後に比べる - ちょうどは残り、わずかに上は除外`() {
        val cap = withCombo { it.copy(wideMaxProb = 0.2) }
        // 上限 0.2 と下限 0.2 → p == min は残る（p < min のときだけ除外）
        assertNotNull(oneWide(withCombo(cap) { it.copy(wideMinProb = 0.2) }))
        assertNull(oneWide(withCombo(cap) { it.copy(wideMinProb = 0.2000001) }))
        // 生の確率は 0.66 だが、下限は上限をかけた後の 0.2 と比べる
        assertNull(oneWide(withCombo(cap) { it.copy(wideMinProb = 0.25) }))
        // 下限 0 以下は無効
        assertNotNull(oneWide(withCombo(cap) { it.copy(wideMinProb = 0.0) }))
        assertNotNull(oneWide(withCombo(cap) { it.copy(wideMinProb = -0.5) }))
    }

    @Test
    fun `EV がちょうど 0 の買い目は除外し、わずかに正なら残る`() {
        val cap = withCombo { it.copy(wideMaxProb = 0.25) } // p = 0.25 に固定
        assertNull(oneWide(cap, low = 4.0), "0.25 × 4.0 − 1 = 0 は除外（ev <= 0）")
        val t = oneWide(cap, low = 4.01)
        assertNotNull(t)
        assertTrue(t.ev.value > 0.0)
        assertNull(oneWide(cap, low = 3.99))
    }

    @Test
    fun `三連複も同じ段階で処理される`() {
        val rawP = plF.top3SetProb(1, 2, 3)
        val real = ComboOdds(sanrenpuku = mapOf(SanrenpukuKey.of(1, 2, 3) to 20.0))
        fun tri(f: (ComboSettings) -> ComboSettings): ComboTicket? {
            val cfg = withCombo(raw.copy(betTypes = setOf(BetType.SANRENPUKU))) { f(it.copy(topKPartners = 2, boxSize = 3)) }
            return build(1, oddsF, cfg, real, pops = listOf(9, 1, 2, 3, 4, 5))?.strategies?.singleOrNull()?.tickets?.singleOrNull()
        }
        assertEquals(rawP, tri { it }!!.probUsed.value)
        assertEquals(0.1, tri { it.copy(sanrenpukuMaxProb = 0.1) }!!.probUsed.value)
        assertEquals(1.5 * (0.75 / 20.0), tri { it.copy(sanrenpukuMaxOverMarket = 1.5) }!!.probUsed.value)
        assertNull(tri { it.copy(sanrenpukuMaxProb = 0.1, sanrenpukuMinProb = 0.1000001) })
        assertNotNull(tri { it.copy(sanrenpukuMaxProb = 0.1, sanrenpukuMinProb = 0.1) })
        assertNull(tri { it.copy(sanrenpukuMaxProb = 0.05) }, "0.05 × 20.0 − 1 = 0 は除外")
    }

    // ---- 並べ替え・点数上限 ----------------------------------------------------------------------------------------

    @Test
    fun `買い目は EV 降順 - 同値は生成順（流しが BOX より先）`() {
        val b = build(1, listOf(3.0, 4.5, 7.0, 12.0, 25.0, 40.0), withCombo { it.copy(topKPartners = 3, boxSize = 4) }, all(6))!!
        val tickets = b.entry(BetType.WIDE).tickets
        assertEquals(tickets.sortedByDescending { it.ev.value }, tickets)
        val i = tickets.indexOfFirst { it.kumi == "01-02" }
        assertEquals(i + 1, tickets.indexOfFirst { it.kumi == "[BOX]01-02" })
    }

    @Test
    fun `点数上限は max(1, n) - 0 や負でも 1 点は残る`() {
        for (n in listOf(0, -3, 1)) {
            val b = build(1, oddsF, withCombo { it.copy(maxTicketsPerType = n, boxSize = 4) }, all(6))!!
            assertEquals(1, b.entry(BetType.WIDE).tickets.size, "n=$n")
            assertEquals(1, b.entry(BetType.SANRENPUKU).tickets.size, "n=$n")
        }
        assertEquals(3, build(1, oddsF, withCombo { it.copy(maxTicketsPerType = 3, boxSize = 4) }, all(6))!!.entry(BetType.WIDE).tickets.size)
    }

    @Test
    fun `代表値は最良の買い目 - odds・確率・EV・組合せ・戦略名`() {
        val b = build(1, oddsF, withCombo { it.copy(maxTicketsPerType = 5, boxSize = 4) }, all(6))!!
        val e = b.entry(BetType.WIDE)
        val best = e.tickets.first()
        assertEquals(best.odds, e.odds)
        assertEquals(best.probRaw, e.probRaw)
        assertEquals(best.probUsed, e.probUsed)
        assertEquals(best.ev, e.ev)
        assertEquals(best.kumi, e.kumi)
        assertEquals("ワイド：軸1→流し＋BOX候補（上位5点）", e.strategy)
        assertEquals("三連複：軸1→流し＋BOX候補（上位5点）", b.entry(BetType.SANRENPUKU).strategy)
        assertFalse(e.isComboSkip)
    }

    // ---- 賭け額と配分 ---------------------------------------------------------------------------------------------

    private val stakeCfg = withCombo { it.copy(maxTicketsPerType = 6, boxSize = 4) }.copy(evShrinkFactor = 0.7, bankroll = 1000.0)

    private fun realStake(): ComboOdds {
        val p = market(oddsF)
        return ComboOdds(
            wide = (1..6).flatMap { a -> (a + 1..6).map { b -> WideKey.of(a, b) to WideOddsRange(0.5 / (p[a - 1] * p[b - 1]) + 1.0, 0.7 / (p[a - 1] * p[b - 1]) + 1.0) } }.toMap(),
            sanrenpuku = triAll(6, 0.0).keys.associateWith { k -> 0.3 / (p[k.first - 1] * p[k.second - 1] * p[k.third - 1]) + 1.0 },
        )
    }

    @Test
    fun `best_only - 先頭の買い目に全額、他は 0_0。合計は推奨賭け額`() {
        val e = build(1, oddsF, stakeCfg, realStake())!!.entry(BetType.WIDE)
        assertTrue(e.suggestedBet > 0)
        assertEquals(e.suggestedBet, e.tickets[0].stake)
        assertTrue(e.tickets.drop(1).all { it.stake == 0.0 })
        assertEquals(e.suggestedBet, e.tickets.sumOf { it.stake ?: 0.0 })
        // 期待利益は買い目の合計 = 先頭の賭け額 × EV
        assertEquals(e.suggestedBet * e.tickets[0].ev.value, e.expectedProfit)
    }

    @Test
    fun `proportional_ev - EV に比例して合計が推奨賭け額になる`() {
        val cfg = stakeCfg.copy(ticketAllocation = "proportional_ev")
        val e = build(1, oddsF, cfg, realStake())!!.entry(BetType.SANRENPUKU)
        val sumEv = e.tickets.sumOf { it.ev.value }
        for (t in e.tickets) assertEquals(e.suggestedBet * t.ev.value / sumEv, t.stake!!, 1e-12)
        assertEquals(e.suggestedBet, e.tickets.sumOf { it.stake!! }, 1e-12)
        assertEquals(e.tickets.sumOf { it.stake!! * it.ev.value }, e.expectedProfit, 1e-12)
        assertTrue(e.tickets.map { it.stake!! }.distinct().size > 1, "買い目ごとに配分が違う")
    }

    @Test
    fun `配分の文字列 - 大文字小文字は区別せず、proportional_ev 以外はすべて best_only`() {
        fun first(alloc: String): ComboTicket? =
            build(1, oddsF, stakeCfg.copy(ticketAllocation = alloc), realStake())!!.entry(BetType.WIDE).tickets.let { it.getOrNull(1) }
        val prop = first("proportional_ev")!!.stake!!
        assertTrue(prop > 0.0)
        assertEquals(prop, first("PROPORTIONAL_EV")!!.stake)
        assertEquals(prop, first("Proportional_Ev")!!.stake)
        for (other in listOf("best_only", "", " proportional_ev", "proportional", "unknown", "BEST_ONLY")) {
            assertEquals(0.0, first(other)!!.stake, "『$other』は best_only 扱い")
        }
    }

    @Test
    fun `賭け額 0 のとき - 買い目に賭け額を付けず、期待利益も 0 で、見送り理由を記録する`() {
        val e = build(1, oddsF, stakeCfg.copy(minBet = 10_000.0), realStake())!!.entry(BetType.WIDE)
        assertEquals(0.0, e.suggestedBet)
        assertTrue(e.tickets.all { it.stake == null })
        assertEquals(0.0, e.expectedProfit)
        assertTrue(e.skipReason!!.startsWith("Kelly推奨額("))
        assertTrue(e.skipReason.endsWith("点) < min_bet(10000.0点)"))
        assertFalse(e.clipped)
        assertTrue(e.ev.value > 0, "賭け額 0 でも EV は代表値のまま")
    }

    @Test
    fun `賭け額は先頭の買い目の Kelly - min_bet 以上 max_bet 以下はそのまま、超過は max_bet にクリップ`() {
        val e = build(1, oddsF, stakeCfg.copy(maxBet = 1e9), realStake())!!.entry(BetType.WIDE)
        val best = e.tickets[0]
        val kellyRaw = KellyMath.calcKellyFraction(best.probUsed.value, best.odds, 1.0)
        assertEquals(kellyRaw, e.kellyRaw)
        assertEquals(kellyRaw * (0.25 * 0.8), e.kellyAdjusted) // 確率源ラベルが一致しないので係数 0.8（H10）
        assertEquals(1000.0 * (kellyRaw * (0.25 * 0.8)), e.suggestedBet)
        assertEquals(e.suggestedBet, e.kellyBetBeforeClip)
        assertEquals(kellyRaw * (0.25 * 0.8), e.kellyFraction)
        val clipped = build(1, oddsF, stakeCfg.copy(maxBet = 5.0), realStake())!!.entry(BetType.WIDE)
        assertEquals(5.0, clipped.suggestedBet)
        assertTrue(clipped.clipped)
        assertEquals(e.suggestedBet, clipped.kellyBetBeforeClip)
    }

    @Test
    fun `確率源ラベルによる Kelly 係数（H10） - 完全一致のラベルだけ 1_0 や 0_9`() {
        fun adj(label: String) = build(1, oddsF, stakeCfg.copy(probSource = label, maxBet = 1e9, kellyFraction = 0.5), realStake())!!.entry(BetType.WIDE).kellyAdjusted / build(1, oddsF, stakeCfg.copy(probSource = label, maxBet = 1e9, kellyFraction = 0.5), realStake())!!.entry(BetType.WIDE).kellyRaw
        assertEquals(0.5, adj("ml_features"), 1e-15)
        assertEquals(0.45, adj("ML_MODEL"), 1e-15)
        assertEquals(0.4, adj("market"), 1e-15)
        assertEquals(0.4, adj("market:fallback(ml_disabled)"), 1e-15)
        assertEquals(0.4, adj(""), 1e-15)
    }

    // ---- 最推奨・合計 ---------------------------------------------------------------------------------------------

    @Test
    fun `最推奨 - 賭け額が正のものの中で EV 最大。賭け額が正でなければ全体で EV 最大（見送りが最推奨になり得る）`() {
        val b = build(1, oddsF, stakeCfg, realStake())!!
        val withBet = b.strategies.filter { it.suggestedBet > 0 }
        assertTrue(withBet.isNotEmpty())
        assertEquals(withBet.maxBy { it.ev.value }.type, b.best.type)
        // 賭け額が全部 0 なら EV 最大（同値は先に登録されたもの）
        val none = build(1, oddsF, stakeCfg.copy(minBet = 1e9), realStake())!!
        assertTrue(none.strategies.all { it.suggestedBet == 0.0 })
        assertEquals(none.strategies.maxBy { it.ev.value }.type, none.best.type)
    }

    @Test
    fun `最推奨 - 単勝が負の EV で全て賭け額 0 のとき、見送りエントリ（EV 0）が最推奨で、同値はワイドが先`() {
        val b = build(1, listOf(1.5, 3.0, 5.0, 8.0), raw.copy(requireRealOddsForCombo = true), probs = listOf(0.3, 0.3, 0.2, 0.2))!!
        assertEquals(listOf(BetType.WIN, BetType.WIDE, BetType.SANRENPUKU), b.strategies.map { it.type })
        assertTrue(b.entry(BetType.WIN).ev.value < 0)
        assertEquals(BetType.WIDE, b.best.type)
        assertTrue(b.best.isComboSkip)
    }

    @Test
    fun `最推奨の同値は 単勝 → ワイド → 三連複 の登録順で先のもの`() {
        // 三者とも賭け額 0・EV 0.0 の見送り（単勝は EV 0 ちょうどになる確率 0.5 / odds 2.0）
        val b = build(1, listOf(2.0, 2.0), raw.copy(requireRealOddsForCombo = true, evShrinkFactor = 1.0), probs = listOf(0.5, 0.5))!!
        assertEquals(0.0, b.entry(BetType.WIN).ev.value)
        assertEquals(BetType.WIN, b.best.type)
    }

    @Test
    fun `合計 - 全エントリの賭け額と期待利益の和（best_only 絞り込みでは最推奨だけ）`() {
        val b = build(1, oddsF, stakeCfg, realStake())!!
        assertEquals(PythonCompat.sum(b.strategies.map { it.suggestedBet }), b.totalSuggestedBet)
        assertEquals(PythonCompat.sum(b.strategies.map { it.expectedProfit }), b.totalExpectedProfit)
        val only = build(1, oddsF, stakeCfg.copy(betTypeMode = BetTypeMode.BEST_ONLY), realStake())!!
        assertEquals(listOf(b.best.type), only.strategies.map { it.type })
        assertEquals(b.best.suggestedBet, only.totalSuggestedBet)
        assertEquals(b.best.expectedProfit, only.totalExpectedProfit)
        assertEquals("best_only", only.meta.betTypeMode)
    }

    @Test
    fun `賭式の絞り込み - 対象外の賭式のエントリは作らない`() {
        val b = build(1, oddsF, stakeCfg.copy(betTypes = setOf(BetType.WIDE)), realStake())!!
        assertEquals(listOf(BetType.WIDE), b.strategies.map { it.type })
        assertNull(build(1, oddsF, stakeCfg.copy(betTypes = emptySet()), realStake()))
    }

    // ---- 総投資上限での縮小 ----------------------------------------------------------------------------------------

    @Test
    fun `総投資上限 - 買い目の賭け額は 1 回だけ scale 倍、最推奨の賭け額・期待利益は scale の 2 乗倍（旧実装のまま）`() {
        val cfg = stakeCfg.copy(ticketAllocation = "proportional_ev", betTypes = setOf(BetType.WIDE), maxBet = 1e9)
        val b = build(1, oddsF, cfg, realStake())!!
        val before = b.entry(BetType.WIDE)
        val scale = 0.5
        val capped = b.withRaceCap(cap = 10.0, scale = scale)
        val after = capped.entry(BetType.WIDE)
        assertEquals(before.suggestedBet * scale * scale, after.suggestedBet)
        assertEquals(before.expectedProfit * scale * scale, after.expectedProfit)
        assertEquals(capped.best, after)
        for ((t0, t1) in before.tickets.zip(after.tickets)) {
            assertEquals(t0.stake!! * scale, t1.stake)
            assertEquals(t0.ev, t1.ev) // 賭け額以外は変えない
            assertEquals(t0.probUsed, t1.probUsed)
        }
        // 買い目の賭け額の合計は 縮小前の推奨額 × scale で、最推奨の賭け額（× scale²）とは一致しない（旧実装の不整合。未承認のため直さない）
        assertEquals(before.suggestedBet * scale, after.tickets.sumOf { it.stake!! }, 1e-12)
        assertTrue(abs(after.suggestedBet - after.tickets.sumOf { it.stake!! }) > 1e-6)
        assertTrue(capped.meta.raceBetCapApplied)
        assertEquals(before.suggestedBet, b.totalSuggestedBet, "合計は縮小しない")
    }

    @Test
    fun `総投資上限 - 最推奨以外の賭式は賭け額も買い目も 1 回だけ scale 倍`() {
        val b = build(1, oddsF, stakeCfg.copy(ticketAllocation = "proportional_ev", maxBet = 1e9), realStake())!!
        val other = b.strategies.first { it.type != b.best.type && it.suggestedBet > 0 }
        val scale = 0.25
        val after = b.withRaceCap(10.0, scale).entry(other.type)
        assertEquals(other.suggestedBet * scale, after.suggestedBet)
        assertEquals(other.expectedProfit * scale, after.expectedProfit)
        assertEquals(other.suggestedBet * scale, after.tickets.sumOf { it.stake!! }, 1e-12)
    }

    @Test
    fun `総投資上限 - 賭け額が付かなかった買い目（stake なし）は縮小しても stake なしのまま`() {
        val b = build(1, oddsF, stakeCfg.copy(minBet = 1e9), realStake())!!
        val after = b.withRaceCap(1.0, 0.5)
        assertTrue(after.strategies.flatMap { it.tickets }.all { it.stake == null })
    }

    // ---- 入力の境界 ------------------------------------------------------------------------------------------------

    @Test
    fun `馬番が重複していると買い目の確率を計算するときに拒否する - 見送りだけの経路には影響しない`() {
        val horses = listOf(DecisionHorse(1, 3.0, 1), DecisionHorse(2, 4.0, 2), DecisionHorse(2, 6.0, 3), DecisionHorse(4, 9.0, 4))
        val probs = listOf(0.4, 0.3, 0.2, 0.1)
        val stake = WinStake(1.0, 0.25, 100.0, 1.0, 10.0, "")
        // 実オッズ必須・実オッズ無し: 旧実装の見送りエントリ（確率は計算しない）
        val skip = BetStrategyBuilder.build(1, ProbRaw(0.4), 3.0, raw, stake, ComboRaceContext(horses, probs, ComboOdds.EMPTY, raw.combo))!!
        assertTrue(skip.entry(BetType.WIDE).isComboSkip)
        // 実オッズがあり確率が必要になる
        val real = ComboOdds(wide = mapOf(WideKey.of(1, 2) to WideOddsRange(20.0, 30.0)))
        assertFailsWith<IllegalArgumentException> {
            BetStrategyBuilder.build(1, ProbRaw(0.4), 3.0, raw, stake, ComboRaceContext(horses, probs, real, raw.combo))
        }
        val zero = listOf(DecisionHorse(1, 3.0, 1), DecisionHorse(0, 4.0, 2), DecisionHorse(3, 6.0, 3))
        val real13 = ComboOdds(wide = mapOf(WideKey.of(1, 3) to WideOddsRange(20.0, 30.0)))
        assertFailsWith<IllegalArgumentException> {
            BetStrategyBuilder.build(1, ProbRaw(0.4), 3.0, raw, stake, ComboRaceContext(zero, listOf(0.5, 0.3, 0.2), real13, raw.combo))
        }
    }

    @Test
    fun `馬と確率の数が違えば拒否`() {
        assertFailsWith<IllegalArgumentException> { ComboRaceContext(listOf(DecisionHorse(1, 3.0, 1)), emptyList(), ComboOdds.EMPTY, raw.combo) }
    }

    @Test
    fun `設定の非有限値は拒否する`() {
        assertFailsWith<IllegalArgumentException> { ComboSettings.legacyFunctionDefaults().copy(wideMaxProb = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { ComboSettings.legacyFunctionDefaults().copy(plackettLuceAlpha = Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { ComboSettings.legacyFunctionDefaults().copy(sanrenpukuMaxOverMarket = Double.NaN) }
    }

    @Test
    fun `旧関数の既定値 - 流し 6 頭・点数 6・BOX 4・上限 0_35 と 0_20・alpha 1`() {
        val d = ComboSettings.legacyFunctionDefaults()
        assertEquals(6, d.topKPartners)
        assertEquals(6, d.maxTicketsPerType)
        assertEquals(4, d.boxSize)
        assertEquals(0.35, d.wideMaxProb)
        assertEquals(0.20, d.sanrenpukuMaxProb)
        assertEquals(0.0, d.wideMinProb)
        assertEquals(1.0, d.plackettLuceAlpha)
        assertEquals(0.0, d.wideMaxOverMarket)
    }
}
