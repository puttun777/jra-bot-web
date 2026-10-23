package jp.jrabot.domain.decision

/** 通知の優先方針（旧 `priority_mode`）。旧実装は "hit_rate" 以外の文字列をすべて edge として扱う（境界の変換で合わせる）。 */
enum class PriorityMode { EDGE, HIT_RATE }

/** 旧 `bet_type_mode`。"best_only" なら最推奨 1 賭式だけにする。それ以外は MULTI。 */
enum class BetTypeMode { MULTI, BEST_ONLY }

/**
 * 判定（[WinDecisionEvaluator]）の設定。旧 `evaluate_odds` の引数のうち、ML・DB を使わない範囲（単勝・ワイド・三連複の判定と、それに付随する見送り）のもの。
 *
 * **運用の既定値は持たない**（全項目が必須）。旧実装は関数の既定値と `config.example.yaml` が食い違う（D-016、MIGRATION_MATRIX §7.2）ので、
 * どちらを運用の既定にするかは T1.20（実 config の確認）・T3.8（bot-run の設定）で決める。[legacyFunctionDefaults] は**旧関数のシグネチャの既定値**で、
 * ゴールデン比較（旧実装を引数なしで呼んだ結果との比較）のためにあり、運用の既定ではない。
 *
 * 値の意味と単位:
 * - 金額（[bankroll] / [minBet] / [maxBet] / [maxTotalBetPerRace]）は旧実装の「点」（`Double`）。円の `Money` ではない。
 * - [marketBlendAlpha] / [probClipMax] は**解決済みの値**（旧実装は 引数 > `ml_cfg` > 既定 0.25/0.35 の順に解決する。解決は呼び出し側）。
 * - [probSource] は確率源のラベル。ML 無効の市場フォールバックでは `market:fallback(ml_disabled)`。Kelly 係数（H10）とメタ情報に使う。
 * - [ensembleBeta] は ML 特徴量カバレッジ表示（`beta_eff`）の既定にだけ使う。ML 経路は T3.6 で、ここでは判定に影響しない。
 *
 * ワイド・三連複の買い目は T3.2 で実装した（[combo]・実オッズは [WinDecisionEvaluator.evaluate] の引数 [ComboOdds]）。
 * 未対応（設定できない）: ML（`db` / `race_id` 付きの特徴量推定・ensemble）、固定額賭け（旧実装でも実質無効: [WinStake]）。
 */
