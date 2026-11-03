package jp.jrabot.dashboard

import jp.jrabot.persistence.integration.MySqlSupport
import jp.jrabot.persistence.integration.TestDb
import jp.jrabot.persistence.integration.resetData
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 読取り API・管理 API を**本物の MySQL と最小権限の `app` ユーザー**で通す（HTTP → 認証 → CSRF → 認可 → サービス → DB → 監査）。
 * 認証は本番と同じ JWT 検証器（鍵の取得先だけ偽）、利用者表は合成。実 GCP・実データなし。
 */
@Tag("integration")
class ReadAdminApiMySqlIntegrationTest {
    companion object {
        private val db: TestDb = MySqlSupport.migratedDatabase()
        private val ops = writeOpsState(Files.createTempDirectory("dashboard-api-it"))
        private lateinit var ctx: ConfigurableApplicationContext
        private lateinit var http: Http

        @JvmStatic
        @org.junit.jupiter.api.BeforeAll
        fun start() {
            val props =
                baseProperties(ops).filterNot { it.startsWith("DB_URL=") || it.startsWith("DB_PASSWORD=") || it.startsWith("ALLOWED_USERS=") } +
                    listOf("ALLOWED_USERS=me@example.com,viewer@example.com,t2@example.com") +
                    listOf("DB_URL=${MySqlSupport.jdbcUrl(db.name)}", "DB_PASSWORD=${MySqlSupport.PASSWORDS.getValue("app")}", "server.port=0")
            ctx = SpringApplicationBuilder(DashboardApplication::class.java, TestAuthConfig::class.java).run(*props.map { "--$it" }.toTypedArray())
            http = Http(ctx.environment.serverPort())
        }

        @JvmStatic
        @AfterAll
        fun stop() = ctx.close()
    }

    private val admin get() = TestAuth.human("me@example.com")
    private val viewer get() = TestAuth.human("viewer@example.com")
    private val other get() = TestAuth.human("t2@example.com") // テナント 2

    @BeforeEach
    fun seed() {
        db.resetData()
        db.exec("DELETE FROM audit_log")
        db.exec("DELETE FROM calendar_override")
        // 今日（JST）の開催日・レース・通知済みの Signal（テナント 1）。日付は実行日に依存させないため API の date で指定する
        db.exec(
            "INSERT INTO race_day (tenant_id, race_date, venue_code, kai, nichime, status, first_seen_at, last_confirmed_at) VALUES (1, '2026-10-24', '05', 4, 8, 'RUNNING', '2026-10-21 03:00:00.000000', NOW(6))",
        )
        db.exec(
            "INSERT INTO races (tenant_id, race_id, race_date, venue_code, race_no, created_at, updated_at) VALUES (1, '202610240501', '2026-10-24', '05', 1, NOW(6), NOW(6))",
        )
        db.exec(
            "INSERT INTO race_schedule (tenant_id, race_id, race_date, venue_code, race_no, scheduled_post_at, post_time_unknown, " +
                "status, data_kubun, source_version, race_name, last_changed_at) " +
                "VALUES (1, '202610240501', '2026-10-24', '05', 1, '2026-10-24 01:00:00.000000', 0, 'CARD', '2', '202610232', 'テスト1R', '2026-10-23 03:00:00.000000')",
        )
        db.exec(
            "INSERT INTO signals (tenant_id, race_id, market, umaban, observed_at, odds, prob, ev, is_paper, stake, prob_source, notify_state, created_at) " +
                "VALUES (1, '202610240501', 'win', 2, '2026-10-24 00:55:00.000000', 8.0, 0.2, 0.6, 1, 3.0, 'market', 'SENT', NOW(6))",
        )
        db.exec(
            "INSERT INTO results (tenant_id, race_id, umaban, finish, payout, created_at, updated_at) VALUES (1, '202610240501', 2, 1, NULL, NOW(6), NOW(6))",
        )
        db.exec(
            "INSERT INTO payouts (tenant_id, race_id, market, combo_key, payout, is_provisional, created_at, updated_at) VALUES (1, '202610240501', 'win', '02', 800, 0, NOW(6), NOW(6))",
        )
    }

    @Test
    fun `読取り API は自分のテナントのデータを返し 他テナントには何も見せない`() {
        val races = http.get("/api/v1/races?date=2026-10-24", admin)
        assertEquals(200, races.status)
        assertTrue(races.body.contains("202610240501") && races.body.contains("テスト1R"), races.body)
        assertEquals("[]", http.get("/api/v1/races?date=2026-10-24", other).body)
        val signals = http.get("/api/v1/signals?date=2026-10-24", viewer)
        assertTrue(signals.body.contains("\"umaban\":2") && signals.body.contains("\"state\":\"SENT\"") && signals.body.contains("\"total\":1"), signals.body)
        assertTrue(http.get("/api/v1/signals?date=2026-10-24", other).body.contains("\"total\":0"))
    }

    @Test
    fun `成績は結果と確定払戻から計算される`() {
        val r = http.get("/api/v1/performance?from=2026-10-24&to=2026-10-24", viewer)
        assertEquals(200, r.status)
        assertTrue(r.body.contains("\"winCount\":1") && r.body.contains("\"winHits\":1") && r.body.contains("\"winActualPayout\":24.0"), r.body)
        assertEquals(400, http.get("/api/v1/performance?from=2026-10-24&to=2026-01-01", viewer).status)
    }

