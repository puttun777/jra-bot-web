package jp.jrabot.domain.decision

import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ワイド・三連複が最推奨になるときの判定（シグナル・見送りシグナル・並び・総投資上限）。
 * 旧 Python との数値一致はゴールデン GD06-EVAL-011 が検証する。ここは分岐と、賭式をまたぐ同点の順序の単体テスト。
 */
class WinDecisionComboTest {
    private val odds6 = listOf(3.0, 4.5, 7.0, 12.0, 25.0, 40.0)
    private val odds18 = listOf(4.2, 6.8, 9.6, 13.0, 16.0, 21.0, 26.0, 32.0, 40.0, 52.0, 66.0, 84.0, 110.0, 140.0, 180.0, 240.0, 360.0, 500.0)

    private fun horses(odds: List<Double>) = odds.mapIndexed { i, o -> DecisionHorse(i + 1, o, i + 1) }

    private fun marketProbs(odds: List<Double>): List<Double> {
        val inv = odds.map { 1.0 / it }
        val t = PythonCompat.sum(inv)
        return inv.map { it / t }
    }

    /** 実オッズ: 買い目の確率の目安（独立馬の積）から EV が正と負の両方になるように作る（GD06-EVAL-011 と同じ式）。 */
    private fun realOdds(odds: List<Double>): ComboOdds {
        val p = marketProbs(odds)
        val n = odds.size
        val wide =
            (1..n)
                .flatMap { a ->
                    (a + 1..n).map { b ->
                        WideKey.of(a, b) to
                            WideOddsRange(0.3 / (p[a - 1] * p[b - 1]) + 1.0, 0.5 / (p[a - 1] * p[b - 1]) + 1.0)
                    }
                }.toMap()
        val tri =
            (1..n)
                .flatMap { a ->
                    (a + 1..n).flatMap { b -> (b + 1..n).map { c -> SanrenpukuKey.of(a, b, c) to 0.2 / (p[a - 1] * p[b - 1] * p[c - 1]) + 1.0 } }
                }.toMap()
        return ComboOdds(wide, tri)
    }

    private val cfg =
        WinDecisionConfig.legacyFunctionDefaults().copy(
            evThreshold = -0.5,
            evShrinkFactor = 1.0,
            bankroll = 2000.0,
            maxBet = 50.0,
            maxSignals = 6,
            combo = ComboSettings.legacyFunctionDefaults().copy(boxSize = 4, maxTicketsPerType = 4),
        )

    private fun evaluate(
        config: WinDecisionConfig,
        odds: List<Double> = odds6,
        real: ComboOdds = realOdds(odds),
    ) = WinDecisionEvaluator(config).evaluate(horses(odds), real)

    private fun bets(r: WinDecisionResult) = r.signals.filterIsInstance<BetSignal>()

    // ---- ワイド・三連複が最推奨のシグナル ---------------------------------------------------------------------------

    @Test
    fun `最推奨が三連複のとき - シグナルは最良の買い目の値と、軸の馬の最終確率・市場確率を持つ`() {
        val r = evaluate(cfg)
        val s = bets(r)
        assertTrue(s.isNotEmpty())
        val first = s.first { it.betType == BetType.SANRENPUKU }
        val bundle = assertNotNull(first.betStrategy)
        val best = bundle.best
        assertEquals(BetType.SANRENPUKU, best.type)
        assertEquals("sanrenpuku", first.betType.marketCode)
        assertEquals(best.odds, first.odds)
        assertEquals(best.probRaw, first.prob) // 買い目の生の確率（Plackett–Luce）
        assertEquals(best.ev, first.ev)
        assertEquals(best.suggestedBet, first.betAmount)
        assertEquals(best.tickets.first().kumi, best.kumi)
        // 軸の馬の確率（pModel / pFinal）と ratio は単勝のまま。買い目の確率とは別
        assertTrue(first.pFinal.value != first.prob.value)
        assertEquals(first.pFinal.value / first.pMarket, first.ratio.value)
        assertTrue(first.reason.startsWith("最推奨=三連複（軸: 馬${first.umaban}） EV="))
        // 検算用の値は買い目の生の確率・オッズ基準
        assertEquals(first.odds * first.prob.value, first.calcExpectedReturn)
    }

