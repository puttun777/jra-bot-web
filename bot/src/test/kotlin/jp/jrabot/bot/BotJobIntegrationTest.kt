package jp.jrabot.bot

import jp.jrabot.domain.ops.OpsMode
import jp.jrabot.persistence.integration.MySqlSupport
import jp.jrabot.persistence.integration.TcpProxy
import jp.jrabot.persistence.integration.TestDb
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Job の全体（モード → Spring → スキーマ版 → タスク → 終了コード）を、本物の MySQL 8.4 / 9.7 のコンテナ（V1/V2 を db-migration CLI で適用）で確かめる。
 * DB ユーザーは本番と同じ `bot`。実 GCP・実データなし。モードは合成のローカルファイル。
 */
@Tag("integration")
class BotJobIntegrationTest {
    companion object {
        private val db: TestDb = MySqlSupport.migratedDatabase()
        private val proxy = MySqlSupport.hostAndPort().let { (h, p) -> TcpProxy(h, p).start() }
        private val dir: Path = Files.createTempDirectory("bot-it")

        @JvmStatic
        @AfterAll
        fun stop() = proxy.close()

        private fun ops(mode: String) = writeOpsState(Files.createTempDirectory(dir, "ops"), mode).toString()

        private fun env(
            mode: String,
            url: String = MySqlSupport.jdbcUrl(db.name),
            password: String = MySqlSupport.PASSWORDS.getValue("bot"),
        ) = baseEnv(ops(mode), "DB_URL" to url, "DB_PASSWORD" to password)

        private fun exit(
            task: String,
            env: Map<String, String>,
        ) = BotLauncher().run(args(task), env)
    }

    @Test
    fun `NORMAL かつスキーマ版 OK なら、スタブのタスクはローカルでは終了コード 0（calendar-check・export-dataset は実装済みで別のテスト）`() {
        for (t in BotTask.entries.filter { it != BotTask.CALENDAR_CHECK && it != BotTask.EXPORT_DATASET }) assertEquals(0, exit(t.cliName, env("NORMAL")), t.cliName)
    }

    @Test
    fun `calendar-check は実装済みなので、予定の無い DB では不明（取得の事実なし）として 75 になりスタブの成功にならない`() {
        assertEquals(75, exit("calendar-check", env("NORMAL")))
    }

    @Test
    fun `本番(APP_ENV=prod)ではスタブは成功にならず終了コード 1 TASK_NOT_IMPLEMENTED`() {
        // 本番はローカルファイルのモードを使えないので、モードだけフェイクにして、それ以外（Spring・DB・スキーマ確認）は本物で通す
        val launcher = BotLauncher(modeProviderFactory = { FixedMode(OpsMode.NORMAL) })
        val prodEnv = env("NORMAL") + mapOf("APP_ENV" to "prod", "OPS_MODE_SOURCE" to "gs://synthetic/ops.json")
        for (t in BotTask.entries.filter { it != BotTask.CALENDAR_CHECK && it != BotTask.EXPORT_DATASET }) assertEquals(1, launcher.run(args(t.cliName), prodEnv), t.cliName)
    }

    @Test
    fun `STARTING STOPPING STOPPED は終了コード 0、UNKNOWN は 75 で、DB に一切接続しない`() {
        val before = MySqlSupport.serverConnections()
        for (t in BotTask.entries) {
            for (m in listOf("STARTING", "STOPPING", "STOPPED")) assertEquals(0, exit(t.cliName, env(m)), "${t.cliName} $m")
            val unknown = env("NORMAL").toMutableMap().also { it["OPS_MODE_SOURCE"] = dir.resolve("missing-${t.cliName}.json").toString() }
            assertEquals(75, exit(t.cliName, unknown), "${t.cliName} UNKNOWN")
        }
        val after = MySqlSupport.serverConnections()
        assertEquals(1L, after - before, "確認用の 1 本以外に DB 接続が発生した（モード確認の前後でサーバの接続数が増えている）")
    }

    @Test
    fun `NORMAL のときだけ DB に接続してスキーマ版を確認する`() {
        val before = MySqlSupport.serverConnections()
        assertEquals(0, exit("reconcile", env("NORMAL")))
        val after = MySqlSupport.serverConnections()
        assertTrue(after - before >= 2, "NORMAL で DB に接続していない: ${after - before}")
    }

    @Test
    fun `スキーマ版が足りなければ 1、DB に接続できなければ 75 と区別される`() {
        db.exec("UPDATE flyway_schema_history SET success = 0 WHERE version = '4'")
        try {
            assertEquals(1, exit("reconcile", env("NORMAL")), "スキーマ不足は 1")
        } finally {
            db.exec("UPDATE flyway_schema_history SET success = 1 WHERE version = '4'")
        }
        assertEquals(0, exit("reconcile", env("NORMAL")))
        proxy.stop()
        try {
            val viaProxy = env("NORMAL", url = "jdbc:mysql://127.0.0.1:${proxy.port}/${db.name}")
            assertEquals(75, exit("reconcile", viaProxy), "DB 不達は 75")
        } finally {
            proxy.start()
        }
        assertEquals(0, exit("reconcile", env("NORMAL", url = "jdbc:mysql://127.0.0.1:${proxy.port}/${db.name}")))
    }

    @Test
    fun `スキーマ版が必要な版より新しいのは許容する`() {
        db.exec(
            "INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, " +
                "installed_by, installed_on, execution_time, success) " +
                "VALUES (900, '10', 'future', 'SQL', 'V10__future.sql', 0, 'migrate', NOW(), 1, 1)",
        )
        try {
            assertEquals(0, exit("retention", env("NORMAL")))
        } finally {
            db.exec("DELETE FROM flyway_schema_history WHERE version = '10'")
        }
    }

    @Test
    fun `認証失敗・存在しない DB は 75 ではなく 1`() {
        assertEquals(1, exit("calendar-check", env("NORMAL", password = "wrong-password")))
        assertEquals(1, exit("calendar-check", env("NORMAL", url = MySqlSupport.jdbcUrl("no_such_db_xyz"))))
    }

    @Test
    fun `履歴の表が無い DB は 1（マイグレーションを勝手に実行しない）`() {
        val empty =
            "jrbotempty" +
                java.util.UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(8)
        MySqlSupport.rootConnection(null).use { c ->
            c.createStatement().use {
                it.execute("CREATE DATABASE `$empty`")
                it.execute("GRANT SELECT ON `$empty`.* TO 'bot'@'%'")
            }
        }
        assertEquals(1, exit("calendar-check", env("NORMAL", url = MySqlSupport.jdbcUrl(empty))))
        MySqlSupport.rootConnection(empty).use { c ->
            c.createStatement().use { s -> s.executeQuery("SHOW TABLES").use { assertEquals(false, it.next(), "表が作られている") } }
        }
    }
}