data class WinDecisionConfig(
    val evThreshold: Double,
    val maxSignals: Int,
    val dropThreshold: Double,
    val surgeThreshold: Int,
    val bankroll: Double,
    val includeBetStrategy: Boolean,
    /** 判定対象の賭式。null は全賭式（旧 `bet_types=None`）。 */
    val betTypes: Set<BetType>?,
    val evShrinkFactor: Double,
    /** 単勝の p/市場確率の上限。null または 0 以下は無効。 */
    val winMaxOverMarket: Double?,
    val winPayoutRate: Double,
    val widePayoutRate: Double,
    val sanrenpukuPayoutRate: Double,
    val showAdjustedMkt: Boolean,
    val marketBlendAlpha: Double,
    val probClipMax: Double?,
    val enableProbClip: Boolean,
    val notifySkips: Boolean,
    val kellyFraction: Double,
    val minBet: Double,
    val maxBet: Double,
    val requireRealOddsForCombo: Boolean,
    val ticketAllocation: String,
    /** ワイド・三連複の買い目生成の設定（流し・BOX・確率の上下限・Plackett–Luce の α など）。 */
    val combo: ComboSettings,
    val betTypeMode: BetTypeMode,
    val maxTotalBetPerRace: Double?,
    val raceId: String?,
    val priorityMode: PriorityMode,
    val hitRateMinEv: Double?,
    /** hit_rate 時の同率の賭式優先（昇順）。null または空は ["ワイド", "三連複"]。 */
    val hitRateBetTypePriority: List<String>?,
    val winOddsMax: Double?,
    val winProbMin: Double?,
    val winRatioMin: Double?,
    val probSource: String,
    val ensembleBeta: Double,
    val pipelineVersion: PipelineVersion = PipelineVersion.LEGACY,
    val fixes: DecisionFixes = DecisionFixes.NONE,
) {
    init {
        require(maxSignals >= 0) { "max_signals は 0 以上（旧実装は負数で『末尾を除く』という意図しないスライスになる）: $maxSignals" }
        if (fixes.anyEnabled) throw UnsupportedDecisionScopeException("未承認の数値変更（fixes）は有効にできない: $fixes")
        requireFinite("ev_threshold", evThreshold)
        requireFinite("drop_threshold", dropThreshold)
        requireFinite("bankroll", bankroll)
        requireFinite("ev_shrink_factor", evShrinkFactor)
        requireFinite("win_payout_rate", winPayoutRate)
        requireFinite("wide_payout_rate", widePayoutRate)
        requireFinite("sanrenpuku_payout_rate", sanrenpukuPayoutRate)
        requireFinite("market_blend_alpha", marketBlendAlpha)
        requireFinite("kelly_fraction", kellyFraction)
        requireFinite("min_bet", minBet)
        requireFinite("max_bet", maxBet)
        requireFinite("ensemble_beta", ensembleBeta)
        listOf(
            "win_max_over_market" to winMaxOverMarket,
            "prob_clip_max" to probClipMax,
            "max_total_bet_per_race" to maxTotalBetPerRace,
            "hit_rate_min_ev" to hitRateMinEv,
            "win_odds_max" to winOddsMax,
            "win_prob_min" to winProbMin,
            "win_ratio_min" to winRatioMin,
        ).forEach { (name, v) -> if (v != null) requireFinite(name, v) }
    }

    private fun requireFinite(
        name: String,
        value: Double,
    ) = require(value.isFinite()) { "$name は有限な数: $value" }

    fun allows(type: BetType): Boolean = betTypes == null || type in betTypes

    companion object {
        /** 旧 `evaluate_odds` のシグネチャの既定値（ML 無効・DB なし・`ml_cfg={"enabled": False}` 相当）。**運用の既定ではない**（ゴールデン比較用）。 */
        fun legacyFunctionDefaults(): WinDecisionConfig =
            WinDecisionConfig(
                evThreshold = 0.08,
                maxSignals = 2,
                dropThreshold = 0.20,
                surgeThreshold = 3,
                bankroll = 100.0,
                includeBetStrategy = true,
                betTypes = null,
                evShrinkFactor = 0.7,
                winMaxOverMarket = 0.0,
                winPayoutRate = 0.80,
                widePayoutRate = 0.775,
                sanrenpukuPayoutRate = 0.75,
                showAdjustedMkt = false,
                marketBlendAlpha = 0.25,
                probClipMax = 0.35,
                enableProbClip = true,
                notifySkips = false,
                kellyFraction = 0.25,
                minBet = 1.0,
                maxBet = 10.0,
                requireRealOddsForCombo = true,
                ticketAllocation = "best_only",
                combo = ComboSettings.legacyFunctionDefaults(),
                betTypeMode = BetTypeMode.MULTI,
                maxTotalBetPerRace = null,
                raceId = null,
                priorityMode = PriorityMode.EDGE,
                hitRateMinEv = null,
                hitRateBetTypePriority = null,
                winOddsMax = null,
                winProbMin = null,
                winRatioMin = null,
                probSource = MARKET_FALLBACK_ML_DISABLED,
                ensembleBeta = 0.35,
            )

        /** ML 無効（`ml_cfg.enabled=false`）のときの市場フォールバックの確率源ラベル。 */
        const val MARKET_FALLBACK_ML_DISABLED = "market:fallback(ml_disabled)"
    }
}

/** 判定の入力 1 頭分（旧 `OddsData` の `umaban` / `odds` / `popularity`）。オッズ ≤ 0 も受け付ける（旧実装は「データ無し」の 0 を扱う）。非有限値は拒否する。 */
data class DecisionHorse(
    val umaban: Int,
    val odds: Double,
    val popularity: Int,
) {
    init {
        require(odds.isFinite()) { "オッズは有限な数: 馬$umaban odds=$odds（旧実装は NaN/Infinity を黙って計算に流すが、この版は拒否する）" }
        require(popularity >= 0) { "人気順位は 0 以上: 馬$umaban popularity=$popularity" }
    }
}
