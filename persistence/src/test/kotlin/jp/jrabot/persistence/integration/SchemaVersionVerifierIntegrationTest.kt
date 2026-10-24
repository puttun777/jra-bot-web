package jp.jrabot.persistence.integration

import com.zaxxer.hikari.HikariDataSource
import jp.jrabot.persistence.jdbc.DataSourceFactory
import jp.jrabot.persistence.jdbc.DbSettings
import jp.jrabot.persistence.schema.SchemaCheck
import jp.jrabot.persistence.schema.SchemaFailure
import jp.jrabot.persistence.schema.SchemaVersion
import jp.jrabot.persistence.schema.SchemaVersionVerifier
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** CONTRACTS §4.3: `flyway_schema_history` の最大成功版と REQUIRED_SCHEMA_VERSION の比較、接続不能と他の失敗の区別（本物の MySQL）。 */
@Tag("integration")
class SchemaVersionVerifierIntegrationTest {
    companion object {
        private val db = MySqlSupport.migratedDatabase()
        private val opened = mutableListOf<HikariDataSource>()

        private fun track(ds: javax.sql.DataSource): HikariDataSource = (ds as HikariDataSource).also { opened += it }

        private fun lazyPool(
            user: String,
            password: String = MySqlSupport.PASSWORDS.getValue(user),
            url: String = MySqlSupport.jdbcUrl(db.name),
        ) = track(DataSourceFactory.createLazy(DbSettings(url, user, password, 2), "schema-test-$user"))

        private fun verifier(
            user: String,
            required: String = "2",
            password: String = MySqlSupport.PASSWORDS.getValue(user),
            url: String = MySqlSupport.jdbcUrl(db.name),
        ) = SchemaVersionVerifier(lazyPool(user, password, url), SchemaVersion.parse(required))

        @JvmStatic
        @AfterAll
        fun close() = opened.forEach { it.close() }
    }

    @Test
    fun `適用済みの最大版が必要な版以上なら Satisfied（app・ingest・bot のどれでも読める）`() {
        for (user in listOf("app", "ingest", "bot")) {
            assertEquals(SchemaCheck.Satisfied(SchemaVersion.parse("4")), verifier(user).check(), user)
        }
    }

    @Test
    fun `必要な版が適用済みより新しければ Insufficient`() {
        val r = verifier("bot", required = "5").check()
        assertEquals(SchemaCheck.Insufficient(SchemaVersion.parse("4"), SchemaVersion.parse("5")), r)
    }

    @Test
    fun `適用済みが必要な版より新しいのは許容する（expand_contract）`() {
        assertEquals(SchemaCheck.Satisfied(SchemaVersion.parse("4")), verifier("bot", required = "1").check())
        val newer = MySqlSupport.migratedDatabase()
        newer.exec(
            "INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, " +
                "installed_by, installed_on, execution_time, success) " +
                "VALUES (99, '10', 'future', 'SQL', 'V10__future.sql', 0, 'migrate', NOW(), 1, 1)",
        )
        val ds = track(DataSourceFactory.createLazy(DbSettings(MySqlSupport.jdbcUrl(newer.name), "app", MySqlSupport.PASSWORDS.getValue("app"), 1), "newer"))
        assertEquals(SchemaCheck.Satisfied(SchemaVersion.parse("10")), SchemaVersionVerifier(ds, SchemaVersion.parse("2")).check())
    }

    @Test
    fun `失敗した版（success=0）は数えない`() {
        val d = MySqlSupport.migratedDatabase()
        d.exec(
            "INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, " +
                "installed_by, installed_on, execution_time, success) " +
                "VALUES (99, '5', 'broken', 'SQL', 'V5__broken.sql', 0, 'migrate', NOW(), 1, 0)",
        )
        val ds = track(DataSourceFactory.createLazy(DbSettings(MySqlSupport.jdbcUrl(d.name), "bot", MySqlSupport.PASSWORDS.getValue("bot"), 1), "failedver"))
        assertEquals(SchemaCheck.Insufficient(SchemaVersion.parse("4"), SchemaVersion.parse("5")), SchemaVersionVerifier(ds, SchemaVersion.parse("5")).check())
    }

