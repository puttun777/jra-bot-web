package jp.jrabot.ingest

import jp.jrabot.domain.ops.OpsMode
import jp.jrabot.persistence.integration.runConcurrently
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * エージェント認証と入口の検証（実 HTTP + 本物の MySQL）。認証に成功するまで業務データ（取込のバッチ・生レコード・開催日）を一切保存しない。
 * 実 GCP・実データなし。
 */
@Tag("integration")
@ExtendWith(OutputCaptureExtension::class)
class AgentAuthIntegrationTest {
    private val it = IngestIt

    @BeforeEach
    fun setUp() = it.reset()

    private fun businessRows(): Long =
        it.count(
            "SELECT (SELECT COUNT(*) FROM ingest_batch) + (SELECT COUNT(*) FROM ingest_raw) + (SELECT COUNT(*) FROM ingest_fetch) + " +
                "(SELECT COUNT(*) FROM race_day) + (SELECT COUNT(*) FROM race_schedule) + (SELECT COUNT(*) FROM races) + (SELECT COUNT(*) FROM odds_snapshots)",
        )

    private fun validBody() = it.bytes(it.envelope(records = listOf(it.nys("20261101"))))

    @Test
    fun `正しい署名の heartbeat は200で、最終接続が認証したエージェントのテナントに記録される`() {
        val r =
            it.post(
                it.one,
                "/v1/agent/heartbeat",
                it.bytes(
                    mapOf(
                        "agentVersion" to "1.2.3",
                        "queueDepth" to 2,
                        "clockSkewMs" to -15,
                        "mode" to "NORMAL",
                        "ackedTs" to mapOf("RACE" to "20261031200000"),
                    ),
                ),
            )
        assertEquals(200, r.status, r.body)
        assertEquals("1.2.3", it.db.scalar("SELECT agent_version FROM agent_heartbeat WHERE tenant_id = 1 AND agent_id = 'ag_one'"))
        assertEquals(
            """{"RACE":"20261031200000"}""",
            it.db
                .scalar(
                    "SELECT JSON_UNQUOTE(JSON_EXTRACT(JSON_OBJECT('x', acked_ts), '\$.x')) FROM agent_heartbeat WHERE agent_id = 'ag_one'",
                )?.replace(" ", ""),
        )
        assertEquals(0, it.count("SELECT COUNT(*) FROM agent_heartbeat WHERE tenant_id = 2"))
        // upsert（1 エージェント 1 行）
        assertEquals(200, it.post(it.one, "/v1/agent/heartbeat", it.bytes(mapOf("agentVersion" to "1.2.4"))).status)
        assertEquals(1, it.count("SELECT COUNT(*) FROM agent_heartbeat"))
        assertEquals("1.2.4", it.db.scalar("SELECT agent_version FROM agent_heartbeat"))
    }

    @Test
    fun `署名なし・ヘッダ欠落・不正な署名・本文の改ざんは401で、業務データを一切保存しない`() {
        val body = validBody()
        assertEquals(401, it.postRaw("/v1/agent/ingest", body, mapOf("Content-Type" to "application/json")).status)
        assertEquals(401, it.post(it.one, "/v1/agent/ingest", body, tamperSig = true).status)
        assertEquals(401, it.post(it.one, "/v1/agent/ingest", body, signedBody = "{}".toByteArray()).status, "署名した本文と送った本文が違う")
        assertEquals(401, it.post(it.one, "/v1/agent/ingest", body, token = "jrat1_wrong-token-value").status)
        assertEquals(401, it.post(it.one, "/v1/agent/ingest", body, agentHeader = "ag_unknown").status)
        assertEquals(
            401,
            it
                .postRaw(
                    "/v1/agent/ingest",
                    body,
                    mapOf(
                        "Content-Type" to "application/json",
                        "X-Agent-Id" to "ag_one",
                        "X-Signature" to "0".repeat(64),
                    ),
                ).status,
        )
        assertEquals(0, businessRows())
        assertEquals(0, it.count("SELECT COUNT(*) FROM agent_nonce"), "署名が正しくない要求は nonce 表を増やさない")
    }

    @Test
    fun `nonce の再利用（リプレイ）と時刻のずれは401`() {
        val body = validBody()
        val n = it.nonce()
        assertEquals(200, it.post(it.one, "/v1/agent/ingest", body, nonce = n).status)
        val replay = it.post(it.one, "/v1/agent/ingest", body, nonce = n)
        assertEquals(401, replay.status)
        assertEquals(1, it.count("SELECT COUNT(*) FROM agent_nonce WHERE nonce = '$n'"))
        assertEquals(401, it.post(it.one, "/v1/agent/ingest", body, tsOffsetSec = -301).status)
        assertEquals(401, it.post(it.one, "/v1/agent/ingest", body, tsOffsetSec = 301).status)
        // 同じ nonce を同時に 6 本: 通るのは 1 本だけ
        val n2 = it.nonce()
        val results = runConcurrently(6) { _ -> it.post(it.one, "/v1/agent/heartbeat", it.bytes(mapOf("agentVersion" to "1.0.0")), nonce = n2).status }
        assertEquals(1, results.count { r -> r.getOrNull() == 200 }, results.toString())
        assertEquals(5, results.count { r -> r.getOrNull() == 401 })
    }

