package jp.jrabot.domain.ml.registry

import jp.jrabot.domain.ml.MissingSignatureException
import jp.jrabot.domain.ml.ModelId
import jp.jrabot.domain.ml.ModelReader
import jp.jrabot.domain.ml.StorageException
import jp.jrabot.domain.ml.model.EnsembleSpec
import jp.jrabot.domain.ml.model.ExpectedModel
import jp.jrabot.domain.ml.model.ModelKind
import jp.jrabot.domain.ml.model.ModelLoadResult
import jp.jrabot.domain.ml.model.ModelLoader
import jp.jrabot.domain.ml.model.VerifiedEnsembleSet
import jp.jrabot.domain.ml.model.VerifiedModel
import jp.jrabot.domain.ml.pipeline.FallbackReason

/** 1 つのモデルの取得結果。 */
sealed interface RoleLoad {
    data class Ready(
        val model: VerifiedModel,
    ) : RoleLoad

    data class Missing(
        val reason: FallbackReason,
        val detail: String,
    ) : RoleLoad
}

/** 固定したモデル群を、保存先から検証つきで読んだもの（run の間は不変）。 */
class LoadedModels(
    val legacy: RoleLoad,
    val calibration: RoleLoad,
    val alpha: RoleLoad,
    /** ensemble セットから読んだメンバー（calibration / alpha）と β・coverage_weights。セットが無い/読めないときは null。 */
    val ensemble: EnsembleLoad,
)

sealed interface EnsembleLoad {
    data class Ready(
        val set: VerifiedEnsembleSet,
        val calibration: RoleLoad,
        val alpha: RoleLoad,
    ) : EnsembleLoad {
        val spec: EnsembleSpec get() = set.spec
    }

    data class Missing(
        val reason: FallbackReason,
        val detail: String,
    ) : EnsembleLoad
}

/**
 * [ModelRunPin] で固定したモデルを、[ModelReader]（`MODEL_STORE_URI` の成果物）から読んで [ModelLoader] で検証する。
 * **レジストリの記録（sha256・feature_schema_hash・kind）と成果物を照合**し、署名・ハッシュ・スキーマ・次元のどれかが不正なら読み込みを拒否して理由を返す（黙って成功にしない）。
 * 読んだ結果は不変で、同じ [ModelSession] の中で再読込しない（run の間はモデルが変わらない）。
 */
class ModelSession(
    private val pin: ModelRunPin,
    private val store: ModelReader,
    private val loader: ModelLoader,
    private val registry: ModelRegistryReader,
) {
    val loaded: LoadedModels by lazy {
        LoadedModels(
            legacy = linear(ModelKind.LEGACY_COMPAT),
            calibration = linear(ModelKind.CALIBRATION),
            alpha = linear(ModelKind.ALPHA),
            ensemble = ensemble(),
        )
    }

    private fun linear(kind: ModelKind): RoleLoad {
        val rec = pin.record(kind) ?: return RoleLoad.Missing(FallbackReason.MODEL_NOT_ACTIVE, "${kind.json} の ACTIVE モデルが無い")
        return loadRecord(rec).let { r ->
            when (r) {
                is ModelLoadResult.Loaded -> RoleLoad.Ready(r.model)
                is ModelLoadResult.Rejected -> RoleLoad.Missing(r.reason, r.detail)
                is ModelLoadResult.LoadedEnsemble -> RoleLoad.Missing(FallbackReason.MODEL_INVALID, "種別が違う成果物")
            }
        }
    }

    private fun loadRecord(rec: ModelRecord): ModelLoadResult =
        try {
            val artifact = store.read(pin.tenantId, rec.modelId)
            if (artifact == null) {
                ModelLoadResult.Rejected(FallbackReason.MODEL_NOT_FOUND, "成果物が無い")
            } else {
                loader.load(pin.tenantId, rec.modelId, artifact, ExpectedModel(rec.sha256, rec.featureSchemaHash, rec.kind))
            }
        } catch (e: MissingSignatureException) {
            ModelLoadResult.Rejected(FallbackReason.MODEL_SIGNATURE_INVALID, "署名ファイルが無い")
        } catch (e: StorageException) {
            ModelLoadResult.Rejected(FallbackReason.MODEL_STORAGE_ERROR, "保存先の I/O に失敗")
        }

    private fun ensemble(): EnsembleLoad {
        val rec =
            pin.record(ModelKind.ENSEMBLE_SET)
                ?: return EnsembleLoad.Missing(FallbackReason.MODEL_NOT_ACTIVE, "ensemble_set の ACTIVE モデルが無い")
        val set =
            when (val r = loadRecord(rec)) {
                is ModelLoadResult.LoadedEnsemble -> r.set
                is ModelLoadResult.Rejected -> return EnsembleLoad.Missing(r.reason, r.detail)
                is ModelLoadResult.Loaded -> return EnsembleLoad.Missing(FallbackReason.MODEL_INVALID, "種別が違う成果物")
            }
        return EnsembleLoad.Ready(set, member(set.spec.calibrationModelId, ModelKind.CALIBRATION), member(set.spec.alphaModelId, ModelKind.ALPHA))
    }

    /** メンバーは、レジストリに記録があり REJECTED でないことを要求し、記録（sha256・スキーマのハッシュ・種別）と照合する。 */
    private fun member(
        id: ModelId,
        kind: ModelKind,
    ): RoleLoad {
        val rec = registry.find(pin.tenantId, id) ?: return RoleLoad.Missing(FallbackReason.MODEL_NOT_FOUND, "メンバーがレジストリに無い: ${kind.json}")
        if (rec.status == ModelStatus.REJECTED) return RoleLoad.Missing(FallbackReason.MODEL_NOT_ACTIVE, "メンバーが REJECTED: ${kind.json}")
        return when (val r = loadRecord(rec)) {
            is ModelLoadResult.Loaded -> if (r.model.kind == kind) RoleLoad.Ready(r.model) else RoleLoad.Missing(FallbackReason.MODEL_INVALID, "メンバーの種別が違う")
            is ModelLoadResult.Rejected -> RoleLoad.Missing(r.reason, r.detail)
            is ModelLoadResult.LoadedEnsemble -> RoleLoad.Missing(FallbackReason.MODEL_INVALID, "メンバーが ensemble")
        }
    }
}
