package jp.jrabot.dashboard

import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 設定が足りない・不正なときに、安全に（起動を失敗させて）止まる。本番でローカル実装へ黙って切り替えない。DB・GCP には接続しない。 */
class DashboardStartupConfigTest {
    private val ops = writeOpsState(Files.createTempDirectory("ingest-startup"))

    private fun start(vararg props: String): ConfigurableApplicationContext =
        SpringApplicationBuilder(DashboardApplication::class.java, TestAuthConfig::class.java)
            .web(WebApplicationType.NONE)
            .properties(*props)
            .run(*props.map { "--$it" }.toTypedArray())

    private fun base(
        vararg override: Pair<String, String?>,
    ): Array<String> {
        val m = baseProperties(ops).associate { it.substringBefore('=') to it.substringAfter('=') }.toMutableMap()
        override.forEach { (k, v) -> if (v == null) m.remove(k) else m[k] = v }
        return m.map { "${it.key}=${it.value}" }.toTypedArray()
    }

    private fun rootMessages(e: Throwable): String = generateSequence(e) { it.cause }.joinToString(" | ") { it.message.orEmpty() }

    @Test
    fun `必須の環境変数が 1 つでも欠けると起動に失敗し、メッセージに秘密を含めない`() {
        // 他のテストと同じ JVM の環境変数に値が入っていないこと（入っていれば欠けにならない）を前提にする
        for (key in listOf("APP_ENV", "DB_URL", "DB_USER", "DB_PASSWORD", "OPS_MODE_SOURCE", "IAP_AUDIENCE", "OPS_SA_EMAIL")) {
            assertTrue(System.getenv(key) == null, "テスト環境に $key が設定されている")
            val e = assertFailsWith<Throwable>(key) { start(*base(key to null)).close() }
            val msg = rootMessages(e)
            assertTrue(msg.contains(key), "$key が欠けていることがメッセージで分かる: $msg")
            assertFalse(msg.contains("synthetic-password-never-logged"), "秘密がメッセージに出ている")
        }
    }

    @Test
    fun `APP_ENV が local でも prod でもない値は起動に失敗する`() {
        for (v in listOf("", "dev", "production", "LOCAL", "stg")) {
            assertFailsWith<Throwable>(v) { start(*base("APP_ENV" to v)).close() }
        }
    }

    @Test
    fun `本番(APP_ENV=prod)でローカルファイルのモードは使えない（ローカル実装へ黙ってフォールバックしない）`() {
        val e = assertFailsWith<Throwable> { start(*base("APP_ENV" to "prod", "DB_USER" to "app")).close() }
        assertTrue(rootMessages(e).contains("APP_ENV=local"), rootMessages(e))
    }

    @Test
    fun `本番では dashboard 用の DB ユーザー(app)以外（migrate など）では起動しない`() {
        val gcs = "OPS_MODE_SOURCE" to "gs://synthetic-bucket/state/ops-state.json"
        for (user in listOf("migrate", "ingest", "bot", "root")) {
            assertFailsWith<Throwable>(user) { start(*base("APP_ENV" to "prod", "DB_USER" to user, gcs)).close() }
        }
    }

    @Test
    fun `本番の gs の設定ならネットワークにも GCP にも触れずに起動できる`() {
        start(*base("APP_ENV" to "prod", "DB_USER" to "app", "OPS_MODE_SOURCE" to "gs://synthetic-bucket/state/ops-state.json")).use { ctx ->
            assertNotNull(ctx.getBean(jp.jrabot.domain.ops.ModeProvider::class.java))
        }
    }

    @Test
    fun `ローカル(APP_ENV=local)はローカルファイルで GCP の認証なしに起動できる`() {
        start(*base()).use { ctx -> assertNotNull(ctx.getBean(jp.jrabot.domain.ops.ModeProvider::class.java)) }
    }

    @Test
    fun `OPS_MODE_SOURCE が不正な形式・DB_POOL_MAX が範囲外なら起動に失敗する`() {
        assertFailsWith<Throwable> { start(*base("OPS_MODE_SOURCE" to "http://example.com/x")).close() }
        assertFailsWith<Throwable> { start(*base("OPS_MODE_SOURCE" to "gs://only-bucket")).close() }
        assertFailsWith<Throwable> { start(*base("DB_POOL_MAX" to "0")).close() }
        assertFailsWith<Throwable> { start(*base("DB_POOL_MAX" to "25")).close() }
        assertFailsWith<Throwable> { start(*base("DB_POOL_MAX" to "abc")).close() }
    }

    @Test
    fun `DB_POOL_MAX でプール上限を調整できる（既定は 3）`() {
        start(*base("DB_POOL_MAX" to "2")).use { ctx ->
            assertEquals(2, (ctx.getBean(javax.sql.DataSource::class.java) as com.zaxxer.hikari.HikariDataSource).maximumPoolSize)
        }
        start(*base()).use { ctx ->
            assertEquals(3, (ctx.getBean(javax.sql.DataSource::class.java) as com.zaxxer.hikari.HikariDataSource).maximumPoolSize)
        }
    }
}
