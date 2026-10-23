package jp.jrabot.cloudgcp.ops

import com.google.cloud.NoCredentials
import com.google.cloud.http.HttpTransportOptions
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import java.time.Duration

/**
 * 読取り専用に使う Cloud Storage クライアントの生成。接続・読取りの上限は [timeout]（既定 3 秒）、リトライ全体は [timeout] の 80%。
 * 呼び出し側の上限（[GcsOpsStateSource] の [timeout]）より少し手前でリトライを打ち切るので、サーバエラーは SDK の失敗として分類され、
 * 上限の強制は SDK が止まったとき（認証取得など）の最後の安全網になる。
 */
internal object GcsStorageFactory {
    /**
     * 本番用: Application Default Credentials（Cloud Run のランタイム SA）。長期の鍵ファイルは使わない。
     * ADC を取得できなければ認証なしの要求になり、非公開バケットは 401/403 → UNKNOWN になる（NORMAL にはならない）。
     */
    fun create(timeout: Duration = GcsOpsStateSource.READ_TIMEOUT): Storage = builder(timeout).build().service

    /** テスト用: HTTP の接続先をローカルのフェイクサーバに向ける（認証なし）。実 GCP には接続しない。 */
    fun createForFakeEndpoint(
        host: String,
        timeout: Duration,
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
                    .setTotalTimeoutDuration(timeout.multipliedBy(8).dividedBy(10))
                    .setMaxAttempts(3)
                    .build(),
            )
    }
}
