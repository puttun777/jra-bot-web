package jp.jrabot.domain.decision

/**
 * ワイド・三連複の買い目生成の設定（旧 `suggest_multiple_bet_strategies` / `evaluate_odds` の引数）。
 *
 * **運用の既定値は持たない**（旧関数の既定値と `config.example.yaml` が食い違う: D-016。[legacyFunctionDefaults] は旧関数のシグネチャの既定値で、比較用）。
 * 払戻率（`wide_payout_rate` / `sanrenpuku_payout_rate`）・確率の縮小（`ev_shrink_factor`）・Kelly・賭け額・`ticket_allocation` は [WinDecisionConfig] 側にある。
 *
 * - 旧実装の比較: `*_max_prob > 0` のときだけ上限を適用、`*_min_prob > 0` のときだけ下限で足切り、`*_max_over_market <= 0` は無効（0 が『無効』を表す）。
 * - 点数: ワイドの相手は `max(1, topKPartners)` 頭、三連複の相手は `max(3, topKPartners)` 頭、BOX 候補は人気上位 `max(3, boxSize)` 頭、
 *   賭式ごとの点数上限は `max(1, maxTicketsPerType)`（旧実装の `max` をそのまま使うので 0 や負でも拒否しない）。
 * - [plackettLuceAlpha]: [PlackettLuce.effectiveAlpha]（0.0 は 1.0、`[0.1, 5]` に収める）。非有限値は拒否する。
 */
data class ComboSettings(
    val topKPartners: Int,
    val maxTicketsPerType: Int,
    val boxSize: Int,
    val wideMinProb: Double,
    val wideMaxProb: Double,
    val sanrenpukuMinProb: Double,
    val sanrenpukuMaxProb: Double,
    val plackettLuceAlpha: Double,
    val wideMaxOverMarket: Double,
    val sanrenpukuMaxOverMarket: Double,
) {
    init {
        listOf(
            "wide_min_prob" to wideMinProb,
            "wide_max_prob" to wideMaxProb,
            "sanrenpuku_min_prob" to sanrenpukuMinProb,
            "sanrenpuku_max_prob" to sanrenpukuMaxProb,
            "plackett_luce_alpha" to plackettLuceAlpha,
            "wide_max_over_market" to wideMaxOverMarket,
            "sanrenpuku_max_over_market" to sanrenpukuMaxOverMarket,
        ).forEach { (name, v) -> require(v.isFinite()) { "$name は有限な数: $v" } }
    }

    companion object {
        /** 旧 `evaluate_odds` のシグネチャの既定値。**運用の既定ではない**（ゴールデン比較用）。 */
        fun legacyFunctionDefaults() =
            ComboSettings(
                topKPartners = 6,
                maxTicketsPerType = 6,
                boxSize = 4,
                wideMinProb = 0.0,
                wideMaxProb = 0.35,
                sanrenpukuMinProb = 0.0,
                sanrenpukuMaxProb = 0.20,
                plackettLuceAlpha = 1.0,
                wideMaxOverMarket = 0.0,
                sanrenpukuMaxOverMarket = 0.0,
            )
    }
}
