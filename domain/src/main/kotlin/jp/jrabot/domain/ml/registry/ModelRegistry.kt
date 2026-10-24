package jp.jrabot.domain.ml.registry

import jp.jrabot.domain.ml.ModelId
import jp.jrabot.domain.ml.model.ModelKind
import jp.jrabot.domain.tenant.TenantId
import java.time.Instant
import java.time.LocalDate

/** `ml_model.status`（ML_DESIGN §6）。状態遷移 CANDIDATE → ACTIVE → RETIRED、品質ゲート不合格は REJECTED。 */
enum class ModelStatus { CANDIDATE, ACTIVE, RETIRED, REJECTED }

/**
 * `ml_model` の 1 行（V2 の DDL に対応）。ACTIVE の行は `activatedAt` を持つ（DDL の CHECK と同じ）。
 * [featureSchemaHash] は `sha256:` + 64 桁（CHAR(71)）、[sha256]・[signatureSha256] は 64 桁の小文字 16 進。
 */
data class ModelRecord(
    val tenantId: TenantId,
    val modelId: ModelId,
    val kind: ModelKind,
    val schemaVersion: Int,
    val featureSchemaHash: String,
    val artifactUri: String,
    val sha256: String,
    val signatureSha256: String,
    val status: ModelStatus,
    val trainedFrom: LocalDate? = null,
    val trainedTo: LocalDate? = null,
    val nTrain: Long? = null,
    val createdAt: Instant,
    val activatedAt: Instant? = null,
    val activatedBy: String? = null,
    val previousModelId: ModelId? = null,
) {
    init {
        require(schemaVersion >= 1) { "schema_version は 1 以上" }
        require(HASH71.matches(featureSchemaHash)) { "feature_schema_hash は sha256: + 64 桁" }
        require(HEX64.matches(sha256) && HEX64.matches(signatureSha256)) { "sha256 は 64 桁の小文字 16 進" }
        require(status != ModelStatus.ACTIVE || activatedAt != null) { "ACTIVE の行は activatedAt が必要" }
    }

    val isActive: Boolean get() = status == ModelStatus.ACTIVE

    private companion object {
        val HEX64 = Regex("[0-9a-f]{64}")
        val HASH71 = Regex("sha256:[0-9a-f]{64}")
    }
}

/**
 * モデルレジストリの**読取り**（bot が使う）。DB の `ml_model` の `app`/`bot` の SELECT に対応する。
 * **昇格（CANDIDATE → ACTIVE）の操作はここに無い**: 管理者の昇格操作は Stage 4（dashboard）で、ml-train も ACTIVE にする権限を持たない（CONTRACTS §5.1・§8.2）。
 * 未承認のモデルを自動で ACTIVE にしない。
 */
interface ModelRegistryReader {
    fun find(
        tenantId: TenantId,
        modelId: ModelId,
    ): ModelRecord?

    /** その種別の ACTIVE のモデル（`UNIQUE(tenant_id, kind, active_flag)` により高々 1 つ）。 */
    fun active(
        tenantId: TenantId,
        kind: ModelKind,
    ): ModelRecord?
}
