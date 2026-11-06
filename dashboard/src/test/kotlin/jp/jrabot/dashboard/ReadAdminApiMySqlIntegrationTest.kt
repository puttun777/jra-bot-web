package jp.jrabot.dashboard

import jp.jrabot.dashboard.api.ComboSummaryDto
import jp.jrabot.dashboard.api.DayDto
import jp.jrabot.dashboard.api.MeDto
import jp.jrabot.dashboard.api.OverrideDto
import jp.jrabot.dashboard.api.PageDto
import jp.jrabot.dashboard.api.PerformanceDto
import jp.jrabot.dashboard.api.RaceDto
import jp.jrabot.dashboard.api.RunDto
import jp.jrabot.dashboard.api.SignalDto
import jp.jrabot.dashboard.api.StatusDto
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 読取り API・管理 API を**本物の MySQL と最小権限の `app` ユーザー**で通す（HTTP → 認証 → CSRF → 認可 → サービス → DB → 監査）。
 * 認証は本番と同じ JWT 検証器（鍵の取得先だけ偽）、利用者表は合成。実 GCP・実データなし。
 */
@Tag("integration")
class ReadAdminApiMySqlIntegrationTest {
    companion object {
        private val READER: JsonMapper = JsonMapper.builder().build()
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
     * 画面（dashboard/src/main/ts/schema.ts）が受け取る形と、実際の HTTP の応答の形が一致すること（D-263）。見本は src/test/resources/frontend-contract。
     * オブジェクトのキーの集合と、両方が null でないときの JSON の型を比べる（配列は見本の先頭要素と実際の全要素）。
     * 今日（JST）の開催日・レース・実行記録も入れて、status の days・lastRun・nextRaceAt を値のある状態で比べる（レビュー 中: 空の配列・null で比較を素通りしていた）。
     * TypeScript の試験は同じ見本を schema.ts の検査に通すので、サーバの DTO の項目を変えて見本・schema を直し忘れると、どちらかが失敗する。
     */
    @Test
    fun `API の応答の形は画面の契約の見本（frontend-contract）と一致する`() {
        assertEquals(201, createCancel().status)
        val today = LocalDate.now(ZoneId.of("Asia/Tokyo"))
        val raceId = today.format(DateTimeFormatter.BASIC_ISO_DATE) + "0501"
        db.exec(
            "INSERT INTO race_day (tenant_id, race_date, venue_code, kai, nichime, status, first_seen_at, last_confirmed_at) " +
                "VALUES (1, '$today', '05', 4, 8, 'RUNNING', NOW(6), NOW(6))",
        )
        db.exec(
            "INSERT INTO races (tenant_id, race_id, race_date, venue_code, race_no, created_at, updated_at) VALUES (1, '$raceId', '$today', '05', 1, NOW(6), NOW(6))",
        )
        db.exec(
            "INSERT INTO race_schedule (tenant_id, race_id, race_date, venue_code, race_no, scheduled_post_at, post_time_unknown, " +
                "status, data_kubun, source_version, race_name, last_changed_at) " +
                "VALUES (1, '$raceId', '$today', '05', 1, DATE_ADD(NOW(6), INTERVAL 2 HOUR), 0, 'CARD', '2', '202610232', '今日1R', NOW(6))",
        )
        for ((n, date) in listOf(1 to "2026-10-24", 2 to today.toString())) {
            db.exec(
                "INSERT INTO bot_run (tenant_id, run_id, run_date, trigger_type, state, exit_reason, started_at, finished_at) " +
                    "VALUES (1, '00000000-0000-4000-8000-00000000000$n', '$date', 'SCHEDULER', 'SUCCEEDED', 'DAY_COMPLETED', " +
                    "'2026-10-24 00:30:00.000000', '2026-10-24 08:00:00.000000')",
            )
        }
        val cases =
            linkedMapOf(
                "status.json" to "/api/v1/status",
                "races.json" to "/api/v1/races?date=2026-10-24",
                "signals.json" to "/api/v1/signals?date=2026-10-24",
                "performance.json" to "/api/v1/performance?from=2026-10-24&to=2026-10-24",
                "runs.json" to "/api/v1/runs?date=2026-10-24",
                "overrides.json" to "/api/v1/admin/overrides?date=2026-10-24",
                "me.json" to "/api/v1/me",
                "ml-models.json" to "/api/v1/admin/ml-models",
            )
        val compared = mutableListOf<String>()
        for ((file, path) in cases) {
            val res = http.get(path, admin)
            assertEquals(200, res.status, "$path: ${res.body}")
            assertSameShape(sample(file), READER.readTree(res.body), file, compared, strict = false)
        }
        // 実際の応答が空の配列だと要素の形を比べられない。比べた位置を確かめる（見本の配列のうち、ここで要素まで比べたもの）。
        // performance.combos は合成の買い目の通知が要るため、下の DTO の試験で比べる
        for (p in listOf("status.json.days[0]", "races.json[0]", "signals.json.items[0]", "runs.json[0]", "overrides.json[0]")) {
            assertTrue(p in compared, "$p を比べていない: $compared")
        }
        assertTrue("status.json.lastRun.finishedAt" in compared && "status.json.nextRaceAt" in compared, "status の値のある項目を比べていない: $compared")
    }

    /**
     * DTO のすべての項目に値がある応答を、アプリと同じ JsonMapper（Spring の Bean）で JSON にし、見本と**型まで厳密に**比べる（null を許さない）。
     * HTTP の試験では値が null になりやすい項目（取消・期限・買い目の集計など）の JSON の型を、ここで確かめる（レビュー 中）。
     */
    @Test
    fun `すべての項目に値がある DTO の JSON は見本と型まで一致する`() {
        val mapper = ctx.getBean(JsonMapper::class.java)
        val at = Instant.parse("2026-10-24T00:30:00Z")
        val day = LocalDate.of(2026, 10, 24)
        val run = RunDto("00000000-0000-4000-8000-000000000001", day, "SCHEDULER", "FAILED", at, at.plusSeconds(900), "DB_UNAVAILABLE")
        val dtos =
            linkedMapOf<String, Any>(
                "status.json" to StatusDto(at, day, "NORMAL", listOf(DayDto("05", "RUNNING", at)), run, at.plusSeconds(3600), false, at),
                "races.json" to listOf(RaceDto("202610240501", "05", 1, "テスト1R", at, "CARD")),
                "signals.json" to PageDto(listOf(SignalDto(1L, "202610240501", "win", 2, at, 8.0, 0.2, 0.6, 3.0, "market", "SENT")), 0, 50, 1),
                "performance.json" to
                    PerformanceDto(
                        day,
                        day,
                        1,
                        1,
                        3.0,
                        24.0,
                        24.0,
                        1.0,
                        8.0,
                        listOf(ComboSummaryDto("wide", 2, 1, 2.0, 3.5, 0.5, 1.75)),
                        0,
                        0,
                        0,
                        0,
                    ),
                "runs.json" to listOf(run),
                "overrides.json" to
                    listOf(
                        OverrideDto(
                            1L,
                            "RACE",
                            day,
                            "202610240501",
                            "SET_POST_TIME",
                            "2026-10-24T06:30:00Z",
                            "天候",
                            "me@example.com",
                            at,
                            at,
                            at,
                            "me@example.com",
                        ),
                    ),
                "me.json" to MeDto("me@example.com", "ADMIN", 1L),
                "ml-models.json" to
                    listOf(
                        jp.jrabot.dashboard.api.MlModelDto(
                            "cal-001",
                            "calibration",
                            "ACTIVE",
                            1,
                            "sha256:" + "a".repeat(64),
                            day,
                            day,
                            1234L,
                            at,
                            at,
                            "me@example.com",
                            "cal-000",
                            mapOf("brier" to 0.2, "logloss" to 0.5),
                            "calibration",
                            listOf("ok"),
                            true,
                        ),
                    ),
            )
        for ((file, dto) in dtos) {
            val actual = READER.readTree(mapper.writeValueAsString(dto))
            assertSameShape(sample(file), actual, file, mutableListOf(), strict = true)
        }
    }

    /** 画面の ES モジュールは毎回再検証させる（デプロイの直後に新旧のモジュールが混ざって読み込めなくなるのを防ぐ。レビュー 中）。配信の型も確かめる。 */
    @Test
    fun `画面と ES モジュールは no-cache で配信され モジュールは JavaScript`() {
        val index = http.get("/")
        assertEquals(200, index.status)
        assertTrue(index.body.contains("""<script type="module" src="/js/app.js"></script>"""), index.body.take(200))
        assertTrue(index.header("Cache-Control")?.contains("no-cache") == true, "index: ${index.header("Cache-Control")}")
        for (m in listOf("app", "api", "dom", "format", "schema", "validate", "view")) {
            val r = http.get("/js/$m.js")
            assertEquals(200, r.status, m)
            assertTrue(r.header("Content-Type")?.contains("javascript") == true, "$m: ${r.header("Content-Type")}")
            assertTrue(r.header("Cache-Control")?.contains("no-cache") == true, "$m: ${r.header("Cache-Control")}")
        }
        assertEquals(404, http.get("/app.js").status, "旧 app.js は無い")
    }

    private fun sample(file: String): JsonNode = READER.readTree(checkNotNull(javaClass.getResource("/frontend-contract/$file")) { "見本 $file が無い" }.readText())

    private fun assertSameShape(
        expected: JsonNode,
        actual: JsonNode?,
        path: String,
        compared: MutableList<String>,
        strict: Boolean,
    ) {
        if (actual == null) throw AssertionError("$path が応答に無い")
        if (strict) {
            assertTrue(!expected.isNull && !actual.isNull, "$path が null（見本と DTO はすべての項目に値を入れる）")
        } else if (expected.isNull || actual.isNull) {
            return
        }
        compared += path
        assertEquals(expected.nodeType, actual.nodeType, "$path の JSON の型")
        if (expected.isObject) {
            val keys = expected.properties().map { it.key }.toSortedSet()
            assertEquals(keys, actual.properties().map { it.key }.toSortedSet(), "$path のキー（画面の schema.ts と見本を合わせる）")
            for (k in keys) assertSameShape(expected.get(k), actual.get(k), "$path.$k", compared, strict)
        } else if (expected.isArray) {
            assertTrue(expected.size() > 0, "$path: 見本の配列は 1 件以上")
            if (strict) assertTrue(actual.size() > 0, "$path: DTO の配列は 1 件以上")
            for (i in 0 until actual.size()) assertSameShape(expected.get(0), actual.get(i), "$path[$i]", compared, strict)
        }
    }
}
