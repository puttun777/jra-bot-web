package jp.jrabot.domain.decision

/**
 * 単勝の判定。参照元 `ev/decision.py` の `evaluate_odds` のうち、**ML 無効（市場フォールバック）・DB なし・単勝**の経路を同じ分岐順で再現する。
 *
 * 流れ（旧実装と同順）:
 * 1. 入力なしなら空（旧実装は入力が空のとき `return_debug` でも配列だけを返すので、[WinDecisionResult.legacyRejectCounts] は null）。
 * 2. 確率: [MarketProbability.estimateProbNormalized] → [MarketBlend.apply]（旧欠陥 H4 の二重ブレンドも `legacy` のまま）。
 * 3. 馬ごと: hit_rate の単勝フィルタ（odds 上限 → 確率下限 → ratio 下限）→ 急落/人気急上昇 → 戦略（[BetStrategyBuilder]）→
 *    EV 閾値（**`best.ev <= 閾値` は除外**。hit_rate で `hitRateMinEv` があればそれ）→ 賭け額 0 は見送り（`notifySkips` のときだけ [SkipSignal]）→
 *    `win_max_over_market`（単勝のみ。`ratio > 上限` で除外）→ [BetSignal]。
 * 4. 並び: edge は EV 降順、hit_rate は (確率, EV, 賭式優先の小さい番号) の降順。同点は入力順（安定ソート）。`maxSignals` 件に切り詰め。
 * 5. `maxTotalBetPerRace`: 選択分の賭け額合計が上限を超えるとき全体を比例縮小（E6: `min_bet` を再判定しない。[BetStrategyBundle.withRaceCap]）。
 *
 * 旧欠陥の扱い（`pipeline_version=legacy`、`fixes.*=false`）: E3/E5 は理由コードだけ追加（R）、E6/E12/E14/H10 は旧挙動のまま（C は未承認）。
 * 状態を持たない（[config] と [snapshots] 以外に依存しない）。時刻は読まない。
 *
 * 入力の非有限値・負の `maxSignals` は拒否する（旧実装は NaN を黙って流し、負のスライスで意図しない件数になる）。
 */
