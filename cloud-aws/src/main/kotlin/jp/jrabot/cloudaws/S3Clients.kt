package jp.jrabot.cloudaws

import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.retry.RetryMode
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import java.time.Duration

/**
 * S3 クライアントの生成（D-280）。HTTP は JDK の URLConnection 版、認証は既定の連鎖（EC2 ではインスタンスプロファイルの短期の資格情報。
 * **長期のアクセスキーを使わない**: 本番の構成でアクセスキーの環境変数を渡さない）。リージョンは `AWS_REGION`（無ければ SDK の既定の連鎖）。
 * 接続・読取りの上限は [timeout]、API 呼び出し全体（再試行を含む）は [timeout] の [apiFraction] 倍。
 */
object S3Clients {
    const val ENV_REGION = "AWS_REGION"

    fun create(
        timeout: Duration,
        env: Map<String, String> = System.getenv(),
        apiFraction: Double = 2.0,
    ): S3Client {
        val builder =
            S3Client
                .builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(timeout).socketTimeout(timeout))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .overrideConfiguration { c ->
                    c.apiCallTimeout(Duration.ofMillis((timeout.toMillis() * apiFraction).toLong())).retryStrategy(RetryMode.STANDARD)
                }
        env[ENV_REGION]?.trim()?.takeIf { it.isNotEmpty() }?.let { builder.region(Region.of(it)) }
        return builder.build()
    }

    /** 試験用: ローカルのフェイクサーバ（パス形式・匿名）。実 AWS には接続しない。 */
    fun forFakeEndpoint(
        endpoint: String,
        timeout: Duration,
    ): S3Client =
        S3Client
            .builder()
            .endpointOverride(URI.create(endpoint))
            .forcePathStyle(true)
            .region(Region.AP_NORTHEAST_1)
            .credentialsProvider(AnonymousCredentialsProvider.create())
            // フェイクは aws-chunked のチェックサムを解さないので、必要なときだけ計算する（本番は SDK の既定）
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(timeout).socketTimeout(timeout))
            .overrideConfiguration { c -> c.apiCallTimeout(timeout).retryStrategy(RetryMode.STANDARD) }
            .build()
}

/** `s3://<bucket>/<key>` の分解（`OPS_MODE_SOURCE` など、オブジェクト 1 つを指す URI）。 */
data class S3ObjectLocation(
    val bucket: String,
    val key: String,
) {
    companion object {
        private val bucketPattern = Regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$")
        private val keyPattern = Regex("^[A-Za-z0-9._/-]{1,512}$")

        /** 不正なら [IllegalArgumentException]（メッセージに入力の値を含めない）。 */
        fun parse(uri: String): S3ObjectLocation {
            require(uri.startsWith("s3://")) { "s3:// で始まる URI ではない" }
            val rest = uri.removePrefix("s3://")
            val slash = rest.indexOf('/')
            require(slash > 0) { "s3://<bucket>/<key> の形式ではない" }
            val bucket = rest.substring(0, slash)
            val key = rest.substring(slash + 1)
            require(bucketPattern.matches(bucket)) { "バケット名が不正" }
            require(keyPattern.matches(key) && !key.endsWith("/") && !key.startsWith("/")) { "キーが不正" }
            require(key.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "キーが不正" }
            return S3ObjectLocation(bucket, key)
        }
    }
}
