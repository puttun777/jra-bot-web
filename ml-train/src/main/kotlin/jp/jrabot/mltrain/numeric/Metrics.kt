package jp.jrabot.mltrain.numeric

import org.hipparchus.stat.descriptive.rank.Percentile
import org.hipparchus.stat.ranking.NaNStrategy
import org.hipparchus.stat.ranking.NaturalRanking
import org.hipparchus.stat.ranking.TiesStrategy
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * 評価指標（旧 ml/src/ml_train/metrics.py の移植）。計算できない指標は値を作らず null にして `unavailable` に理由を残す。
 * 順位（AUC の同順位は平均順位）と分位点（numpy の既定 = 線形補間 = R-7）は Hipparchus を使う。
 */
object Metrics {
    private const val EPS = 1e-15

    fun logloss(
        y: IntArray,
        p: DoubleArray,
    ): Double {
        var s = 0.0
        for (i in y.indices) {
            val q = min(max(p[i], EPS), 1.0 - EPS)
            s += y[i] * ln(q) + (1 - y[i]) * ln(1.0 - q)
        }
        return -s / y.size
    }

    fun brier(
        y: IntArray,
        p: DoubleArray,
    ): Double = y.indices.sumOf { (p[it] - y[it]).let { d -> d * d } } / y.size

    /** Mann–Whitney U による AUC（同順位は平均順位）。片方のクラスしか無ければ null。 */
    fun auc(
        y: IntArray,
        p: DoubleArray,
    ): Double? {
        val nPos = y.sum()
        val nNeg = y.size - nPos
        if (nPos == 0 || nNeg == 0) return null
        val ranks = NaturalRanking(NaNStrategy.FAILED, TiesStrategy.AVERAGE).rank(p)
        var sumPos = 0.0
        for (i in y.indices) if (y[i] == 1) sumPos += ranks[i]
        return (sumPos - nPos * (nPos + 1) / 2.0) / (nPos.toDouble() * nNeg)
    }

    /** `y ~ sigmoid(slope · logit(p) + intercept)` の最尤。片方のクラスしか無ければ null。 */
    fun calibrationSlopeIntercept(
        y: IntArray,
        p: DoubleArray,
    ): Pair<Double, Double>? {
        val s = y.sum()
        if (s == 0 || s == y.size) return null
        return Trainer.fitPlatt(DoubleArray(p.size) { Trainer.clipP(p[it]) }, y)
    }

    /** 同じレースに含まれる行の合計が 1 になるよう正規化（データセットに含まれる行だけが対象）。 */
    fun raceNormalize(
        raceId: List<String>,
        p: DoubleArray,
    ): DoubleArray {
        val sums = HashMap<String, Double>()
        for (i in p.indices) sums.merge(raceId[i], p[i], Double::plus)
        return DoubleArray(p.size) { p[it] / sums.getValue(raceId[it]) }
    }

    /** 市場確率 `1/max(odds, 0.1)`（生。[P_EPS, 1−P_EPS] に収める）と、同じレース内で正規化したもの。 */
    fun marketProbability(
        odds: DoubleArray,
        raceId: List<String>,
    ): Pair<DoubleArray, DoubleArray> {
        val raw = DoubleArray(odds.size) { Trainer.clipP(1.0 / max(odds[it], 0.1)) }
        return raw to raceNormalize(raceId, raw)
    }

    /** 各レースで最も確率の高い馬が 1 着だった割合（同率は先の行）。 */
    fun top1HitRate(
        raceId: List<String>,
        y: IntArray,
        p: DoubleArray,
    ): Double {
        val best = LinkedHashMap<String, Int>()
        for (i in p.indices) {
            val cur = best[raceId[i]]
            if (cur == null || p[i] > p[cur]) best[raceId[i]] = i
        }
        return best.values.sumOf { y[it] }.toDouble() / best.size
    }

    private val QUANTILES = listOf(0.0, 0.01, 0.05, 0.25, 0.5, 0.75, 0.95, 0.99, 1.0)

    /** 平均と分位点（numpy.quantile の既定 = 線形補間。キーは Python の `f"{q:g}"`）。 */
    fun distribution(p: DoubleArray): Map<String, Any> {
        val pct = Percentile().withEstimationType(Percentile.EstimationType.R_7)
        val q = LinkedHashMap<String, Double>()
        for (x in QUANTILES) q[gKey(x)] = if (x == 0.0) p.min() else pct.evaluate(p, x * 100.0)
        return linkedMapOf("mean" to p.average(), "quantiles" to q)
    }

