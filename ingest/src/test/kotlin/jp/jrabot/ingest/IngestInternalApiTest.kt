package jp.jrabot.ingest

import com.zaxxer.hikari.HikariDataSource
import jp.jrabot.cloudgcp.CloudGcpModule
import jp.jrabot.domain.DomainModule
import jp.jrabot.domain.ops.ModeProvider
import jp.jrabot.persistence.PersistenceModule
import jp.jrabot.persistence.schema.SchemaVersionVerifier
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import jp.jrabot.domain.race.RaceRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 実際の Tomcat（ランダムポート）で動かす ingest。DB は接続を受け付けないポート、運用モードは合成のローカルファイル。
 * 実 GCP・実 DB・実データには接続しない。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestAuthConfig::class)
class IngestInternalApiTest {
    companion object {
        private val dir: Path = Files.createTempDirectory("ingest-test")
        private val ops = writeOpsState(dir, seq = 12, mode = "NORMAL", desired = "NORMAL")

        @JvmStatic
        @DynamicPropertySource
        fun props(r: DynamicPropertyRegistry) {
            baseProperties(ops).forEach { kv -> r.add(kv.substringBefore('=')) { kv.substringAfter('=') } }
        }

        private val internalPaths = listOf("/internal/health", "/internal/ready", "/internal/ops/mode")
    }

    @Autowired lateinit var context: ApplicationContext

    @Autowired lateinit var env: Environment

    private val http get() = Http(env.serverPort())

    @Test
    fun `コンテキストが起動し、DI が解決され、共有モジュールがある`() {
        assertNotNull(context.getBean(ModeProvider::class.java))
        assertNotNull(context.getBean(RaceRepository::class.java))
        assertTrue(context.getBeansOfType(Class.forName("org.seasar.doma.jdbc.Config")).isNotEmpty(), "Doma の Config が構成されている")
        assertNotNull(context.getBean(SchemaVersionVerifier::class.java))
        assertEquals("2", context.getBean(SchemaVersionVerifier::class.java).required.toString())
        assertEquals(listOf("domain", "persistence", "cloud-gcp"), listOf(DomainModule.NAME, PersistenceModule.NAME, CloudGcpModule.NAME))
    }

    @Test
    fun `起動しただけでは DB に接続しない（プールは最初の接続まで起動しない）`() {
        val ds = context.getBean(javax.sql.DataSource::class.java) as HikariDataSource
        assertNull(ds.hikariPoolMXBean, "起動時にプールが接続を試みている")
        assertEquals(3, ds.maximumPoolSize, "ingest のプール既定値は 3（CONTRACTS §8.3）")
        assertEquals("ingest", ds.username)
    }

    @Test
    fun `Flyway は組み込まれていない（アプリの起動でマイグレーションを実行しない）`() {
        assertFalse(context.beanDefinitionNames.any { it.contains("flyway", ignoreCase = true) })
        assertTrue(runCatching { Class.forName("org.flywaydb.core.Flyway") }.isFailure, "Flyway がクラスパスにある")
    }

    @Test
    fun `health は DB が停止していても 200 を返し、DB を使わない`() {
        val r = http.get("/internal/health", TestAuth.validToken())
        assertEquals(200, r.status)
        assertEquals("""{"status":"UP","release":"dev"}""", r.body)
        assertEquals("no-store", r.header("Cache-Control"))
        assertNull((context.getBean(javax.sql.DataSource::class.java) as HikariDataSource).hikariPoolMXBean, "health がプールを起動した")
    }

