package jp.jrabot.mltrain.job

/**
 * 品質ゲート（旧 ml/src/ml_train/gate.py の移植）。**数値の閾値は未承認で、既定値を持たない**（docs/UNRESOLVED.md）。明示した check だけを評価する。
 * - 設定が無い／check が 0 個 → NOT_CONFIGURED（fail closed。REJECTED）。暗黙の閾値で CANDIDATE にしない。
 * - 設定した check が評価できない（UNAVAILABLE。例: alpha に市場ベースラインが無い）→ 不合格（REJECTED）。架空の値で通さない。
 * - 判定は PASS（全 check が PASS）→ CANDIDATE、それ以外 → REJECTED。ACTIVE にはしない。
 */
object QualityGate {
    data class Check(
        val name: String,
        val status: String,
        val observed: Double?,
        val threshold: Double?,
        val detail: String = "",
    )

    data class Result(
        val verdict: String,
        val purpose: String,
        val checks: List<Check>,
    ) {
        val passed: Boolean get() = verdict == "PASS"
        val reasons: List<String>
            get() = if (verdict == "NOT_CONFIGURED") listOf("QUALITY_GATE_NOT_CONFIGURED") else checks.filter { it.status != "PASS" }.map { "${it.name}:${it.status}" }
    }

    /** job が集めた観測値。metrics は評価用データの指標（[jp.jrabot.mltrain.numeric.Metrics.evaluate]）。 */
    class Facts(
        val metrics: Map<String, Any?>,
        val nTrain: Int,
        val nTrainPos: Int,
        val nValid: Int,
        val nValidPos: Int,
        val constantFeatureRatio: Double,
        val horseMasterCoverage: Double,
        val activeLogloss: Double?,
    )

    private fun le(
        name: String,
        observed: Double?,
        t: Double,
        what: String,
    ) = if (observed == null) Check(name, "UNAVAILABLE", null, t, "${what}を計算できない") else Check(name, if (observed <= t) "PASS" else "FAIL", observed, t)

    private fun ge(
        name: String,
        observed: Double?,
        t: Double,
        what: String,
    ) = if (observed == null) Check(name, "UNAVAILABLE", null, t, "${what}を計算できない") else Check(name, if (observed >= t) "PASS" else "FAIL", observed, t)

    private fun Map<String, Any?>.num(key: String): Double? = (this[key] as? Number)?.toDouble()

    fun evaluate(
        gate: GateConfig,
        f: Facts,
    ): Result {
        if (!gate.configured) return Result("NOT_CONFIGURED", gate.purpose, emptyList())
        val m = f.metrics
        val out = ArrayList<Check>()
        for (name in gate.checks.keys.sorted()) {
            val t = gate.threshold(name)
            out +=
                when (name) {
                    "min_train_rows" -> ge(name, f.nTrain.toDouble(), t, "train 行数")
                    "min_train_positives" -> ge(name, f.nTrainPos.toDouble(), t, "train の正例数")
                    "min_valid_rows" -> ge(name, f.nValid.toDouble(), t, "valid 行数")
                    "min_valid_positives" -> ge(name, f.nValidPos.toDouble(), t, "valid の勝ち馬数")
                    "max_valid_logloss" -> le(name, m.num("logloss"), t, "LogLoss")
                    "max_valid_brier" -> le(name, m.num("brier"), t, "Brier")
                    "min_valid_auc" -> ge(name, m.num("auc"), t, "AUC")
                    "max_logloss_minus_market" -> {
                        val base = m.num("baseline_market_logloss")
                        le(name, base?.let { checkNotNull(m.num("logloss_race_normalized")) - it }, t, "市場ベースラインとの LogLoss 差")
                    }
                    "calibration_slope_min" -> ge(name, m.num("calibration_slope"), t, "校正傾き")
                    "calibration_slope_max" -> le(name, m.num("calibration_slope"), t, "校正傾き")
                    "max_abs_calibration_intercept" -> le(name, m.num("calibration_intercept")?.let { kotlin.math.abs(it) }, t, "校正切片")
                    "max_constant_feature_ratio" -> le(name, f.constantFeatureRatio, t, "定数特徴量の割合")
                    "min_horse_master_coverage" -> ge(name, f.horseMasterCoverage, t, "馬マスタのカバレッジ")
                    "max_logloss_regression_vs_active" ->
                        le(name, f.activeLogloss?.let { checkNotNull(m.num("logloss")) - it }, t, "直前の ACTIVE との LogLoss 差（ACTIVE が無い/指標が無い）")
                    else -> Check(name, "UNAVAILABLE", null, t, "未対応の check") // 設定は検証済みなので到達しない。未知の check を黙って通さない
                }
        }
        return Result(if (out.all { it.status == "PASS" }) "PASS" else "FAIL", gate.purpose, out)
    }
}
