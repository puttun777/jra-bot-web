package jp.jrabot.persistence.schema

import java.util.Properties

/**
 * ビルド時に実行 jar へ焼き込まれた情報（`jrabot-app.properties`。Gradle の `generateAppInfo` が生成する。CONTRACTS §2・§4.3）。
 * 実行時の環境変数では**上書きできない**（`REQUIRED_SCHEMA_VERSION` を環境変数で下げて検証を弱められない）。
 * [releaseId] は表示用で、環境変数 `RELEASE_ID` があればそちらを使う（イメージのタグ/digest との対応づけ）。
 */
class AppBuildInfo(
    val releaseId: String,
    val requiredSchemaVersion: SchemaVersion,
) {
    companion object {
        const val RESOURCE = "/jrabot-app.properties"

        /** 見つからない・形式が不正なら [IllegalStateException]（起動を失敗させる）。 */
        fun load(releaseIdOverride: String? = null): AppBuildInfo {
            val props = Properties()
            val stream = AppBuildInfo::class.java.getResourceAsStream(RESOURCE) ?: throw IllegalStateException("$RESOURCE がクラスパスにない（ビルドの generateAppInfo が必要）")
            stream.use { props.load(it) }
            val required =
                SchemaVersion.parseOrNull(props.getProperty("requiredSchemaVersion"))
                    ?: throw IllegalStateException("$RESOURCE の requiredSchemaVersion が不正")
            val release =
                releaseIdOverride?.trim()?.takeIf { it.isNotEmpty() } ?: props.getProperty("releaseId")?.trim()?.takeIf { it.isNotEmpty() } ?: "dev"
            return AppBuildInfo(sanitize(release), required)
        }

        /** 表示用なので制御文字・長すぎる値を落とす（ログ/JSON に出る）。 */
        private fun sanitize(s: String): String = s.filter { it.isLetterOrDigit() || it in "-._:/@+" }.take(100).ifEmpty { "dev" }
    }
}
