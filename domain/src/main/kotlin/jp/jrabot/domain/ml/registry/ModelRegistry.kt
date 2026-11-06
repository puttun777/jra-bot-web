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
 * **昇格（CANDIDATE → ACTIVE）の操作はここに無い**: 昇格は本人の判断（品質ゲートの閾値が未承認。dashboard も一覧の表示だけ: D-268）で、ml-train も ACTIVE にする権限を持たない（CONTRACTS §5.1・§8.2）。
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

/**
 * 学習 Job（ml-train）が登録する 1 行。**status は CANDIDATE か REJECTED だけ**（ACTIVE への昇格は管理者の操作。CONTRACTS §5.1・§8.2）。
 * [metricsJson] は `ml_model.metrics_json`（正準 JSON の文字列。値・資格情報を含まない指標だけ）。
 */
data class NewModelRecord(
    val tenantId: TenantId,
    val modelId: ModelId,
    val kind: ModelKind,
    val schemaVersion: Int,
    val featureSchemaHash: String,
    val artifactUri: String,
    val sha256: String,
    val signatureSha256: String,
    val status: ModelStatus,
    val trainedFrom: LocalDate?,
    val trainedTo: LocalDate?,
    val nTrain: Long,
    val metricsJson: String,
    val createdAt: Instant,
) {
    init {
        require(status == ModelStatus.CANDIDATE || status == ModelStatus.REJECTED) { "学習 Job が登録できるのは CANDIDATE / REJECTED だけ" }
        require(schemaVersion >= 1)
        require(Regex("sha256:[0-9a-f]{64}").matches(featureSchemaHash)) { "feature_schema_hash は sha256: + 64 桁" }
        require(Regex("[0-9a-f]{64}").matches(sha256) && Regex("[0-9a-f]{64}").matches(signatureSha256)) { "sha256 は 64 桁の小文字 16 進" }
        require(nTrain >= 0)
    }
}

/** ACTIVE のモデルの ID と `metrics_json`（NULL のこともある）。 */
data class ActiveModelMetrics(
    val modelId: ModelId,
    val metricsJson: String?,
)

/** 同じ (tenant_id, model_id) が既に登録されている（並行して同じモデルが登録された）。 */
class DuplicateModelException(
    message: String,
) : RuntimeException(message)

/**
 * 学習 Job が使うレジストリ（DB ユーザー mltrain は `ml_model` の SELECT / INSERT だけ）。既存行の更新・削除・昇格の操作は無い。
 * [ModelRegistryReader] を継承しない（bot 等の Spring の配線で同じ型の Bean が 2 つにならないように）。
 */
interface TrainingRegistry {
    /** 同じ model_id が登録済みか（冪等な再実行の判定）。 */
    fun find(
        tenantId: TenantId,
        modelId: ModelId,
    ): ModelRecord?

    /** その種別の ACTIVE のモデル（無ければ null）。品質ゲートの『直前の ACTIVE との比較』とレポートに使う。 */
    fun activeMetrics(
        tenantId: TenantId,
        kind: ModelKind,
    ): ActiveModelMetrics?

    /** 主キーの重複は [DuplicateModelException]。 */
    fun insert(row: NewModelRecord)
}

/**
 * 一覧の 1 行（dashboard の表示用。R22）。**表示のための生の値**で、[ModelRecord] の検査（ハッシュの形式・種別・状態の列挙）はしない
 * （1 行が想定外の値でも一覧全体を失敗させない: D-268 のレビュー 中）。[metricsJson] は `ml_model.metrics_json`（指標だけで値・資格情報を含まない）。
 */
data class ModelCatalogEntry(
    val modelId: String,
    val kind: String,
    val status: String,
    val schemaVersion: Int,
    val featureSchemaHash: String,
    val trainedFrom: LocalDate?,
    val trainedTo: LocalDate?,
    val nTrain: Long?,
    val createdAt: Instant,
    val activatedAt: Instant?,
    val activatedBy: String?,
    val previousModelId: String?,
    val metricsJson: String?,
)

/**
 * モデルの一覧の**読取りだけ**（R22: 管理画面での表示。D-268）。**有効化（ACTIVE への昇格）の操作は持たない**: 品質ゲートの閾値が未承認のため、
 * 昇格は本人の判断事項（CLAUDE.md・COMPLETION R22 の B）。テナントの範囲だけ。**ACTIVE の行を先頭に**、続けて新しい順（上限の外に有効なモデルが落ちないように）。
 */
interface ModelCatalogReader {
    fun recent(
        tenantId: TenantId,
        limit: Int,
    ): List<ModelCatalogEntry>
}
