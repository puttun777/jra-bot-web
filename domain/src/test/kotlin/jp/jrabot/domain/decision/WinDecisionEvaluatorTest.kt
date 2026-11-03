package jp.jrabot.domain.decision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 単勝の判定。期待値の数値は旧 Python（固定コミット f7dbf69）の `evaluate_odds` を実際に実行した値（ゴールデン GD09-EVAL-001 ほか）。
 * ゴールデン全体との比較は :golden-runner と `golden-runner check`（CI。旧 `tools.golden.kotlin_compare`）が行う。ここは分岐と境界の単体テスト。
 */
class WinDecisionEvaluatorTest {
    private val loose = WinDecisionConfig.legacyFunctionDefaults().copy(evThreshold = -0.5, evShrinkFactor = 1.0)

    private fun race(vararg odds: Double): List<DecisionHorse> = odds.mapIndexed { i, o -> DecisionHorse(i + 1, o, i + 1) }

    private val race6 = race(3.0, 4.5, 7.0, 12.0, 25.0, 40.0)

    private fun bets(r: WinDecisionResult) = r.signals.filterIsInstance<BetSignal>()

    private fun evaluate(
        config: WinDecisionConfig,
        horses: List<DecisionHorse>,
        snapshots: RecentSnapshotsProvider? = null,
    ) = WinDecisionEvaluator(config, snapshots).evaluate(horses)

    // ---- 正常系と旧 Python との数値一致 -------------------------------------------------------------------------

    @Test
    fun `6 頭のレース - 旧 Python と同じ信号・賭け額・理由文字列・集計`() {
        val r = evaluate(loose, race6)
        val s = bets(r)
        assertEquals(listOf(1, 2), s.map { it.umaban })
        val a = s[0]
        assertEquals(0.18165696849645063, a.ev.value)
        assertEquals(1.8165696849645065, a.betAmount)
        assertEquals(0.39388565616548354, a.prob.value)
        assertEquals(0.39388565616548354, a.pFinal.value)
        assertEquals(0.3333333333333333, a.pMarket)
        assertEquals(1.1816569684964506, a.ratio.value)
        assertEquals(1.1816569684964506, a.expectedReturn)
        assertEquals(0.18165696849645063, a.calcEv.value)
        assertEquals(1.1816569684964506, a.calcRatio.value)
        assertEquals("最推奨=単勝（軸: 馬1） EV=18.2% / 参考: 単勝EV=18.2%（odds=3.0, p=39.4%）", a.reason)
        assertEquals("market:fallback(ml_disabled)", a.probSource)
        assertEquals(FeatureCoverage(1.0, 0.35, hasHorseMaster = false, hasPastRuns = false, pastN = 0), a.coverage)
        assertEquals("win", a.betType.marketCode)
        assertEquals(1.033655773809873, s[1].betAmount)
        assertEquals(LegacyRejectCounts(kellyBelowMinbet = 4), r.legacyRejectCounts)
        assertEquals(4, r.rejections.count { it.reason == DecisionReason.NO_STAKE })
    }

    @Test
    fun `戦略 - 単勝の項目は旧 Python と同じで、ワイドと三連複は実オッズ未取得の見送りエントリ`() {
        val bundle = bets(evaluate(loose, race6))[0].betStrategy!!
        assertEquals(listOf(BetType.WIN, BetType.WIDE, BetType.SANRENPUKU), bundle.strategies.map { it.type })
        val win = bundle.strategies[0]
        assertEquals(win, bundle.best)
        assertEquals("投資戦略: None (Kelly分数=0.25)", win.strategy) // 旧実装の引数がローカル変数に隠れて None になる癖
        assertEquals(0.3299925420332046, win.expectedProfit)
        assertEquals(0.018165696849645065, win.kellyFraction)
        assertEquals(0.09082848424822532, win.kellyRaw)
        assertEquals(win.probRaw.value, win.probUsed.value, "ev_shrink_factor=1.0 なら縮小前後は同じ")
        val wide = bundle.strategies[1]
        assertTrue(wide.isComboSkip)
        assertEquals("実オッズ未取得のため見送り（推定オッズは使用しない設定）", wide.skipReason)
        assertEquals("ワイド: 見送り", wide.strategy)
        assertEquals(0.0, wide.suggestedBet)
        assertEquals(1.8165696849645065, bundle.totalSuggestedBet)
        assertEquals(0.3299925420332046, bundle.totalExpectedProfit)
        assertEquals(0.2, bundle.meta.kellyFractionEffective) // 常に 0.8 倍（H10）
        assertEquals(0.25, bundle.meta.kellyFractionInput)
    }

