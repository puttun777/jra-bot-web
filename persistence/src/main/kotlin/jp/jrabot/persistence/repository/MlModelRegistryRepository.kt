package jp.jrabot.persistence.repository

import jp.jrabot.domain.ml.ModelId
import jp.jrabot.domain.ml.model.ModelKind
import jp.jrabot.domain.ml.registry.ModelCatalogEntry
import jp.jrabot.domain.ml.registry.ModelCatalogReader
import jp.jrabot.domain.ml.registry.ModelRecord
import jp.jrabot.domain.ml.registry.ModelRegistryReader
import jp.jrabot.domain.ml.registry.ModelStatus
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.persistence.dao.MlModelDao
import jp.jrabot.persistence.dao.MlModelEntity
import jp.jrabot.persistence.mapping.enumFromDb
import jp.jrabot.persistence.mapping.toInstantUtc
import jp.jrabot.persistence.mapping.toInstantUtcOrNull

/**
 * `ml_model` の読取りだけ（[ModelRegistryReader]）。テナントは必ず SQL の条件に入れる（他テナントのモデルを返さない）。
 * ACTIVE への昇格・退役の書込みは持たない（管理者操作は Stage 4。ml-train は CANDIDATE の INSERT だけ）。
 */
internal class MlModelRegistryRepository(
    private val dao: MlModelDao,
) : ModelRegistryReader {
    override fun find(
        tenantId: TenantId,
        modelId: ModelId,
    ): ModelRecord? = dao.selectById(tenantId.value, modelId.value)?.toDomain()

    override fun active(
        tenantId: TenantId,
        kind: ModelKind,
    ): ModelRecord? = dao.selectActive(tenantId.value, kind.json)?.toDomain()
}

/** 一覧の読取りだけ（R22。D-268）。昇格の操作は持たない。 */
internal class MlModelCatalogRepository(
    private val dao: MlModelDao,
) : ModelCatalogReader {
    override fun recent(
        tenantId: TenantId,
        limit: Int,
    ): List<ModelCatalogEntry> {
        require(limit in 1..MAX_LIMIT) { "limit は 1〜$MAX_LIMIT" }
        return dao.selectRecent(tenantId.value, limit).map { e ->
            // 表示用の生の値（ModelRecord の検査はしない: 想定外の 1 行で一覧全体を失敗させない。D-268 のレビュー 中）
            ModelCatalogEntry(
                modelId = e.modelId,
                kind = e.kind,
                status = e.status,
                schemaVersion = e.schemaVersion,
                featureSchemaHash = e.featureSchemaHash,
                trainedFrom = e.trainedFrom,
                trainedTo = e.trainedTo,
                nTrain = e.nTrain,
                createdAt = e.createdAt.toInstantUtc(),
                activatedAt = e.activatedAt.toInstantUtcOrNull(),
                activatedBy = e.activatedBy,
                previousModelId = e.previousModelId,
                metricsJson = e.metricsJson,
            )
        }
    }

    companion object {
        const val MAX_LIMIT = 200
    }
}

private fun MlModelEntity.toDomain(): ModelRecord =
    ModelRecord(
        tenantId = TenantId.of(tenantId),
        modelId = ModelId.of(modelId),
        kind = ModelKind.fromJson(kind) ?: throw IllegalStateException("ml_model.kind の値 '$kind' がドメインの ModelKind に無い（DDL とドメインの食い違い）"),
        schemaVersion = schemaVersion,
        featureSchemaHash = featureSchemaHash,
        artifactUri = artifactUri,
        sha256 = sha256,
        signatureSha256 = signatureSha256,
        status = enumFromDb<ModelStatus>(status, "ml_model.status"),
        trainedFrom = trainedFrom,
        trainedTo = trainedTo,
        nTrain = nTrain,
        createdAt = createdAt.toInstantUtc(),
        activatedAt = activatedAt.toInstantUtcOrNull(),
        activatedBy = activatedBy,
        previousModelId = previousModelId?.let { ModelId.of(it) },
    )
