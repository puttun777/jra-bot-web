package jp.jrabot.domain.ml.model

import jp.jrabot.domain.ml.ModelArtifact
import jp.jrabot.domain.ml.ModelId
import jp.jrabot.domain.ml.json.CanonicalJson
import jp.jrabot.domain.ml.json.JsonFormatException
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.StrictJson
import jp.jrabot.domain.ml.pipeline.FallbackReason
import jp.jrabot.domain.tenant.TenantId

/** 読込みの結果。 */
sealed interface ModelLoadResult {
    data class Loaded(
        val model: VerifiedModel,
    ) : ModelLoadResult

    data class LoadedEnsemble(
        val set: VerifiedEnsembleSet,
    ) : ModelLoadResult

    data class Rejected(
        val reason: FallbackReason,
        val detail: String,
    ) : ModelLoadResult
}

/** レジストリが記録している期待値（あれば照合する。改ざん・差し替えの検出）。 */
data class ExpectedModel(
    val sha256: String? = null,
    val featureSchemaHash: String? = null,
    val kind: ModelKind? = null,
)

/**
 * モデル成果物の検証つき読込み。**fail closed**: 次のいずれかに当てはまればロードを拒否し、理由を返す（推論に使えない）:
 *
 * 1. 署名が無い・64 バイトでない・どの検証鍵でも検証できない → [FallbackReason.MODEL_SIGNATURE_INVALID]
 *    （**署名の検証は JSON の解析より前**。信頼できないバイト列を、署名が通る前に解釈しない）
 * 2. JSON が壊れている・正準形でない・必須項目が無い・未知のキーがある・型が違う → [FallbackReason.MODEL_INVALID]
 * 3. `model_schema_version` が未対応 → [FallbackReason.MODEL_SCHEMA_MISMATCH]
 * 4. `sha256` フィールドが内容と一致しない、またはレジストリの記録と違う → [FallbackReason.MODEL_HASH_INVALID]
 * 5. `feature_schema` のハッシュが内容と違う、種別が許さない、レジストリの記録と違う → [FallbackReason.FEATURE_SCHEMA_MISMATCH]
 * 6. 重みの長さがスキーマの次元と違う・前処理の長さが違う → [FallbackReason.INVALID_MODEL_DIMENSION]
 * 7. `tenant_id`・`model_id` が要求（パスの組立てに使った値）と違う（他テナントのモデルの混入）→ [FallbackReason.MODEL_INVALID]
 * NaN / Infinity は JSON として表現できず（[StrictJson] が拒否）、重みの検査でも拒否する。ログ・例外に重み・署名・鍵を載せない。
 */
class ModelLoader(
    private val trust: ModelTrust,
) {
    fun load(
        tenantId: TenantId,
        requestedId: ModelId,
        artifact: ModelArtifact,
        expected: ExpectedModel = ExpectedModel(),
    ): ModelLoadResult {
        if (artifact.modelId != requestedId) return rejected(FallbackReason.MODEL_INVALID, "成果物の model_id が要求と違う")
        val raw = artifact.json()
        if (raw.size > MAX_BYTES) return rejected(FallbackReason.MODEL_INVALID, "モデル JSON が大きすぎる")

        // 1. 署名（解析の前）
        if (!trust.verify(raw, artifact.signature())) return rejected(FallbackReason.MODEL_SIGNATURE_INVALID, "署名を検証できない")

        return try {
            // 2. 構文。正準形（ファイルのバイト列 = 正準 JSON）であること
            val tree =
                try {
                    StrictJson.parse(raw, MAX_BYTES) as? JsonObject ?: throw ModelRejectedException(FallbackReason.MODEL_INVALID, "トップレベルがオブジェクトでない")
                } catch (e: JsonFormatException) {
                    throw ModelRejectedException(FallbackReason.MODEL_INVALID, "JSON として不正")
                }
            if (!CanonicalJson.encodeBytes(tree).contentEquals(raw)) throw ModelRejectedException(FallbackReason.MODEL_INVALID, "正準 JSON ではない")

            // 3,5,6. 意味
            val doc = ModelDocument.parse(tree)

            // 4. sha256
            if (ModelDocument.computeSha256(tree) != doc.sha256) throw ModelRejectedException(FallbackReason.MODEL_HASH_INVALID, "sha256 が内容と一致しない")
            if (expected.sha256 != null &&
                expected.sha256 != doc.sha256
            ) {
                throw ModelRejectedException(FallbackReason.MODEL_HASH_INVALID, "レジストリの sha256 と一致しない")
            }

            // 7. 取り違え
            if (doc.tenantId != tenantId.value) throw ModelRejectedException(FallbackReason.MODEL_INVALID, "tenant_id が要求と違う")
            if (doc.modelId != requestedId) throw ModelRejectedException(FallbackReason.MODEL_INVALID, "model_id が要求と違う")
            if (expected.kind != null && expected.kind != doc.kind) throw ModelRejectedException(FallbackReason.MODEL_INVALID, "kind がレジストリと違う")
            if (expected.featureSchemaHash != null) {
                val actual = doc.featureSchema?.hash ?: ensembleSchemaHash(doc)
                if (expected.featureSchemaHash !=
                    actual
                ) {
                    throw ModelRejectedException(FallbackReason.FEATURE_SCHEMA_MISMATCH, "レジストリの feature_schema_hash と一致しない")
                }
            }

            if (doc.kind ==
                ModelKind.ENSEMBLE_SET
            ) {
                ModelLoadResult.LoadedEnsemble(VerifiedEnsembleSet(doc))
            } else {
                ModelLoadResult.Loaded(VerifiedModel.create(doc))
            }
        } catch (e: ModelRejectedException) {
            rejected(e.reason, e.message ?: "拒否")
        }
    }

    private fun rejected(
        reason: FallbackReason,
        detail: String,
    ): ModelLoadResult.Rejected = ModelLoadResult.Rejected(reason, detail)

    companion object {
        const val MAX_BYTES = 1024 * 1024

        /**
         * ensemble セットの `feature_schema_hash`（レジストリの列は CHAR(71) で NOT NULL）: メンバー構成（`ensemble` オブジェクト）の正準 JSON の SHA-256 に `sha256:` を付けたもの。
         * メンバーの ID・β・coverage_weights のどれかが変わると変わる。
         */
        fun ensembleSchemaHash(doc: ModelDocument): String = "sha256:" + CanonicalJson.sha256Hex(requireNotNull(doc.json["ensemble"]) { "ensemble が無い" })
    }
}
