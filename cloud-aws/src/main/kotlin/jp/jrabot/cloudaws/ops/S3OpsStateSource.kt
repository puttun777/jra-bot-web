package jp.jrabot.cloudaws.ops

import jp.jrabot.cloudaws.S3Clients
import jp.jrabot.cloudaws.S3ObjectLocation
import jp.jrabot.platform.ops.CachingModeProvider
import jp.jrabot.platform.ops.OpsFailureReason
import jp.jrabot.platform.ops.OpsStateReadException
import jp.jrabot.platform.ops.OpsStateSource
import jp.jrabot.platform.ops.RemoteOpsStateSourceFactory
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException
import software.amazon.awssdk.core.exception.ApiCallTimeoutException
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * S3 上の `ops-state.json` を読む（読取りのみ。書込み・作成・削除の呼び出しは持たない。D-280）。GCS 版（`:cloud-gcp`）と同じ規則。
 *
 * - 認証: [client] が返す S3 クライアントの既定の連鎖（EC2 のインスタンスプロファイル）。必要な権限は対象オブジェクトの `s3:GetObject` だけ。
 * - タイムアウト: [timeout]（既定 3 秒）を**呼び出し側から見た上限**として強制する（専用スレッドで読み、超えたら中断を試みて SOURCE_TIMEOUT）。
 * - [client] は初回の [read] で呼ばれ、失敗したら次の [read] でもう一度呼ぶ（クライアントの生成失敗で起動を止めず、UNKNOWN として扱う）。
 * - エラー: 404 → SOURCE_NOT_FOUND、401/403 → SOURCE_PERMISSION_DENIED、タイムアウト → SOURCE_TIMEOUT、その他 → SOURCE_UNAVAILABLE。
 *   例外のメッセージには HTTP ステータスと例外クラス名だけを入れる（資格情報・URL・内容を入れない）。
 */
class S3OpsStateSource(
    private val location: S3ObjectLocation,
    private val client: () -> S3Client,
    private val timeout: Duration = OpsStateSource.READ_TIMEOUT,
    private val executor: ExecutorService = sharedExecutor,
) : OpsStateSource {
    @Volatile
    private var s3: S3Client? = null

    override fun read(): ByteArray {
        val future = executor.submit(Callable { readNow() })
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw OpsStateReadException(OpsFailureReason.SOURCE_TIMEOUT, "読取りが ${timeout.toMillis()}ms を超えた", e)
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw OpsStateReadException(OpsFailureReason.SOURCE_UNAVAILABLE, "読取り中に中断された", e)
        } catch (e: ExecutionException) {
            throw translate(e.cause ?: e)
        }
    }

    private fun readNow(): ByteArray {
        val request =
            GetObjectRequest
                .builder()
                .bucket(location.bucket)
                .key(location.key)
                .build()
        clientOrCreate().getObject(request).use { input ->
            val bytes = input.readNBytes(OpsStateSource.MAX_BYTES + 1)
            if (bytes.size > OpsStateSource.MAX_BYTES) {
                throw OpsStateReadException(OpsFailureReason.SOURCE_TOO_LARGE, "オブジェクトが大きすぎる")
            }
            return bytes
        }
    }

    private fun clientOrCreate(): S3Client = s3 ?: client().also { s3 = it }

    private fun translate(cause: Throwable): Throwable =
        when {
            cause is OpsStateReadException -> {
                cause
            }

            cause is Error -> {
                cause
            }

            cause is S3Exception -> {
                fromStatus(cause)
            }

            cause is ApiCallTimeoutException || cause is ApiCallAttemptTimeoutException || hasTimeoutCause(cause) -> {
                OpsStateReadException(OpsFailureReason.SOURCE_TIMEOUT, "通信がタイムアウトした", cause)
            }

            cause is SdkClientException -> {
                OpsStateReadException(OpsFailureReason.SOURCE_UNAVAILABLE, "読取りに失敗した(${cause.javaClass.simpleName})", cause)
            }

            else -> {
                OpsStateReadException(OpsFailureReason.SOURCE_UNAVAILABLE, "読取りに失敗した(${cause.javaClass.simpleName})", cause)
            }
        }

    private fun fromStatus(e: S3Exception): OpsStateReadException =
        when (val code = e.statusCode()) {
            404 -> OpsStateReadException(OpsFailureReason.SOURCE_NOT_FOUND, "オブジェクトが存在しない", e)
            401, 403 -> OpsStateReadException(OpsFailureReason.SOURCE_PERMISSION_DENIED, "読取り権限がない(HTTP $code)", e)
            408 -> OpsStateReadException(OpsFailureReason.SOURCE_TIMEOUT, "サーバがタイムアウトした(HTTP 408)", e)
            else -> OpsStateReadException(OpsFailureReason.SOURCE_UNAVAILABLE, "読取りに失敗した(HTTP $code)", e)
        }

    private fun hasTimeoutCause(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth++ < 8) {
            if (t is SocketTimeoutException || (t is IOException && t.javaClass.simpleName.contains("Timeout"))) return true
            t = t.cause
        }
        return false
    }

    private companion object {
        val sharedExecutor: ExecutorService =
            Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "ops-state-reader-s3").apply { isDaemon = true }
            }
    }
}

/** `OPS_MODE_SOURCE=s3://<bucket>/<key>` の読取り元（[jp.jrabot.platform.ops.OpsModeProviders] に渡す。D-280）。クライアントは最初の読取りで作る。 */
object S3OpsStateSources : RemoteOpsStateSourceFactory {
    override val scheme: String = "s3"
    override val sourceLabel: String = CachingModeProvider.SOURCE_S3

    override fun create(uri: String): OpsStateSource =
        S3OpsStateSource(S3ObjectLocation.parse(uri), { S3Clients.create(OpsStateSource.READ_TIMEOUT, apiFraction = 0.8) })
}