    @Test
    fun `シグナルの戦略一式には買い目と賭け額の配分が入る`() {
        val r = evaluate(cfg.copy(ticketAllocation = "proportional_ev"))
        val bundle = assertNotNull(bets(r).first().betStrategy)
        val e = bundle.best
        assertTrue(e.tickets.size in 1..4)
        assertEquals(e.suggestedBet, e.tickets.sumOf { it.stake!! }, 1e-12)
        assertEquals(listOf(BetType.WIN, BetType.WIDE, BetType.SANRENPUKU), bundle.strategies.map { it.type })
    }

    @Test
    fun `賭式を絞ると、その賭式がシグナルになる`() {
        for (type in listOf(BetType.WIDE, BetType.SANRENPUKU, BetType.WIN)) {
            val s = bets(evaluate(cfg.copy(betTypes = setOf(type))))
            assertTrue(s.isNotEmpty() && s.all { it.betType == type }, "type=$type")
        }
    }

    @Test
    fun `include_bet_strategy が false でも判定は戦略ベース - 戦略一式だけが付かない`() {
        val with = bets(evaluate(cfg))
        val without = bets(evaluate(cfg.copy(includeBetStrategy = false)))
        assertEquals(with.map { it.betType to it.betAmount }, without.map { it.betType to it.betAmount })
        assertTrue(without.all { it.betStrategy == null })
        assertTrue(with.all { it.betStrategy != null })
    }

    @Test
    fun `bet_type_mode=best_only - 戦略一式は最推奨 1 賭式だけで、合計もそれに揃う`() {
        val s = bets(evaluate(cfg.copy(betTypeMode = BetTypeMode.BEST_ONLY)))
        for (sig in s) {
            val b = sig.betStrategy!!
            assertEquals(listOf(sig.betType), b.strategies.map { it.type })
            assertEquals(b.best.suggestedBet, b.totalSuggestedBet)
            assertEquals("best_only", b.meta.betTypeMode)
        }
    }

    // ---- 実オッズの有無 -------------------------------------------------------------------------------------------

    @Test
    fun `実オッズを渡さないと、ワイド・三連複は見送りエントリで、単勝の結果は実オッズの有無に影響されない`() {
        val none = evaluate(cfg.copy(betTypes = setOf(BetType.WIN)), real = ComboOdds.EMPTY)
        val withReal = evaluate(cfg.copy(betTypes = setOf(BetType.WIN)))
        assertEquals(none, withReal, "単勝だけを対象にすれば実オッズは結果に影響しない")
        val all = evaluate(cfg, real = ComboOdds.EMPTY)
        for (sig in bets(all)) assertEquals(BetType.WIN, sig.betType)
        val e =
            bets(all)
                .first()
                .betStrategy!!
                .strategies
                .single { it.type == BetType.WIDE }
        assertTrue(e.isComboSkip)
    }

    @Test
    fun `実オッズが無く単勝が除外されると、見送りエントリが最推奨になり見送りシグナルになる`() {
        val c = cfg.copy(betTypes = setOf(BetType.WIDE, BetType.SANRENPUKU), notifySkips = true, evThreshold = -1.5)
        val r = evaluate(c, real = ComboOdds.EMPTY)
        val skips = r.signals.filterIsInstance<SkipSignal>()
        assertEquals(6, skips.size)
        for (s in skips) {
            assertEquals(BetType.WIDE, s.betType) // 同値（EV 0.0）はワイドが先
            assertEquals("実オッズ未取得のため見送り（推定オッズは使用しない設定）", s.skipReason)
            assertEquals(c.widePayoutRate, s.payoutRate)
        }
        assertTrue(r.rejections.all { it.reason == DecisionReason.NO_STAKE })
    }

