package jp.jrabot.domain.decision

/**
 * 市場（オッズ）から作る確率。参照元 `ev/probability.py` の `estimate_prob_raw` / `estimate_prob` / `estimate_prob_normalized`（`use_ml=False` の経路）。
 *
 * ML 経由の経路（`use_ml=True`）は T3.6。ここは**市場確率だけ**（呼び出し側が ML 無効の場合のフォールバックと同じ経路）。
 * 確率は素の `Double`（`Probability` は 0〜1 の範囲外を拒否するが、旧実装は範囲外・0 を扱うため）。丸めはしない。
 */
object MarketProbability {
    /** 生の暗黙確率 1/odds（正規化前）。`odds <= 0` は 0.0。 */
    fun estimateProbRaw(odds: Double): Double {
        if (odds <= 0) return 0.0
        return 1.0 / odds
    }

    /** 暗黙確率 `min(1/odds, 0.99)`（単体用。レース全体の正規化は [estimateProbNormalized]）。`odds <= 0` は 0.0。 */
    fun estimateProb(odds: Double): Double {
        if (odds <= 0) return 0.0
        val implicit = 1.0 / odds
        return PythonCompat.min(implicit, 0.99)
    }

    /**
     * レース内で確率を正規化する（合計 1.0）。手順は旧実装のとおり:
     * 1. 各馬の生確率 `estimateProbRaw`、合計 `totalRaw`（Python の `sum`: [PythonCompat.sum]）。空入力は空。
     * 2. `totalRaw <= 0`（全馬 odds<=0）なら均等配分 `1/n`。
     * 3. 正規化 `p/totalRaw`。各馬 ratio = 正規化後 / 生（生 > 0 のとき、そうでなければ 1.0）。
     *    ratio > 1+limit なら 生×(1+limit)、ratio < 1−limit なら 生×(1−limit)、それ以外は正規化後の値（**±limit 補正。境界ちょうどは補正しない**）。
     * 4. 補正後の合計が正なら再正規化。
     * 補正後に再正規化するため、最終値が元の確率の ±limit を超えることがある（旧欠陥 E13。仕様として固定）。
     */
    fun estimateProbNormalized(
        oddsList: List<Double>,
        correctionLimit: Double = 0.10,
    ): List<Double> {
        if (oddsList.isEmpty()) return emptyList()

        val rawProbs = oddsList.map { estimateProbRaw(it) }
        val totalRaw = PythonCompat.sum(rawProbs)

        if (totalRaw <= 0) {
            return List(oddsList.size) { 1.0 / oddsList.size }
        }

        val normalized = rawProbs.map { it / totalRaw }

        var corrected =
            normalized.zip(rawProbs).map { (normProb, rawProb) ->
                val ratio = if (rawProb > 0) normProb / rawProb else 1.0
                if (ratio > 1.0 + correctionLimit) {
                    rawProb * (1.0 + correctionLimit)
                } else if (ratio < 1.0 - correctionLimit) {
                    rawProb * (1.0 - correctionLimit)
                } else {
                    normProb
                }
            }

        val totalCorrected = PythonCompat.sum(corrected)
        if (totalCorrected > 0) {
            corrected = corrected.map { it / totalCorrected }
        }
        return corrected
    }
}
