package jp.jrabot.domain.ml.model

import jp.jrabot.domain.ml.features.FeatureSchema
import jp.jrabot.domain.ml.features.FeatureVector

/**
 * **署名・sha256・スキーマ・重みの長さをすべて検証済み**のモデル（[ModelLoader] だけが作れる。コンストラクタは非公開）。推論はこの型だけが行える
 * （検証前の [ModelDocument] や生の [jp.jrabot.domain.ml.ModelArtifact] からは推論できない）。不変。
 */
class VerifiedModel private constructor(
    val document: ModelDocument,
    private val weights: DoubleArray,
    private val bias: Double,
    val schema: FeatureSchema,
) {
    val modelId get() = document.modelId
    val kind get() = document.kind
    val sha256 get() = document.sha256

    /**
     * 馬ごとの確率（モデル内の後処理まで）。[blendOdds] は内部の市場ブレンドに使う 1 頭ごとのオッズ（旧実装の特徴量行列の 0 列目）、
     * [marketBlendAlpha] は内部ブレンドの α（1.0 ならブレンドしない）。
     * - 特徴量のスキーマが違えば [SchemaMismatchException]（暗黙の補正をしない）。
     * - 前処理（standardize）があれば適用してから `X·w + b`。
     * - NaN / Infinity は [NonFiniteComputationException]。
     */
    fun predict(
        rows: List<FeatureVector>,
        blendOdds: List<Double>,
        marketBlendAlpha: Double,
    ): List<Double> {
        require(rows.size == blendOdds.size) { "行数とオッズの数が違う" }
        return rows.mapIndexed { i, row ->
            if (row.schema != schema) throw SchemaMismatchException("特徴量のスキーマがモデルと違う")
            val x = document.preprocessing?.apply(row.values()) ?: row.values()
            val p = LogisticScoring.rawProbability(x, weights, bias)
            LogisticScoring.finalClamp(LogisticScoring.postprocess(p, blendOdds[i], document.calibration, marketBlendAlpha))
        }
    }

    /** 重みなど内部の値を含めない。 */
    override fun toString(): String = "VerifiedModel(${document.kind.json}, ${document.modelId}, ${schema.size} 次元)"

    companion object {
        /** [ModelLoader] からだけ呼ぶ。 */
        internal fun create(document: ModelDocument): VerifiedModel {
            val w = requireNotNull(document.weights()) { "線形モデルに重みが無い" }
            val schema = requireNotNull(document.featureSchema) { "線形モデルに feature_schema が無い" }
            require(w.size == schema.size) { "重みの長さがスキーマと違う" }
            return VerifiedModel(document, w, requireNotNull(document.bias), schema)
        }
    }
}

/** 特徴量ベクトルのスキーマがモデルのスキーマと違う。 */
class SchemaMismatchException(
    message: String,
) : IllegalArgumentException(message)

/** 検証済みの ensemble セット（メンバーの ID・β・coverage_weights）。メンバーのモデルは別に検証済みであること。 */
class VerifiedEnsembleSet internal constructor(
    val document: ModelDocument,
) {
    val spec: EnsembleSpec = requireNotNull(document.ensemble) { "ensemble が無い" }
    val modelId get() = document.modelId
    val sha256 get() = document.sha256
}
