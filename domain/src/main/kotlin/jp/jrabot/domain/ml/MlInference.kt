package jp.jrabot.domain.ml

import jp.jrabot.domain.ml.features.FeatureSchema
import jp.jrabot.domain.ml.features.FeatureVector
import jp.jrabot.domain.ml.model.NonFiniteComputationException
import jp.jrabot.domain.ml.model.SchemaMismatchException
import jp.jrabot.domain.ml.model.VerifiedModel
import jp.jrabot.domain.ml.pipeline.FallbackReason
import jp.jrabot.domain.ml.pipeline.LegacyProbabilityPipeline
import jp.jrabot.domain.ml.pipeline.PipelineConfig
import jp.jrabot.domain.ml.pipeline.ProbabilityModelType
import jp.jrabot.domain.ml.pipeline.ProbabilityPipelineVersion
import jp.jrabot.domain.ml.pipeline.ProbabilityResult
import jp.jrabot.domain.ml.pipeline.RawOutputs
import jp.jrabot.domain.ml.pipeline.RoleOutput
import jp.jrabot.domain.ml.pipeline.V2ProbabilityPipeline
import jp.jrabot.domain.ml.registry.EnsembleLoad
import jp.jrabot.domain.ml.registry.LoadedModels
import jp.jrabot.domain.ml.registry.RoleLoad

/** 馬ごとの特徴量の供給（スキーマ指定）。旧互換は [jp.jrabot.domain.ml.features.LegacyFeatureBuilder]、新方式は [jp.jrabot.domain.ml.features.PointInTimeFeatureBuilder] を包む。 */
fun interface FeatureProvider {
    fun vector(
        schema: FeatureSchema,
        horseIndex: Int,
    ): FeatureVector
}

/** 推論の入力 1 頭分（オッズ・人気）。 */
data class HorseOdds(
    val umaban: Int,
    val odds: Double,
    val popularity: Int,
)

/**
 * 固定済みのモデル（[LoadedModels]：署名・sha256・スキーマ・次元を検証済み）で確率を計算する。検証前のモデルは型として渡せない。
 *
 * - 各モデルの特徴量は**そのモデルのスキーマ**で作る（alpha は 35 次元、calibration は 40 次元。スライス/ゼロ埋めで合わせない）。
 * - `legacy`: モデル内の市場ブレンドを α（実行時の上書き）で適用する（旧 H4）。alpha モデルの内部ブレンドに使うオッズは、旧実装がオッズ 0 で作った特徴量の 0 列目（100.0）と同じ定数。
 *   calibration / legacy_compat は特徴量の `odds`（0 以下は 100.0）。
 * - `v2`: モデル内の市場ブレンドは行わない（[RoleOutput.Scored.internalBlendAlpha] = null）。
 * - 特徴量の作成・推論で例外が出たモデルは [RoleOutput.Unavailable]（理由つき）にして、パイプラインが段階的にフォールバックする（黙って成功にしない）。
 * 時刻・IO を持たない（特徴量は [FeatureProvider] が供給する）。
 */
