package jp.jrabot.domain.decision

import kotlin.math.exp
import kotlin.math.ln

/**
 * 判定側の市場ブレンド（参照元 `evaluate_odds` の「最終確率に必ず market_blend_alpha を適用する」ブロック。ML_DESIGN の G5）。
 *
 * モデル確率（ML 無効時は正規化した市場確率）と市場確率 1/odds を logit 空間で混合し、馬ごとの上限でクリップし、全体を再正規化する。
 * **旧欠陥 H4**: ML 無効でも「市場確率を市場確率とブレンド」する二重ブレンドになる。`pipeline_version=legacy` ではそのまま再現する
 * （1 回だけにする v2 は未承認で、この版には無い: [PipelineVersion]）。
 *
 * 数値: `np.log` / `np.exp` と `kotlin.math.ln` / `exp` は最後の 1〜2 ULP が違い得る（許容誤差 1e-9 内）。
 */
object MarketBlend {
    /** 上限クリップと再正規化の前の、馬ごとの最終確率を返す（再正規化は [apply]）。 */
    internal fun blendOne(
        pModel: Double,
        odds: Double,
        alpha: Double,
        enableProbClip: Boolean,
        probClipMax: Double?,
    ): Double {
        var pMkt = if (odds > 0) 1.0 / PythonCompat.max(odds, 0.1) else 0.0
        pMkt = PythonCompat.min(PythonCompat.max(pMkt, 1e-6), 1.0 - 1e-6)

        var pFinal: Double
        if (alpha > 0.001) {
            val zModel = if (pModel > 1e-6 && pModel < 1.0 - 1e-6) ln(pModel / (1.0 - pModel)) else 0.0
            val zMkt = if (pMkt > 1e-6 && pMkt < 1.0 - 1e-6) ln(pMkt / (1.0 - pMkt)) else 0.0
            val z = alpha * zModel + (1.0 - alpha) * zMkt
            pFinal = 1.0 / (1.0 + exp(-clip(z, -50.0, 50.0)))
        } else {
            pFinal = pMkt
        }

        if (enableProbClip && probClipMax != null && probClipMax > 0) {
            pFinal = PythonCompat.min(pFinal, probClipMax)
        }
        return pFinal
    }

    /** `np.clip` 相当（NaN は NaN のまま）。 */
    private fun clip(
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

    /**
     * 正規化済みのモデル確率 [probs] に市場ブレンドを適用する。
     * - [alpha] は呼び出し側で解決済みの値（`[0, 1]` に収める: 旧 `min(max(a, 0.0), 1.0)`）。**`alpha >= 0.999` は何もしない**
     *   （ブレンドもクリップも再正規化も行わず入力のまま返す。クリップがブレンドのブロック内にあるため）。
     * - `alpha > 0.001` の馬は logit 混合、`alpha <= 0.001` は市場確率そのもの。
     * - 馬ごとのブレンド後の合計が正なら全体を合計で割って再正規化する。**クリップ後に再正規化するため、最終値が上限を超え得る**。
     */
    fun apply(
        probs: List<Double>,
        oddsValues: List<Double>,
        alpha: Double,
        enableProbClip: Boolean,
        probClipMax: Double?,
    ): List<Double> {
        require(probs.size == oddsValues.size) { "確率とオッズの件数が違う: ${probs.size} / ${oddsValues.size}" }
        val alphaFinal = PythonCompat.clamp(alpha, 0.0, 1.0)
        if (probs.isEmpty() || !(alphaFinal < 0.999)) return probs

        val blended = probs.zip(oddsValues).map { (p, o) -> blendOne(p, o, alphaFinal, enableProbClip, probClipMax) }
        val total = PythonCompat.sum(blended)
        return if (total > 0) blended.map { it / total } else probs
    }
}
