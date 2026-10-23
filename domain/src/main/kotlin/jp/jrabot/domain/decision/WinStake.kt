package jp.jrabot.domain.decision

/** 単勝の賭け額計算（旧 `_calc_bet_amount` の Kelly 経路）の結果。金額は「点」。 */
data class WinBetInfo(
    val suggestedBet: Double,
    val kellyRaw: Double,
    val kellyAdjusted: Double,
    val kellyBetBeforeClip: Double,
    val clipped: Boolean,
    val skipReason: String?,
)

/**
 * 単勝の確率の縮小・Kelly 係数・賭け額。参照元 `ev/bet_strategy.py` の `suggest_multiple_bet_strategies` 内の
 * `_prob_used` / `_kelly_frac_adjusted` / `_calc_bet_amount`。
 *
 * - [probSource] は旧実装の確率源ラベル（`prob_source`）。Kelly 係数は**文字列の完全一致**（小文字化後）で決まる。`evaluate_odds` が渡すラベルは
 *   `ml:alpha` / `market:fallback(...)` などで `ml_features` / `ml_model` / `market` のどれにも一致せず、**常に係数 0.8 になる**（旧欠陥 H10。
 *   `pipeline_version=legacy` ではそのまま再現する。enum 化の案は未承認: [DecisionFixes.kellyMultiplierBySourceEnum]）。
 * - 賭け額は「点」（`Money` ではない）。`maxFraction` は 1.0 固定（旧欠陥 E12: 設定値の `max_fraction` は判定に効かない）。
 * - 賭けの方式は常に Kelly。`evaluate_odds` の引数 `bet_strategy`（"kelly"/"fixed"）はローカル変数 `bet_strategy = None` に隠れて
 *   `suggest_multiple_bet_strategies` に渡らないため、**固定額（"fixed"）は旧実装でも一度も使われない**。この版は固定額を持たない
 *   （ラベルも旧実装のまま `投資戦略: None (…)`）。
 */
class WinStake(
    private val evShrinkFactor: Double,
    private val kellyFraction: Double,
    private val bankroll: Double,
    private val minBet: Double,
    private val maxBet: Double,
    private val probSource: String,
) {
    /** 旧 `_prob_used`: `min(max(raw × clamp(f, 0, 1), 0), 0.99)`。 */
    fun probUsed(probRaw: ProbRaw): ProbUsed {
        val f = PythonCompat.clamp(evShrinkFactor, 0.0, 1.0)
        val p = probRaw.value * f
        return ProbUsed(PythonCompat.clamp(p, 0.0, 0.99))
    }

    /** 旧 `_kelly_frac_adjusted(base)`: `min(max(base × m, 0), 1)`。m は確率源ラベルの完全一致（それ以外は 0.8）。 */
    fun kellyFractionAdjusted(base: Double): Double = PythonCompat.clamp(base * kellyMultiplier(), 0.0, 1.0)

    fun kellyMultiplier(): Double =
        when ((probSource.ifEmpty { "unknown" }).lowercase()) {
            "ml_features" -> 1.0
            "ml_model" -> 0.9
            "market" -> 0.8
            else -> 0.8
        }

    /** メタ情報の `ev_shrink_factor`: 旧 `float(min(max(float(f or 1.0), 0.0), 1.0))`（`f == 0.0` は falsy で 1.0 になる点も旧実装のまま）。 */
    fun evShrinkFactorForMeta(): Double = PythonCompat.clamp(if (evShrinkFactor == 0.0) 1.0 else evShrinkFactor, 0.0, 1.0)

    /**
     * 旧 `_calc_bet_amount`（Kelly）。`bankroll × kelly_raw × 調整後の Kelly 分数` が `minBet` 未満なら 0（見送り。理由の文字列は旧実装のまま）、
     * `maxBet` 超なら `maxBet`（clipped）、その間はそのまま。`odds == 1.0` かつ確率 > 0 では [LegacyZeroDivisionException]（E5）。
     */
    fun calcBetAmount(
        probUsed: ProbUsed,
        odds: Double,
    ): WinBetInfo {
        val kellyRaw = KellyMath.calcKellyFraction(probUsed.value, odds, maxFraction = 1.0)
        val kf = kellyFractionAdjusted(kellyFraction)
        val kellyAdjusted = kellyRaw * kf
        val betBeforeClip = bankroll * kellyAdjusted

        if (betBeforeClip < minBet) {
            return WinBetInfo(
                suggestedBet = 0.0,
                kellyRaw = kellyRaw,
                kellyAdjusted = kellyAdjusted,
                kellyBetBeforeClip = betBeforeClip,
                clipped = false,
                skipReason = "Kelly推奨額(${PythonCompat.fixed(betBeforeClip, 2)}点) < min_bet(${PythonCompat.fixed(minBet, 1)}点)",
            )
        }
        if (betBeforeClip > maxBet) {
            return WinBetInfo(maxBet, kellyRaw, kellyAdjusted, betBeforeClip, clipped = true, skipReason = null)
        }
        return WinBetInfo(betBeforeClip, kellyRaw, kellyAdjusted, betBeforeClip, clipped = false, skipReason = null)
    }

    /** 戦略の「Kelly 割合」表示値（旧 `win_kelly`）: `calc_kelly_fraction(prob_used, odds, 1.0) × 調整後の分数`。 */
    fun displayKellyFraction(
        probUsed: ProbUsed,
        odds: Double,
    ): Double = KellyMath.calcKellyFraction(probUsed.value, odds, maxFraction = 1.0) * kellyFractionAdjusted(kellyFraction)
}