    @Test
    fun `失効は403、トークン期限切れは401、別のエージェントの署名では通らない`() {
        val revoked = it.register("ag_revoked", 1)
        it.db.exec("UPDATE agent SET status = 'REVOKED', revoked_at = NOW(6), revoked_by = 'test' WHERE agent_id = 'ag_revoked'")
        val expired = it.register("ag_expired", 1, Instant.now().minus(Duration.ofMinutes(1)))
        val hb = it.bytes(mapOf("agentVersion" to "1.0.0"))
        assertEquals(403, it.post(revoked, "/v1/agent/heartbeat", hb).status)
        assertEquals(401, it.post(expired, "/v1/agent/heartbeat", hb).status)
        // ag_one を名乗って ag_two の鍵で署名
        assertEquals(401, it.post(it.one, "/v1/agent/heartbeat", hb, token = it.two.token).status)
        assertEquals(0, it.count("SELECT COUNT(*) FROM agent_heartbeat"))
        it.db.exec("DELETE FROM agent_nonce")
        it.db.exec("DELETE FROM agent WHERE agent_id IN ('ag_revoked', 'ag_expired')")
    }

    @Test
    fun `テナントは認証したエージェントの行から決まり、本文の指定や別エージェントの名乗りは受け付けない`() {
        // ag_two（テナント 2）のデータはテナント 2 にだけ入る
        val ok = it.ingest(it.two, it.envelope(agent = it.two, records = listOf(it.nys("20261101"))))
        assertEquals(200, ok.status, ok.body)
        assertEquals(1, it.count("SELECT COUNT(*) FROM race_day WHERE tenant_id = 2"))
        assertEquals(0, it.count("SELECT COUNT(*) FROM race_day WHERE tenant_id = 1"))
        assertEquals(1, it.count("SELECT COUNT(*) FROM ingest_batch WHERE tenant_id = 2 AND agent_id = 'ag_two'"))
        // 本文で tenantId を指示 → 422、保存なし
        val withTenant = it.envelope(records = listOf(it.nys("20261102"))).toMutableMap().also { m -> m["tenantId"] = 2 }
        val r = it.ingest(it.one, withTenant)
        assertEquals(422, r.status)
        assertTrue(r.body.contains("TENANT_FIELD_FORBIDDEN"), r.body)
        // 認証した ag_one が ag_two を名乗る → 403
        assertEquals(403, it.ingest(it.one, it.envelope(agent = it.two, records = listOf(it.nys("20261102")))).status)
        assertEquals(0, it.count("SELECT COUNT(*) FROM race_day WHERE race_date = '2026-11-02'"))
    }

    @Test
    fun `運用モードが NORMAL 以外なら503で、認証もDBも触らず何も保存しない`() {
        val body = validBody()
        for (m in listOf(OpsMode.STARTING, OpsMode.STOPPING, OpsMode.STOPPED, OpsMode.UNKNOWN)) {
            it.mode.mode = m
            val r = it.post(it.one, "/v1/agent/ingest", body)
            assertEquals(503, r.status, "$m")
            if (m == OpsMode.UNKNOWN) assertNull(r.header("X-Ops-Mode")) else assertEquals(m.name, r.header("X-Ops-Mode"))
            assertEquals(0, businessRows(), "$m")
            assertEquals(0, it.count("SELECT COUNT(*) FROM agent_nonce"), "$m: NORMAL 以外では認証の nonce 表にも触れない")
        }
        it.mode.mode = OpsMode.NORMAL
        assertEquals(200, it.post(it.one, "/v1/agent/ingest", body).status)
    }

    @Test
    fun `パスの書き換えで認証・ガードを迂回できない（署名なしで取込のハンドラに到達しない）`() {
        val body = validBody()
        val headers = mapOf("Content-Type" to "application/json")
        for (path in listOf(
            "//v1/agent/ingest",
            "/V1/AGENT/ingest",
            "/v1/agent;x=1/ingest",
            "/v1/./agent/ingest",
            "/x/../v1/agent/ingest",
            "/v1/agent/ingest/",
            "/v1/agent/ingest.json",
            "/%76%31/agent/ingest",
            "/v1/agent/%69ngest",
        )) {
            val r = it.postRaw(path, body, headers)
            assertTrue(r.status != 200, "$path -> ${r.status}")
        }
        assertEquals(0, businessRows(), "署名なしで何も保存されない")
    }

