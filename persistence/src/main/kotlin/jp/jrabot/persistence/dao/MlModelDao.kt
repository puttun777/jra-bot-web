package jp.jrabot.persistence.dao

import org.seasar.doma.Dao
import org.seasar.doma.Entity
import org.seasar.doma.Id
import org.seasar.doma.Insert
import org.seasar.doma.Select
import org.seasar.doma.Table
import org.seasar.doma.jdbc.entity.NamingType
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * `ml_model` の読取り（bot・dashboard・ingest は SELECT のみ。**昇格（UPDATE）は Stage 4 の dashboard の管理操作で、ここには無い**）。
 * `metrics_json`（JSON）と `active_flag` は読まない（bot の推論に不要。有効かどうかは `status` で見る）。
 */
@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "ml_model")
internal data class MlModelEntity(
    @Id val tenantId: Long,
    @Id val modelId: String,
    val kind: String,
    val schemaVersion: Int,
    val featureSchemaHash: String,
    val artifactUri: String,
    val sha256: String,
    val signatureSha256: String,
    val status: String,
    val trainedFrom: LocalDate?,
    val trainedTo: LocalDate?,
    val nTrain: Long?,
    val createdAt: LocalDateTime,
    val activatedAt: LocalDateTime?,
    val activatedBy: String?,
    val previousModelId: String?,
)

/** 一覧の表示用（R22。D-268）: [MlModelEntity] の列に metrics_json を加えたもの。 */
@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "ml_model")
internal data class MlModelCatalogEntity(
    @Id val tenantId: Long,
    @Id val modelId: String,
    val kind: String,
    val schemaVersion: Int,
    val featureSchemaHash: String,
    val artifactUri: String,
    val sha256: String,
    val signatureSha256: String,
    val status: String,
    val trainedFrom: LocalDate?,
    val trainedTo: LocalDate?,
    val nTrain: Long?,
    val createdAt: LocalDateTime,
    val activatedAt: LocalDateTime?,
    val activatedBy: String?,
    val previousModelId: String?,
    val metricsJson: String?,
)

/** 学習 Job が INSERT する列（active_flag・activated_* は書かない = NULL）。 */
@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "ml_model")
internal data class MlModelInsertEntity(
    @Id val tenantId: Long,
    @Id val modelId: String,
    val kind: String,
    val schemaVersion: Int,
    val featureSchemaHash: String,
    val artifactUri: String,
    val sha256: String,
    val signatureSha256: String,
    val status: String,
    val trainedFrom: LocalDate?,
    val trainedTo: LocalDate?,
    val nTrain: Long?,
    val metricsJson: String,
    val createdAt: LocalDateTime,
)

@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
internal data class MlModelActiveMetricsEntity(
    val modelId: String,
    val metricsJson: String?,
)

@Dao
internal interface MlModelDao {
    @Select
    fun selectById(
        tenantId: Long,
        modelId: String,
    ): MlModelEntity?

    /** ACTIVE のモデルの model_id と metrics_json（学習 Job の品質ゲートの比較に使う。無ければ null）。 */
    @Select
    fun selectActiveMetrics(
        tenantId: Long,
        kind: String,
    ): MlModelActiveMetricsEntity?

    /** 学習 Job の登録（mltrain は INSERT だけ。status は CANDIDATE / REJECTED。主キーの重複は例外）。 */
    @Insert
    fun insert(entity: MlModelInsertEntity): org.seasar.doma.jdbc.Result<MlModelInsertEntity>

    /** 新しい順の一覧（dashboard の表示用。テナントの範囲だけ。R22）。 */
    @Select
    fun selectRecent(
        tenantId: Long,
        limit: Int,
    ): List<MlModelCatalogEntity>

    /** 有効（ACTIVE）なモデル。`UNIQUE(tenant_id, kind, active_flag)` により高々 1 行。 */
    @Select
    fun selectActive(
        tenantId: Long,
        kind: String,
    ): MlModelEntity?
}
