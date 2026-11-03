package jp.jrabot.mltrain.numeric

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * v2 の学習: 標準化 + L2 ロジスティック回帰 + Platt 校正 + 直近重み（旧 ml/src/ml_train/trainer.py の移植。ML_DESIGN §4・§11.7）。
 * - 特徴量は Kotlin の DatasetExporter が作った行列をそのまま使う（再計算しない）。
 * - 推論の算術は `LogisticScoring` と同じ（z を ±50 に収める → sigmoid → [1e-6, 1−1e-6] → Platt → [1e-6, 1−1e-6]）。
 * - class_weight は使わない（旧実装の H5: 事前確率の歪みを避ける）。乱数は使わない（決定的）。
 */
object Trainer {
    const val Z_LIMIT = 50.0
    const val P_EPS = 1e-6

    class TrainingFailed(
        val reason: String,
        message: String,
    ) : RuntimeException(message)

    fun clipP(p: Double): Double = min(max(p, P_EPS), 1.0 - P_EPS)

    fun logit(p: Double): Double = ln(p / (1.0 - p))

    /** `w_i = 0.5 ** ((ref − race_date_i).days / halflife)`、ref = train の最大 race_date。train の平均 1 に正規化（ML_DESIGN §11.7）。 */
    fun sampleWeights(
        raceDates: List<LocalDate>,
        halflifeDays: Int,
    ): DoubleArray {
        require(raceDates.isNotEmpty()) { "train が空" }
        val ref = raceDates.max()
        val w = DoubleArray(raceDates.size) { 0.5.pow(ChronoUnit.DAYS.between(raceDates[it], ref).toDouble() / halflifeDays) }
        val mean = w.average()
        require(mean.isFinite() && mean > 0.0) { "sample_weight が不正" }
        return DoubleArray(w.size) { w[it] / mean }
    }

    /** 列ごとの平均と母標準偏差（numpy の std と同じ ddof=0）。定数の列（std ≤ 1e-12）は 1.0（モデルの std は正の有限値が契約）。 */
    class Standardizer(
        val mean: DoubleArray,
        val std: DoubleArray,
    ) {
        fun apply(x: DoubleArray): DoubleArray = DoubleArray(x.size) { (x[it] - mean[it]) / std[it] }

        fun apply(x: Array<DoubleArray>): Array<DoubleArray> = Array(x.size) { apply(x[it]) }

        companion object {
            fun fit(x: Array<DoubleArray>): Standardizer {
                val n = x.size
                val d = x[0].size
                val mean = DoubleArray(d) { j -> columnMean(x, j) }
                val std =
                    DoubleArray(d) { j ->
                        var s = 0.0
                        for (row in x) s += (row[j] - mean[j]).let { it * it }
                        val sd = sqrt(s / n)
                        if (sd > 1e-12) sd else 1.0
                    }
                return Standardizer(mean, std)
            }

            private fun columnMean(
                x: Array<DoubleArray>,
                j: Int,
            ): Double {
                var s = 0.0
                for (row in x) s += row[j]
                return s / x.size
            }
        }
    }

    class LinearModel(
        val standardizer: Standardizer,
        val weights: DoubleArray,
        val bias: Double,
        val platt: Pair<Double, Double>? = null,
        /** 学習の停止状態（反復回数・停止時の勾配・丸めで止めたか。レポートに記録する） */
        val solver: Logistic.Fit? = null,
    ) {
        fun rawProbability(x: DoubleArray): Double = Logistic.clippedSigmoid(Logistic.dot(standardizer.apply(x), weights) + bias, Z_LIMIT)

        /** モデルのみの確率（市場ブレンドなし。Kotlin の推論で alpha ≥ 0.999 のときと同じ）。 */
        fun predict(x: DoubleArray): Double {
            var p = clipP(rawProbability(x))
            if (platt != null) p = clipP(Logistic.clippedSigmoid(platt.first * logit(p) + platt.second, Z_LIMIT))
            return p
        }

        fun predict(x: Array<DoubleArray>): DoubleArray = DoubleArray(x.size) { predict(x[it]) }

        fun withPlatt(
            a: Double,
            b: Double,
        ): LinearModel = LinearModel(standardizer, weights, bias, a to b, solver)
    }

    fun trainLinear(
        x: Array<DoubleArray>,
        y: IntArray,
        sampleWeight: DoubleArray,
        l2C: Double,
        maxIter: Int,
        tol: Double,
    ): LinearModel {
        val st = Standardizer.fit(x)
        val fit =
            try {
                // 設定の tol は上限。Newton 法は数回で最適解の近くまで詰められるので 1e-10 まで詰める（真の最適解に近い方が再現性が高い）
                // 丸めで止まったときに受け入れる勾配は設定の tol を超えない（記録した tol より緩い解を合格にしない）
                Logistic.fit(st.apply(x), y, sampleWeight, l2C, minOf(tol, 1e-10), maxIter, acceptTol = minOf(tol, Logistic.ROUNDOFF_GRADIENT))
            } catch (e: Logistic.NotConverged) {
                throw TrainingFailed("TRAINING_DID_NOT_CONVERGE", "Newton 法が収束しなかった（max_iter を増やすか l2_c を見直す）: ${e.message}")
            } catch (e: ArithmeticException) {
                throw TrainingFailed("TRAINING_FAILED", "学習に失敗: ${e.message}")
            }
        if (!(fit.weights.all { it.isFinite() } && fit.bias.isFinite())) throw TrainingFailed("TRAINING_FAILED", "重みが有限でない")
        return LinearModel(st, fit.weights, fit.bias, solver = fit)
    }

    /**
     * 校正用データで `p' = sigmoid(a · logit(p) + b)` を最尤で当てる（ほぼ無正則化: C = 1e6。旧版と同じ設定）。
     * 収束判定の tol は旧版（lbfgs の 1e-10）と同じ尺度。分離に近いデータでは a・b が大きくなる（旧版も同じ）。
     */
    fun fitPlatt(
        p: DoubleArray,
        y: IntArray,
    ): Pair<Double, Double> = fitPlattDetailed(p, y).let { it.weights[0] to it.bias }

    /** [fitPlatt] と同じ。停止状態（[Logistic.Fit]）も返す。 */
    fun fitPlattDetailed(
        p: DoubleArray,
        y: IntArray,
    ): Logistic.Fit {
        val z = Array(p.size) { doubleArrayOf(logit(clipP(p[it]))) }
        val fit =
            try {
                Logistic.fit(z, y, null, 1e6, 1e-10, 1000)
            } catch (e: Logistic.NotConverged) {
                throw TrainingFailed("CALIBRATION_FAILED", "Platt の推定が収束しなかった: ${e.message}")
            } catch (e: ArithmeticException) {
                throw TrainingFailed("CALIBRATION_FAILED", "Platt の推定に失敗: ${e.message}")
            }
        val a = fit.weights[0]
        val b = fit.bias
        if (!(a.isFinite() && b.isFinite())) throw TrainingFailed("CALIBRATION_FAILED", "Platt の a・b が有限でない")
        return fit
    }
}
