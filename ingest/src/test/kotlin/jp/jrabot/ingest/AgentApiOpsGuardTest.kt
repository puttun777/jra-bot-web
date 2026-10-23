package jp.jrabot.ingest

import com.zaxxer.hikari.HikariDataSource
import jp.jrabot.domain.ops.OpsMode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * エージェント向け API の運用モードガード（CONTRACTS §3.2・§3.6・§5.3）。拒否のとき、ハンドラ（= DB に触れる処理）に到達しない。
 * `/v1/agent` 配下の本体は Stage 3。ここではテスト用の受け皿（[AgentProbe]）が「ガードの先」を表す。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestAuthConfig::class, AgentGuardTestConfig::class)
class AgentApiOpsGuardTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(r: DynamicPropertyRegistry) {
            val ops = writeOpsState(Files.createTempDirectory("ingest-guard"))
            baseProperties(ops).forEach { kv -> r.add(kv.substringBefore('=')) { kv.substringAfter('=') } }
        }
    }

    @Autowired lateinit var context: ApplicationContext

    @Autowired lateinit var env: Environment

    @Autowired lateinit var mode: ControllableModeProvider

    @Autowired lateinit var probe: AgentProbe

    private val http get() = Http(env.serverPort())

    @BeforeEach
    fun reset() {
        mode.mode = OpsMode.NORMAL
        probe.reached.set(0)
    }

    private fun assertNoDbAccess() {
        assertNull((context.getBean(javax.sql.DataSource::class.java) as HikariDataSource).hikariPoolMXBean, "ガードの拒否で DB のプールが起動した")
    }

    @Test
    fun `NORMAL ならモードガードは通し、次の層（エージェント認証）が判断する（KEK 未設定なら 503 で未認証のハンドラには到達しない）`() {
        for (r in listOf(http.get("/v1/agent/plan"), http.send("POST", "/v1/agent/ingest"), http.send("POST", "/v1/agent/heartbeat"))) {
            assertEquals(503, r.status)
            assertNull(r.header("X-Ops-Mode"), "モードを理由にした拒否ではない")
            assertEquals("""{"error":"AGENT_AUTH_NOT_CONFIGURED"}""", r.body)
        }
        assertEquals(0, probe.reached.get(), "認証できないのにハンドラへ到達した（未認証の経路）")
    }

    @Test
    fun `STARTING STOPPING STOPPED は 503 と X-Ops-Mode で、ハンドラに到達しない`() {
        for (m in listOf(OpsMode.STARTING, OpsMode.STOPPING, OpsMode.STOPPED)) {
            mode.mode = m
            for (r in listOf(http.get("/v1/agent/plan"), http.send("POST", "/v1/agent/ingest"), http.send("POST", "/v1/agent/heartbeat"))) {
                assertEquals(503, r.status, "$m")
                assertEquals(m.name, r.header("X-Ops-Mode"), "$m")
                assertEquals("""{"error":"OPS_MODE_NOT_NORMAL"}""", r.body)
                assertEquals("no-store", r.header("Cache-Control"))
            }
        }
        assertEquals(0, probe.reached.get(), "拒否されたのにハンドラが呼ばれた")
        assertNoDbAccess()
    }

    @Test
    fun `UNKNOWN は 503 で X-Ops-Mode を付けない`() {
        mode.mode = OpsMode.UNKNOWN
        val r = http.get("/v1/agent/plan")
        assertEquals(503, r.status)
        assertNull(r.header("X-Ops-Mode"))
        assertEquals("""{"error":"OPS_MODE_UNKNOWN"}""", r.body)
        assertEquals(0, probe.reached.get())
        assertNoDbAccess()
    }

    @Test
    fun `パスの書き換えでガードを迂回できない`() {
        mode.mode = OpsMode.STOPPED
        for (path in listOf(
            "//v1/agent/plan",
            "/V1/AGENT/plan",
            "/v1/agent;x=1/plan",
            "/v1/./agent/plan",
            "/x/../v1/agent/plan",
            "/v1/agent/plan/",
            "/%76%31/agent/plan",
        )) {
            assertNotEquals(200, http.get(path).status, path)
        }
        assertEquals(0, probe.reached.get())
        assertNoDbAccess()
    }

    @Test
    fun `v1 agent mode はモードガードの対象外（DB に触れずビーコンと同じ内容を返す API。実装は T3．5 の前提。認証は必要）`() {
        mode.mode = OpsMode.STOPPED
        val r = http.get("/v1/agent/mode")
        assertNull(r.header("X-Ops-Mode"), "ガードは掛からない")
        assertEquals("""{"error":"AGENT_AUTH_NOT_CONFIGURED"}""", r.body, "ガードの次の層（認証）が判断する")
    }

    @Test
    fun `internal にはガードを掛けない（STARTING でも ready で DB の準備を確認できる）`() {
        for (m in listOf(OpsMode.STARTING, OpsMode.STOPPING, OpsMode.STOPPED, OpsMode.UNKNOWN)) {
            mode.mode = m
            val health = http.get("/internal/health", TestAuth.validToken())
            assertEquals(200, health.status, "$m")
            assertNull(health.header("X-Ops-Mode"))
            // ready は DB の判定（このテストでは DB が無いので 503）。ただしモードを理由にした拒否（X-Ops-Mode）ではない
            val ready = http.get("/internal/ready", TestAuth.validToken())
            assertEquals(503, ready.status)
            assertNull(ready.header("X-Ops-Mode"), "$m")
            assertEquals(true, ready.body.contains("DB_UNAVAILABLE"), "$m: ${ready.body}")
        }
    }

    @Test
    fun `ガードは 1 リクエストにつきモードを 1 回だけ読む`() {
        mode.mode = OpsMode.STOPPED
        mode.calls.set(0)
        http.get("/v1/agent/plan")
        assertEquals(1, mode.calls.get())
    }
}
