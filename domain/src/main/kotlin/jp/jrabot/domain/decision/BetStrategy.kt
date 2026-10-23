package jp.jrabot.domain.decision

/**
 * 1 つの賭式の戦略（旧実装の `strategies[<賭式>]` の dict）。金額は旧実装の「点」。
 *
 * [probRaw]（表示用の生確率）と [probUsed]（EV・Kelly に使った確率）、[ev]（[probUsed] 基準）を別々に持つ（E4）。
 * T3.1 では単勝のほかに、ワイド/三連複の**見送りエントリ**（実オッズ未取得で `require_real_odds_for_combo=true` のとき旧実装が必ず作る。[isComboSkip]）
 * だけを持つ。ワイド/三連複の本格的な買い目は T3.2。
 */
data class StrategyEntry(
    val type: BetType,
    val umaban: Int,
    val odds: Double,
    val probRaw: ProbRaw,
    val probUsed: ProbUsed,
    val ev: Ev,
    val suggestedBet: Double,
    val expectedProfit: Double,
    val kellyFraction: Double,
    val kellyRaw: Double,
    val kellyAdjusted: Double,
    val kellyBetBeforeClip: Double,
    val clipped: Boolean,
    val skipReason: String?,
    val strategy: String,
    /** 見送りエントリの注記（単勝は null）。 */
    val note: String? = null,
) {
    val isComboSkip: Boolean get() = type != BetType.WIN

    internal fun scaled(scale: Double): StrategyEntry = copy(suggestedBet = suggestedBet * scale, expectedProfit = expectedProfit * scale)
}

/** 戦略の付帯情報（旧 `bet_strategy["meta"]`）。レース上限の縮小を適用したときだけ `raceBetCap*` が入る。 */
data class StrategyMeta(
    val requireRealOddsForCombo: Boolean,
    val ticketAllocation: String,
    val probSource: String,
    val kellyFractionInput: Double,
    val kellyFractionEffective: Double,
    val evShrinkFactor: Double,
    /** `bet_type_mode="best_only"` で最推奨 1 賭式に絞ったときだけ "best_only"。 */
    val betTypeMode: String? = null,
    val raceBetCapApplied: Boolean = false,
    val raceBetCap: Double? = null,
    val raceBetScale: Double? = null,
)

/**
 * 1 頭分の戦略一式（旧 `bet_strategy`）。[strategies] は旧実装の dict の挿入順（単勝 → ワイド → 三連複）。
 * [best] は [strategies] の要素そのもの（旧実装では同一オブジェクト）。
 */
data class BetStrategyBundle(
    val strategies: List<StrategyEntry>,
    val best: StrategyEntry,
    val totalSuggestedBet: Double,
    val totalExpectedProfit: Double,
    val meta: StrategyMeta,
) {
    /** `bet_type_mode="best_only"`: 最推奨 1 賭式だけにして合計を作り直す（旧 `_prune_bet_strategy_to_best`）。 */
    internal fun prunedToBest(): BetStrategyBundle =
        copy(
            strategies = listOf(best),
            totalSuggestedBet = best.suggestedBet,
            totalExpectedProfit = best.expectedProfit,
            meta = meta.copy(betTypeMode = "best_only"),
        )

    /**
     * レース単位の総投資上限による縮小（旧 `max_total_bet_per_race`）。**旧欠陥 E6 を含めてそのまま再現する**:
     * 比例縮小で `min_bet` を再判定しない。さらに旧実装では `best_strategy` と `strategies[<最推奨の賭式>]` が**同一オブジェクト**で、
     * 両方を順に `scale` 倍するため、最推奨の賭け額・期待利益は **scale² 倍**になる（シグナルの `bet_amount` は scale 倍）。
     * 合計（`totalSuggestedBet` / `totalExpectedProfit`）は縮小しない。
     */
    internal fun withRaceCap(
        cap: Double,
        scale: Double,
    ): BetStrategyBundle {
        val bestType = best.type
        val scaledStrategies = strategies.map { if (it.type == bestType) it.scaled(scale).scaled(scale) else it.scaled(scale) }
        return copy(
            strategies = scaledStrategies,
            best = scaledStrategies.first { it.type == bestType },
            meta = meta.copy(raceBetCapApplied = true, raceBetCap = cap, raceBetScale = scale),
        )
    }
}
