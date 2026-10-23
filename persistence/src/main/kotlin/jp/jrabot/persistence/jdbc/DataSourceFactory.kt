package jp.jrabot.persistence.jdbc

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

/**
 * HikariCP の DataSource を作る（CONTRACTS §8.1・§8.3）。接続の約束事:
 * - UTC: セッションの `time_zone` を `+00:00` に固定する（`connectionInitSql`）。JVM の既定タイムゾーンには依存しない
 *   （時刻の型変換は [jp.jrabot.persistence.doma.UtcJdbcMappingVisitor] が `LocalDateTime` をそのまま渡す）。
 * - `useAffectedRows=true`: UPDATE / `ON DUPLICATE KEY UPDATE` が「値が変わらなかった行」を 0 件と数える。upsert の
 *   `UNCHANGED` の判定がこれに依存する（既定の `false` では 1 件になる）。
 * - `rewriteBatchedStatements=false`・`allowMultiQueries=false`: バッチごとの更新件数を得る・複文を許さない。
 * - UTF-8（utf8mb4）、接続タイムアウト 10 秒。
 */
object DataSourceFactory {
    const val CONNECTION_TIMEOUT_MS = 10_000L

    /** Cloud SQL の `wait_timeout`（既定 8 時間）より十分短い。 */
    const val MAX_LIFETIME_MS = 25 * 60 * 1000L

    fun create(
        settings: DbSettings,
        poolName: String = "jrabot-db",
    ): HikariDataSource = HikariDataSource(hikariConfig(settings, poolName))

    internal fun hikariConfig(
        settings: DbSettings,
        poolName: String,
    ): HikariConfig =
        HikariConfig().apply {
            jdbcUrl = settings.url
            username = settings.user
            password = settings.password
            this.poolName = poolName
            maximumPoolSize = settings.poolMax
            minimumIdle = minOf(1, settings.poolMax)
            connectionTimeout = CONNECTION_TIMEOUT_MS
            maxLifetime = MAX_LIFETIME_MS
            connectionInitSql = "SET time_zone = '+00:00'"
            for ((key, value) in REQUIRED_PROPERTIES) addDataSourceProperty(key, value)
        }

    /** URL に何が書かれていても、この値で上書きする（契約の一部なので設定で外せない）。 */
    internal val REQUIRED_PROPERTIES: Map<String, String> =
        linkedMapOf(
            "connectionTimeZone" to "UTC",
            "characterEncoding" to "UTF-8",
            "useAffectedRows" to "true",
            "rewriteBatchedStatements" to "false",
            "allowMultiQueries" to "false",
        )
}