    @Test
    fun `実オッズ必須でない設定 - 推定オッズで判定でき、例外にならない`() {
        val r = evaluate(cfg.copy(requireRealOddsForCombo = false), real = ComboOdds.EMPTY)
        assertTrue(bets(r).isNotEmpty())
        assertTrue(
            bets(r)
                .first()
                .betStrategy!!
                .meta.requireRealOddsForCombo
                .not(),
        )
    }

    @Test
    fun `賭け額 0 のワイド・三連複 - notify_skips なら見送りシグナル、なしなら除外`() {
        val c = cfg.copy(minBet = 1e9, betTypes = setOf(BetType.WIDE))
        assertTrue(evaluate(c).signals.isEmpty())
        val withSkips = evaluate(c.copy(notifySkips = true))
        val skip = withSkips.signals.first()
        assertIs<SkipSignal>(skip)
        assertEquals(BetType.WIDE, skip.betType)
        assertTrue(skip.skipReason!!.startsWith("Kelly推奨額("))
        assertEquals(c.widePayoutRate, skip.payoutRate)
        assertTrue(skip.ev.value > 0.0)
        assertTrue(
            skip.betStrategy.best.tickets
                .all { it.stake == null },
        )
    }

    // ---- 総投資上限 ------------------------------------------------------------------------------------------------

    @Test
    fun `総投資上限 - シグナルの賭け額は scale 倍、戦略の最推奨は scale の 2 乗倍、買い目は scale 倍`() {
        val base = cfg.copy(maxSignals = 3, kellyFraction = 1.0, maxBet = 10.0, ticketAllocation = "proportional_ev")
        val uncapped = bets(evaluate(base))
        val total = uncapped.sumOf { it.betAmount }
        val cap = total / 2.0
        val capped = bets(evaluate(base.copy(maxTotalBetPerRace = cap)))
        val scale = cap / total
        assertEquals(uncapped.size, capped.size)
        for ((u, c) in uncapped.zip(capped)) {
            assertEquals(u.betAmount * scale, c.betAmount)
            val ub = u.betStrategy!!.best
            val cb = c.betStrategy!!.best
            assertEquals(ub.suggestedBet * scale * scale, cb.suggestedBet)
            for ((t0, t1) in ub.tickets.zip(cb.tickets)) assertEquals(t0.stake!! * scale, t1.stake)
            assertEquals(scale, c.betStrategy.meta.raceBetScale)
        }
        assertEquals(cap, capped.sumOf { it.betAmount }, 1e-9)
    }

    @Test
    fun `総投資上限が合計以上なら縮小しない - 0 以下や null は無効`() {
        val base = cfg.copy(maxSignals = 3)
        val uncapped = bets(evaluate(base))
        for (cap in listOf(1e9, 0.0, -5.0)) assertEquals(uncapped, bets(evaluate(base.copy(maxTotalBetPerRace = cap))))
    }

    // ---- 並び ------------------------------------------------------------------------------------------------------

    @Test
    fun `edge - EV の降順で max_signals 件`() {
        val all = bets(evaluate(cfg.copy(maxSignals = 6)))
        assertEquals(all.sortedByDescending { it.ev.value }, all)
        val top2 = bets(evaluate(cfg.copy(maxSignals = 2)))
        assertEquals(all.take(2), top2)
        assertTrue(evaluate(cfg.copy(maxSignals = 0)).signals.isEmpty())
    }

