package jp.jrabot.persistence.jdbc

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DbSettingsTest {
    private val env = mapOf("DB_URL" to "jdbc:mysql://db:3306/jrabot", "DB_USER" to "app", "DB_PASSWORD" to "s3cret-value")

    @Test
    fun `環境変数から作れて、DB_POOL_MAX が既定値を上書きする`() {
        assertEquals(3, DbSettings.fromEnv(env, defaultPoolMax = 3).poolMax)
        assertEquals(5, DbSettings.fromEnv(env + ("DB_POOL_MAX" to "5"), defaultPoolMax = 3).poolMax)
        assertEquals(3, DbSettings.fromEnv(env + ("DB_POOL_MAX" to " "), defaultPoolMax = 3).poolMax)
    }

    @Test
    fun `必須の欠落・不正な URL・範囲外のプール上限は拒否する`() {
        assertFailsWith<IllegalArgumentException> { DbSettings.fromEnv(env - "DB_PASSWORD", 3) }
        assertFailsWith<IllegalArgumentException> { DbSettings.fromEnv(env + ("DB_URL" to "jdbc:postgresql://x/y"), 3) }
        assertFailsWith<IllegalArgumentException> { DbSettings.fromEnv(env + ("DB_POOL_MAX" to "0"), 3) }
        assertFailsWith<IllegalArgumentException> { DbSettings.fromEnv(env + ("DB_POOL_MAX" to "25"), 3) }
        assertFailsWith<IllegalArgumentException> { DbSettings.fromEnv(env + ("DB_POOL_MAX" to "many"), 3) }
        assertEquals(24, DbSettings.fromEnv(env + ("DB_POOL_MAX" to "24"), 3).poolMax)
    }

    @Test
    fun `パスワードと URL は toString に出ない`() {
        val text = DbSettings.fromEnv(env, 3).toString()
        assertFalse(text.contains("s3cret-value"))
        assertFalse(text.contains("jdbc:mysql"))
    }

    @Test
    fun `HikariCP の設定は契約どおり（接続タイムアウト 10 秒・UTC・useAffectedRows）`() {
        val config = DataSourceFactory.hikariConfig(DbSettings.fromEnv(env, 4), "t")
        assertEquals(10_000L, config.connectionTimeout)
        assertEquals(4, config.maximumPoolSize)
        assertEquals("SET time_zone = '+00:00'", config.connectionInitSql)
        assertTrue(config.maxLifetime < 8 * 60 * 60 * 1000L) // Cloud SQL の wait_timeout(既定 8 時間)より短い
        val props = config.dataSourceProperties
        assertEquals("true", props.getProperty("useAffectedRows"))
        assertEquals("UTC", props.getProperty("connectionTimeZone"))
        assertEquals("UTF-8", props.getProperty("characterEncoding"))
        assertEquals("false", props.getProperty("rewriteBatchedStatements"))
        assertEquals("false", props.getProperty("allowMultiQueries"))
    }
}
