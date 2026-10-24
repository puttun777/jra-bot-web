package jp.jrabot.domain.ml.model

import jp.jrabot.domain.decision.PythonCompat
import kotlin.math.exp
import kotlin.math.ln

/** Platt 校正 `p' = sigmoid(a · logit(p) + b)`。a・b は有限。 */
data class PlattCalibration(
    val a: Double,
    val b: Double,
) {
    init {
        require(a.isFinite() && b.isFinite()) { "Platt の a・b は有限な数: a=$a b=$b" }
    }
}

/** 推論の計算が有限でなくなった（NaN / Infinity）。サイレントに置換せず、呼び出し側が [jp.jrabot.domain.ml.pipeline.FallbackReason.MODEL_PREDICTION_FAILED] にする。 */
class NonFiniteComputationException(
    message: String,
) : ArithmeticException(message)

/**
 * 勝率推定の算術（旧 `WinProbabilityModel` の `_sigmoid` / `_predict_proba_array` / `_postprocess_prob`。ML_DESIGN §7.2）。
 * すべて `Double`。**丸めはしない**。許容誤差は ML_DESIGN §7.2（確率 1e-9 以内）。
 *
 * - `dot` は特徴量の順の単純な逐次加算（numpy の BLAS の加算順とは異なり、相対 1e-15 程度の差が出る）。
 * - `exp` / `ln` は `kotlin.math`（`StrictMath` にはしない: 差は 1 ULP 程度）。`np.log(p/(1-p))` と同じ式を使う（`log1p` に置き換えない）。
 * - **次元の切詰め/ゼロ埋め（旧 `_align_feature_matrix`）はここに無い**。重みと特徴量の長さが違えば例外（安全なモデル契約）。旧挙動の再現は
 *   ゴールデン専用の `:golden-runner` の互換モジュールだけが持つ。
 */
object LogisticScoring {
    const val Z_LIMIT = 50.0
    const val P_EPS = 1e-6

    /** `np.clip`（NaN は NaN のまま）。 */
    fun clip(
        x: Double,
        lo: Double,
        hi: Double,
    ): Double =
        if (x.isNaN()) {
            x
        } else if (x < lo) {
            lo
        } else if (x > hi) {
            hi
        } else {
            x
        }

    /** `1 / (1 + exp(-clip(z, ±50)))`。 */
    fun sigmoid(z: Double): Double = 1.0 / (1.0 + exp(-clip(z, -Z_LIMIT, Z_LIMIT)))

    /** `X·w`（逐次加算）+ `bias`。長さが違えば [IllegalArgumentException]。 */
    fun linear(
        x: DoubleArray,
        w: DoubleArray,
        bias: Double,
    ): Double {
        require(x.size == w.size) { "特徴量の数 ${x.size} と重みの数 ${w.size} が違う" }
        var acc = 0.0
        for (i in x.indices) acc += x[i] * w[i]
        return acc + bias
    }

    /** `sigmoid(X·w + b)`。結果が有限でなければ [NonFiniteComputationException]。 */
    fun rawProbability(
        x: DoubleArray,
        w: DoubleArray,
        bias: Double,
    ): Double {
        val z = linear(x, w, bias)
        if (!z.isFinite()) throw NonFiniteComputationException("z が有限でない")
        return sigmoid(z)
    }

    /**
     * 旧 `_postprocess_prob(p, odds)`:
     * 1. p を [1e-6, 1-1e-6] に収める。
     * 2. [platt] があれば logit 空間で `a·z+b` → sigmoid → 再び [1e-6, 1-1e-6]。
     * 3. 市場確率 `1/max(odds, 0.1)`（[1e-6, 1-1e-6] に収める）と、`alpha`（[0,1] に収める）で logit 混合。
     *    `alpha >= 0.999` はモデルの確率そのもの、`alpha <= 0.001` は市場確率そのもの。
     * [odds] は旧実装では特徴量行列の 0 列目（`odds <= 0` は 100.0 に置換済みの値）。
     */
    fun postprocess(
        p0: Double,
        odds: Double,
        platt: PlattCalibration?,
        marketBlendAlpha: Double,
    ): Double {
        if (!p0.isFinite() || !odds.isFinite() || !marketBlendAlpha.isFinite()) throw NonFiniteComputationException("後処理の入力が有限でない")
        var p = PythonCompat.min(PythonCompat.max(p0, P_EPS), 1.0 - P_EPS)

        if (platt != null) {
            val z = ln(p / (1.0 - p))
            val z2 = platt.a * z + platt.b
            p = 1.0 / (1.0 + exp(-clip(z2, -Z_LIMIT, Z_LIMIT)))
            p = PythonCompat.min(PythonCompat.max(p, P_EPS), 1.0 - P_EPS)
        }

        var pMkt = 1.0 / PythonCompat.max(odds, 0.1)
        pMkt = PythonCompat.min(PythonCompat.max(pMkt, P_EPS), 1.0 - P_EPS)

        val a = PythonCompat.min(PythonCompat.max(marketBlendAlpha, 0.0), 1.0)
        if (a >= 0.999) return p
        if (a <= 0.001) return pMkt

        val zModel = ln(p / (1.0 - p))
        val zMkt = ln(pMkt / (1.0 - pMkt))
        val z = a * zModel + (1.0 - a) * zMkt
        return 1.0 / (1.0 + exp(-clip(z, -Z_LIMIT, Z_LIMIT)))
    }

    /** 最終の確率を [0, 1] に収めて有限を保証する（旧 `max(0.0, min(1.0, prob))`）。 */
    fun finalClamp(p: Double): Double {
        if (!p.isFinite()) throw NonFiniteComputationException("確率が有限でない")
        return PythonCompat.max(0.0, PythonCompat.min(1.0, p))
    }
}