    @Test
    fun `hit_rate - 軸の馬の最終確率の降順（買い目の賭式に関わらない）`() {
        val r = evaluate(cfg.copy(priorityMode = PriorityMode.HIT_RATE, maxSignals = 6))
        val s = bets(r)
        assertEquals(s.sortedByDescending { it.pFinal.value }, s)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), s.map { it.umaban })
    }

    private fun sig(
        umaban: Int,
        type: BetType,
        pFinal: Double,
        ev: Double,
    ) = BetSignal(
        umaban = umaban,
        betType = type,
        odds = 10.0,
        popularity = umaban,
        prob = ProbRaw(0.1),
        ev = Ev(ev),
        betAmount = 1.0,
        probSource = "x",
        pModel = ProbRaw(pFinal),
        pMarket = 0.1,
        pFinal = ProbRaw(pFinal),
        pMkt = 0.1,
        ratio = Ratio(1.0),
        expectedReturn = 1.0,
        coverage = FeatureCoverage(1.0, 0.35, hasHorseMaster = false, hasPastRuns = false, pastN = 0),
        calcExpectedReturn = 1.0,
        calcEv = Ev(0.0),
        calcPMkt = 0.1,
        calcRatio = Ratio(1.0),
        reason = "",
        betStrategy = null,
    )

    private fun skip(
        umaban: Int,
        type: BetType,
        prob: Double,
        ev: Double,
    ) = SkipSignal(
        raceId = null,
        umaban = umaban,
        betType = type,
        ev = Ev(ev),
        odds = 10.0,
        prob = ProbRaw(prob),
        probSource = "x",
        betStrategy = BetStrategyBundle(emptyList(), StrategyEntryForTest, 0.0, 0.0, StrategyMeta(true, "best_only", "x", 0.25, 0.2, 1.0)),
        skipReason = "r",
        payoutRate = null,
        showAdjustedMkt = false,
    )

    private fun order(
        signals: List<DecisionSignal>,
        mode: PriorityMode = PriorityMode.HIT_RATE,
        priority: List<String>? = null,
    ) = SignalOrdering.sorted(signals, mode, priority).map { it.umaban }

    @Test
    fun `hit_rate の同点 - 確率が同じなら EV、EV も同じなら賭式優先（ワイド → 三連複 → その他）`() {
        val a = sig(1, BetType.SANRENPUKU, 0.3, 0.5)
        val b = sig(2, BetType.WIDE, 0.3, 0.5)
        val c = sig(3, BetType.WIN, 0.3, 0.5)
        val d = sig(4, BetType.WIDE, 0.3, 0.6) // EV が高い → 賭式に関わらず先
        val e = sig(5, BetType.SANRENPUKU, 0.4, 0.1) // 確率が高い → 最優先
        assertEquals(listOf(5, 4, 2, 1, 3), order(listOf(a, b, c, d, e)))
        // 既定の優先は [ワイド, 三連複]。単勝はリストに無いので 999
        assertEquals(listOf(5, 4, 2, 1, 3), order(listOf(c, a, b, d, e), priority = emptyList()))
    }

    @Test
    fun `hit_rate の賭式優先を指定すると入れ替わる - リストに無い賭式は最後`() {
        val a = sig(1, BetType.SANRENPUKU, 0.3, 0.5)
        val b = sig(2, BetType.WIDE, 0.3, 0.5)
        val c = sig(3, BetType.WIN, 0.3, 0.5)
        assertEquals(listOf(3, 1, 2), order(listOf(a, b, c), priority = listOf("単勝", "三連複", "ワイド")))
        assertEquals(listOf(1, 2, 3), order(listOf(a, b, c), priority = listOf("三連複")), "三連複 → (ワイド と 単勝は 999 で同点なので入力順)")
    }

    @Test
    fun `hit_rate の同点が完全に同じ（確率・EV・賭式）なら入力順（安定ソート）`() {
        val s = listOf(sig(1, BetType.WIDE, 0.3, 0.5), sig(2, BetType.WIDE, 0.3, 0.5), sig(3, BetType.WIDE, 0.3, 0.5))
        assertEquals(listOf(1, 2, 3), order(s))
        assertEquals(listOf(1, 2, 3), order(s, PriorityMode.EDGE))
    }

    @Test
    fun `edge は EV だけで並べ、賭式優先を見ない - EV が同じなら入力順`() {
        val s = listOf(sig(1, BetType.SANRENPUKU, 0.1, 0.5), sig(2, BetType.WIDE, 0.9, 0.5), sig(3, BetType.WIN, 0.5, 0.7))
        assertEquals(listOf(3, 1, 2), order(s, PriorityMode.EDGE))
    }

    @Test
    fun `見送りシグナルの hit_rate のキーは prob（シグナルは p_final）`() {
        val s = listOf(sig(1, BetType.WIDE, 0.2, 0.5), skip(2, BetType.WIDE, 0.3, 0.5))
        assertEquals(listOf(2, 1), order(s))
        assertEquals(listOf(1, 2), order(s, PriorityMode.EDGE), "EV 同値は入力順")
    }

    // ---- 入力の境界・性能 ------------------------------------------------------------------------------------------

    @Test
    fun `馬番が重複していて実オッズで買い目を作るときは拒否する`() {
        val h = listOf(DecisionHorse(1, 3.0, 1), DecisionHorse(2, 4.5, 2), DecisionHorse(2, 7.0, 3), DecisionHorse(4, 12.0, 4))
        val real = ComboOdds(wide = mapOf(WideKey.of(1, 2) to WideOddsRange(20.0, 30.0)))
        assertFailsWith<IllegalArgumentException> { WinDecisionEvaluator(cfg).evaluate(h, real) }
        // 実オッズが無く見送りだけなら、旧実装と同じ（T3.1 の挙動）で拒否しない
        assertNotNull(WinDecisionEvaluator(cfg).evaluate(h))
    }

    @Test
    fun `単勝だけを対象にした判定は、ワイド・三連複の設定に影響されない`() {
        val a = evaluate(cfg.copy(betTypes = setOf(BetType.WIN)))
        val b =
            evaluate(cfg.copy(betTypes = setOf(BetType.WIN), combo = ComboSettings.legacyFunctionDefaults().copy(topKPartners = 1, plackettLuceAlpha = 3.0)))
        assertEquals(a, b)
    }

    @Test
    fun `18 頭の全組合せの実オッズで判定が短時間で終わる`() {
        val real = realOdds(odds18)
        // 全ワイド（18C2 = 153 通り）・全三連複（18C3 = 816 通り）の実オッズを注入
        assertTrue(real.hasWide && real.hasSanrenpuku)
        val c = cfg.copy(maxSignals = 18, combo = ComboSettings.legacyFunctionDefaults().copy(boxSize = 5, maxTicketsPerType = 6))
        lateinit var result: WinDecisionResult
        val nanos = measureNanoTime { result = evaluate(c, odds18, real) }
        println("evaluate 18 頭（全ワイド 153・全三連複 816 の実オッズ、軸 18 頭分）: ${nanos / 1_000_000.0} ms")
        assertTrue(result.signals.isNotEmpty())
        assertTrue(nanos < 5_000_000_000L, "5 秒以内（実測 ${nanos / 1_000_000} ms）")
        assertFalse(result.signals.any { it is BetSignal && it.betAmount.isNaN() })
        assertNull(result.snapshotFilterErrors.firstOrNull())

        // 最大の組合せ数: 流しの相手 17 頭・BOX 18 頭（全軸で 流し C(17,2)=136 + BOX 816 の三連複と、ワイド 17 + 153）
        val heavy = cfg.copy(maxSignals = 18, combo = ComboSettings.legacyFunctionDefaults().copy(topKPartners = 17, boxSize = 18, maxTicketsPerType = 6))
        val heavyNanos = measureNanoTime { result = evaluate(heavy, odds18, real) }
        println("evaluate 18 頭（最大: top_k_partners=17, box_size=18）: ${heavyNanos / 1_000_000.0} ms")
        assertTrue(heavyNanos < 10_000_000_000L, "10 秒以内（実測 ${heavyNanos / 1_000_000} ms）")
    }
}

/** 見送りシグナルのテスト用の最小の戦略エントリ（並びの検査では中身を見ない）。 */
private val StrategyEntryForTest =
    StrategyEntry(
        type = BetType.WIDE,
        umaban = 1,
        odds = 0.0,
        probRaw = ProbRaw(0.0),
        probUsed = ProbUsed(0.0),
        ev = Ev(0.0),
        suggestedBet = 0.0,
        expectedProfit = 0.0,
        kellyFraction = 0.0,
        kellyRaw = 0.0,
        kellyAdjusted = 0.0,
        kellyBetBeforeClip = 0.0,
        clipped = false,
        skipReason = null,
        strategy = "",
    )
