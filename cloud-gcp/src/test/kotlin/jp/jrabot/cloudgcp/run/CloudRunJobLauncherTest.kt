package jp.jrabot.cloudgcp.run

import com.sun.net.httpserver.HttpServer
import jp.jrabot.domain.bot.JobLaunchRequest
import jp.jrabot.domain.bot.LaunchResult
import jp.jrabot.domain.bot.LaunchableJob
import jp.jrabot.domain.tenant.TenantId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** ローカルの偽サーバに対して、リクエストの形（宛先・認証・引数の上書き）と応答の分類を確かめる。実 GCP には接続しない。 */
class CloudRunJobLauncherTest {
    private class Seen(
        val path: String,
        val auth: String?,
        val body: String,
    )

    private val seen = CopyOnWriteArrayList<Seen>()
    private var status = 200
    private var responseBody =
        """{"name":"projects/p/locations/l/operations/op-1","metadata":{"name":"projects/p/locations/l/jobs/jra-bot-bot-run/executions/exec-123"}}"""
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                seen += Seen(ex.requestURI.rawPath, ex.requestHeaders.getFirst("Authorization"), ex.requestBody.readBytes().decodeToString())
                val bytes = responseBody.toByteArray()
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }

    @AfterEach
    fun stop() = server.stop(0)

    private fun launcher(token: () -> String = { "synthetic-token" }) =
        CloudRunJobLauncher(
            "jra-bot-test1",
            "asia-northeast1",
            mapOf(LaunchableJob.BOT_RUN to "jra-bot-bot-run", LaunchableJob.ML_TRAIN to "jra-bot-ml-train"),
            token,
            baseUrl = "http://127.0.0.1:${server.address.port}",
            timeout = Duration.ofSeconds(3),
        )

    private fun req(
        job: LaunchableJob = LaunchableJob.BOT_RUN,
        params: Map<String, String> = mapOf("date" to "2026-10-24", "trigger" to "scheduler"),
    ) = JobLaunchRequest(TenantId.of(1), job, params)

    @Test
    fun `宛先は Job の run で Bearer を付け 引数だけを上書きし task と tenant-id を必ず付ける`() {
        val r = launcher().launch(req())
        assertEquals(LaunchResult.Launched("exec-123"), r)
        val s = seen.single()
        assertEquals("/v2/projects/jra-bot-test1/locations/asia-northeast1/jobs/jra-bot-bot-run:run", s.path)
        assertEquals("Bearer synthetic-token", s.auth)
        assertTrue("\"args\":[\"--task=bot-run\",\"--tenant-id=1\",\"--date=2026-10-24\",\"--trigger=scheduler\"]" in s.body, s.body)
        assertFalse("image" in s.body || "env" in s.body, "イメージ・環境変数は上書きしない: ${s.body}")
    }

    @Test
    fun `未設定の Job と ml-train は起動せず 一時的でない失敗`() {
        for (job in listOf(LaunchableJob.CALENDAR_CHECK, LaunchableJob.ML_TRAIN)) {
            val r = assertIs<LaunchResult.Failed>(launcher().launch(req(job)))
            assertFalse(r.transient, job.name)
        }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `HTTP の失敗は 429 と 5xx だけ一時的 認可・設定の誤りは一時的でない 理由に本文やトークンを含めない`() {
        for ((code, transient) in listOf(429 to true, 500 to true, 503 to true, 400 to false, 401 to false, 403 to false, 404 to false)) {
            status = code
            responseBody = """{"error":{"message":"synthetic-token leaked?"}}"""
            val r = assertIs<LaunchResult.Failed>(launcher().launch(req()))
            assertEquals(transient, r.transient, "status=$code")
            assertEquals("LAUNCH_HTTP_$code", r.reason)
            assertFalse("token" in r.reason.lowercase())
        }
    }

    @Test
    fun `トークンを取れない・接続できない・応答が不明でも 例外にせず一時的な失敗か受付として返す`() {
        val noToken = assertIs<LaunchResult.Failed>(launcher { throw IllegalStateException("no credentials") }.launch(req()))
        assertTrue(noToken.transient)
        assertEquals("TOKEN_UNAVAILABLE", noToken.reason)
        server.stop(0)
        val down = assertIs<LaunchResult.Failed>(launcher().launch(req()))
        assertTrue(down.transient)
    }

    @Test
    fun `応答の形が想定外でも 受け付けられていれば起動済みとして返す`() {
        responseBody = "not json"
        assertEquals(LaunchResult.Launched("unknown"), launcher().launch(req()))
    }

    @Test
    fun `予約済みのキー（task・tenant-id）と空の値は依頼の時点で拒否する`() {
        for (bad in listOf(mapOf("task" to "retention"), mapOf("tenant-id" to "2"), mapOf("date" to ""))) {
            kotlin.test.assertFailsWith<IllegalArgumentException>(bad.toString()) { JobLaunchRequest(TenantId.of(1), LaunchableJob.BOT_RUN, bad) }
        }
    }
}
