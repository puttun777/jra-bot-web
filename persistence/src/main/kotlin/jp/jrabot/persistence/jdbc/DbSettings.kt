package jp.jrabot.persistence.jdbc

/**
 * DB 接続の設定（CONTRACTS §2・§8: `DB_URL` `DB_USER` `DB_PASSWORD` `DB_POOL_MAX`）。パスワードは `toString` に出さない。
 * プールの上限は実行単位ごとの既定値（§8.3）を [fromEnv] の引数で渡し、`DB_POOL_MAX` で上書きできる。
 */
class DbSettings(
    val url: String,
    val user: String,
    val password: String,
    val poolMax: Int,
) {
    init {
        require(url.startsWith("jdbc:mysql://")) { "DB_URL は jdbc:mysql:// で始まる必要がある" }
        require(user.isNotEmpty()) { "DB_USER が空" }
        require(poolMax in 1..MAX_POOL) { "プール上限は 1〜$MAX_POOL（全体の設計上限 24）: $poolMax" }
    }

    override fun toString(): String = "DbSettings(url=<hidden>, user=$user, password=***, poolMax=$poolMax)"

    companion object {
        /** 全実行単位が同時に動いた最悪の場合の合計が 24（CONTRACTS §8.3）。1 つのプールがそれを超えることはない。 */
        const val MAX_POOL = 24

        fun fromEnv(
            env: Map<String, String>,
            defaultPoolMax: Int,
        ): DbSettings {
            val missing = listOf("DB_URL", "DB_USER", "DB_PASSWORD").filter { env[it].isNullOrEmpty() }
            require(missing.isEmpty()) { "必須の環境変数がありません: ${missing.joinToString(", ")}" }
            val poolMax =
                env["DB_POOL_MAX"]?.takeIf { it.isNotBlank() }?.let {
                    requireNotNull(it.trim().toIntOrNull()) { "DB_POOL_MAX は整数: '$it'" }
                } ?: defaultPoolMax
            return DbSettings(env.getValue("DB_URL").trim(), env.getValue("DB_USER").trim(), env.getValue("DB_PASSWORD"), poolMax)
        }
    }
}
