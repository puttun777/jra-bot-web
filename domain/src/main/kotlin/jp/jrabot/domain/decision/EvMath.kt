package jp.jrabot.domain.decision

/**
 * 期待値（EV）の計算。参照元 `ev/ev_calc.py` の `calc_ev` / `calc_expected_return` / `calc_expected_profit` と同じ式・同じ分岐。
 *
 * 入力は旧実装と同じ素の `Double`（確率は 0〜1 のはずだが**検査も補正もしない**。`Probability` / `Odds` の値オブジェクトは範囲外を拒否するので、
 * 旧実装の境界挙動（prob=-0.1、odds=0 など）を再現するこの層では使わない。値オブジェクトとの変換は呼び出し側の境界で行う）。
 * 丸めはしない。NaN は旧実装と同じく比較が偽になり、そのまま演算に流れる。
 */
object EvMath {
    /** EV = prob × odds − 1（期待利益率）。`prob <= 0` または `odds <= 0` は −1.0。 */
    fun calcEv(
        prob: Double,
        odds: Double,
    ): Double {
        if (prob <= 0 || odds <= 0) return -1.0
        return prob * odds - 1.0
    }

    /** 期待回収倍率 = prob × odds。`prob <= 0` または `odds <= 0` は 0.0。 */
    fun calcExpectedReturn(
        prob: Double,
        odds: Double,
    ): Double {
        if (prob <= 0 || odds <= 0) return 0.0
        return prob * odds
    }

    /** 期待利益 = stake × EV（`stake` は旧実装の「点」。円の `Money` ではない）。 */
    fun calcExpectedProfit(
        stake: Double,
        prob: Double,
        odds: Double,
    ): Double = stake * calcEv(prob, odds)
}
