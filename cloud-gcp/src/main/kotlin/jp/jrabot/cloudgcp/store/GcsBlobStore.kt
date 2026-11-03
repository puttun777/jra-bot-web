package jp.jrabot.cloudgcp.store

import jp.jrabot.domain.ml.ArtifactBlobStore
import jp.jrabot.domain.ml.ArtifactKeys
import jp.jrabot.domain.ml.ArtifactWriteOutcome
import jp.jrabot.domain.ml.StoragePermissionException

/**
 * Cloud Storage の [ArtifactBlobStore]（ml-train が `MODEL_STORE_URI=gs://…` のときに使う）。作成は `ifGenerationMatch=0` の前提条件つき
 * （同時の作成でも 1 つだけが成功し、上書きしない）。既にあれば内容を読んで UNCHANGED / CONFLICT を判定する。
 * I/O の失敗は [jp.jrabot.domain.ml.StorageException]。**実バケット・IAM では未検証**（Stage 5）。
 */
class GcsBlobStore internal constructor(
    private val location: GcsBucketPrefix,
    private val client: ObjectClient,
) : ArtifactBlobStore {
    override val uriPrefix: String = "gs://${location.bucket}" + if (location.prefix.isEmpty()) "" else "/" + location.prefix.trimEnd('/')

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
            } catch (e: com.google.cloud.storage.StorageException) {
                if (e.code == 401 || e.code == 403) throw StoragePermissionException("$what の権限が無い(${e.code})", e)
                throw e
            }
        }

    companion object {
        /** `gs://bucket[/prefix]`。ADC のクライアントは最初の操作の前に作る（生成時に通信しない）。不正な URI は [IllegalArgumentException]。 */
        fun open(uri: String): GcsBlobStore = GcsBlobStore(GcsBucketPrefix.parse(uri), LazyObjectClient { GoogleObjectClient(GoogleObjectClient.adc()) })
    }
}

/** 最初の操作で実クライアントを作る（設定の検証だけの段階で認証・通信をしない）。 */
internal class LazyObjectClient(
    factory: () -> ObjectClient,
) : ObjectClient {
    private val delegate by lazy(factory)

    override fun read(
        bucket: String,
        name: String,
    ): ByteArray? = delegate.read(bucket, name)

    override fun createIfAbsent(
        bucket: String,
        name: String,
        content: ByteArray,
        contentType: String,
    ): Boolean = delegate.createIfAbsent(bucket, name, content, contentType)

    override fun delete(
        bucket: String,
        name: String,
    ) = delegate.delete(bucket, name)
}
