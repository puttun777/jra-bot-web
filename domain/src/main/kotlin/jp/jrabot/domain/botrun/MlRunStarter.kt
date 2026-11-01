package jp.jrabot.domain.botrun

import jp.jrabot.domain.ml.MlInference
import jp.jrabot.domain.ml.ModelReader
import jp.jrabot.domain.ml.model.ModelKind
import jp.jrabot.domain.ml.model.ModelLoader
import jp.jrabot.domain.ml.pipeline.FallbackReason
import jp.jrabot.domain.ml.pipeline.ProbabilityModelType
import jp.jrabot.domain.ml.registry.EnsembleLoad
import jp.jrabot.domain.ml.registry.LoadedModels
import jp.jrabot.domain.ml.registry.ModelRegistryReader
import jp.jrabot.domain.ml.registry.ModelRunPin
import jp.jrabot.domain.ml.registry.ModelSession
import jp.jrabot.domain.ml.registry.RoleLoad
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Clock

/**
 * bot-run の開始時に、使う ML モデルを**1 回だけ固定**する（ML_DESIGN §6。run の途中で管理者が別のモデルを ACTIVE にしても、この run は固定した model_id を使い続ける）。
 *
 * 流れ: [ModelRunPin.capture]（各種別の ACTIVE を読んで固定）→ [ModelSession]（保管先から読み、**署名・sha256・feature_schema_hash・次元をレジストリの記録と照合**）→
 * [MlInference]。署名不正・ハッシュ不一致・成果物なしのモデルは `Missing(理由)` になり、パイプラインが**理由つきで**市場確率へフォールバックする
 * （黙って成功にしない。`race_evaluations.fallback_reason` に記録）。レジストリや保管先の障害は投げずに『使えない』ML として扱う（run を止めない）。
 *
 * ACTIVE への昇格・品質閾値・v2 の有効化はここでは一切しない（読むだけ）。
 */
object MlRunStarter {
    fun start(
        modelType: ProbabilityModelType?,
        tenantId: TenantId,
        registry: ModelRegistryReader,
        store: ModelReader,
        loader: ModelLoader,
        clock: Clock,
    ): MlRunContext {
        if (modelType == null) return MlRunContext.DISABLED
        return try {
            val pin = ModelRunPin.capture(registry, tenantId, clock)
            val loaded = ModelSession(pin, store, loader, registry).loaded
            val kind =
                when (modelType) {
                    ProbabilityModelType.LEGACY -> ModelKind.LEGACY_COMPAT
                    ProbabilityModelType.CALIBRATION -> ModelKind.CALIBRATION
                    ProbabilityModelType.ALPHA -> ModelKind.ALPHA
                    ProbabilityModelType.ENSEMBLE -> ModelKind.ENSEMBLE_SET
                }
            MlRunContext(modelType, MlInference(loaded), pin.record(kind)?.modelId?.value)
        } catch (e: RuntimeException) {
            // 例外の内容（保管先の URL などを含み得る）は載せず、型名だけ
            unavailable(modelType, FallbackReason.MODEL_STORAGE_ERROR, "ML の固定に失敗: ${e.javaClass.simpleName}")
        }
    }

    /** どのモデルも使えない ML（理由つき）。評価は理由を記録して市場確率へフォールバックする。 */
    fun unavailable(
        modelType: ProbabilityModelType,
        reason: FallbackReason,
        note: String,
    ): MlRunContext {
        val missing = RoleLoad.Missing(reason, note)
        val loaded = LoadedModels(missing, missing, missing, EnsembleLoad.Missing(reason, note))
        return MlRunContext(modelType, MlInference(loaded), null, note)
    }
}