class WinDecisionEvaluator(
    private val config: WinDecisionConfig,
    private val snapshots: RecentSnapshotsProvider? = null,
) {
    private val stake =
        WinStake(config.evShrinkFactor, config.kellyFraction, config.bankroll, config.minBet, config.maxBet, config.probSource)

    fun evaluate(horses: List<DecisionHorse>): WinDecisionResult {
        if (horses.isEmpty()) return WinDecisionResult(emptyList(), null, emptyList(), config.probSource)

        val oddsValues = horses.map { it.odds }
        val normalized = MarketProbability.estimateProbNormalized(oddsValues)
        val probs = MarketBlend.apply(normalized, oddsValues, config.marketBlendAlpha, config.enableProbClip, config.probClipMax)

        val counts = Counter()
        val rejections = mutableListOf<Rejection>()
        val snapshotErrors = mutableListOf<Int>()
        val signals = mutableListOf<DecisionSignal>()

        fun reject(
            horse: DecisionHorse,
            reason: DecisionReason,
        ) {
            rejections += Rejection(horse.umaban, reason)
            counts.add(reason)
        }

        for ((horse, prob) in horses.zip(probs)) {
            val odds = horse.odds
            val pMarket = if (odds > 0) 1.0 / PythonCompat.max(odds, 0.1) else 0.0
            val pFinal = prob
            val ratio = if (pMarket > 0) pFinal / pMarket else 0.0
            val winEv = EvMath.calcEv(prob, odds)

            if (config.priorityMode == PriorityMode.HIT_RATE) {
                val oddsMax = config.winOddsMax
                if (oddsMax != null && odds > oddsMax) {
                    reject(horse, DecisionReason.WIN_ODDS_ABOVE_MAX)
                    continue
                }
                val probMin = config.winProbMin
                if (probMin != null && pFinal < probMin) {
                    reject(horse, DecisionReason.WIN_PROB_BELOW_MIN)
                    continue
                }
                val ratioMin = config.winRatioMin
                if (ratioMin != null && ratio < ratioMin) {
                    reject(horse, DecisionReason.WIN_RATIO_BELOW_MIN)
                    continue
                }
            }

            if (snapshots != null) {
                try {
                    val recent = snapshots.recentSnapshots(horse.umaban)
                    if (SnapshotFilters.checkOddsDrop(recent, odds, config.dropThreshold)) {
                        reject(horse, DecisionReason.ODDS_DROP)
                        continue
                    }
                    if (SnapshotFilters.checkPopularitySurge(recent, horse.popularity, config.surgeThreshold)) {
                        reject(horse, DecisionReason.POPULARITY_SURGE)
                        continue
                    }
                } catch (e: Exception) {
                    // 旧実装: 取得の失敗はフィルタを適用しないまま続行する（コメントは『スキップ』だが実際は pass）。失敗した馬番は記録する
                    snapshotErrors += horse.umaban
                }
            }

            val bundle =
                try {
                    BetStrategyBuilder.build(horse.umaban, ProbRaw(prob), odds, config, stake)
                } catch (e: LegacyZeroDivisionException) {
                    reject(horse, DecisionReason.ODDS_AT_PAR)
                    continue
                }
            if (bundle == null) {
                reject(horse, DecisionReason.NO_STRATEGY)
                continue
            }
            val best = bundle.best

            val evThreshold = if (config.priorityMode == PriorityMode.HIT_RATE) config.hitRateMinEv ?: config.evThreshold else config.evThreshold
            if (best.ev.value <= evThreshold) {
                reject(horse, DecisionReason.EV_NOT_ABOVE_THRESHOLD)
                continue
            }

            val suggestedBet = best.suggestedBet
            if (suggestedBet <= 0) {
                reject(horse, DecisionReason.NO_STAKE)
                if (config.notifySkips) {
                    signals += skipSignal(best, bundle, config.probSource)
                }
                continue
            }

            val maxOverMarket = config.winMaxOverMarket
            if (best.type == BetType.WIN && maxOverMarket != null && maxOverMarket > 0) {
                val odds0 = best.odds
                val p0 = best.probRaw.value
                if (odds0 > 0 && p0 > 0) {
                    val pMkt = 1.0 / odds0
                    val ratioCheck = if (pMkt > 0) p0 / pMkt else 0.0
                    if (ratioCheck > maxOverMarket) {
                        reject(horse, DecisionReason.WIN_OVER_MARKET)
                        continue
                    }
                }
            }

            signals += betSignal(horse, prob, pMarket, ratio, winEv, bundle)
        }

        val sorted = sortSignals(signals)
        val selected = applyRaceCap(sorted.take(config.maxSignals))
        return WinDecisionResult(selected, counts.toLegacy(), rejections, config.probSource, snapshotErrors)
    }

    private fun skipSignal(
        best: StrategyEntry,
        bundle: BetStrategyBundle,
        probSource: String,
    ) = SkipSignal(
        raceId = config.raceId,
        umaban = best.umaban,
        betType = best.type,
        ev = best.ev,
        odds = best.odds,
        prob = best.probRaw,
        probSource = probSource,
        betStrategy = bundle,
        skipReason = best.skipReason,
        payoutRate =
            when (best.type) {
                BetType.WIDE -> config.widePayoutRate
                BetType.SANRENPUKU -> config.sanrenpukuPayoutRate
                BetType.WIN -> config.winPayoutRate
            },
        showAdjustedMkt = config.showAdjustedMkt,
    )

    private fun betSignal(
        horse: DecisionHorse,
        prob: Double,
        pMarket: Double,
        ratio: Double,
        winEv: Double,
        bundle: BetStrategyBundle,
    ): BetSignal {
        val best = bundle.best
        val bestOdds = best.odds
        val bestProb = best.probRaw.value
        val bestEv = best.ev.value

        // 検算用: 生の確率基準の期待回収・EV・市場確率・ratio（戦略の EV は縮小後の確率基準なので一致しないことがある: E4）
        val expectedReturn = if (bestOdds > 0 && bestProb > 0) bestOdds * bestProb else 0.0
        val evCalc = if (expectedReturn > 0) expectedReturn - 1.0 else 0.0
        val pMktCalc = if (bestOdds > 0) 1.0 / bestOdds else 0.0
        val ratioCalc = if (pMktCalc > 0) bestProb / pMktCalc else 0.0

        val reason =
            "最推奨=${best.type.label}（軸: 馬${horse.umaban}） EV=${PythonCompat.percent(bestEv, 1)} / " +
                "参考: 単勝EV=${PythonCompat.percent(winEv, 1)}（odds=${PythonCompat.repr(horse.odds)}, p=${PythonCompat.percent(prob, 1)}）"

        return BetSignal(
            umaban = horse.umaban,
            betType = best.type,
            odds = bestOdds,
            popularity = horse.popularity,
            prob = ProbRaw(bestProb),
            ev = Ev(bestEv),
            betAmount = best.suggestedBet,
            probSource = config.probSource,
            pModel = ProbRaw(prob),
            pMarket = pMarket,
            pFinal = ProbRaw(prob),
            pMkt = pMktCalc,
            ratio = Ratio(ratio),
            expectedReturn = expectedReturn,
            coverage = FeatureCoverage(coverageScore = 1.0, betaEff = config.ensembleBeta, hasHorseMaster = false, hasPastRuns = false, pastN = 0),
            calcExpectedReturn = expectedReturn,
            calcEv = Ev(evCalc),
            calcPMkt = pMktCalc,
            calcRatio = Ratio(ratioCalc),
            reason = reason,
            betStrategy = if (config.includeBetStrategy) bundle else null,
        )
    }

    private fun sortSignals(signals: List<DecisionSignal>): List<DecisionSignal> {
        val comparator: Comparator<DecisionSignal> =
            if (config.priorityMode == PriorityMode.HIT_RATE) {
                val priority = config.hitRateBetTypePriority?.takeIf { it.isNotEmpty() } ?: listOf(BetType.WIDE.label, BetType.SANRENPUKU.label)
                Comparator { a, b ->
                    val byProb = descending(hitRateProb(a), hitRateProb(b))
                    if (byProb != 0) {
                        byProb
                    } else {
                        val byEv = descending(a.ev.value, b.ev.value)
                        if (byEv != 0) byEv else descending(-priorityIndex(priority, a).toDouble(), -priorityIndex(priority, b).toDouble())
                    }
                }
            } else {
                Comparator { a, b -> descending(a.ev.value, b.ev.value) }
            }
        return signals.sortedWith(comparator) // 安定ソート: 同点は入力順（Python の sort(reverse=True) と同じ）
    }

    /** hit_rate のキーの確率。シグナルは p_final、見送りシグナルは prob。 */
    private fun hitRateProb(s: DecisionSignal): Double =
        when (s) {
            is BetSignal -> s.pFinal.value
            is SkipSignal -> s.prob.value
        }

    private fun priorityIndex(
        priority: List<String>,
        s: DecisionSignal,
    ): Int {
        val i = priority.indexOf(s.betType.label)
        return if (i >= 0) i else 999
    }

    /** Python の比較（`<`/`>`）で降順。-0.0 と 0.0 は同値。 */
    private fun descending(
        a: Double,
        b: Double,
    ): Int =
        if (a > b) {
            -1
        } else if (a < b) {
            1
        } else {
            0
        }

    private fun applyRaceCap(selected: List<DecisionSignal>): List<DecisionSignal> {
        val cap = config.maxTotalBetPerRace
        if (cap == null || !(cap > 0)) return selected
        var total = 0.0
        for (s in selected) {
            total +=
                when (s) {
                    is BetSignal -> s.betAmount
                    is SkipSignal -> s.betAmount ?: 0.0
                }
        }
        if (!(total > cap && total > 0)) return selected

        val scale = cap / total
        return selected.map { s ->
            when (s) {
                is BetSignal -> s.copy(betAmount = s.betAmount * scale, betStrategy = s.betStrategy?.withRaceCap(cap, scale))
                is SkipSignal -> s.copy(betAmount = (s.betAmount ?: 0.0) * scale, betStrategy = s.betStrategy.withRaceCap(cap, scale))
            }
        }
    }

    private class Counter {
        private val counts = HashMap<String, Int>()

        fun add(reason: DecisionReason) {
            reason.legacyCounter?.let { counts.merge(it, 1, Int::plus) }
        }

        fun toLegacy() =
            LegacyRejectCounts(
                evBelow = counts["ev_below"] ?: 0,
                oddsCap = counts["odds_cap"] ?: 0,
                probBelow = counts["prob_below"] ?: 0,
                ratioBelow = counts["ratio_below"] ?: 0,
                dropFiltered = counts["drop_filtered"] ?: 0,
                kellyBelowMinbet = counts["kelly_below_minbet"] ?: 0,
            )
    }
}
