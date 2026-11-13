package jp.jrabot.cloudgcp.ops

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageException
import jp.jrabot.platform.ops.CachingModeProvider
import jp.jrabot.platform.ops.OpsFailureReason
import jp.jrabot.platform.ops.OpsStateReadException
import jp.jrabot.platform.ops.OpsStateSource
import jp.jrabot.platform.ops.RemoteOpsStateSourceFactory
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** `gs://<bucket>/<object>` の分解結果（`OPS_MODE_SOURCE`。CONTRACTS §2）。 */
data class GcsLocation(
    val bucket: String,
    val objectName: String,
) {
    companion object {
        private const val PREFIX = "gs://"
        private val bucketPattern = Regex("^[a-z0-9][a-z0-9._-]{1,220}[a-z0-9]$")

        /** 不正なら [IllegalArgumentException]（メッセージに入力の値を含めない）。 */
        fun parse(uri: String): GcsLocation {
            require(uri.startsWith(PREFIX)) { "gs:// で始まる URI ではない" }
            val rest = uri.removePrefix(PREFIX)
            val slash = rest.indexOf('/')
            require(slash > 0) { "gs://<bucket>/<object> の形式ではない" }
            val bucket = rest.substring(0, slash)
            val objectName = rest.substring(slash + 1)
            require(bucketPattern.matches(bucket)) { "バケット名が不正" }
            require(objectName.isNotEmpty() && !objectName.endsWith("/")) { "オブジェクト名が不正" }
            require(objectName.none { it.isISOControl() } && '?' !in objectName && '#' !in objectName) { "オブジェクト名が不正" }
            return GcsLocation(bucket, objectName)
        }
    }
}

/**
 * Cloud Storage 上の `ops-state.json` を読む（読取りのみ。書込み・作成・削除の呼び出しは持たない）。
 *
 * - 認証: [storage] が返す Google Cloud Storage クライアントの Application Default Credentials（Cloud Run ではランタイム SA）。
 *   必要な権限は対象オブジェクトの読取り（`storage.objects.get`）だけ。
 * - タイムアウト: [timeout]（既定 3 秒）を**呼び出し側から見た上限**として強制する。SDK 側にも接続・読取り・リトライ全体の 3 秒を設定するが、
 *   認証情報の取得などで SDK が止まっても [read] は上限で戻る（専用スレッドで読み、超えたら中断を試みて SOURCE_TIMEOUT）。
 * - [storage] は初回の [read] で呼ばれ、失敗したら次の [read] でもう一度呼ぶ（クライアントの生成失敗で起動を止めず、UNKNOWN として扱う）。
 * - エラー: 404 → SOURCE_NOT_FOUND、401/403 → SOURCE_PERMISSION_DENIED、タイムアウト → SOURCE_TIMEOUT、その他 → SOURCE_UNAVAILABLE。
 *   例外のメッセージには HTTP ステータスと例外クラス名だけを入れる（トークン・URL・内容を入れない）。
 */
class GcsOpsStateSource(
    private val location: GcsLocation,
    private val storage: () -> Storage,
    private val timeout: Duration = READ_TIMEOUT,
    private val executor: ExecutorService = sharedExecutor,
) : OpsStateSource {
    @Volatile
    private var client: Storage? = null

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
        val bytes = clientOrCreate().readAllBytes(BlobId.of(location.bucket, location.objectName))
        if (bytes.size > OpsStateSource.MAX_BYTES) {
            throw OpsStateReadException(OpsFailureReason.SOURCE_TOO_LARGE, "オブジェクトが大きすぎる")
        }
        return bytes
    }

    private fun clientOrCreate(): Storage = client ?: storage().also { client = it }

    private fun translate(cause: Throwable): Throwable =
        when {
            cause is OpsStateReadException -> cause

            cause is Error -> cause

            // OOM など。握りつぶさない
            cause is StorageException -> fromStatus(cause)

            hasTimeoutCause(cause) -> OpsStateReadException(OpsFailureReason.SOURCE_TIMEOUT, "通信がタイムアウトした", cause)

            // SDK・認証・通信の失敗は外部要因として UNKNOWN にする（種別だけ記録）
            else -> OpsStateReadException(OpsFailureReason.SOURCE_UNAVAILABLE, "読取りに失敗した(${cause.javaClass.simpleName})", cause)
        }

    private fun fromStatus(e: StorageException): OpsStateReadException =
        when (val code = e.code) {
            404 -> {
                OpsStateReadException(OpsFailureReason.SOURCE_NOT_FOUND, "オブジェクトが存在しない", e)
            }

            401, 403 -> {
                OpsStateReadException(OpsFailureReason.SOURCE_PERMISSION_DENIED, "読取り権限がない(HTTP $code)", e)
            }

            408 -> {
                OpsStateReadException(OpsFailureReason.SOURCE_TIMEOUT, "サーバがタイムアウトした(HTTP 408)", e)
            }

            else -> {
                if (hasTimeoutCause(e)) {
                    OpsStateReadException(OpsFailureReason.SOURCE_TIMEOUT, "通信がタイムアウトした", e)
                } else {
                    OpsStateReadException(OpsFailureReason.SOURCE_UNAVAILABLE, "読取りに失敗した(HTTP $code)", e)
                }
            }
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

    companion object {
        /** CONTRACTS §5.2: 取得のタイムアウトは 3 秒。 */
        val READ_TIMEOUT: Duration = OpsStateSource.READ_TIMEOUT

        private val sharedExecutor: ExecutorService =
            Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "ops-state-reader").apply { isDaemon = true }
            }
    }
}

/** `OPS_MODE_SOURCE=gs://<bucket>/<object>` の読取り元（[jp.jrabot.platform.ops.OpsModeProviders] に渡す。D-280）。ADC のクライアントは最初の読取りで作る。 */
object GcsOpsStateSources : RemoteOpsStateSourceFactory {
    override val scheme: String = "gs"
    override val sourceLabel: String = CachingModeProvider.SOURCE_GCS

    override fun create(uri: String): OpsStateSource = GcsOpsStateSource(GcsLocation.parse(uri), { GcsStorageFactory.create() })
}
