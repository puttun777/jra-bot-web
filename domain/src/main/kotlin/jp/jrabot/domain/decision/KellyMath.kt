package jp.jrabot.domain.decision

/**
 * 旧実装の Python が `ZeroDivisionError`（float の 0 除算）を投げる箇所の再現。Kotlin の `Double` は 0 除算で Inf/NaN になるため、
 * 例外として明示する。**旧欠陥 E5**（`odds == 1.0` の Kelly）の発生源。判定側は [WinDecisionEvaluator] で捕捉して理由 `ODDS_AT_PAR` を付ける。
 */
class LegacyZeroDivisionException(
    message: String,
) : ArithmeticException(message)

/** [KellyMath.suggestBetAmount] の結果（旧実装の dict と同じ 5 項目）。金額は旧実装の「点」（`Money` ではない）。 */
data class BetSuggestion(
    val kellyFraction: Double,
    val suggestedBet: Double,
    val expectedProfit: Double,
    val strategy: String,
    val ev: Double,
)

/**
 * Kelly 基準と推奨賭け額。参照元 `ev/ev_calc.py` の `calc_kelly_fraction` / `suggest_bet_amount`。
 *
 * `bankroll` / `minBet` / `maxBet` は旧実装の「点」の `Double`。円の `Money`（Long）とは別の量なので、変換や混用をしない。
 */
object KellyMath {
    /**
     * Kelly 割合 f* = (p·b − q) / b（b = odds − 1、q = 1 − p）。`prob <= 0` または `odds <= 0` は 0.0、結果が 0 以下なら 0.0、
     * それ以外は `min(kelly, maxFraction)`。
     *
     * **`odds == 1.0` は b = 0 の 0 除算**で、旧実装は `ZeroDivisionError`。これを [LegacyZeroDivisionException] として再現する（E5。数値の補正はしない）。
     * `0 < odds < 1` は b < 0 になり、旧実装は正の値を返す（JRA のオッズは 1.0 以上のはずなので実運用では起きない想定。ここでは補正せず、
     * 入力境界での拒否は取込側 T3.4 の判断: DECISIONS D-104）。
     */
    fun calcKellyFraction(
        prob: Double,
        odds: Double,
        maxFraction: Double = 0.25,
    ): Double {
        if (prob <= 0 || odds <= 0) return 0.0
        val b = odds - 1.0
        val q = 1.0 - prob
        if (b == 0.0) throw LegacyZeroDivisionException("float division by zero (odds=$odds)")
        val kelly = (prob * b - q) / b
        if (kelly <= 0) return 0.0
        return PythonCompat.min(kelly, maxFraction)
    }

    /**
     * 推奨賭け額。EV が 0 以下または Kelly が 0 以下なら 0、それ以外は `max(minBet, min(bankroll × kelly, maxBet))`。
     * `maxFraction` はここ（`calc_kelly_fraction` の上限）にだけ効く。**判定（evaluate_odds）側の賭け額計算は `maxFraction = 1.0` 固定で呼ぶ**
     * ので、設定値の `max_fraction` は判定には反映されない（旧欠陥 E12。既定は旧挙動のまま: [DecisionFixes.applyMaxFraction]）。
     */
    fun suggestBetAmount(
        prob: Double,
        odds: Double,
        bankroll: Double = 100.0,
        maxFraction: Double = 0.25,
        minBet: Double = 1.0,
        maxBet: Double = 10.0,
    ): BetSuggestion {
        val ev = EvMath.calcEv(prob, odds)
        val kellyFraction = calcKellyFraction(prob, odds, maxFraction)

        var suggestedBet = bankroll * kellyFraction
        suggestedBet =
            if (ev <= 0 || kellyFraction <= 0) {
                0.0
            } else {
                PythonCompat.max(minBet, PythonCompat.min(suggestedBet, maxBet))
            }

        val expectedProfit = if (suggestedBet > 0) EvMath.calcExpectedProfit(suggestedBet, prob, odds) else 0.0

        val strategy =
            when {
                ev <= 0 -> "投資推奨なし（EVが負）"
                kellyFraction <= 0 -> "投資推奨なし（ケリー基準が負）"
                kellyFraction < 0.05 -> "小額投資推奨（ケリー基準が低い）"
                kellyFraction < 0.15 -> "中額投資推奨"
                else -> "高額投資推奨（ケリー基準が高い）"
            }
        return BetSuggestion(kellyFraction, suggestedBet, expectedProfit, strategy, ev)
    }
}
