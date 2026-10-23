package jp.jrabot.domain.decision

/**
 * 1 頭分の戦略一式を作る（旧 `suggest_multiple_bet_strategies` の T3.1 の範囲）。
 *
 * - 単勝: 確率を縮小（[WinStake.probUsed]）→ EV → Kelly の賭け額 → 期待利益。
 * - ワイド/三連複: 実オッズを受け取らない（T3.1）ので、`require_real_odds_for_combo=true` では旧実装が必ず作る**見送りエントリ**だけ。
 *   `false`（推定オッズで買い目を作る）は T3.2 なので [UnsupportedDecisionScopeException]。
 * - 最推奨: 賭け額 > 0 のものの中で EV 最大、無ければ全体で EV 最大（**見送りエントリが最推奨になり得る**: 旧欠陥 E7。同値は先に挿入されたものが残る）。
 *
 * 旧実装の dict の挿入順は 単勝 → ワイド → 三連複。`max()` の同値の扱い（先勝ち）はこの順序に依存する。
 */
internal object BetStrategyBuilder {
    private const val COMBO_SKIP_REASON = "実オッズ未取得のため見送り（推定オッズは使用しない設定）"
    private const val COMBO_SKIP_NOTE = "※ 実オッズが取れた場合のみ判定/投資します"

    /** 戦略が 1 つも作れない（対象の賭式が無い）ときは null。単勝で `odds == 1.0`（確率 > 0）のときは [LegacyZeroDivisionException]。 */
    fun build(
        umaban: Int,
        probRaw: ProbRaw,
        winOdds: Double,
        config: WinDecisionConfig,
        stake: WinStake,
    ): BetStrategyBundle? {
        val comboNeeded = config.allows(BetType.WIDE) || config.allows(BetType.SANRENPUKU)
        if (comboNeeded && !config.requireRealOddsForCombo) {
            throw UnsupportedDecisionScopeException("ワイド/三連複を推定オッズで判定する経路（require_real_odds_for_combo=false）は T3.2 で実装する")
        }

        val entries = mutableListOf<StrategyEntry>()
        if (config.allows(BetType.WIN)) entries += winEntry(umaban, probRaw, winOdds, config, stake)
        if (config.allows(BetType.WIDE)) entries += comboSkipEntry(BetType.WIDE, umaban)
        if (config.allows(BetType.SANRENPUKU)) entries += comboSkipEntry(BetType.SANRENPUKU, umaban)
        if (entries.isEmpty()) return null

        val candidates = entries.filter { it.suggestedBet > 0 }.ifEmpty { entries }
        var best = candidates.first()
        for (e in candidates.drop(1)) if (e.ev.value > best.ev.value) best = e

        val bundle =
            BetStrategyBundle(
                strategies = entries,
                best = best,
                totalSuggestedBet = PythonCompat.sum(entries.map { it.suggestedBet }),
                totalExpectedProfit = PythonCompat.sum(entries.map { it.expectedProfit }),
                meta =
                    StrategyMeta(
                        requireRealOddsForCombo = config.requireRealOddsForCombo,
                        ticketAllocation = config.ticketAllocation.ifEmpty { "best_only" },
                        probSource = config.probSource.ifEmpty { "unknown" },
                        kellyFractionInput = config.kellyFraction,
                        kellyFractionEffective = stake.kellyFractionAdjusted(config.kellyFraction),
                        evShrinkFactor = stake.evShrinkFactorForMeta(),
                    ),
            )
        return if (config.betTypeMode == BetTypeMode.BEST_ONLY) bundle.prunedToBest() else bundle
    }

    private fun winEntry(
        umaban: Int,
        probRaw: ProbRaw,
        winOdds: Double,
        config: WinDecisionConfig,
        stake: WinStake,
    ): StrategyEntry {
        val probUsed = stake.probUsed(probRaw)
        val winEv = EvMath.calcEv(probUsed.value, winOdds)
        val info = stake.calcBetAmount(probUsed, winOdds)
        val winBet = info.suggestedBet
        val expectedProfit = if (winBet > 0) EvMath.calcExpectedProfit(winBet, probUsed.value, winOdds) else 0.0
        return StrategyEntry(
            type = BetType.WIN,
            umaban = umaban,
            odds = winOdds,
            probRaw = probRaw,
            probUsed = probUsed,
            ev = Ev(winEv),
            suggestedBet = winBet,
            expectedProfit = expectedProfit,
            kellyFraction = stake.displayKellyFraction(probUsed, winOdds),
            kellyRaw = info.kellyRaw,
            kellyAdjusted = info.kellyAdjusted,
            kellyBetBeforeClip = info.kellyBetBeforeClip,
            clipped = info.clipped,
            skipReason = info.skipReason,
            // 旧実装は賭けの方式の引数がローカル変数 `bet_strategy = None` に隠れるため、ラベルは常に "None"
            strategy = "投資戦略: None (Kelly分数=${PythonCompat.fixed(config.kellyFraction, 2)})",
        )
    }

    private fun comboSkipEntry(
        type: BetType,
        umaban: Int,
    ) = StrategyEntry(
        type = type,
        umaban = umaban,
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
        skipReason = COMBO_SKIP_REASON,
        strategy = "${type.label}: 見送り",
        note = COMBO_SKIP_NOTE,
    )
}
