package jp.jrabot.persistence.integration

import com.zaxxer.hikari.HikariDataSource
import jp.jrabot.domain.repository.UpsertOutcome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.seasar.doma.jdbc.Config
import org.seasar.doma.jdbc.dialect.MysqlDialect
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** G-VER-2（Doma 3.14.1 + kapt + Kotlin 2.4.21 + doma-spring-boot-starter 3.0.0）と接続の約束事（UTC・useAffectedRows）の確認。 */
@Tag("integration")
class WiringIntegrationTest {
    companion object {
        private val db = MySqlSupport.migratedDatabase()
        private val h = RepositoryHarness(db)

        @JvmStatic
        @AfterAll
        fun close() = h.close()
    }

    @Test
    fun `サーバは想定した版で動いている`() {
        println("MySQL server version = ${MySqlSupport.serverVersion()}")
        assertTrue(MySqlSupport.serverVersion().startsWith(MySqlSupport.image.substringAfter(':').substringBefore('-')))
    }

    @Test
    fun `使っている Connector_J の版を記録し、版を差し替えた構成(G-DB-0b)では指定どおりの版が使われている`() {
        val version =
            h.dataSource.connection.use { it.metaData.driverVersion }
        println("MySQL Connector/J driver version = $version")
        val expected = System.getProperty("expected.connectorj.version").orEmpty()
        if (expected.isNotEmpty()) assertTrue(version.contains(expected), "Connector/J の版が $expected ではない: $version")
    }

    @Test
    fun `starter が作る Config は JVM のタイムゾーンに依存しない自前の方言を使う`() {
        val config = h.bean(Config::class.java)
        assertIs<MysqlDialect>(config.dialect)
        assertEquals("jp.jrabot.persistence.doma.UtcJdbcMappingVisitor", config.dialect.jdbcMappingVisitor.javaClass.name)
    }

    @Test
    fun `DAO は kapt が生成した実装で実行できる`() {
        // 生成された RaceDaoImpl（SQL テンプレート）を通る一番短い経路
        assertEquals(UpsertOutcome.CREATED, h.races.upsert(Fixtures.race()))
        assertEquals(Fixtures.race(), h.races.find(T1, Fixtures.raceId()))
    }

    @Test
    fun `接続のセッションは UTC 固定で、更新件数は変更なしを 0 と数える`() {
        h.dataSource.connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT @@session.time_zone, @@session.character_set_client, @@session.character_set_connection").use {
                    it.next()
                    assertEquals("+00:00", it.getString(1))
                    assertEquals("utf8mb4", it.getString(2))
                    assertEquals("utf8mb4", it.getString(3))
                }
            }
        }
        val ds = h.dataSource as HikariDataSource
        assertEquals(10_000L, ds.connectionTimeout)
        assertEquals(4, ds.maximumPoolSize)
        assertEquals("true", ds.dataSourceProperties.getProperty("useAffectedRows"))
    }
}