    @Test
    fun `縮小係数 0_7 - EV と賭け額は縮小後の確率で、シグナルの確率と比は縮小前（E4）`() {
        val r = evaluate(loose.copy(evShrinkFactor = 0.7, evThreshold = -1.0, bankroll = 1000.0, maxBet = 100.0), race(6.0, 6.0, 6.0))
        val a = bets(r)[0]
        val win = a.betStrategy!!.best
        assertNotEquals(win.probRaw.value, win.probUsed.value)
        assertEquals(win.probRaw.value * 0.7, win.probUsed.value, 1e-15)
        assertEquals(a.prob.value, win.probRaw.value) // 表示用の確率は縮小前
        assertEquals(EvMath.calcEv(win.probUsed.value, 6.0), a.ev.value) // 戦略の EV は縮小後基準
        assertNotEquals(a.ev.value, a.calcEv.value) // 検算の EV（生の確率基準）とずれる → 旧実装の EV 不一致警告の原因
        assertEquals(a.ratio.value, a.pFinal.value / a.pMarket, 1e-15) // ratio は縮小前の確率基準
    }

    // ---- EV 閾値 ----------------------------------------------------------------------------------------------

    @Test
    fun `EV 閾値ちょうどは除外し、わずかに下回る閾値なら採用する`() {
        val ev = bets(evaluate(loose, race6))[0].ev.value
        // 閾値 = ちょうど馬1 の EV: 馬1 は除外される（EV <= 閾値）
        val at = evaluate(loose.copy(evThreshold = ev, maxSignals = 6), race6)
        assertFalse(bets(at).any { it.umaban == 1 })
        assertTrue(at.rejections.any { it.umaban == 1 && it.reason == DecisionReason.EV_NOT_ABOVE_THRESHOLD })
        // 1 ULP 下げると採用
        val below = evaluate(loose.copy(evThreshold = Math.nextDown(ev), maxSignals = 6), race6)
        assertTrue(bets(below).any { it.umaban == 1 })
    }

    @Test
    fun `EV 閾値が高いと全馬が EV 不足で除外され、旧集計の ev_below に入る`() {
        val r = evaluate(loose.copy(evThreshold = 0.5), race6)
        assertTrue(r.signals.isEmpty())
        assertEquals(LegacyRejectCounts(evBelow = 6), r.legacyRejectCounts)
    }

    @Test
    fun `hit_rate - 最低 EV が指定されればそれ、無ければ ev_threshold を使う`() {
        val hr = loose.copy(priorityMode = PriorityMode.HIT_RATE, maxSignals = 6)
        val ev1 = bets(evaluate(hr, race6))[0].ev.value
        val noMin = evaluate(hr.copy(evThreshold = ev1, hitRateMinEv = null), race6)
        assertFalse(bets(noMin).any { it.umaban == 1 }) // ev_threshold = EV ちょうど → 除外
        val withMin = evaluate(hr.copy(evThreshold = ev1, hitRateMinEv = -0.9), race6)
        assertTrue(bets(withMin).any { it.umaban == 1 }) // hit_rate_min_ev が優先される
        val tight = evaluate(hr.copy(evThreshold = -0.9, hitRateMinEv = ev1), race6)
        assertFalse(bets(tight).any { it.umaban == 1 }) // hit_rate_min_ev ちょうども除外
        // edge モードでは hit_rate_min_ev は無視される
        val edge = evaluate(hr.copy(priorityMode = PriorityMode.EDGE, evThreshold = -0.9, hitRateMinEv = 5.0), race6)
        assertTrue(bets(edge).any { it.umaban == 1 })
    }

