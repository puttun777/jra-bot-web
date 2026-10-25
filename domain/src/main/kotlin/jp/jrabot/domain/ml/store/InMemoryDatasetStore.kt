package jp.jrabot.domain.ml.store

import jp.jrabot.domain.ml.ArtifactWriteOutcome
import jp.jrabot.domain.ml.DatasetDescriptor
import jp.jrabot.domain.ml.DatasetId
import jp.jrabot.domain.ml.DatasetStore
import jp.jrabot.domain.ml.DatasetWriteResult
import jp.jrabot.domain.tenant.TenantId
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

/** メモリ上の [DatasetStore]（単体テスト・合成データ用）。テナントごとに別の表を持つ。 */
class InMemoryDatasetStore : DatasetStore {
    private val csvs = HashMap<Pair<Long, String>, ByteArray>()
    private val manifests = HashMap<Pair<Long, String>, ByteArray>()

    private fun key(
        t: TenantId,
        d: DatasetId,
    ) = t.value to d.value

    override fun open(
        tenantId: TenantId,
        datasetId: DatasetId,
    ): InputStream? = csvs[key(tenantId, datasetId)]?.let { ByteArrayInputStream(it) }

    override fun readManifest(
        tenantId: TenantId,
        datasetId: DatasetId,
    ): ByteArray? = manifests[key(tenantId, datasetId)]?.copyOf()

    override fun write(
        tenantId: TenantId,
        datasetId: DatasetId,
        content: InputStream,
    ): DatasetWriteResult {
        val bytes = content.use { it.readAllBytes() }
        val k = key(tenantId, datasetId)
        if (k in csvs) return DatasetWriteResult.AlreadyExists
        csvs[k] = bytes
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return DatasetWriteResult.Created(DatasetDescriptor(datasetId, bytes.size.toLong(), sha))
    }

    override fun writeManifest(
        tenantId: TenantId,
        datasetId: DatasetId,
        manifest: ByteArray,
    ): ArtifactWriteOutcome {
        val k = key(tenantId, datasetId)
        val existing = manifests[k]
        if (existing != null) return if (existing.contentEquals(manifest)) ArtifactWriteOutcome.UNCHANGED else ArtifactWriteOutcome.CONFLICT
        manifests[k] = manifest.copyOf()
        return ArtifactWriteOutcome.CREATED
    }

    /** テスト用: CSV だけを置く（manifest 前の途中状態の再現）。 */
    fun putCsvOnly(
        tenantId: TenantId,
        datasetId: DatasetId,
        gz: ByteArray,
    ) {
        csvs[key(tenantId, datasetId)] = gz.copyOf()
    }

    fun hasCsv(
        tenantId: TenantId,
        datasetId: DatasetId,
    ): Boolean = key(tenantId, datasetId) in csvs
}