    /** Python の `f"{q:g}"`（0.01 → "0.01"、0 → "0"、1 → "1"）。 */
    private fun gKey(x: Double): String = if (x == x.toLong().toDouble()) x.toLong().toString() else x.toBigDecimal().stripTrailingZeros().toPlainString()

    /** 予測確率の分位でビンを作り、各ビンの平均予測と実測の勝率（numpy の array_split と同じ分け方: 先頭の n % k 個が 1 つ多い）。 */
    fun reliabilityBins(
        y: IntArray,
        p: DoubleArray,
        bins: Int = 10,
    ): List<Map<String, Any>> {
        val order = p.indices.sortedWith(compareBy<Int> { p[it] }) // 安定ソート（numpy の kind="stable"）
        val k = min(bins, order.size)
        val base = order.size / k
        val extra = order.size % k
        val out = ArrayList<Map<String, Any>>()
        var start = 0
        for (b in 0 until k) {
            val size = base + if (b < extra) 1 else 0
            if (size == 0) continue
            val chunk = order.subList(start, start + size)
            start += size
            out += linkedMapOf("n" to size, "mean_pred" to chunk.sumOf { p[it] } / size, "observed" to chunk.sumOf { y[it] }.toDouble() / size)
        }
        return out
    }

    fun constantFeatureRatio(x: Array<DoubleArray>): Double {
        val d = x[0].size
        var constant = 0
        for (j in 0 until d) {
            val mean = x.sumOf { it[j] } / x.size
            val sd = kotlin.math.sqrt(x.sumOf { (it[j] - mean).let { v -> v * v } } / x.size)
            if (sd <= 1e-12) constant++
        }
        return constant.toDouble() / d
    }

    class Evaluation(
        val metrics: LinkedHashMap<String, Any?>,
        val unavailable: LinkedHashMap<String, String>,
    )

    /** 評価用データでの指標一式と、計算できなかった指標の理由（`unavailable`）。キーの集合は旧版と同じ。 */
    fun evaluate(
        y: IntArray,
        p: DoubleArray,
        raceId: List<String>,
        odds: DoubleArray?,
    ): Evaluation {
        val unavailable = LinkedHashMap<String, String>()
        val pn = raceNormalize(raceId, p).map { min(max(it, EPS), 1.0 - EPS) }.toDoubleArray()
        val m = LinkedHashMap<String, Any?>()
        m["n"] = y.size
        m["n_positive"] = y.sum()
        m["n_races"] = raceId.toSet().size
        m["positive_rate"] = y.sum().toDouble() / y.size
        m["logloss"] = logloss(y, p)
        m["brier"] = brier(y, p)
        m["auc"] = auc(y, p)
        m["accuracy_at_0_5"] = y.indices.count { (if (p[it] >= 0.5) 1 else 0) == y[it] }.toDouble() / y.size
        m["logloss_race_normalized"] = logloss(y, pn)
        m["brier_race_normalized"] = brier(y, pn)
        m["top1_hit_rate"] = top1HitRate(raceId, y, p)
        m["distribution"] = distribution(p)
        m["reliability"] = reliabilityBins(y, p)
        val cal = calibrationSlopeIntercept(y, DoubleArray(p.size) { Trainer.clipP(p[it]) })
        m["calibration_slope"] = cal?.first
        m["calibration_intercept"] = cal?.second
        if (cal == null) unavailable["calibration_slope"] = "評価データが片方のクラスだけ"
        if (m["auc"] == null) unavailable["auc"] = "評価データが片方のクラスだけ"
        if (odds == null) {
            for (k in listOf("baseline_market_logloss", "baseline_market_brier", "baseline_market_auc", "baseline_market_top1_hit_rate")) {
                m[k] = null
                unavailable[k] = "データセットに odds 特徴量が無い（alpha）。市場ベースラインは作らない"
            }
            m["market_baseline_available"] = false
        } else {
            val (raw, norm) = marketProbability(odds, raceId)
            m["baseline_market_logloss"] = logloss(y, norm)
            m["baseline_market_brier"] = brier(y, norm)
            m["baseline_market_auc"] = auc(y, norm)
            m["baseline_market_top1_hit_rate"] = top1HitRate(raceId, y, norm)
            m["baseline_market_logloss_raw_overround"] = logloss(y, raw)
            m["market_baseline_available"] = true
        }
        return Evaluation(m, unavailable)
    }
}
