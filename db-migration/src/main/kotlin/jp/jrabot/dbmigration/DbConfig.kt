package jp.jrabot.dbmigration

/**
 * 接続設定（CONTRACTS §2: `DB_URL` `DB_USER` `DB_PASSWORD`）。パスワードは `toString` にも出さない。
 * 接続ユーザーは `migrate`（DDL 権限）だけを使う（CONTRACTS §4.2）。
 */
class DbConfig(
    val url: String,
    val user: String,
    val password: String,
) {
    /** 接続待ちで無限に待たないよう、`connectTimeout` が無い URL にだけ 10 秒を足す（CONTRACTS §8.3 の connectionTimeout と同じ）。 */
    fun effectiveUrl(): String {
        if (url.contains("connectTimeout=", ignoreCase = true)) return url
        val separator = if (url.contains('?')) '&' else '?'
        return "$url${separator}connectTimeout=$CONNECT_TIMEOUT_MS"
    }

    /** ログやエラーメッセージから取り除く値（パスワード、URL に埋め込まれたパスワード）。 */
    fun secrets(): List<String> = listOf(password, urlPassword()).filter { it.isNotEmpty() }

    private fun urlPassword(): String =
        URL_PASSWORD
            .find(url)
            ?.groupValues
            ?.get(1)
            .orEmpty()

    override fun toString(): String = "DbConfig(url=<hidden>, user=$user, password=***)"

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        private val URL_PASSWORD = Regex("[?&;]password=([^&;]*)", RegexOption.IGNORE_CASE)

        fun fromEnv(env: Map<String, String>): ConfigResult {
            val url = env["DB_URL"]?.trim().orEmpty()
            val user = env["DB_USER"]?.trim().orEmpty()
            val password = env["DB_PASSWORD"]
            val missing =
                buildList {
                    if (url.isEmpty()) add("DB_URL")
                    if (user.isEmpty()) add("DB_USER")
                    if (password == null) add("DB_PASSWORD")
                }
            if (missing.isNotEmpty()) return ConfigResult.Invalid("必須の環境変数がありません: ${missing.joinToString(", ")}")
            if (!url.startsWith("jdbc:mysql://")) return ConfigResult.Invalid("DB_URL は jdbc:mysql:// で始まる必要があります")
            return ConfigResult.Ok(DbConfig(url, user, checkNotNull(password)))
        }
    }
}

sealed interface ConfigResult {
    data class Ok(
        val config: DbConfig,
    ) : ConfigResult

    data class Invalid(
        val message: String,
    ) : ConfigResult
}

/** 診断メッセージから秘密を取り除く。完全一致の値と、`password=…` 形式の両方を伏せる。 */
object Redactor {
    private val PASSWORD_PARAM = Regex("(?i)(password|passwd|pwd)=[^&;\\s,)]*")

    fun redact(
        text: String,
        secrets: Collection<String>,
    ): String {
        var result = text
        for (secret in secrets.filter { it.isNotEmpty() }.sortedByDescending { it.length }) {
            result = result.replace(secret, "***")
        }
        return PASSWORD_PARAM.replace(result) { "${it.groupValues[1]}=***" }
    }
}