    // ---- hit_rate の単勝フィルタ ---------------------------------------------------------------------------------

    @Test
    fun `win_odds_max - 上限ちょうどは除外せず、超えたら除外（odds より大きいとき）`() {
        val hr = loose.copy(priorityMode = PriorityMode.HIT_RATE, maxSignals = 6, notifySkips = true)
        val at = evaluate(hr.copy(winOddsMax = 12.0), race6)
        assertEquals(2, at.rejections.count { it.reason == DecisionReason.WIN_ODDS_ABOVE_MAX }) // 25.0, 40.0
        val below = evaluate(hr.copy(winOddsMax = 11.999), race6)
        assertEquals(3, below.rejections.count { it.reason == DecisionReason.WIN_ODDS_ABOVE_MAX }) // 12.0 も除外
        assertEquals(3, below.legacyRejectCounts!!.oddsCap)
    }

    @Test
    fun `win_prob_min と win_ratio_min - 下限未満だけ除外し、フィルタの順序は odds 上限 → 確率 → ratio`() {
        val hr = loose.copy(priorityMode = PriorityMode.HIT_RATE, maxSignals = 6)
        val p1 = bets(evaluate(hr, race6))[0].pFinal.value
        val atProb = evaluate(hr.copy(winProbMin = p1), race6) // 馬1 は下限ちょうど: 除外されない
        assertFalse(atProb.rejections.any { it.umaban == 1 && it.reason == DecisionReason.WIN_PROB_BELOW_MIN })
        val aboveProb = evaluate(hr.copy(winProbMin = Math.nextUp(p1)), race6)
        assertTrue(aboveProb.rejections.any { it.umaban == 1 && it.reason == DecisionReason.WIN_PROB_BELOW_MIN })
        val ratio1 = bets(evaluate(hr, race6))[0].ratio.value
        assertFalse(evaluate(hr.copy(winRatioMin = ratio1), race6).rejections.any { it.umaban == 1 && it.reason == DecisionReason.WIN_RATIO_BELOW_MIN })
        assertTrue(
            evaluate(hr.copy(winRatioMin = Math.nextUp(ratio1)), race6).rejections.any {
                it.umaban == 1 &&
                    it.reason == DecisionReason.WIN_RATIO_BELOW_MIN
            },
        )
        // 全部に当たる馬は最初のフィルタの理由になる
        val all = evaluate(hr.copy(winOddsMax = 3.5, winProbMin = 0.9, winRatioMin = 9.0), race6)
        assertEquals(listOf(DecisionReason.WIN_PROB_BELOW_MIN), all.rejections.filter { it.umaban == 1 }.map { it.reason })
        assertEquals(listOf(DecisionReason.WIN_ODDS_ABOVE_MAX), all.rejections.filter { it.umaban == 2 }.map { it.reason })
    }

    @Test
    fun `edge モードでは hit_rate のフィルタは無効`() {
        val r = evaluate(loose.copy(winOddsMax = 1.0, winProbMin = 0.99, winRatioMin = 99.0), race6)
        assertEquals(2, bets(r).size)
        assertEquals(0, r.legacyRejectCounts!!.oddsCap)
    }

    // ---- 急落・人気急上昇フィルタ ---------------------------------------------------------------------------------

    @Test
    fun `急落と人気急上昇のフィルタで除外され、理由は区別され、旧集計は drop_filtered に合算される`() {
        val sn = { rows: List<OddsPopularitySnapshot> -> SnapshotsNewestFirst.ofNewestFirst(rows) }
        val provider =
            RecentSnapshotsProvider { u ->
                when (u) {
                    1 -> sn(listOf(OddsPopularitySnapshot(3.0, 1), OddsPopularitySnapshot(5.0, 1)))

                    // 5.0 → 3.0: 0.4 の急落
                    2 -> sn(listOf(OddsPopularitySnapshot(4.5, 2), OddsPopularitySnapshot(4.6, 5)))

                    // 人気 5 → 2: 差 3（境界で急上昇）
                    else -> SnapshotsNewestFirst.EMPTY
                }
            }
        val r = evaluate(loose.copy(maxSignals = 6), race6, provider)
        assertEquals(DecisionReason.ODDS_DROP, r.rejections.first { it.umaban == 1 }.reason)
        assertEquals(DecisionReason.POPULARITY_SURGE, r.rejections.first { it.umaban == 2 }.reason)
        assertEquals(2, r.legacyRejectCounts!!.dropFiltered)
        assertFalse(bets(r).any { it.umaban in listOf(1, 2) })
    }

