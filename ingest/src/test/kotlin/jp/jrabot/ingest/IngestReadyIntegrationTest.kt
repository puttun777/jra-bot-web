package jp.jrabot.ingest

import jp.jrabot.domain.ops.OpsMode
import jp.jrabot.persistence.integration.MySqlSupport
import jp.jrabot.persistence.integration.TcpProxy
import jp.jrabot.persistence.integration.TestDb
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `/internal/ready` と DB（本物の MySQL 8.4 / 9.7 のコンテナ。スキーマは db-migration CLI の V1/V2）。DB ユーザーは本番と同じ `ingest`。
 * 実 GCP・実データなし。DB の停止/復旧は TCP 中継で再現する。
 */
@Tag("integration")
class IngestReadyIntegrationTest {
    companion object {
        private val db: TestDb = MySqlSupport.migratedDatabase()
        private val proxy = MySqlSupport.hostAndPort().let { (h, p) -> TcpProxy(h, p).start() }
        private val ops = writeOpsState(Files.createTempDirectory("ingest-it"))

        @JvmStatic
        @AfterAll
        fun stop() = proxy.close()

        private fun dbUrl(viaProxy: Boolean = true) = if (viaProxy) "jdbc:mysql://127.0.0.1:${proxy.port}/${db.name}" else MySqlSupport.jdbcUrl(db.name)

        private fun run(
            vararg extra: String,
            guardConfig: Boolean = true,
            password: String = MySqlSupport.PASSWORDS.getValue("ingest"),
            url: String = dbUrl(),
        ): Pair<ConfigurableApplicationContext, Http> {
            val props =
                baseProperties(ops).filterNot { it.startsWith("DB_URL=") || it.startsWith("DB_PASSWORD=") } +
                    listOf("DB_URL=$url", "DB_PASSWORD=$password", "server.port=0") + extra
            val sources =
                if (guardConfig) {
                    arrayOf(
                        IngestApplication::class.java,
                        TestAuthConfig::class.java,
                        AgentGuardTestConfig::class.java,
                    )
                } else {
                    arrayOf(IngestApplication::class.java, TestAuthConfig::class.java)
                }
            val ctx = SpringApplicationBuilder(*sources).run(*props.map { "--$it" }.toTypedArray())
            return ctx to Http(ctx.environment.serverPort())
        }

        private fun setHistory(sql: String) = db.exec(sql)

        private fun insertVersion(
            rank: Int,
            version: String,
            success: Int = 1,
        ) = setHistory(
            "INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, " +
                "installed_by, installed_on, execution_time, success) " +
                "VALUES ($rank, '$version', 'synthetic', 'SQL', 'V$version.sql', 0, 'migrate', NOW(), 1, $success)",
        )
    }

    @Test
    fun `正常な DB(V2 適用済み)では ready=200 で、現在のスキーマ版を返す`() {
        val (ctx, http) = run()
        ctx.use {
            val r = http.get("/internal/ready", TestAuth.validToken())
            assertEquals(200, r.status, r.body)
            assertEquals("""{"status":"READY","schemaVersion":"2","requiredSchemaVersion":"2"}""", r.body)
        }
    }

    @Test
    fun `ready はモードを見ない（STARTING STOPPING STOPPED UNKNOWN でも DB が良ければ 200）`() {
        val (ctx, http) = run()
        ctx.use {
            val mode = ctx.getBean(ControllableModeProvider::class.java)
            for (m in OpsMode.entries) {
                mode.mode = m
                assertEquals(200, http.get("/internal/ready", TestAuth.validToken()).status, "mode=$m")
            }
        }
    }