    @Test
    fun `status は運用モードを返す（mode は ops ファイル由来で DB を通らない）`() {
        val s = http.get("/api/v1/status", viewer)
        assertEquals(200, s.status)
        assertTrue(s.body.contains("\"mode\":\"NORMAL\""), s.body)
    }

    private fun createCancel(token: String = admin) =
        http.send(
            "POST",
            "/api/v1/admin/overrides",
            token,
            body = """{"scope":"RACE","date":"2026-10-24","raceId":"202610240501","action":"CANCEL","reason":"天候"}""",
        )

    @Test
    fun `管理者の手動補正と監査記録は DB に残り 閲覧者は 403 で何も増えない`() {
        val created = createCancel()
        assertEquals(201, created.status, created.body)
        assertEquals(1, db.count("SELECT COUNT(*) FROM calendar_override WHERE tenant_id = 1 AND action = 'CANCEL' AND revoked_at IS NULL"))
        assertEquals(1, db.count("SELECT COUNT(*) FROM audit_log WHERE tenant_id = 1 AND actor = 'me@example.com' AND action = 'CALENDAR_OVERRIDE_CREATE'"))
        assertEquals(1, db.count("SELECT COUNT(*) FROM race_schedule_event WHERE event_type = 'MANUAL_OVERRIDE'"))
        // 取消
        val id = db.scalar("SELECT id FROM calendar_override")!!
        val revoked = http.send("POST", "/api/v1/admin/overrides/$id/revoke", admin, body = "{}")
        assertEquals(200, revoked.status, revoked.body)
        assertEquals(2, db.count("SELECT COUNT(*) FROM audit_log WHERE action LIKE 'CALENDAR_OVERRIDE_%'"))
        assertEquals(409, http.send("POST", "/api/v1/admin/overrides/$id/revoke", admin, body = "{}").status, "二重の取消は 409")
        // 閲覧者は 403 で、補正も監査も増えない
        assertEquals(403, createCancel(viewer).status)
        assertEquals(2, db.count("SELECT COUNT(*) FROM audit_log"))
        assertEquals(1, db.count("SELECT COUNT(*) FROM calendar_override"))
    }

    /**
     * 画面（dashboard/src/main/ts/schema.ts）が受け取る形と、実際の応答の形が一致すること（D-263）。見本は src/test/resources/frontend-contract。
     * オブジェクトのキーの集合と、両方が null でないときの JSON の型を比べる（配列は見本の先頭要素と実際の全要素）。
     * TypeScript の試験は同じ見本を schema.ts の検査に通すので、サーバの DTO の項目を変えて見本・schema を直し忘れると、どちらかが失敗する。
     */
    @Test
    fun `API の応答の形は画面の契約の見本（frontend-contract）と一致する`() {
        assertEquals(201, createCancel().status)
        db.exec(
            "INSERT INTO bot_run (tenant_id, run_id, run_date, trigger_type, state, exit_reason, started_at, finished_at) " +
                "VALUES (1, '00000000-0000-4000-8000-000000000001', '2026-10-24', 'SCHEDULER', 'SUCCEEDED', 'DAY_COMPLETED', " +
                "'2026-10-24 00:30:00.000000', '2026-10-24 08:00:00.000000')",
        )
        val cases =
            linkedMapOf(
                "status.json" to "/api/v1/status",
                "races.json" to "/api/v1/races?date=2026-10-24",
                "signals.json" to "/api/v1/signals?date=2026-10-24",
                "performance.json" to "/api/v1/performance?from=2026-10-24&to=2026-10-24",
                "runs.json" to "/api/v1/runs?date=2026-10-24",
                "overrides.json" to "/api/v1/admin/overrides?date=2026-10-24",
                "me.json" to "/api/v1/me",
            )
        val mapper = JsonMapper.builder().build()
        val compared = mutableListOf<String>()
        for ((file, path) in cases) {
            val res = http.get(path, admin)
            assertEquals(200, res.status, "$path: ${res.body}")
            val expected = mapper.readTree(checkNotNull(javaClass.getResource("/frontend-contract/$file")) { "見本 $file が無い" }.readText())
            assertSameShape(expected, mapper.readTree(res.body), file, compared)
        }
        // 実際の応答が空の配列だと要素の形を比べられない。比べた位置を確かめる（見本の配列のうち、ここで要素まで比べたもの）
        for (p in listOf("races.json[0]", "signals.json.items[0]", "runs.json[0]", "overrides.json[0]")) assertTrue(p in compared, "$p を比べていない: $compared")
    }

    private fun assertSameShape(
        expected: JsonNode,
        actual: JsonNode?,
        path: String,
        compared: MutableList<String>,
    ) {
        if (actual == null) throw AssertionError("$path が応答に無い")
        if (expected.isNull || actual.isNull) return
        assertEquals(expected.nodeType, actual.nodeType, "$path の JSON の型")
        if (expected.isObject) {
            val keys = expected.properties().map { it.key }.toSortedSet()
            assertEquals(keys, actual.properties().map { it.key }.toSortedSet(), "$path のキー（画面の schema.ts と見本を合わせる）")
            for (k in keys) assertSameShape(expected.get(k), actual.get(k), "$path.$k", compared)
        } else if (expected.isArray && expected.size() > 0) {
            for (i in 0 until actual.size()) {
                compared += "$path[$i]"
                assertSameShape(expected.get(0), actual.get(i), "$path[$i]", compared)
            }
        }
    }
}