    @Test
    fun `スナップショット取得の失敗は旧実装と同じくフィルタなしで続行し、失敗した馬番を記録する`() {
        val provider = RecentSnapshotsProvider { u -> if (u == 1) error("取得失敗") else SnapshotsNewestFirst.EMPTY }
        val r = evaluate(loose, race6, provider)
        assertTrue(bets(r).any { it.umaban == 1 }) // 除外されずに採用される
        assertEquals(listOf(1), r.snapshotFilterErrors)
    }

    // ---- 賭け額・見送り ---------------------------------------------------------------------------------------

    @Test
    fun `賭け額が 0 の候補は信号にならず、notify_skips のときだけ見送りシグナルを返す`() {
        val plain = evaluate(loose.copy(maxSignals = 6), race6)
        assertEquals(2, plain.signals.size)
        val skips = evaluate(loose.copy(maxSignals = 6, notifySkips = true, raceId = "synthetic-1"), race6)
        assertEquals(6, skips.signals.size)
        val skip = assertIs<SkipSignal>(skips.signals[2])
        assertEquals("synthetic-1", skip.raceId)
        assertEquals(BetType.WIN, skip.betType)
        assertEquals(0.8, skip.payoutRate) // win_payout_rate 0.80
        assertEquals(false, skip.showAdjustedMkt)
        assertTrue(skip.skipReason!!.startsWith("Kelly推奨額("), skip.skipReason)
        assertNull(skip.betAmount)
        assertEquals(LegacyRejectCounts(kellyBelowMinbet = 4), skips.legacyRejectCounts) // 見送りシグナルを返しても集計は同じ
    }

    @Test
    fun `単勝を含まない賭式の指定では、見送りエントリが最推奨になり EV 0 の見送りシグナルになる（E7）`() {
        val r = evaluate(loose.copy(betTypes = setOf(BetType.WIDE), notifySkips = true, maxSignals = 6), race6)
        val first = assertIs<SkipSignal>(r.signals[0])
        assertEquals(BetType.WIDE, first.betType)
        assertEquals(0.0, first.ev.value)
        assertEquals(0.775, first.payoutRate)
        assertEquals(0.0, first.odds)
        assertEquals("実オッズ未取得のため見送り（推定オッズは使用しない設定）", first.skipReason)
    }

    @Test
    fun `対象の賭式が空なら戦略が作れず、理由なしで除外（旧集計には入らない）`() {
        val r = evaluate(loose.copy(betTypes = emptySet()), race6)
        assertTrue(r.signals.isEmpty())
        assertEquals(LegacyRejectCounts(), r.legacyRejectCounts)
        assertEquals(6, r.rejections.count { it.reason == DecisionReason.NO_STRATEGY }) // R: 理由コードは付く（E3）
    }

    @Test
    fun `win_max_over_market - 上限を超える馬を除外し、旧集計に入らない経路にも理由コードを付ける（E3）`() {
        val r = evaluate(loose.copy(winMaxOverMarket = 1.18, maxSignals = 6), race6)
        val over = r.rejections.filter { it.reason == DecisionReason.WIN_OVER_MARKET }
        assertEquals(listOf(1, 2), over.map { it.umaban }) // ratio 1.18166, 1.18089
        assertEquals(LegacyRejectCounts(kellyBelowMinbet = 4), r.legacyRejectCounts) // 旧集計には入らない
        assertTrue(bets(r).isEmpty())
        // 0 以下または null は無効
        assertEquals(2, bets(evaluate(loose.copy(winMaxOverMarket = 0.0), race6)).size)
        assertEquals(2, bets(evaluate(loose.copy(winMaxOverMarket = null), race6)).size)
        assertEquals(2, bets(evaluate(loose.copy(winMaxOverMarket = -1.0), race6)).size)
        // 上限ちょうどは除外しない（ratio > 上限）
        val ratio1 = bets(evaluate(loose, race6))[0].calcRatio.value
        assertTrue(bets(evaluate(loose.copy(winMaxOverMarket = ratio1), race6)).any { it.umaban == 1 })
    }