    @Test
    fun `ops mode の API はモードを見せるが ready の結果とは独立`() {
        val (ctx, http) = run(guardConfig = false)
        ctx.use {
            assertEquals(200, http.get("/internal/ready", TestAuth.validToken()).status)
            val r = http.get("/internal/ops/mode", TestAuth.validToken())
            assertEquals(200, r.status)
            assertTrue(r.body.contains(""""source":"file""""), r.body)
        }
    }

    @Test
    fun `DB が停止すると ready=503 でも health は 200 のまま、復旧すれば 200 に戻る`() {
        val (ctx, http) = run()
        ctx.use {
            assertEquals(200, http.get("/internal/ready", TestAuth.validToken()).status)
            proxy.stop()
            try {
                val down = http.get("/internal/ready", TestAuth.validToken())
                assertEquals(503, down.status, down.body)
                assertTrue(down.body.contains("DB_UNAVAILABLE"), down.body)
                assertEquals(200, http.get("/internal/health", TestAuth.validToken()).status, "DB が止まっていても health は 200")
            } finally {
                proxy.start()
            }
            assertEquals(200, http.get("/internal/ready", TestAuth.validToken()).status)
        }
    }

    @Test
    fun `スキーマ版が足りなければ ready=503、新しければ 200（mode が NORMAL でなくても判定する）`() {
        val (ctx, http) = run()
        ctx.use {
            ctx.getBean(ControllableModeProvider::class.java).mode = OpsMode.STARTING
            assertEquals(200, http.get("/internal/ready", TestAuth.validToken()).status)
            // V2 を「失敗」に変えると、成功した最大版は 1 になる
            setHistory("UPDATE flyway_schema_history SET success = 0 WHERE version = '2'")
            try {
                val r = http.get("/internal/ready", TestAuth.validToken())
                assertEquals(503, r.status, r.body)
                assertEquals("""{"status":"NOT_READY","reason":"SCHEMA_INSUFFICIENT","schemaVersion":"1","requiredSchemaVersion":"2"}""", r.body)
            } finally {
                setHistory("UPDATE flyway_schema_history SET success = 1 WHERE version = '2'")
            }
            // 新しい版が入っていても許容する（expand/contract）
            insertVersion(900, "10")
            try {
                val r = http.get("/internal/ready", TestAuth.validToken())
                assertEquals(200, r.status, r.body)
                assertTrue(r.body.contains(""""schemaVersion":"10""""), r.body)
            } finally {
                setHistory("DELETE FROM flyway_schema_history WHERE version = '10'")
            }
            assertEquals(200, http.get("/internal/ready", TestAuth.validToken()).status)
        }
    }

    @Test
    fun `接続はできるが履歴の表が無い DB は ready=503 SCHEMA_INSUFFICIENT`() {
        val empty =
            "jrnohist" +
                java.util.UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(8)
        MySqlSupport.rootConnection(null).use { c ->
            c.createStatement().use {
                it.execute("CREATE DATABASE `$empty`")
                it.execute("GRANT SELECT ON `$empty`.* TO 'ingest'@'%'")
            }
        }
        val (ctx, http) = run(url = MySqlSupport.jdbcUrl(empty))
        ctx.use {
            val r = http.get("/internal/ready", TestAuth.validToken())
            assertEquals(503, r.status, r.body)
            assertTrue(r.body.contains("SCHEMA_INSUFFICIENT") && r.body.contains(""""schemaVersion":null"""), r.body)
        }
    }

    @Test
    fun `パスワードが違うと ready=503 DB_AUTH_FAILED（接続不能とは区別し、秘密を出さない）`() {
        val (ctx, http) = run(password = "wrong-password-xyz", url = dbUrl(viaProxy = false))
        ctx.use {
            val r = http.get("/internal/ready", TestAuth.validToken())
            assertEquals(503, r.status, r.body)
            assertTrue(r.body.contains("DB_AUTH_FAILED"), r.body)
            assertFalse(r.body.contains("wrong-password-xyz"))
            assertEquals(200, http.get("/internal/health", TestAuth.validToken()).status)
        }
    }

    @Test
    fun `NORMAL 以外のエージェント API の拒否では DB に接続しない（サーバの接続数が増えない）`() {
        val (ctx, http) = run(url = dbUrl(viaProxy = false))
        ctx.use {
            val mode = ctx.getBean(ControllableModeProvider::class.java)
            val probe = ctx.getBean(AgentProbe::class.java)
            val before = MySqlSupport.serverConnections()
            for (m in listOf(OpsMode.STARTING, OpsMode.STOPPING, OpsMode.STOPPED, OpsMode.UNKNOWN)) {
                mode.mode = m
                assertEquals(503, http.get("/v1/agent/plan").status, "$m")
                assertEquals(503, http.send("POST", "/v1/agent/ingest").status, "$m")
            }
            assertEquals(0, probe.reached.get())
            val after = MySqlSupport.serverConnections()
            assertEquals(1L, after - before, "確認用の 1 本以外に DB 接続が発生した")
        }
    }

    @Test
    fun `使う DB ユーザーは ingest（最小権限）で、他の権限が必要な操作はできない`() {
        val (ctx, _) = run()
        ctx.use {
            val ds = ctx.getBean(javax.sql.DataSource::class.java)
            ds.connection.use { c ->
                c.createStatement().use { s ->
                    s.executeQuery("SELECT CURRENT_USER()").use { rs ->
                        rs.next()
                        assertTrue(rs.getString(1).startsWith("ingest@"))
                    }
                }
                val e = runCatching { c.createStatement().use { it.execute("CREATE TABLE should_not_exist (x INT)") } }.exceptionOrNull()
                assertTrue(e != null, "ingest ユーザーで DDL が実行できてしまった")
            }
        }
    }
}
