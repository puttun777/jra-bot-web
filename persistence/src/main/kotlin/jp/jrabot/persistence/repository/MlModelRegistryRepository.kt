package jp.jrabot.persistence.repository

import jp.jrabot.domain.ml.ModelId
import jp.jrabot.domain.ml.model.ModelKind
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