    // ---- 並びと件数 -------------------------------------------------------------------------------------------

    private val tied = (1..18).map { DecisionHorse(it, 20.0, it) }
    private val rich = loose.copy(bankroll = 2000.0, maxBet = 50.0)

    @Test
    fun `全馬が同じオッズ - EV は同点で、入力順のまま max_signals 件`() {
        val r = evaluate(rich.copy(maxSignals = 5), tied)
        assertEquals(listOf(1, 2, 3, 4, 5), bets(r).map { it.umaban })
        assertEquals(1, bets(r).map { it.ev.value }.toSet().size)
        assertEquals(18, evaluate(rich.copy(maxSignals = 100), tied).signals.size)
        assertEquals(0, evaluate(rich.copy(maxSignals = 0), tied).signals.size)
    }

    @Test
    fun `edge は EV の降順、同点は入力順`() {
        val horses = race(8.0, 8.0, 8.0, 5.0, 5.0)
        val r = evaluate(rich.copy(maxSignals = 5), horses)
        assertEquals(listOf(4, 5, 1, 2, 3), bets(r).map { it.umaban })
    }

    @Test
    fun `hit_rate は 確率 → EV → 賭式優先 の降順で、同点は入力順`() {
        val horses = race(8.0, 8.0, 8.0, 5.0, 5.0)
        val r = evaluate(rich.copy(maxSignals = 5, priorityMode = PriorityMode.HIT_RATE), horses)
        assertEquals(listOf(4, 5, 1, 2, 3), bets(r).map { it.umaban })
    }

    @Test
    fun `max_signals は選別後の件数の上限で、負数は拒否する`() {
        assertEquals(1, evaluate(loose.copy(maxSignals = 1), race6).signals.size)
        assertFailsWith<IllegalArgumentException> { loose.copy(maxSignals = -1) }
    }

    // ---- レース単位の総投資上限（E6） -----------------------------------------------------------------------------

    @Test
    fun `総投資上限 - 合計が超えたときだけ全体を比例縮小し、min_bet を再判定しない（E6）`() {
        val big = loose.copy(kellyFraction = 1.0, maxBet = 10.0, maxSignals = 3)
        val uncapped = bets(evaluate(big, race6))
        val total = uncapped.sumOf { it.betAmount }
        val capped = bets(evaluate(big.copy(maxTotalBetPerRace = 3.0), race6))
        val scale = 3.0 / total
        capped.zip(uncapped).forEach { (c, u) -> assertEquals(u.betAmount * scale, c.betAmount, 1e-12) }
        assertTrue(capped.any { it.betAmount < big.minBet }, "縮小後に min_bet を下回る賭け額が出る（旧挙動）")
        // 上限以下なら縮小しない
        assertEquals(uncapped.map { it.betAmount }, bets(evaluate(big.copy(maxTotalBetPerRace = total), race6)).map { it.betAmount })
        assertEquals(uncapped.map { it.betAmount }, bets(evaluate(big.copy(maxTotalBetPerRace = 0.0), race6)).map { it.betAmount })
        assertEquals(uncapped.map { it.betAmount }, bets(evaluate(big.copy(maxTotalBetPerRace = -5.0), race6)).map { it.betAmount })
    }

