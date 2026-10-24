package jp.jrabot.domain.ml.pipeline

import jp.jrabot.domain.decision.PythonCompat

/** パイプラインの共通の段階（旧 `evaluate_odds` の入れ子関数・ensemble の式）。`legacy` と `v2` が共有する。 */
internal object ProbabilityStages {
    /**
     * 旧 `_normalize_with_correction(raw, correction_limit=0.10)`（`evaluate_odds` の入れ子関数）。
     * 合計が 0 以下なら均等配分。`raw > 0` の馬だけ ±limit の補正（境界ちょうどは補正しない）。補正後に再正規化する。
     * 空入力は空。`MarketProbability.estimateProbNormalized`（市場確率用）とは別物（raw ≤ 0 の扱いと分岐が違う）。
     */
    fun normalizeWithCorrection(
        raw: List<Double>,
        correctionLimit: Double = 0.10,
    ): List<Double> {
        if (raw.isEmpty()) return emptyList()
        val s = PythonCompat.sum(raw)
        if (s <= 0) return List(raw.size) { 1.0 / raw.size }
        val normalized = raw.map { it / s }
        val corrected =
            normalized.zip(raw).map { (normP, rawP) ->
                if (rawP > 0) {
                    val ratio = normP / rawP
                    if (ratio > 1.0 + correctionLimit) {
                        rawP * (1.0 + correctionLimit)
                    } else if (ratio < 1.0 - correctionLimit) {
                        rawP * (1.0 - correctionLimit)
                    } else {
                        normP
                    }
                } else {
                    normP
                }
            }
        val s2 = PythonCompat.sum(corrected)
        return if (s2 > 0) corrected.map { it / s2 } else normalized
    }

    /**
     * ensemble の馬ごとの合成（旧 `evaluate_odds`）。`betaEff = β × coverage`。
     * - オッズ有り（`odds > 0`）かつ calibration あり: alpha が無ければ calibration のみ、あれば `(1-β_eff)·calib + β_eff·alpha`。
     * - それ以外: alpha（無ければ calibration、どちらも無ければ 0.0）。
     */
    fun ensembleOne(
        odds: Double,
        pc: Double?,
        pa: Double?,
        betaEff: Double,
    ): Double {
        val oddsOk = odds > 0.0
        return if (oddsOk && pc != null) {
            if (pa == null) pc else (1.0 - betaEff) * pc + betaEff * pa
        } else {
            pa ?: pc ?: 0.0
        }
    }

    /** 馬ごとの出どころ（ensemble）。 */
    fun ensembleSource(
        odds: Double,
        hasCalib: Boolean,
        hasAlpha: Boolean,
    ): ProbSource {
        val oddsOk = odds > 0.0
        return when {
            oddsOk && hasCalib && hasAlpha -> ProbSource.ML_ENSEMBLE_CALIB_ALPHA
            oddsOk && hasCalib -> ProbSource.ML_ENSEMBLE_CALIB_ONLY
            hasAlpha -> ProbSource.ML_ENSEMBLE_ALPHA_ONLY
            hasCalib -> ProbSource.ML_ENSEMBLE_CALIB_ONLY
            else -> ProbSource.MARKET_FALLBACK
        }
    }

    fun coverageOf(
        coverage: List<Double?>?,
        i: Int,
    ): Double = coverage?.getOrNull(i) ?: 1.0

    fun requireSizes(
        n: Int,
        vararg outputs: RoleOutput?,
    ) {
        for (o in outputs) {
            if (o is RoleOutput.Scored) {
                require(o.probabilities.size == n) { "確率の数 ${o.probabilities.size} が馬の数 $n と違う" }
                require(o.probabilities.all { it.isFinite() }) { "確率に非有限の値" }
            }
        }
    }

    fun clampBeta(beta: Double): Double = PythonCompat.min(PythonCompat.max(beta, 0.0), 1.0)
}