    @Test
    fun `本文の上限と形式の検証（413 400 422）`() {
        val big = ByteArray(1024 * 1024 + 10) { 'a'.code.toByte() }
        assertEquals(413, it.post(it.one, "/v1/agent/ingest", big).status)
        // 展開爆弾: 小さい gzip が上限を超えて展開される
        val zeros = it.gzip(ByteArray(40 * 1024 * 1024))
        assertTrue(zeros.size < 1024 * 1024, "テスト用の gzip は 1MiB 未満のはず: ${zeros.size}")
        val bomb = it.post(it.one, "/v1/agent/ingest", zeros, gzip = true)
        assertEquals(413, bomb.status, bomb.body)
        assertTrue(bomb.body.contains("BODY_TOO_LARGE_INFLATED"))
        assertEquals(400, it.post(it.one, "/v1/agent/ingest", "not gzip".toByteArray(), gzip = true).status)
        assertEquals(400, it.post(it.one, "/v1/agent/ingest", "{broken".toByteArray()).status)
        assertEquals(400, it.post(it.one, "/v1/agent/ingest", """{"a":1,"a":2}""".toByteArray()).status, "重複キー")
        assertEquals(400, it.post(it.one, "/v1/agent/ingest", """{"a":1} {"b":2}""".toByteArray()).status, "末尾に余分な内容")
        assertEquals(400, it.post(it.one, "/v1/agent/ingest", "[".repeat(40).toByteArray()).status, "深すぎる入れ子")
        assertEquals(400, it.post(it.one, "/v1/agent/ingest", validBody(), contentType = "text/plain").status)
        assertEquals(400, it.post(it.one, "/v1/agent/ingest", validBody(), contentType = null).status)
        val many = it.envelope(records = (1..201).map { i -> it.rec("YS", "r$i".toByteArray()) }, dataspec = "RACE", eof = false)
        assertEquals(413, it.ingest(it.one, many).status)
        assertEquals(0, businessRows())
    }

    @Test
    fun `封筒の検証エラー（protocol・データ種別・UUID・Base64・SHA-256・未来の時刻）は422で保存しない`() {
        fun env(vararg o: Pair<String, Any?>) = it.envelope(records = listOf(it.nys("20261101"))).toMutableMap().also { m -> o.forEach { (k, v) -> m[k] = v } }
        assertEquals(422, it.ingest(it.one, env("protocol" to 2)).status)
        assertEquals(422, it.ingest(it.one, env("dataspec" to "ZZZZ")).status)
        assertEquals(422, it.ingest(it.one, env("batchId" to "not-a-uuid")).status)
        assertEquals(422, it.ingest(it.one, env("observedAt" to ItClock.now().plusSeconds(3600).toString())).status)
        val badB64 = listOf(mapOf("id" to "RA", "bytesB64" to "@@@@", "sha256" to "0".repeat(64)))
        assertEquals(422, it.ingest(it.one, env("records" to badB64)).status)
        val badSha = listOf(mapOf("id" to "RA", "bytesB64" to "aGVsbG8=", "sha256" to "0".repeat(64)))
        assertEquals(422, it.ingest(it.one, env("records" to badSha)).status)
        assertEquals(0, businessRows())
    }

    @Test
    fun `ログに署名・トークン・nonce・本文を出さない`(out: CapturedOutput) {
        val marker = "秘密のレース名ZZZ-MARKER"
        val sensitive = it.bytes(it.envelope(records = listOf(it.nra("202611010511", "2", "20261030", name = marker))))
        val nonce = it.nonce()
        it.post(it.one, "/v1/agent/ingest", sensitive, nonce = nonce, tamperSig = true) // 401
        it.post(it.one, "/v1/agent/ingest", sensitive, token = "jrat1_leaked-looking-token")
        it.post(it.one, "/v1/agent/ingest", sensitive, contentType = "text/plain")
        it.post(it.one, "/v1/agent/ingest", "{broken".toByteArray())
        it.faults.rawInsertFailOn = 1
        it.post(it.one, "/v1/agent/ingest", sensitive) // 500（注入した失敗）
        val log = out.all
        for (secret in listOf(it.one.token, it.two.token, nonce, marker, "jrat1_leaked-looking-token")) {
            assertFalse(log.contains(secret), "ログに出てはいけない値: ${secret.take(8)}…")
        }
        assertFalse(Regex("[0-9a-f]{64}").containsMatchIn(log.lines().filter { l -> l.contains("agent") }.joinToString("\n")), "署名・ハッシュらしい値がログにある")
    }
}
