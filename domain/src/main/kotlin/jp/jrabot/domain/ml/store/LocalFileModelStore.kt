package jp.jrabot.domain.ml.store

import jp.jrabot.domain.ml.ArtifactWriteOutcome
import jp.jrabot.domain.ml.MissingSignatureException
import jp.jrabot.domain.ml.ModelArtifact
import jp.jrabot.domain.ml.ModelId
import jp.jrabot.domain.ml.ModelStore
import jp.jrabot.domain.ml.StorageException
import jp.jrabot.domain.tenant.TenantId
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * ローカルのディレクトリに置く [ModelStore]（`APP_ENV=local`・合成データの疎通検証用。**実バケット（GCS）には書かない**）。
 * 配置は本番と同じ `<root>/{tenant_id}/models/{model_id}.json` と `.sig`。パスは [TenantId] と [ModelId]（英数字・`_`・`-`）だけから組み立てるのでパストラバーサルは起きない。
 * 作成のみ（上書きしない）。同一内容の再書込みは UNCHANGED、異なる内容は CONFLICT。
 */
class LocalFileModelStore(
    private val root: Path,
) : ModelStore {
    private fun dir(tenantId: TenantId): Path = root.resolve(tenantId.value.toString()).resolve("models")

    override fun read(
        tenantId: TenantId,
        modelId: ModelId,
    ): ModelArtifact? {
        val json = dir(tenantId).resolve("${modelId.value}.json")
        val sig = dir(tenantId).resolve("${modelId.value}.sig")
        try {
            if (!Files.isRegularFile(json)) return null
            if (!Files.isRegularFile(sig)) throw MissingSignatureException("署名ファイルが無い")
            return ModelArtifact(modelId, Files.readAllBytes(json), Files.readAllBytes(sig))
        } catch (e: IOException) {
            throw StorageException("モデルの読込みに失敗", e)
        }
    }

    override fun write(
        tenantId: TenantId,
        artifact: ModelArtifact,
    ): ArtifactWriteOutcome {
        val d = dir(tenantId)
        val json = d.resolve("${artifact.modelId.value}.json")
        val sig = d.resolve("${artifact.modelId.value}.sig")
        try {
            Files.createDirectories(d)
            if (Files.exists(json) || Files.exists(sig)) {
                val same =
                    Files.isRegularFile(json) && Files.isRegularFile(sig) &&
                        Files.readAllBytes(json).contentEquals(artifact.json()) && Files.readAllBytes(sig).contentEquals(artifact.signature())
                return if (same) ArtifactWriteOutcome.UNCHANGED else ArtifactWriteOutcome.CONFLICT
            }
            // 署名を先に置き、JSON を最後に原子的に置く（JSON があれば署名もある）
            val tmpSig = Files.createTempFile(d, ".sig", ".tmp")
            val tmpJson = Files.createTempFile(d, ".json", ".tmp")
            Files.write(tmpSig, artifact.signature())
            Files.write(tmpJson, artifact.json())
            Files.move(tmpSig, sig, StandardCopyOption.ATOMIC_MOVE)
            Files.move(tmpJson, json, StandardCopyOption.ATOMIC_MOVE)
            return ArtifactWriteOutcome.CREATED
        } catch (e: IOException) {
            throw StorageException("モデルの書込みに失敗", e)
        }
    }
}
