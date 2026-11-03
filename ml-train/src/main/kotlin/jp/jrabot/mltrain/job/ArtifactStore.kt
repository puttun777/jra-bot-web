package jp.jrabot.mltrain.job

import jp.jrabot.domain.ml.ArtifactBlobStore
import jp.jrabot.domain.ml.ArtifactWriteOutcome
import jp.jrabot.domain.ml.StorageException
import jp.jrabot.domain.ml.StoragePermissionException

/** 保存先の I/O の失敗（一時的な失敗: exit 75）。 */
class StoreError(
    message: String,
    cause: Throwable? = null,
) : TransientError("STORAGE_ERROR", message, cause)

/** 同じキーに内容の異なる成果物が既にある（上書きしない）。 */
class ArtifactConflict(
    key: String,
) : MlTrainError("ARTIFACT_CONFLICT", "同じキーに内容の異なる成果物が既にある: $key")

/** この実行が新しく作ったキー（失敗時の cleanup の対象）。 */
class Written {
    val created: MutableList<String> = ArrayList()
}

/**
 * 成果物の配置（旧 ml/src/ml_train/storage.py の ArtifactStore の移植。推論側の `ModelStore` / `DatasetStore` と同じ配置
 * `{MODEL_STORE_URI}/{tenant_id}/{datasets|models|reports}/…`）。
 * モデルは **`.sig` → レポート → `.json`** の順（JSON が確定の印）。失敗時は、この実行が作ったものだけを消す（[cleanup]）。
 */
class ArtifactStore(
    private val blob: ArtifactBlobStore,
) {
    private inline fun <T> io(block: () -> T): T =
        try {
            block()
        } catch (e: StoragePermissionException) {
            throw MlTrainError("STORAGE_PERMISSION_DENIED", e.message ?: "保存先の権限が無い", cause = e)
        } catch (e: StorageException) {
            throw StoreError(e.message ?: "保存先の I/O に失敗", e)
        }

    fun datasetKeys(
        tenantId: Long,
        datasetId: String,
    ): Pair<String, String> = "$tenantId/datasets/$datasetId.manifest.json" to "$tenantId/datasets/$datasetId.csv.gz"

    /** (manifest, csv.gz)。manifest が無ければ null（manifest が確定の印。CSV だけの途中状態は『まだ無い』）。 */
    fun readDataset(
        tenantId: Long,
        datasetId: String,
    ): Pair<ByteArray, ByteArray>? {
        val (mk, ck) = datasetKeys(tenantId, datasetId)
        val manifest = io { blob.get(mk) } ?: return null
        val csv = io { blob.get(ck) } ?: throw StoreError("manifest があるのにデータセット本体が無い")
        return manifest to csv
    }

    fun modelKeys(
        tenantId: Long,
        modelId: String,
    ): Pair<String, String> = "$tenantId/models/$modelId.json" to "$tenantId/models/$modelId.sig"

    fun reportKey(
        tenantId: Long,
        modelId: String,
    ): String = "$tenantId/reports/$modelId.report.json"

    fun artifactUri(
        tenantId: Long,
        modelId: String,
    ): String = "${blob.uriPrefix}/$tenantId/models/$modelId.json"

    fun readModel(
        tenantId: Long,
        modelId: String,
    ): Pair<ByteArray, ByteArray>? {
        val (jk, sk) = modelKeys(tenantId, modelId)
        val raw = io { blob.get(jk) } ?: return null
        val sig = io { blob.get(sk) } ?: throw StoreError("署名ファイルが無い")
        return raw to sig
    }

    fun writeModel(
        tenantId: Long,
        modelId: String,
        raw: ByteArray,
        sig: ByteArray,
        report: ByteArray,
        written: Written,
    ) {
        val (jk, sk) = modelKeys(tenantId, modelId)
        for ((key, data, type) in listOf(
            Triple(sk, sig, "application/octet-stream"),
            Triple(reportKey(tenantId, modelId), report, "application/json"),
            Triple(jk, raw, "application/json"),
        )) {
            when (io { blob.putIfAbsent(key, data, type) }) {
                ArtifactWriteOutcome.CONFLICT -> {
                    throw ArtifactConflict(key)
                }

                ArtifactWriteOutcome.CREATED -> {
                    written.created.add(key)
                }

                ArtifactWriteOutcome.UNCHANGED -> {}
            }
        }
    }

    /** この実行が作ったものだけを消す（`.json` を先に消して確定の印を外す）。消せなかったキーを返す。 */
    fun cleanup(written: Written): List<String> {
        val failed = ArrayList<String>()
        for (key in written.created.sortedBy { !it.endsWith(".json") }) {
            try {
                blob.delete(key)
            } catch (_: StorageException) {
                failed.add(key)
            }
        }
        written.created.clear()
        return failed
    }
}
