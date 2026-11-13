package jp.jrabot.cloudaws.store

import jp.jrabot.cloudaws.S3Clients
import jp.jrabot.domain.ml.ArtifactBlobStore
import jp.jrabot.domain.ml.DatasetStore
import jp.jrabot.domain.ml.ModelStore
import jp.jrabot.platform.store.LazyObjectClient
import jp.jrabot.platform.store.ObjectAccessDeniedException
import jp.jrabot.platform.store.ObjectBlobStore
import jp.jrabot.platform.store.ObjectClient
import jp.jrabot.platform.store.ObjectDatasetStore
import jp.jrabot.platform.store.ObjectModelStore
import jp.jrabot.platform.store.ObjectStoreLocation
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.time.Duration

/**
 * S3 の [ObjectClient]（D-280）。作成は `If-None-Match: *` の条件付き（同時の作成でも 1 つだけが成功し、既存を上書きしない。412 は「既にある」）。
 * 404 は「無い」（null）、401・403 は [ObjectAccessDeniedException]。それ以外の失敗（409 の競合・5xx・通信）は SDK の例外のまま投げ、
 * 呼び出し側（`:platform` の保管）が [jp.jrabot.domain.ml.StorageException] に変換する。暗号化はバケットの既定（SSE-S3）に任せる。
 */
class S3ObjectClient(
    private val s3: S3Client,
) : ObjectClient {
    override fun read(
        bucket: String,
        name: String,
    ): ByteArray? =
        try {
            s3
                .getObjectAsBytes(
                    GetObjectRequest
                        .builder()
                        .bucket(bucket)
                        .key(name)
                        .build(),
                ).asByteArray()
        } catch (e: S3Exception) {
            if (e.statusCode() == 404) null else throw translate(e)
        }

    override fun createIfAbsent(
        bucket: String,
        name: String,
        content: ByteArray,
        contentType: String,
    ): Boolean =
        try {
            s3.putObject(
                PutObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key(name)
                    .contentType(contentType)
                    .ifNoneMatch("*")
                    .build(),
                RequestBody.fromBytes(content),
            )
            true
        } catch (e: S3Exception) {
            if (e.statusCode() == 412) false else throw translate(e)
        }

    override fun delete(
        bucket: String,
        name: String,
    ) {
        try {
            s3.deleteObject(
                DeleteObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key(name)
                    .build(),
            )
        } catch (e: S3Exception) {
            throw translate(e)
        }
    }

    private fun translate(e: S3Exception): RuntimeException =
        if (e.statusCode() == 401 ||
            e.statusCode() == 403
        ) {
            ObjectAccessDeniedException(e.statusCode(), e)
        } else {
            e
        }

    companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}

/**
 * `MODEL_STORE_URI=s3://bucket[/prefix]` の保管（モデル・データセット・ml-train の成果物。D-280）。クライアントは最初の操作で作る（生成時に AWS へ接続しない）。
 * 配置・作成のみ・競合の判定は GCS と同じ provider 中立の実装（`:platform`）。不正な URI は [IllegalArgumentException]。
 */
object S3Stores {
    fun location(uri: String): ObjectStoreLocation = ObjectStoreLocation.parse(uri, "s3", ObjectStoreLocation.S3_BUCKET)

    fun modelStore(uri: String): ModelStore = ObjectModelStore(location(uri), lazyClient())

    fun datasetStore(uri: String): DatasetStore = ObjectDatasetStore(location(uri), lazyClient())

    fun blobStore(uri: String): ArtifactBlobStore = ObjectBlobStore(location(uri), lazyClient())

    private fun lazyClient(): ObjectClient = LazyObjectClient { S3ObjectClient(S3Clients.create(S3ObjectClient.TIMEOUT)) }
}