    @Test
    fun `Repeatable（版なし）の行は無視し、10 は 9 より新しい（数値比較）`() {
        val d = MySqlSupport.migratedDatabase()
        d.exec(
            "INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, " +
                "installed_by, installed_on, execution_time, success) " +
                "VALUES (98, '9', 'nine', 'SQL', 'V9__nine.sql', 0, 'migrate', NOW(), 1, 1), (99, '10', 'ten', 'SQL', 'V10__ten.sql', 0, 'migrate', NOW(), 1, 1)",
        )
        val ds = track(DataSourceFactory.createLazy(DbSettings(MySqlSupport.jdbcUrl(d.name), "bot", MySqlSupport.PASSWORDS.getValue("bot"), 1), "numeric"))
        assertEquals(SchemaCheck.Satisfied(SchemaVersion.parse("10")), SchemaVersionVerifier(ds, SchemaVersion.parse("10")).check())
    }

    @Test
    fun `履歴の表が無い空の DB は Insufficient（現在版なし）で、マイグレーションを実行しない`() {
        val name =
            "jrempty" +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(8)
        MySqlSupport.rootConnection(null).use { c ->
            c.createStatement().use {
                it.execute("CREATE DATABASE `$name`")
                it.execute("GRANT SELECT ON `$name`.* TO 'bot'@'%'")
            }
        }
        val ds = track(DataSourceFactory.createLazy(DbSettings(MySqlSupport.jdbcUrl(name), "bot", MySqlSupport.PASSWORDS.getValue("bot"), 1), "empty"))
        val r = SchemaVersionVerifier(ds, SchemaVersion.parse("2")).check()
        assertEquals(SchemaCheck.Insufficient(null, SchemaVersion.parse("2")), r)
        // 何も作られていない
        MySqlSupport.rootConnection(name).use { c ->
            c.createStatement().use { s -> s.executeQuery("SHOW TABLES").use { assertEquals(false, it.next()) } }
        }
    }

    @Test
    fun `履歴を読む権限が無いユーザー(mltrain)は Failed(権限)であり、接続不能とは区別される`() {
        val r = verifier("mltrain").check()
        assertEquals(SchemaCheck.Failed(SchemaFailure.DATABASE_OR_PERMISSION), r)
    }

    @Test
    fun `パスワードが違うのは Failed(認証)で Unreachable ではない`() {
        assertEquals(SchemaCheck.Failed(SchemaFailure.AUTHENTICATION), verifier("bot", password = "wrong-password").check())
    }

    @Test
    fun `存在しない DB は Failed`() {
        val r = verifier("bot", url = MySqlSupport.jdbcUrl("no_such_db_xyz")).check()
        assertIs<SchemaCheck.Failed>(r)
        assertEquals(SchemaFailure.DATABASE_OR_PERMISSION, r.reason)
    }

    @Test
    fun `DB に接続できないのは Unreachable、復旧すれば同じプールで Satisfied に戻る`() {
        val (host, port) = MySqlSupport.hostAndPort()
        TcpProxy(host, port).start().use { proxy ->
            val url = "jdbc:mysql://${proxy.jdbcHost}:${proxy.port}/${db.name}"
            val v = verifier("bot", url = url)
            assertEquals(SchemaCheck.Satisfied(SchemaVersion.parse("4")), v.check())
            proxy.stop()
            assertEquals(SchemaCheck.Unreachable, v.check())
            proxy.start()
            assertEquals(SchemaCheck.Satisfied(SchemaVersion.parse("4")), v.check())
        }
    }

    @Test
    fun `最初から接続できないホストも Unreachable`() {
        // 接続を受け付けないポート（使い終わったループバックのポート）
        val closed = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }
        assertEquals(SchemaCheck.Unreachable, verifier("bot", url = "jdbc:mysql://127.0.0.1:$closed/${db.name}").check())
    }

    @Test
    fun `遅延 DataSource は作っただけでは DB に接続しない`() {
        val before = MySqlSupport.serverConnections()
        val ds = lazyPool("bot")
        Thread.sleep(1500) // プールの起動やハウスキーパーが接続するなら、この間に増える
        val after = MySqlSupport.serverConnections()
        assertEquals(1L, after - before, "確認用の接続 1 本だけが増えるはず（プールが接続していない）")
        assertNull(ds.hikariPoolMXBean, "プールは最初の getConnection まで起動しない")
        // 使うと接続する
        ds.connection.use { }
        assertEquals(true, ds.hikariPoolMXBean != null)
    }
}