    @Test
    fun `総投資上限 - 戦略の最推奨の賭け額は scale の 2 乗倍になる旧挙動（best_strategy と strategies が同一オブジェクト）`() {
        val big = loose.copy(kellyFraction = 1.0, maxBet = 10.0, maxSignals = 3)
        val uncapped = bets(evaluate(big, race6))
        val scale = 12.0 / uncapped.sumOf { it.betAmount }
        val c = bets(evaluate(big.copy(maxTotalBetPerRace = 12.0), race6))[0]
        val u = uncapped[0]
        val cb = c.betStrategy!!
        val ub = u.betStrategy!!
        assertEquals(u.betAmount * scale, c.betAmount, 1e-12) // シグナルは 1 回
        assertEquals(ub.best.suggestedBet * scale * scale, cb.best.suggestedBet, 1e-12) // 戦略は 2 回
        assertEquals(cb.best, cb.strategies.first { it.type == BetType.WIN })
        assertEquals(ub.totalSuggestedBet, cb.totalSuggestedBet) // 合計は縮小しない
        assertTrue(cb.meta.raceBetCapApplied)
        assertEquals(12.0, cb.meta.raceBetCap)
        assertEquals(scale, cb.meta.raceBetScale)
    }

    @Test
    fun `総投資上限 - 見送りシグナルは賭け額 0 が入る（上限が適用されたときだけ）`() {
        val cfg = loose.copy(kellyFraction = 1.0, maxBet = 10.0, maxSignals = 6, notifySkips = true)
        assertNull((evaluate(cfg, race6).signals.filterIsInstance<SkipSignal>()).first().betAmount)
        val capped = evaluate(cfg.copy(maxTotalBetPerRace = 2.0), race6).signals.filterIsInstance<SkipSignal>()
        assertTrue(capped.isNotEmpty())
        capped.forEach { assertEquals(0.0, it.betAmount) }
    }

    // ---- 賭式モード・出力の形 ---------------------------------------------------------------------------------

    @Test
    fun `best_only は最推奨 1 賭式に絞り、合計とメタを作り直す`() {
        val b = bets(evaluate(loose.copy(betTypeMode = BetTypeMode.BEST_ONLY), race6))[0].betStrategy!!
        assertEquals(listOf(BetType.WIN), b.strategies.map { it.type })
        assertEquals("best_only", b.meta.betTypeMode)
        assertEquals(b.best.suggestedBet, b.totalSuggestedBet)
        val multi = bets(evaluate(loose, race6))[0].betStrategy!!
        assertNull(multi.meta.betTypeMode)
    }

    @Test
    fun `include_bet_strategy が false なら信号に戦略を含めない`() {
        assertNull(bets(evaluate(loose.copy(includeBetStrategy = false), race6))[0].betStrategy)
    }

    // ---- 頭数・極端な入力 -------------------------------------------------------------------------------------

    @Test
    fun `入力が空なら空で、旧集計は null（旧実装は配列だけを返す）`() {
        val r = evaluate(loose, emptyList())
        assertTrue(r.signals.isEmpty())
        assertNull(r.legacyRejectCounts)
    }

    @Test
    fun `1 頭 - 確率は 1_0 に正規化され、ブレンドの後も 1_0`() {
        val r = evaluate(loose, race(3.0))
        val s = bets(r).single()
        assertEquals(1.0, s.prob.value)
        assertEquals(10.0, s.betAmount) // 縮小なしでも 0.99 に収められ、max_bet 10.0 で打ち切り
        val best = s.betStrategy!!.best
        assertEquals(true, best.clipped)
        assertEquals(0.99, best.probUsed.value)
        assertEquals("最推奨=単勝（軸: 馬1） EV=197.0% / 参考: 単勝EV=200.0%（odds=3.0, p=100.0%）", s.reason)
    }

    @Test
    fun `2 頭 と 18 頭 でも確率の合計は 1`() {
        for (n in listOf(2, 18)) {
            val horses = (1..n).map { DecisionHorse(it, 2.0 + it, it) }
            val sum =
                PythonCompat.sum(
                    evaluate(loose.copy(maxSignals = n, notifySkips = true), horses).signals.mapNotNull { (it as? BetSignal)?.pFinal?.value },
                )
            assertTrue(sum <= 1.0 + 1e-12, "n=$n sum=$sum")
        }
    }

