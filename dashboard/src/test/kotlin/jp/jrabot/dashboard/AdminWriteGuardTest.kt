package jp.jrabot.dashboard

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
 * 書込み（管理操作）の運用モードガード（CONTRACTS §3.2・§5.3）。拒否のとき、ハンドラに到達しない。
 * 管理操作そのものは Stage 4。ここではテスト用の受け皿（[AdminProbe]）が「ガードの先」を表す。本番のコードに管理操作は無い。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestAuthConfig::class, AdminGuardTestConfig::class)
class AdminWriteGuardTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(r: DynamicPropertyRegistry) {
            val ops = writeOpsState(Files.createTempDirectory("dashboard-guard"))
            baseProperties(ops).forEach { kv -> r.add(kv.substringBefore('=')) { kv.substringAfter('=') } }
        }

        private val writes = listOf("POST", "PUT", "PATCH", "DELETE")
    }

    @Autowired lateinit var context: ApplicationContext

    @Autowired lateinit var env: Environment

    @Autowired lateinit var mode: ControllableModeProvider

    @Autowired lateinit var probe: AdminProbe

    private val http get() = Http(env.serverPort())

    @BeforeEach
    fun reset() {
        mode.mode = OpsMode.NORMAL
        probe.reached.set(0)
    }

    @Test
    fun `NORMAL なら通過して後続(Stage 4 の認可)へ進む`() {
        for (m in writes) assertEquals(200, http.send(m, "/api/v1/admin/probe").status, m)
        assertEquals(200, http.send("POST", "/other/write").status)
        assertEquals(5, probe.reached.get())
    }

    @Test
    fun `STARTING STOPPING STOPPED は書込みを 409 OPS_MODE_NOT_NORMAL で拒否し、ハンドラに到達しない`() {
        for (md in listOf(OpsMode.STARTING, OpsMode.STOPPING, OpsMode.STOPPED)) {
            mode.mode = md
            for (m in writes) {
                val r = http.send(m, "/api/v1/admin/probe")
                assertEquals(409, r.status, "$md $m")
                assertEquals("""{"error":"OPS_MODE_NOT_NORMAL"}""", r.body)
                assertEquals("no-store", r.header("Cache-Control"))
            }
        }
        assertEquals(0, probe.reached.get(), "拒否されたのにハンドラが呼ばれた")
        assertNull((context.getBean(javax.sql.DataSource::class.java) as HikariDataSource).hikariPoolMXBean, "ガードの拒否で DB のプールが起動した")
    }

    @Test
    fun `UNKNOWN も書込みは 409`() {
        mode.mode = OpsMode.UNKNOWN
        for (m in writes) assertEquals(409, http.send(m, "/api/v1/admin/probe").status, m)
        assertEquals(0, probe.reached.get())
    }

    @Test
    fun `管理操作のパスに限らず、すべての書込みに掛かる（Stage 4 でルートを足しても素通りしない）`() {
        mode.mode = OpsMode.STOPPED
        assertEquals(409, http.send("POST", "/other/write").status)
        assertEquals(409, http.send("POST", "/anything/at/all").status)
        assertEquals(0, probe.reached.get())
    }

    @Test
    fun `読み取り(GET)はモードで拒否しない（STOPPED や UNKNOWN のバナー表示のため。閲覧の可否は Stage 4）`() {
        for (md in listOf(OpsMode.STARTING, OpsMode.STOPPING, OpsMode.STOPPED, OpsMode.UNKNOWN)) {
            mode.mode = md
            assertEquals(200, http.get("/api/v1/admin/probe").status, "$md")
        }
    }

    @Test
    fun `internal にはガードを掛けない（ready は STARTING でも DB の準備を確認する）`() {
        mode.mode = OpsMode.STARTING
        assertEquals(200, http.get("/internal/health", TestAuth.validToken()).status)
        assertEquals(503, http.get("/internal/ready", TestAuth.validToken()).status) // DB が無いための 503 であって、モードを理由にした拒否(409)ではない
    }

    @Test
    fun `ガードは 1 リクエストにつきモードを 1 回だけ読む`() {
        mode.mode = OpsMode.STOPPED
        mode.calls.set(0)
        http.send("POST", "/api/v1/admin/probe")
        assertEquals(1, mode.calls.get())
        assertNotEquals(0, mode.calls.get())
    }

    @Test
    fun `管理操作の本番コードは存在しない（ガードの先に公開するルートが無い）`() {
        mode.mode = OpsMode.NORMAL
        // 受け皿を除くと、書込み系のルートは本番の構成に無い。ここでは受け皿以外のパスが 404 であることを確認する
        assertEquals(404, http.send("POST", "/api/v1/admin/real-operation").status)
        assertEquals(404, http.send("DELETE", "/api/v1/admin/agents/1").status)
    }
}
