package jp.jrabot.cloudgcp.store

import com.google.cloud.NoCredentials
import com.google.cloud.http.HttpTransportOptions
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageException
import com.google.cloud.storage.StorageOptions
import java.time.Duration

/** `gs://<bucket>[/<prefix>]`（`MODEL_STORE_URI`。CONTRACTS §2）の分解結果。[prefix] は空か、末尾が `/` の相対パス。 */
data class GcsBucketPrefix(
    val bucket: String,
    val prefix: String,
) {
    companion object {
        private val bucketPattern = Regex("^[a-z0-9][a-z0-9._-]{1,220}[a-z0-9]$")
        private val prefixSegment = Regex("^[A-Za-z0-9._-]{1,100}$")

        /** 不正なら [IllegalArgumentException]（メッセージに入力の値を含めない）。 */
        fun parse(uri: String): GcsBucketPrefix {
            require(uri.startsWith("gs://")) { "gs:// で始まる URI ではない" }
            val rest = uri.removePrefix("gs://").trimEnd('/')
            val bucket = rest.substringBefore('/')
            require(bucketPattern.matches(bucket)) { "バケット名が不正" }
            val segments = if ('/' in rest) rest.substringAfter('/').split('/') else emptyList()
            require(segments.all { prefixSegment.matches(it) && it != "." && it != ".." }) { "プレフィックスが不正" }
            return GcsBucketPrefix(bucket, if (segments.isEmpty()) "" else segments.joinToString("/") + "/")
        }
    }
}

/**
 * オブジェクトストレージの最小の操作（[GcsModelStore]・[GcsDatasetStore] が使う）。SDK の例外はここを越えて型を漏らさず、呼び出し側で
 * [jp.jrabot.domain.ml.StorageException] に変換する。**削除は『この実行が新しく作ったものを片付ける』ためだけ**に使う（既存の成果物を消さない）。
 */
internal interface ObjectClient {
    /** 無ければ null。 */
    fun read(
        bucket: String,
        name: String,
    ): ByteArray?

    /** 作成のみ（既にあれば作らず false）。上書きしない。 */
    fun createIfAbsent(
        bucket: String,
        name: String,
        content: ByteArray,
        contentType: String,
    ): Boolean

    fun delete(
        bucket: String,
        name: String,
    )
}

/** Cloud Storage の実クライアント。作成は `ifGenerationMatch=0`（`doesNotExist`）の前提条件つきで、同時の作成でも 1 つだけが成功する。 */
internal class GoogleObjectClient(
    private val storage: Storage,
) : ObjectClient {
    override fun read(
        bucket: String,
        name: String,
    ): ByteArray? =
        try {
            storage.readAllBytes(BlobId.of(bucket, name))
        } catch (e: StorageException) {
            if (e.code == 404) null else throw e
        }

    override fun createIfAbsent(
        bucket: String,
        name: String,
        content: ByteArray,
        contentType: String,
    ): Boolean =
        try {
            storage.create(BlobInfo.newBuilder(BlobId.of(bucket, name)).setContentType(contentType).build(), content, Storage.BlobTargetOption.doesNotExist())
            true
        } catch (e: StorageException) {
            if (e.code == 412) false else throw e
        }

    override fun delete(
        bucket: String,
        name: String,
    ) {
        storage.delete(BlobId.of(bucket, name))
    }

    companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(30)

        /** 本番: Application Default Credentials（Cloud Run のランタイム SA）。長期の鍵ファイルは使わない。 */
        fun adc(timeout: Duration = TIMEOUT): Storage = builder(timeout).build().service

        /** テスト用: ローカルのフェイクサーバ（認証なし）へ。実 GCP には接続しない。 */
        fun forFakeEndpoint(
            host: String,
            timeout: Duration = Duration.ofSeconds(5),
        ): Storage =
            builder(timeout)
                .setHost(host)
                .setProjectId("fake-project")
                .setCredentials(NoCredentials.getInstance())
                .build()
                .service

        private fun builder(timeout: Duration): StorageOptions.Builder {
            val millis = timeout.toMillis().toInt()
            return StorageOptions
                .newBuilder()
                .setTransportOptions(HttpTransportOptions.newBuilder().setConnectTimeout(millis).setReadTimeout(millis).build())
                .setRetrySettings(
                    StorageOptions
                        .getDefaultRetrySettings()
                        .toBuilder()
                        .setTotalTimeoutDuration(timeout.multipliedBy(2))
                        .setMaxAttempts(3)
                        .build(),
                )
        }
    }
}
