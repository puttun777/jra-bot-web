package jp.jrabot.platform.store

import jp.jrabot.domain.ml.ArtifactWriteOutcome
import jp.jrabot.domain.ml.DatasetDescriptor
import jp.jrabot.domain.ml.DatasetId
import jp.jrabot.domain.ml.DatasetStore
import jp.jrabot.domain.ml.DatasetWriteResult
import jp.jrabot.domain.ml.ModelStore
import jp.jrabot.domain.ml.StorageException
import jp.jrabot.domain.tenant.TenantId
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * オブジェクトストレージ（GCS・S3）の [DatasetStore]。配置は `<scheme>://<bucket>/<prefix>{tenant_id}/datasets/{dataset_id}.csv.gz` と `{dataset_id}.manifest.json`（CONTRACTS §5.1）。
 * データセットは不変: CSV は作成のみ（既存があれば [DatasetWriteResult.AlreadyExists]）、manifest は CSV の後に書く確定の印（同一内容なら UNCHANGED）。
 * 1 件の大きさは [MAX_BYTES] まで（メモリに載せる。超えたら [StorageException]）。**実バケット・IAM では未検証**（Stage 5）。
 * 保持期間 90 日はバケットのライフサイクルで設定する（infra。ここでは削除しない）。
 */
class ObjectDatasetStore(
    private val location: ObjectStoreLocation,
    private val client: ObjectClient,
) : DatasetStore {
    private fun csvKey(
        tenantId: TenantId,
        datasetId: DatasetId,
    ) = "${location.prefix}${tenantId.value}/datasets/${datasetId.value}.csv.gz"

    private fun manifestKey(
        tenantId: TenantId,
        datasetId: DatasetId,
    ) = "${location.prefix}${tenantId.value}/datasets/${datasetId.value}.manifest.json"

    override fun open(
        tenantId: TenantId,
        datasetId: DatasetId,
    ): InputStream? = io("データセットの読込み") { client.read(location.bucket, csvKey(tenantId, datasetId)) }?.let { ByteArrayInputStream(it) }

    override fun readManifest(
        tenantId: TenantId,
        datasetId: DatasetId,
    ): ByteArray? = io("manifest の読込み") { client.read(location.bucket, manifestKey(tenantId, datasetId)) }

    override fun write(
        tenantId: TenantId,
        datasetId: DatasetId,
        content: InputStream,
    ): DatasetWriteResult {
        val bytes = content.use { it.readNBytes(MAX_BYTES + 1) }
        if (bytes.size > MAX_BYTES) throw StorageException("データセットが大きすぎる（上限 $MAX_BYTES バイト）")
        val created = io("データセットの書込み") { client.createIfAbsent(location.bucket, csvKey(tenantId, datasetId), bytes, "application/gzip") }
        if (!created) return DatasetWriteResult.AlreadyExists
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return DatasetWriteResult.Created(DatasetDescriptor(datasetId, bytes.size.toLong(), sha))
    }

    override fun writeManifest(
        tenantId: TenantId,
        datasetId: DatasetId,
        manifest: ByteArray,
    ): ArtifactWriteOutcome =
        io("manifest の書込み") {
            val key = manifestKey(tenantId, datasetId)
            if (client.createIfAbsent(location.bucket, key, manifest, "application/json")) {
                ArtifactWriteOutcome.CREATED
            } else if (client.read(location.bucket, key)?.contentEquals(manifest) == true) {
                ArtifactWriteOutcome.UNCHANGED
            } else {
                ArtifactWriteOutcome.CONFLICT
            }
        }

    companion object {
        const val MAX_BYTES = 256 * 1024 * 1024
    }
}
