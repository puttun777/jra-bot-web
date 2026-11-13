package jp.jrabot.cloudgcp.store

import com.google.cloud.NoCredentials
import com.google.cloud.http.HttpTransportOptions
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageException
import com.google.cloud.storage.StorageOptions
import jp.jrabot.platform.store.ObjectAccessDeniedException
import jp.jrabot.platform.store.ObjectClient
import java.time.Duration

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
            if (e.code == 404) null else throw translate(e)
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
            if (e.code == 412) false else throw translate(e)
        }

    override fun delete(
        bucket: String,
        name: String,
    ) {
        try {
            storage.delete(BlobId.of(bucket, name))
        } catch (e: StorageException) {
            throw translate(e)
        }
    }

    /** 401・403 は provider 中立の権限エラーに（[jp.jrabot.platform.store.ObjectBlobStore] が再試行しない失敗として扱う）。他はそのまま。 */
    private fun translate(e: StorageException): RuntimeException = if (e.code == 401 || e.code == 403) ObjectAccessDeniedException(e.code, e) else e

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
                .setTransportOptions(
                    HttpTransportOptions
                        .newBuilder()
                        .setConnectTimeout(millis)
                        .setReadTimeout(millis)
                        .build(),
                ).setRetrySettings(
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
