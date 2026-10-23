package jp.jrabot.domain.decision

/**
 * 1 つの賭式の戦略（旧実装の `strategies[<賭式>]` の dict）。金額は旧実装の「点」。
 *
 * [probRaw]（表示用の生確率）と [probUsed]（EV・Kelly に使った確率）、[ev]（[probUsed] 基準）を別々に持つ（E4）。
 * ワイド/三連複は、買い目 [tickets]（EV 降順・点数上限適用後）の**先頭（最良）の買い目**の値が代表値（[odds]・[probRaw]・[probUsed]・[ev]・[kumi]）で、
 * 賭け額 [suggestedBet] はその最良の買い目の Kelly（`min_bet`/`max_bet` 適用）。各買い目への配分は [ComboTicket.stake]（`ticket_allocation`）。
 * 実オッズが取れず `require_real_odds_for_combo=true` のときは**見送りエントリ**（[isComboSkip]。旧実装がワイド・三連複とも必ず作る）。
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
    /** ワイド/三連複の注記（単勝は null）。 */
    val note: String? = null,
    /** ワイド/三連複の買い目（単勝と見送りエントリは空）。 */
    val tickets: List<ComboTicket> = emptyList(),
    /** 最良の買い目の組合せ（旧 `kumi` / `combo_key`。単勝と見送りエントリは null）。 */
    val kumi: String? = null,
) {
    /** ワイド/三連複で買い目が作れなかった（実オッズ未取得）見送りエントリ。 */
    val isComboSkip: Boolean get() = type != BetType.WIN && tickets.isEmpty()

    /** 賭け額・期待利益と（[scaleTickets] なら）各買い目の賭け額を [scale] 倍する。 */
    internal fun scaled(
        scale: Double,
        scaleTickets: Boolean = true,
    ): StrategyEntry =
        copy(
            suggestedBet = suggestedBet * scale,
            expectedProfit = expectedProfit * scale,
            tickets = if (scaleTickets) tickets.map { it.scaledStake(scale) } else tickets,
        )
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
     * 買い目ごとの賭け額（[ComboTicket.stake]）は `strategies` の側でだけ 1 回 `scale` 倍する（旧実装は `best_strategy` の側では買い目に触れない）。
     * したがって最推奨がワイド/三連複のとき、買い目の賭け額の合計（= 縮小前の最推奨賭け額 × scale）と最推奨の賭け額（× scale²）は**一致しない**（旧実装のまま）。
     * 合計（`totalSuggestedBet` / `totalExpectedProfit`）は縮小しない。
     */
    internal fun withRaceCap(
        cap: Double,
        scale: Double,
    ): BetStrategyBundle {
        val bestType = best.type
        val scaledStrategies = strategies.map { if (it.type == bestType) it.scaled(scale).scaled(scale, scaleTickets = false) else it.scaled(scale) }
        return copy(
            strategies = scaledStrategies,
            best = scaledStrategies.first { it.type == bestType },
            meta = meta.copy(raceBetCapApplied = true, raceBetCap = cap, raceBetScale = scale),
        )
    }
}