    @Test
    fun `odds 1_0 の馬は Kelly が 0 除算で除外され、理由は ODDS_AT_PAR（E5。旧実装は理由なしで同じく除外）`() {
        val r = evaluate(loose.copy(maxSignals = 6), race(1.0, 4.5, 7.0, 12.0, 25.0, 40.0))
        assertEquals(listOf(DecisionReason.ODDS_AT_PAR), r.rejections.filter { it.umaban == 1 }.map { it.reason })
        assertFalse(bets(r).any { it.umaban == 1 })
        assertEquals(LegacyRejectCounts(kellyBelowMinbet = 5), r.legacyRejectCounts) // 旧実装の 5 と一致（odds=1.0 の馬は集計に入らない）
    }

    @Test
    fun `odds 1_01 は除外されず Kelly 0 で見送りになる`() {
        val r = evaluate(loose.copy(maxSignals = 6, notifySkips = true), race(1.01, 4.5))
        assertTrue(r.rejections.none { it.reason == DecisionReason.ODDS_AT_PAR })
    }

    @Test
    fun `オッズ 0 の馬 - 確率 0 で EV は -1、人気 0 でも判定する`() {
        val horses = listOf(DecisionHorse(1, 0.0, 0), DecisionHorse(2, 2.5, 0), DecisionHorse(3, 4.0, 0))
        val r = evaluate(loose.copy(maxSignals = 3, notifySkips = true), horses)
        assertTrue(r.rejections.any { it.umaban == 1 })
        assertFalse(bets(r).any { it.umaban == 1 })
    }

    // ---- 不正入力・未対応・未承認の数値変更 ----------------------------------------------------------------------

    @Test
    fun `非有限のオッズは拒否する（旧実装は NaN を黙って計算に流す）`() {
        assertFailsWith<IllegalArgumentException> { DecisionHorse(1, Double.NaN, 1) }
        assertFailsWith<IllegalArgumentException> { DecisionHorse(1, Double.POSITIVE_INFINITY, 1) }
        assertFailsWith<IllegalArgumentException> { DecisionHorse(1, 2.0, -1) }
    }

    @Test
    fun `非有限の設定値は拒否する`() {
        assertFailsWith<IllegalArgumentException> { loose.copy(evThreshold = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { loose.copy(bankroll = Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { loose.copy(winOddsMax = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { loose.copy(marketBlendAlpha = Double.NaN) }
    }

    @Test
    fun `推定オッズでワイド三連複を判定する経路（require_real_odds_for_combo=false） - 例外にならず、単勝の結果は変わらない`() {
        val requireReal = evaluate(loose, race6)
        val estimated = evaluate(loose.copy(requireRealOddsForCombo = false), race6)
        // 6 頭の市場確率では推定オッズのワイド/三連複は EV が正にならず、単勝が最推奨のまま
        assertEquals(bets(requireReal).map { it.umaban to it.betAmount }, bets(estimated).map { it.umaban to it.betAmount })
        assertEquals(listOf(BetType.WIN, BetType.WIDE, BetType.SANRENPUKU), bets(requireReal)[0].betStrategy!!.strategies.map { it.type })
        assertEquals(listOf(BetType.WIN), bets(estimated)[0].betStrategy!!.strategies.map { it.type }, "買い目が無ければ見送りエントリは作らない")
    }

    @Test
    fun `未承認の数値変更（fixes）は有効にできない`() {
        for (fixes in listOf(
            DecisionFixes(capRescaleRespectsMinBet = true),
            DecisionFixes(applyMaxFraction = true),
            DecisionFixes(snapshotTrendFilter = true),
            DecisionFixes(kellyMultiplierBySourceEnum = true),
        )) {
            assertFailsWith<UnsupportedDecisionScopeException> { loose.copy(fixes = fixes) }
        }
        assertEquals(DecisionFixes.NONE, loose.fixes)
        assertEquals(PipelineVersion.LEGACY, loose.pipelineVersion)
    }

    @Test
    fun `運用の既定値は持たない - 旧関数の既定値は legacyFunctionDefaults で明示する`() {
        val d = WinDecisionConfig.legacyFunctionDefaults()
        assertEquals(0.08, d.evThreshold)
        assertEquals(2, d.maxSignals)
        assertEquals(0.7, d.evShrinkFactor)
        assertEquals(DecisionFixes.NONE, d.fixes)
    }
}
