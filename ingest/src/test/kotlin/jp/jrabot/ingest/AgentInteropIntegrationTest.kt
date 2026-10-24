package jp.jrabot.ingest

import jp.jrabot.domain.ops.OpsMode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Windows エージェント（Python）と Kotlin ingest の結合（実 HTTP + 本物の MySQL + 実際の署名）。認証は迂回しない:
 * Python は本物の署名クライアントで、発行されたトークンから署名する。合成データだけ・実 GCP なし。
 * `AGENT_PYTHON`（cryptography を入れた Python）が無い環境では**失敗する**（スキップして緑にしない）。
 */
@Tag("integration")
class AgentInteropIntegrationTest {
    private val it = IngestIt

    @BeforeEach
    fun setUp() = it.reset()

    private fun repoRoot(): Path {
        var p: Path? = Path.of("").toAbsolutePath()
        while (p != null && !Files.isDirectory(p.resolve("agent/src/jra_agent"))) p = p.parent
        return assertNotNull(p, "agent/ が見つからない")
    }

    private fun runDriver(phase: String): Map<String, Any?> {
        val python = assertNotNull(System.getenv("AGENT_PYTHON"), "AGENT_PYTHON が未設定（CI は設定する。ローカルは venv の python を指定）")
        val root = repoRoot()
        val pb =
            ProcessBuilder(python, "-m", "interop.driver", "http://127.0.0.1:${it.port}", it.one.id, phase)
                .directory(File(root.resolve("agent").toString()))
                .redirectErrorStream(false)
        pb.environment()["PYTHONPATH"] = "${root.resolve("agent/src")}${File.pathSeparator}${root.resolve("agent")}"
        val proc = pb.start()
        proc.outputStream.use { o -> o.write((it.one.token + "\n").toByteArray()) } // トークンは標準入力だけ
        val out = proc.inputStream.readAllBytes().toString(Charsets.UTF_8)
        val err = proc.errorStream.readAllBytes().toString(Charsets.UTF_8)
        assertTrue(proc.waitFor(120, TimeUnit.SECONDS), "driver が終わらない")
        assertEquals(0, proc.exitValue(), "driver 失敗: $err")
        assertTrue(!out.contains(it.one.token) && !err.contains(it.one.token), "トークンが出力に出ている")

        @Suppress("UNCHECKED_CAST")
        return it.json.readValue(out.trim().lines().last(), Map::class.java) as Map<String, Any?>
    }

    @Test
    fun `Python エージェントが本物の署名で plan mode heartbeat を呼び、取得セッションを送り、ACK 直前の切断後の再送で cursor を受け取る`() {
        val r = runDriver("normal")
        assertEquals("NORMAL", r["probe_mode"])
        assertEquals(200, r["plan_status"])
        assertEquals(true, r["plan_idle"], "既定は idle（検証済み仕様が空）")
        assertEquals(200, r["heartbeat"])
        assertEquals(listOf("ACKED", "ACKED", "RETRY", "ACKED"), r["steps"])
        assertEquals(null, r["acked_ts_after_lost_ack"], "ACK を受け取る前は acked_ts を進めない")
        assertEquals("DUPLICATE", r["final_detail"], "同じ batchId の再送は重複 ACK")
        assertEquals("20261031200000", r["acked_ts"], "重複 ACK にも cursor が付く（D-144）")
        assertEquals("COMPLETED", r["session_state"])
        assertEquals(true, r["all_acked"])
        assertEquals(emptyList<String>(), r["problems"])
        assertEquals(3, it.count("SELECT COUNT(*) FROM ingest_batch WHERE agent_id = 'ag_one'"), "同じ batchId なので二重登録なし")
        assertEquals("SUCCEEDED", it.db.scalar("SELECT status FROM ingest_fetch"))
        assertEquals("20261031200000", it.db.scalar("SELECT last_file_timestamp FROM ingest_cursor WHERE data_type = 'YSCH:2'"))
        assertEquals(1, it.count("SELECT COUNT(*) FROM agent_heartbeat WHERE agent_id = 'ag_one'"))
    }

    @Test
    fun `STOPPED では mode は応答し ingest は 503 で エージェントは STOPPED_CONFIRMED になりバッチを保持する`() {
        it.mode.mode = OpsMode.STOPPED
        val r = runDriver("stopped")
        assertEquals("STOPPED", r["probe_mode"])
        assertEquals("HALTED", r["step"], "mode の確認で STOPPED_CONFIRMED になるため、claim も送信もしない")
        assertEquals(mapOf("QUEUED" to 1), r["queued"])
        assertEquals("STOPPED_CONFIRMED", r["mode"])
        assertEquals(0, it.count("SELECT COUNT(*) FROM ingest_batch"))
    }

    @Test
    fun `plan は認証が要り 検証済み仕様が空なら idle で update は manifest が無ければ 204 mode は認証なし`() {
        assertEquals(401, it.get(it.one, "/v1/agent/plan", signed = false).status)
        assertEquals(401, it.get(it.one, "/v1/agent/update", signed = false).status)
        val plan = it.get(it.one, "/v1/agent/plan")
        assertEquals(200, plan.status, plan.body)
        assertTrue(plan.body.contains("\"idle\":true") && plan.body.contains("\"items\":[]"), plan.body)
        assertEquals(204, it.get(it.one, "/v1/agent/update").status)
        val mode = it.get(it.one, "/v1/agent/mode", signed = false)
        assertEquals(200, mode.status)
        assertTrue(mode.body.contains("\"NORMAL\""), mode.body)
    }

    @Test
    fun `STOPPED では plan と update は 503 と X-Ops-Mode で DB に触れず mode だけが応答する`() {
        it.mode.mode = OpsMode.STOPPED
        assertEquals("STOPPED", it.get(it.one, "/v1/agent/plan").header("X-Ops-Mode"))
        assertEquals(503, it.get(it.one, "/v1/agent/update").status)
        assertEquals(200, it.get(it.one, "/v1/agent/mode", signed = false).status)
    }
}
