package jp.jrabot.platform.ops

import jp.jrabot.domain.ops.InspectableModeProvider
import jp.jrabot.domain.time.Clock
import java.net.URI
import java.nio.file.Path

/** `OPS_MODE_SOURCE` / `APP_ENV` の設定が不足・不正で、安全に [ModeProvider] を作れない（起動を失敗させる）。メッセージに設定値を含めない。 */
class OpsModeConfigurationException(
    message: String,
) : IllegalStateException(message)

/**
 * 正本（`ops-state.json`）の遠隔の読取り元（GCS = `gs://`、S3 = `s3://`）。実装は `:cloud-gcp` と `:cloud-aws`（D-280）。
 * [create] は生成時に通信しない（最初の読取りで認証・通信する）。URI が不正なら [IllegalArgumentException]（メッセージに値を含めない）。
 */
interface RemoteOpsStateSourceFactory {
    /** `gs` / `s3`（`<scheme>://` の前の部分）。 */
    val scheme: String

    /** `/internal/ops/mode` の `source` に出す種別（`gcs` / `s3`）。 */
    val sourceLabel: String

    fun create(uri: String): OpsStateSource
}

/**
 * [ModeProvider] の生成（Spring 非依存。各アプリが Bean にする）。
 *
 * - `<scheme>://…`: [remotes] の中でスキームが一致する遠隔の実装（`gs://` = GCS、`s3://` = S3）。`APP_ENV` に関わらず使える。生成時に通信しない。
 * - ローカルファイル（`file:` URI またはパス）: **`APP_ENV=local` を明示したときだけ**。それ以外（未設定・`prod` など）で指定されたら、
 *   ファイル実装に黙って切り替えず [OpsModeConfigurationException]（起動失敗）にする。
 * - `OPS_MODE_SOURCE` 未設定・空・不明なスキーム・不正な URI も [OpsModeConfigurationException]。
 */
object OpsModeProviders {
    const val ENV_SOURCE = "OPS_MODE_SOURCE"
    const val ENV_APP = "APP_ENV"
    const val APP_ENV_LOCAL = "local"

    fun fromEnvironment(
        env: Map<String, String>,
        clock: Clock,
        remotes: List<RemoteOpsStateSourceFactory>,
    ): InspectableModeProvider {
        val raw = env[ENV_SOURCE]?.trim().orEmpty()
        if (raw.isEmpty()) throw OpsModeConfigurationException("$ENV_SOURCE が未設定")
        val remote = remotes.firstOrNull { raw.startsWith("${it.scheme}://") }
        return when {
            remote != null -> {
                val source =
                    try {
                        remote.create(raw)
                    } catch (_: IllegalArgumentException) {
                        throw OpsModeConfigurationException("$ENV_SOURCE が ${remote.scheme}://<bucket>/<object> の形式ではない")
                    }
                CachingModeProvider(source, clock, sourceLabel = remote.sourceLabel)
            }

            isLocalFile(raw) -> {
                if (env[ENV_APP]?.trim() != APP_ENV_LOCAL) {
                    throw OpsModeConfigurationException("ローカルファイルは $ENV_APP=$APP_ENV_LOCAL のときだけ使える")
                }
                file(toPath(raw), clock)
            }

            else -> {
                throw OpsModeConfigurationException("$ENV_SOURCE は ${remotes.joinToString(" または ") { "${it.scheme}://" }} で始まる必要がある")
            }
        }
    }

    /** ローカルファイル実装（テスト・ローカル開発用）。遠隔版と同じ検証・キャッシュ・seq の検査を使う。 */
    fun file(
        path: Path,
        clock: Clock,
    ): InspectableModeProvider = CachingModeProvider(FileOpsStateSource(path), clock, sourceLabel = CachingModeProvider.SOURCE_FILE)

    /** スキーム（`xxx://` など）を持たない文字列と `file:` はローカルのパスとみなす。`http://` など他のスキームは対象外。 */
    private fun isLocalFile(raw: String): Boolean = raw.startsWith("file:") || !Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(raw)

    private fun toPath(raw: String): Path =
        try {
            if (raw.startsWith("file:")) Path.of(URI.create(raw)) else Path.of(raw)
        } catch (_: RuntimeException) {
            throw OpsModeConfigurationException("$ENV_SOURCE のパスが不正")
        }
}
