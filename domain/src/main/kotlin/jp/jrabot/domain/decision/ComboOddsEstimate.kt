package jp.jrabot.domain.decision

/**
 * 単勝オッズからのワイド・三連複の**推定オッズ**（旧 `estimate_wide_odds` / `estimate_trifecta_odds`）。丸めなし・上下限なし。
 * 旧実装は `num_horses` を受け取るが**使わない**（この版も持たない）。払戻率も使わない（単純な係数）。
 * JRA が公表した実オッズ（[ComboOdds]）とは別物。`win_odds <= 0` は 0.0（EV が −1 になり買い目にならない）。
 */
object ComboOddsEstimate {
    /** 単勝 ≤ 3.0: ×0.4 / ≤ 10.0: ×0.35 / それ以外: ×0.3。 */
    fun wide(winOdds: Double): Double {
        if (winOdds <= 0) return 0.0
        return if (winOdds <= 3.0) {
            winOdds * 0.4
        } else if (winOdds <= 10.0) {
            winOdds * 0.35
        } else {
            winOdds * 0.3
        }
    }

    /** 単勝 ≤ 3.0: ×0.03 / ≤ 10.0: ×0.02 / それ以外: ×0.01。 */
    fun trifecta(winOdds: Double): Double {
        if (winOdds <= 0) return 0.0
        return if (winOdds <= 3.0) {
            winOdds * 0.03
        } else if (winOdds <= 10.0) {
            winOdds * 0.02
        } else {
            winOdds * 0.01
        }
    }
}

/**
 * 旧 `calculate_wide_prob` / `calculate_trifecta_prob`（単勝確率に固定の係数を掛けただけの粗い近似）。
 * **旧実装の判定では使われていない**（`evaluate_odds` が使う確率は Plackett–Luce: [PlackettLuce]）。ゴールデン GD05-COMBO-001 が旧関数の値を記録しているので、
 * 単独の関数として写すだけで、判定には接続しない。単勝確率の単純な積でもない。
 */
object LegacySimpleComboProbability {
    /** `min(p + 0.5p, 0.99)`。馬のリストが空、または `p <= 0` は 0.0。 */
    fun wide(
        winProb: Double,
        raceIsEmpty: Boolean,
    ): Double {
        if (raceIsEmpty || winProb <= 0) return 0.0
        val second = winProb * 0.5
        return PythonCompat.min(winProb + second, 0.99)
    }

    /** `min(p + 0.5p + 0.3p, 0.99)`。馬のリストが空、または `p <= 0` は 0.0。 */
    fun trifecta(
        winProb: Double,
        raceIsEmpty: Boolean,
    ): Double {
        if (raceIsEmpty || winProb <= 0) return 0.0
        val second = winProb * 0.5
        val third = winProb * 0.3
        return PythonCompat.min(winProb + second + third, 0.99)
    }
}
