package jp.jrabot.cloudgcp.ops

import com.google.cloud.storage.Storage
import jp.jrabot.domain.ops.ModeProvider
import jp.jrabot.domain.time.Clock
import java.net.URI
import java.nio.file.Path

/** `OPS_MODE_SOURCE` / `APP_ENV` の設定が不足・不正で、安全に [ModeProvider] を作れない（起動を失敗させる）。メッセージに設定値を含めない。 */
class OpsModeConfigurationException(
    message: String,
) : IllegalStateException(message)

/**
 * [ModeProvider] の生成（Spring 非依存。T2.8 が Bean にする）。
 *
 * - `gs://…`: GCS 実装（ADC）。`APP_ENV` に関わらず使える。生成時に通信しない（最初の [ModeProvider.current] で読む）。
 * - ローカルファイル（`file:` URI またはパス）: **`APP_ENV=local` を明示したときだけ**。それ以外（未設定・`prod` など）で指定されたら、
 *   ファイル実装に黙って切り替えず [OpsModeConfigurationException]（起動失敗）にする。
 * - `OPS_MODE_SOURCE` 未設定・空・不明なスキーム・不正な `gs://` も [OpsModeConfigurationException]。
 */
object OpsModeProviders {
    const val ENV_SOURCE = "OPS_MODE_SOURCE"
    const val ENV_APP = "APP_ENV"
    const val APP_ENV_LOCAL = "local"

    fun fromEnvironment(
        env: Map<String, String>,
        clock: Clock,
    ): ModeProvider {
        val raw = env[ENV_SOURCE]?.trim().orEmpty()
        if (raw.isEmpty()) throw OpsModeConfigurationException("$ENV_SOURCE が未設定")
        return when {
            raw.startsWith("gs://") -> {
                gcs(parseLocation(raw), clock)
            }

            isLocalFile(raw) -> {
                if (env[ENV_APP]?.trim() != APP_ENV_LOCAL) {
                    throw OpsModeConfigurationException("ローカルファイルは $ENV_APP=$APP_ENV_LOCAL のときだけ使える")
                }
                file(toPath(raw), clock)
            }

            else -> {
                throw OpsModeConfigurationException("$ENV_SOURCE は gs:// で始まる必要がある")
            }
        }
    }

    /** GCS 実装。[storage] を差し替えられる（既定は ADC のクライアントを最初の読取りで生成する）。 */
    fun gcs(
        location: GcsLocation,
        clock: Clock,
        storage: () -> Storage = { GcsStorageFactory.create() },
    ): ModeProvider = CachingModeProvider(GcsOpsStateSource(location, storage), clock)

    /** ローカルファイル実装（テスト・ローカル開発用）。GCS 版と同じ検証・キャッシュ・seq の検査を使う。 */
    fun file(
        path: Path,
        clock: Clock,
    ): ModeProvider = CachingModeProvider(FileOpsStateSource(path), clock)

    private fun parseLocation(raw: String): GcsLocation =
        try {
            GcsLocation.parse(raw)
        } catch (_: IllegalArgumentException) {
            throw OpsModeConfigurationException("$ENV_SOURCE が gs://<bucket>/<object> の形式ではない")
        }

    /** スキーム（`xxx://` など）を持たない文字列と `file:` はローカルのパスとみなす。`http://` など他のスキームは対象外。 */
    private fun isLocalFile(raw: String): Boolean = raw.startsWith("file:") || !Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(raw)

    private fun toPath(raw: String): Path =
        try {
            if (raw.startsWith("file:")) Path.of(URI.create(raw)) else Path.of(raw)
        } catch (_: RuntimeException) {
            throw OpsModeConfigurationException("$ENV_SOURCE のパスが不正")
        }
}