    @Test
    fun `ready は DB に接続できなければ 503 DB_UNAVAILABLE（接続情報を含まない）`() {
        val r = http.get("/internal/ready", TestAuth.validToken())
        assertEquals(503, r.status)
        assertTrue(r.body.contains(""""status":"NOT_READY""""), r.body)
        assertTrue(r.body.contains(""""reason":"DB_UNAVAILABLE""""), r.body)
        assertTrue(r.body.contains(""""requiredSchemaVersion":"2""""), r.body)
        for (secret in listOf("synthetic-password-never-logged", "127.0.0.1", "jdbc:", "Exception", "ingest")) assertFalse(r.body.contains(secret), "本文に $secret が含まれる: ${r.body}")
    }

    @Test
    fun `ops mode は CONTRACTS 3_2 の形（mode desired seq cacheAgeMs source）で、正本の全文を返さない`() {
        val r = http.get("/internal/ops/mode", TestAuth.validToken())
        assertEquals(200, r.status)
        val body = r.body
        assertTrue(body.contains(""""mode":"NORMAL""""), body)
        assertTrue(body.contains(""""desired":"NORMAL""""), body)
        assertTrue(body.contains(""""seq":12"""), body)
        assertTrue(Regex(""""cacheAgeMs":\d+""").containsMatchIn(body), body)
        assertTrue(body.contains(""""source":"file""""), "ローカルファイルは gcs と偽らない: $body")
        for (leak in listOf("set_by", "github:synthetic", "operation", "schema_version", ops.toString(), "reason")) assertFalse(body.contains(leak), "漏れている: $leak")
        assertEquals(setOf("mode", "desired", "seq", "cacheAgeMs", "source"), Regex(""""(\w+)":""").findAll(body).map { it.groupValues[1] }.toSet())
    }

    @Test
    fun `ops mode を何度呼んでも正本の取得は 15 秒キャッシュ（診断のたびに読み直さない）`() {
        val first = http.get("/internal/ops/mode", TestAuth.validToken()).body
        Files.writeString(ops, Files.readString(ops).replace("\"seq\":12", "\"seq\":13"))
        try {
            repeat(5) { assertEquals(200, http.get("/internal/ops/mode", TestAuth.validToken()).status) }
            // キャッシュ内なので seq は更新されない（ファイルを書き換えても取り直さない）
            assertTrue(http.get("/internal/ops/mode", TestAuth.validToken()).body.contains(""""seq":12"""), first)
        } finally {
            Files.writeString(ops, Files.readString(ops).replace("\"seq\":13", "\"seq\":12"))
        }
    }

    // ---- 認証（CONTRACTS §11.2） ----

    @Test
    fun `無認証の internal はすべて 401 で、本文に内部情報を含まない`() {
        for (path in internalPaths) {
            val r = http.get(path)
            assertEquals(401, r.status, path)
            assertEquals("Bearer", r.header("WWW-Authenticate"))
            assertEquals("""{"error":"unauthorized"}""", r.body)
        }
    }

    @Test
    fun `認証情報が不正なら 401（署名違い・audience 違い・期限切れ・主体違い・email 未検証・issuer 違い・Bearer 以外）`() {
        val other = jp.jrabot.cloudgcp.auth.testing.TestSigner.rs256(TestAuth.signer.kid)
        val bad =
            mapOf(
                "ゴミ" to "garbage",
                "別の鍵の署名" to other.token(jp.jrabot.cloudgcp.auth.testing.Claims.idToken(TestAuth.clock.instant, TestAuth.AUDIENCE, TestAuth.OPS_EMAIL)),
                "audience 違い" to TestAuth.token(audience = "https://other.run.app"),
                "期限切れ" to TestAuth.token(expiresInSeconds = -600),
                "別の主体" to TestAuth.token(email = "someone@example.com"),
                "email 未検証" to TestAuth.token(emailVerified = false),
                "issuer 違い" to TestAuth.token(issuer = "https://evil.example.com"),
            )
        for ((label, token) in bad) {
            for (path in internalPaths) assertEquals(401, http.get(path, token).status, "$label $path")
        }
        // Bearer 以外のスキーム・トークン空
        for (header in listOf("Basic ${TestAuth.validToken()}", "Bearer", "Bearer  ", TestAuth.validToken())) {
            assertEquals(401, http.send("GET", "/internal/health", headers = mapOf("Authorization" to header)).status, header.take(12))
        }
        // IAP のヘッダーは ingest の認証として扱わない（経路ごとに認証方式を分ける）
        assertEquals(401, http.send("GET", "/internal/health", headers = mapOf("X-Goog-IAP-JWT-Assertion" to TestAuth.validToken())).status)
    }

    @Test
    fun `正しい ops 主体だけが internal を参照できる`() {
        for (path in internalPaths) assertNotEquals(401, http.get(path, TestAuth.validToken()).status, path)
        assertEquals(200, http.get("/internal/health", TestAuth.validToken()).status)
        assertEquals(200, http.get("/internal/ops/mode", TestAuth.validToken()).status)
        assertEquals(503, http.get("/internal/ready", TestAuth.validToken()).status) // 認証は通るが DB が無い
    }

    @Test
    fun `パスの書き換えで認証を迂回できない`() {
        for (path in listOf(
            "//internal/health",
            "/internal//health",
            "/INTERNAL/health",
            "/internal;x=1/health",
            "/internal/;x=1/health",
            "/internal/./health",
            "/foo/../internal/health",
            "/%69nternal/health",
            "/internal/health/",
            "/internal/health;jsessionid=x",
        )) {
            val r = http.get(path)
            assertNotEquals(200, r.status, "認証なしで通った: $path")
            assertFalse(r.body.contains("UP"), path)
        }
    }

    @Test
    fun `internal は読み取り専用（GET 以外は認証後でも拒否される）`() {
        for (m in listOf("POST", "PUT", "DELETE")) {
            val r = http.send(m, "/internal/health", TestAuth.validToken())
            assertTrue(r.status in listOf(405, 404), "$m → ${r.status}")
        }
        assertEquals(401, http.send("POST", "/internal/health").status)
    }

    @Test
    fun `エラー応答に例外やスタックを出さない`() {
        val r = http.get("/internal/no-such-endpoint", TestAuth.validToken())
        assertEquals(404, r.status)
        for (leak in listOf("Exception", "at jp.", "trace", "springframework")) assertFalse(r.body.contains(leak), r.body)
    }
}
