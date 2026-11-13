package jp.jrabot.platform.store

import jp.jrabot.domain.ml.ArtifactBlobStore
import jp.jrabot.domain.ml.ArtifactKeys
import jp.jrabot.domain.ml.ArtifactWriteOutcome
import jp.jrabot.domain.ml.StoragePermissionException

/**
 * オブジェクトストレージ（GCS・S3）の [ArtifactBlobStore]（ml-train が `MODEL_STORE_URI=gs://…` または `s3://…` のときに使う）。作成は条件付き（GCS は `ifGenerationMatch=0`、S3 は `If-None-Match: *`）
 * （同時の作成でも 1 つだけが成功し、上書きしない）。既にあれば内容を読んで UNCHANGED / CONFLICT を判定する。
 * I/O の失敗は [jp.jrabot.domain.ml.StorageException]。**実バケット・IAM では未検証**（Stage 5）。
 */
class ObjectBlobStore(
    private val location: ObjectStoreLocation,
    private val client: ObjectClient,
) : ArtifactBlobStore {
    override val uriPrefix: String = location.uriPrefix

    private fun name(key: String): String {
        ArtifactKeys.validate(key)
        return location.prefix + key
    }

    override fun get(key: String): ByteArray? {
        val n = name(key)
        return gio("読込み") { client.read(location.bucket, n) }
    }

    override fun putIfAbsent(
        key: String,
        data: ByteArray,
        contentType: String,
    ): ArtifactWriteOutcome {
        val n = name(key)
        return gio("書込み") {
            if (client.createIfAbsent(location.bucket, n, data, contentType)) {
                ArtifactWriteOutcome.CREATED
            } else {
                val existing = client.read(location.bucket, n)
                if (existing != null && existing.contentEquals(data)) ArtifactWriteOutcome.UNCHANGED else ArtifactWriteOutcome.CONFLICT
            }
        }
    }

    override fun delete(key: String) {
        val n = name(key)
        gio("削除") { client.delete(location.bucket, n) }
    }

    /** [io] と同じだが、401・403（権限）は [StoragePermissionException] に分ける（一時的な失敗として再試行させない）。 */
    private inline fun <T> gio(
        what: String,
        block: () -> T,
    ): T =
        io(what) {
            try {
                block()
            } catch (e: ObjectAccessDeniedException) {
                throw StoragePermissionException("$what の権限が無い(${e.status})", e)
            }
        }
}
