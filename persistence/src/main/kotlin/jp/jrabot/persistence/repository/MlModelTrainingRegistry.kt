package jp.jrabot.persistence.repository

import jp.jrabot.domain.ml.ModelId
import jp.jrabot.domain.ml.model.ModelKind
import jp.jrabot.domain.ml.registry.ActiveModelMetrics
import jp.jrabot.domain.ml.registry.DuplicateModelException
import jp.jrabot.domain.ml.registry.ModelRecord
import jp.jrabot.domain.ml.registry.NewModelRecord
import jp.jrabot.domain.ml.registry.TrainingRegistry
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.persistence.dao.MlModelDao
import jp.jrabot.persistence.dao.MlModelDaoImpl
import jp.jrabot.persistence.dao.MlModelInsertEntity
import jp.jrabot.persistence.doma.jrabotMysqlDialect
import jp.jrabot.persistence.mapping.toDbTime
import org.seasar.doma.jdbc.Config
import org.seasar.doma.jdbc.dialect.Dialect
import javax.sql.DataSource

/**
 * 学習 Job（ml-train）のレジストリ（旧 Python の MySqlRegistry の移植）。**INSERT は CANDIDATE / REJECTED だけ**（[NewModelRecord] が検査）。
 * テナントは必ず SQL の条件・行に入れる。既存行の更新・削除はしない（DB ユーザー mltrain にも権限が無い）。
 */
internal class MlModelTrainingRegistry(
    private val dao: MlModelDao,
) : TrainingRegistry {
    private val reader = MlModelRegistryRepository(dao)

    override fun find(
        tenantId: TenantId,
        modelId: ModelId,
    ): ModelRecord? = reader.find(tenantId, modelId)

    override fun activeMetrics(
        tenantId: TenantId,
        kind: ModelKind,
    ): ActiveModelMetrics? =
        dao.selectActiveMetrics(tenantId.value, kind.json)?.let {
            ActiveModelMetrics(ModelId.ofOrNull(it.modelId) ?: error("ml_model.model_id の形式が不正"), it.metricsJson)
        }

    override fun insert(row: NewModelRecord) {
        try {
            dao.insert(
                MlModelInsertEntity(
                    tenantId = row.tenantId.value,
                    modelId = row.modelId.value,
                    kind = row.kind.json,
                    schemaVersion = row.schemaVersion,
                    featureSchemaHash = row.featureSchemaHash,
                    artifactUri = row.artifactUri,
                    sha256 = row.sha256,
                    signatureSha256 = row.signatureSha256,
                    status = row.status.name,
                    trainedFrom = row.trainedFrom,
                    trainedTo = row.trainedTo,
                    nTrain = row.nTrain,
                    metricsJson = row.metricsJson,
                    createdAt = row.createdAt.toDbTime(),
                ),
            )
        } catch (e: RuntimeException) {
            if (e.isDuplicateOn("ml_model", "PRIMARY")) throw DuplicateModelException("同じ model_id が登録済み")
            throw e
        }
    }
}

/**
 * ml-train から使う公開の入口（DAO・Entity・Doma は内部のまま。ml-train は Spring の DI を使わない）。
 * Doma の Config は [dataSource] とこのリポジトリの方言（UTC の時刻変換）だけを持つ。トランザクションは使わない
 * （SELECT と 1 行の INSERT だけで、各文は自動コミット。DataSourceFactory の既定）。
 */
object TrainingPersistence {
    fun trainingRegistry(dataSource: DataSource): TrainingRegistry {
        val dialect = jrabotMysqlDialect()
        val config =
            object : Config {
                override fun getDataSource(): DataSource = dataSource

                override fun getDialect(): Dialect = dialect
            }
        return MlModelTrainingRegistry(MlModelDaoImpl(config))
    }
}