class MlInference(
    private val models: LoadedModels,
) {
    fun probabilities(
        version: ProbabilityPipelineVersion,
        modelType: ProbabilityModelType,
        mlEnabled: Boolean,
        horses: List<HorseOdds>,
        features: FeatureProvider,
        config: PipelineConfig,
    ): ProbabilityResult {
        val odds = horses.map { it.odds }
        if (!mlEnabled) return run(version, modelType, false, odds, RawOutputs(), null, config)

        val internalAlpha = if (version == ProbabilityPipelineVersion.LEGACY) config.marketBlendAlpha else null

        fun score(
            load: RoleLoad,
            alphaRole: Boolean,
        ): RoleOutput =
            when (load) {
                is RoleLoad.Missing -> RoleOutput.Unavailable(load.reason, load.detail)
                is RoleLoad.Ready -> scoreModel(load.model, horses, features, internalAlpha, alphaRole)
            }

        return when (modelType) {
            ProbabilityModelType.LEGACY -> {
                run(version, modelType, true, odds, RawOutputs(legacy = score(models.legacy, false)), null, config)
            }

            ProbabilityModelType.CALIBRATION -> {
                run(version, modelType, true, odds, RawOutputs(calibration = score(models.calibration, false)), null, config)
            }

            ProbabilityModelType.ALPHA -> {
                run(version, modelType, true, odds, RawOutputs(alpha = score(models.alpha, true)), null, config)
            }

            ProbabilityModelType.ENSEMBLE -> {
                when (val e = models.ensemble) {
                    is EnsembleLoad.Missing -> {
                        run(
                            version,
                            modelType,
                            true,
                            odds,
                            RawOutputs(calibration = RoleOutput.Unavailable(e.reason, e.detail), alpha = RoleOutput.Unavailable(e.reason, e.detail)),
                            null,
                            config,
                        )
                    }

                    is EnsembleLoad.Ready -> {
                        val raw = RawOutputs(calibration = score(e.calibration, false), alpha = score(e.alpha, true))
                        val weights = e.spec.coverageWeights
                        val coverage = coverage(e, features, weights, horses.size)
                        run(version, modelType, true, odds, raw, coverage, config.copy(ensembleBeta = e.spec.beta))
                    }
                }
            }
        }
    }

    private fun scoreModel(
        model: VerifiedModel,
        horses: List<HorseOdds>,
        features: FeatureProvider,
        internalAlpha: Double?,
        alphaRole: Boolean,
    ): RoleOutput =
        try {
            val rows = horses.indices.map { features.vector(model.schema, it) }
            val blendOdds =
                horses.mapIndexed { i, h ->
                    // 旧実装: 内部ブレンドのオッズは特徴量行列の 0 列目。calibration/legacy は odds（0 以下は 100.0 に置換済み）、alpha は 100.0 の定数
                    if (alphaRole || model.schema == FeatureSchema.ALPHA_35) 100.0 else rows[i][0]
                }
            val p = model.predict(rows, blendOdds, internalAlpha ?: 1.0)
            RoleOutput.Scored(p, internalAlpha)
        } catch (e: SchemaMismatchException) {
            RoleOutput.Unavailable(FallbackReason.FEATURE_SCHEMA_MISMATCH, "特徴量のスキーマがモデルと違う")
        } catch (e: NonFiniteComputationException) {
            RoleOutput.Unavailable(FallbackReason.MODEL_PREDICTION_FAILED, "計算が有限でない")
        } catch (e: RuntimeException) {
            // 特徴量の作成（例: 評価時点が発走後）・入力の不整合。例外の内容（実データを含み得る）は載せず、型名だけ
            RoleOutput.Unavailable(FallbackReason.MODEL_PREDICTION_FAILED, "推論に失敗: ${e.javaClass.simpleName}")
        }

    /** ensemble のカバレッジ（旧: 馬マスタ・過去走・過去走数から馬ごとに）。特徴量が作れなければ null（旧: coverage=1.0）。 */
    private fun coverage(
        e: EnsembleLoad.Ready,
        features: FeatureProvider,
        weights: List<Double>,
        n: Int,
    ): List<Double?> {
        val schema = (e.alpha as? RoleLoad.Ready)?.model?.schema ?: (e.calibration as? RoleLoad.Ready)?.model?.schema ?: return List(n) { null }
        return List(n) {
            try {
                features.vector(schema, it).meta.coverageScore(weights)
            } catch (ex: RuntimeException) {
                null
            }
        }
    }

    private fun run(
        version: ProbabilityPipelineVersion,
        modelType: ProbabilityModelType,
        mlEnabled: Boolean,
        odds: List<Double>,
        raw: RawOutputs,
        coverage: List<Double?>?,
        config: PipelineConfig,
    ): ProbabilityResult =
        when (version) {
            ProbabilityPipelineVersion.LEGACY -> LegacyProbabilityPipeline.run(modelType, mlEnabled, odds, raw, coverage, config)
            ProbabilityPipelineVersion.V2 -> V2ProbabilityPipeline.run(modelType, mlEnabled, odds, raw, coverage, config)
        }
}
