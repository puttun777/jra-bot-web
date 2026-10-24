package jp.jrabot.cloudgcp.store

import jp.jrabot.domain.ml.ArtifactWriteOutcome
import jp.jrabot.domain.ml.MissingSignatureException
import jp.jrabot.domain.ml.ModelArtifact
import jp.jrabot.domain.ml.ModelId
import jp.jrabot.domain.ml.ModelStore
import jp.jrabot.domain.ml.StorageException
import jp.jrabot.domain.tenant.TenantId

/**
 * Cloud Storage の [ModelStore]。配置は `gs://<bucket>/<prefix>{tenant_id}/models/{model_id}.json` と `.sig`（CONTRACTS §5.1）。
 * パスは [TenantId] と [ModelId]（英数字・`_`・`-`）だけから組み立てる（パストラバーサルなし）。
 *
 * - 読取りは検証しない（署名・sha256・スキーマの検証は `ModelLoader`。**取得後に必ず検証する**）。`.json` があって `.sig` が無ければ [MissingSignatureException]。
 * - 書込みは作成のみ: `.sig` を先、`.json` を最後（JSON が確定の印）。既存と同一内容なら UNCHANGED、異なれば CONFLICT（上書きしない）。
 *   JSON の作成に失敗したら、この呼び出しが作った `.sig` を片付ける（best effort。削除権限が無ければ残る。JSON が無いので読まれない）。
 * - I/O の失敗（到達不能・権限・タイムアウト）は [StorageException]（Job としては一時的な失敗: exit 75）。**実バケット・IAM では未検証**（Stage 5）。
 */
class GcsModelStore internal constructor(
    private val location: GcsBucketPrefix,
    private val client: ObjectClient,
) : ModelStore {
    private fun key(
        tenantId: TenantId,
        modelId: ModelId,
        ext: String,
    ) = "${location.prefix}${tenantId.value}/models/${modelId.value}.$ext"

    override fun read(
        tenantId: TenantId,
        modelId: ModelId,
    ): ModelArtifact? {
        val json = io("モデルの読込み") { client.read(location.bucket, key(tenantId, modelId, "json")) } ?: return null
        val sig = io("署名の読込み") { client.read(location.bucket, key(tenantId, modelId, "sig")) } ?: throw MissingSignatureException("署名ファイルが無い")
        return ModelArtifact(modelId, json, sig)
    }

    override fun write(
        tenantId: TenantId,
        artifact: ModelArtifact,
    ): ArtifactWriteOutcome {
        val jsonKey = key(tenantId, artifact.modelId, "json")
        val sigKey = key(tenantId, artifact.modelId, "sig")
        val json = artifact.json()
        val sig = artifact.signature()
        return io("モデルの書込み") {
            val createdSig = client.createIfAbsent(location.bucket, sigKey, sig, "application/octet-stream")
            if (!createdSig) return@io compareExisting(jsonKey, sigKey, json, sig)
            val createdJson =
                try {
                    client.createIfAbsent(location.bucket, jsonKey, json, "application/json")
                } catch (e: Exception) {
                    runCatching { client.delete(location.bucket, sigKey) }
                    throw e
                }
            if (createdJson) ArtifactWriteOutcome.CREATED else compareExisting(jsonKey, sigKey, json, sig)
        }
    }

    private fun compareExisting(
        jsonKey: String,
        sigKey: String,
        json: ByteArray,
        sig: ByteArray,
    ): ArtifactWriteOutcome {
        val existingJson = client.read(location.bucket, jsonKey)
        val existingSig = client.read(location.bucket, sigKey)
        return if (existingJson != null && existingSig != null && existingJson.contentEquals(json) && existingSig.contentEquals(sig)) {
            ArtifactWriteOutcome.UNCHANGED
        } else {
            ArtifactWriteOutcome.CONFLICT
        }
    }
}

/** SDK・通信の例外を [StorageException] に変える（メッセージに URL・内容を入れない。型だけ）。 */
internal inline fun <T> io(
    what: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: StorageException) {
        throw e
    } catch (e: Exception) {
        throw StorageException("$what に失敗(${e.javaClass.simpleName})", e)
    }
