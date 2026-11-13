package jp.jrabot.platform.ops

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * `ops-state.json` の生のバイト列を読む口（GCS 版・S3 版とローカルファイル版）。キャッシュ・検証・seq の検査は [CachingModeProvider] の責務で、
 * ここには含めない。
 *
 * - 出力: ファイルの内容そのまま。上限は [MAX_BYTES]。
 * - エラー: 読めない場合は [OpsStateReadException]（理由つき）だけを投げる。呼び出しは [READ_TIMEOUT] 以内に戻る（遠隔版の責務）。
 * - 副作用: なし（読取りのみ）。
 */
fun interface OpsStateSource {
    fun read(): ByteArray

    companion object {
        /** ops-state.json の上限。実際は数 KB。これを超えたら不正として扱う。 */
        const val MAX_BYTES = 1 shl 20

        /** CONTRACTS §5.2: 取得のタイムアウトは 3 秒（呼び出し側から見た上限）。 */
        val READ_TIMEOUT: java.time.Duration = java.time.Duration.ofSeconds(3)
    }
}

/** 読取りの失敗。[reason] は SOURCE_* のいずれか。メッセージに内容・認証情報を含めない。 */
class OpsStateReadException(
    val reason: OpsFailureReason,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * ローカル開発・テスト用のファイル実装（クラウド不要）。パスは呼び出し側が明示する。
 * 本番で使われないよう、[OpsModeProviders.fromEnvironment] は `APP_ENV=local` のときしか生成しない。
 */
class FileOpsStateSource(
    private val path: Path,
) : OpsStateSource {
    override fun read(): ByteArray =
        try {
            Files.newInputStream(path).use { input ->
                val bytes = input.readNBytes(OpsStateSource.MAX_BYTES + 1)
                if (bytes.size > OpsStateSource.MAX_BYTES) {
                    throw OpsStateReadException(OpsFailureReason.SOURCE_TOO_LARGE, "ファイルが大きすぎる")
                }
                bytes
            }
        } catch (e: NoSuchFileException) {
            throw OpsStateReadException(OpsFailureReason.SOURCE_NOT_FOUND, "ファイルが存在しない", e)
        } catch (e: AccessDeniedException) {
            throw OpsStateReadException(OpsFailureReason.SOURCE_PERMISSION_DENIED, "ファイルを読む権限がない", e)
        } catch (e: IOException) {
            throw OpsStateReadException(OpsFailureReason.SOURCE_UNAVAILABLE, "ファイルを読めない(${e.javaClass.simpleName})", e)
        }
}
