package jp.jrabot.domain.ml.store

import jp.jrabot.domain.ml.ArtifactWriteOutcome
import jp.jrabot.domain.ml.DatasetDescriptor
import jp.jrabot.domain.ml.DatasetId
import jp.jrabot.domain.ml.DatasetStore
import jp.jrabot.domain.ml.DatasetWriteResult
import jp.jrabot.domain.ml.StorageException
import jp.jrabot.domain.tenant.TenantId
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * ローカルのディレクトリに置く [DatasetStore]（`APP_ENV=local`・合成データの疎通検証・結合テスト用。**実バケット（GCS）には書かない**）。
 * 配置は本番と同じ `<root>/{tenant_id}/datasets/{dataset_id}.csv.gz` と `{dataset_id}.manifest.json`。パスは [TenantId] と [DatasetId] だけから組み立てる。
 * データセットは不変: CSV は作成のみ（既存があれば [DatasetWriteResult.AlreadyExists]）。manifest は CSV の後に書く確定の印。
 * [DatasetDescriptor.sha256] は**保存した（gzip の）バイト列**の SHA-256 で、manifest の `csv.sha256`（非圧縮 CSV）とは別物。
 */
class LocalFileDatasetStore(
    private val root: Path,
) : DatasetStore {
    private fun dir(tenantId: TenantId): Path = root.resolve(tenantId.value.toString()).resolve("datasets")

    private fun csv(
        tenantId: TenantId,
        datasetId: DatasetId,
    ): Path = dir(tenantId).resolve("${datasetId.value}.csv.gz")

    private fun manifest(
        tenantId: TenantId,
        datasetId: DatasetId,
    ): Path = dir(tenantId).resolve("${datasetId.value}.manifest.json")

    override fun open(
        tenantId: TenantId,
        datasetId: DatasetId,
    ): InputStream? =
        try {
            csv(tenantId, datasetId).takeIf { Files.isRegularFile(it) }?.let { ByteArrayInputStream(Files.readAllBytes(it)) }
        } catch (e: IOException) {
            throw StorageException("データセットの読込みに失敗", e)
        }

    override fun readManifest(
        tenantId: TenantId,
        datasetId: DatasetId,
    ): ByteArray? =
        try {
            manifest(tenantId, datasetId).takeIf { Files.isRegularFile(it) }?.let { Files.readAllBytes(it) }
        } catch (e: IOException) {
            throw StorageException("manifest の読込みに失敗", e)
        }

    override fun write(
        tenantId: TenantId,
        datasetId: DatasetId,
        content: InputStream,
    ): DatasetWriteResult {
        val target = csv(tenantId, datasetId)
        try {
            val bytes = content.use { it.readAllBytes() }
            Files.createDirectories(dir(tenantId))
            if (Files.exists(target)) return DatasetWriteResult.AlreadyExists
            val tmp = Files.createTempFile(dir(tenantId), ".csv", ".tmp")
            try {
                Files.write(tmp, bytes)
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(tmp)
            }
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return DatasetWriteResult.Created(DatasetDescriptor(datasetId, bytes.size.toLong(), sha))
        } catch (e: IOException) {
            throw StorageException("データセットの書込みに失敗", e)
        }
    }

    override fun writeManifest(
        tenantId: TenantId,
        datasetId: DatasetId,
        manifest: ByteArray,
    ): ArtifactWriteOutcome {
        val target = manifest(tenantId, datasetId)
        try {
            Files.createDirectories(dir(tenantId))
            if (Files.exists(target)) {
                return if (Files.readAllBytes(target).contentEquals(manifest)) ArtifactWriteOutcome.UNCHANGED else ArtifactWriteOutcome.CONFLICT
            }
            val tmp = Files.createTempFile(dir(tenantId), ".manifest", ".tmp")
            try {
                Files.write(tmp, manifest)
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(tmp)
            }
            return ArtifactWriteOutcome.CREATED
        } catch (e: IOException) {
            throw StorageException("manifest の書込みに失敗", e)
        }
    }
}
