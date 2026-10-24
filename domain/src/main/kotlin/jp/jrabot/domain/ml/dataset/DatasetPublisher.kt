package jp.jrabot.domain.ml.dataset

import jp.jrabot.domain.ml.ArtifactWriteOutcome
import jp.jrabot.domain.ml.DatasetStore
import jp.jrabot.domain.ml.DatasetWriteResult
import jp.jrabot.domain.ml.json.CanonicalJson
import jp.jrabot.domain.ml.json.JsonFormatException
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.json.StrictJson
import jp.jrabot.domain.tenant.TenantId
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException

/** [DatasetPublisher.publish] の結果。 */
enum class PublishOutcome {
    /** 新しく保存した。 */
    CREATED,

    /** 同じ ID で内容（非圧縮 CSV の SHA-256）が同じものが既にある。再実行しても結果は同じ（冪等）。 */
    UNCHANGED,

    /** 同じ ID で**内容が異なる**ものが既にある。データセットは不変なので上書きしない（別の `--dataset-id` を使う）。 */
    CONFLICT,
}

/**
 * [ExportedDataset] を [DatasetStore] に保管する（`export-dataset` の出口）。順序: **CSV → manifest（確定の印）**。
 * manifest だけがあって CSV が無い状態は作らない。CSV だけが残った途中状態（クラッシュ後）は、再実行で内容が同じなら manifest を書いて確定する。
 * 同じ ID の既存データセットの内容は manifest の `csv.sha256`（CSV が途中状態なら保管された CSV を展開した SHA-256）で比べる。
 * manifest の本文は正準 JSON + 改行 1 つ（Python の `tools/ml/dataset_contract.py` が検証する形）。
 */
class DatasetPublisher(
    private val store: DatasetStore,
) {
    fun publish(
        tenantId: TenantId,
        dataset: ExportedDataset,
    ): PublishOutcome {
        val id = dataset.datasetId
        store.readManifest(tenantId, id)?.let { existing ->
            return if (csvShaOfManifest(existing) == dataset.csvSha256) PublishOutcome.UNCHANGED else PublishOutcome.CONFLICT
        }
        when (store.write(tenantId, id, ByteArrayInputStream(dataset.csvGz()))) {
            is DatasetWriteResult.Created -> {}

            DatasetWriteResult.AlreadyExists -> {
                // manifest が無いのに CSV がある = 途中状態。内容が同じなら続きを書く
                val stored = store.open(tenantId, id) ?: return PublishOutcome.CONFLICT
                if (sha256OfGunzipped(stored.use { it.readAllBytes() }) != dataset.csvSha256) return PublishOutcome.CONFLICT
            }
        }
        val manifest = CanonicalJson.encodeBytes(dataset.manifest) + '\n'.code.toByte()
        return when (store.writeManifest(tenantId, id, manifest)) {
            ArtifactWriteOutcome.CREATED -> PublishOutcome.CREATED
            ArtifactWriteOutcome.UNCHANGED -> PublishOutcome.UNCHANGED
            ArtifactWriteOutcome.CONFLICT -> PublishOutcome.CONFLICT
        }
    }

    private fun csvShaOfManifest(bytes: ByteArray): String? =
        try {
            val csv = (StrictJson.parse(bytes) as? JsonObject)?.get("csv") as? JsonObject
            (csv?.get("sha256") as? JsonString)?.value
        } catch (e: JsonFormatException) {
            null // 読めない既存 manifest は同一とみなさない（CONFLICT）
        }

    private fun sha256OfGunzipped(gz: ByteArray): String? =
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            GZIPInputStream(ByteArrayInputStream(gz)).use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            CanonicalJson.hex(digest.digest())
        } catch (e: ZipException) {
            null
        } catch (e: java.io.EOFException) {
            null
        }
}
