package jp.jrabot.ingest

import jp.jrabot.cloudaws.agent.S3HeartbeatMirror
import jp.jrabot.domain.agent.HeartbeatMirror
import jp.jrabot.ingest.agent.UnavailableHeartbeatMirror
import jp.jrabot.platform.auth.InternalCallerVerifier
import jp.jrabot.platform.auth.StaticTokenVerifier
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** `RUNTIME_PLATFORM=aws`（D-281）の ingest: `/internal` はホストのトークンだけ、最終接続の写しは S3（D-280）。DB・AWS には接続しない。 */
class IngestAwsWiringTest {
    private val token = "Qm4kZx9Pv2Lr7Tn1Hc6Wb3Yd8Fg5Js0Ae_Ku-Io2RsX" // 合成値

    private fun awsProps(vararg extra: String): Array<String> {
        val dir = Files.createTempDirectory("ingest-aws")
        val ops = writeOpsState(dir)
        val tokenFile = dir.resolve("internal-ops-token").also { Files.writeString(it, token) }
        return baseProperties(ops).filterNot { it.startsWith("INTERNAL_ID_TOKEN_AUDIENCE=") || it.startsWith("OPS_SA_EMAIL=") }.toTypedArray() +
            arrayOf("RUNTIME_PLATFORM=aws", "INTERNAL_OPS_TOKEN_FILE=$tokenFile") + extra
    }

    private fun start(
        props: Array<String>,
        web: WebApplicationType = WebApplicationType.NONE,
    ): ConfigurableApplicationContext =
        SpringApplicationBuilder(IngestApplication::class.java)
            .web(web)
            .properties(*(props + "server.port=0"))
            .run(*props.map { "--$it" }.toTypedArray())

    @Test
    fun `AWS の配線（内部 API はホストのトークン、写しは AGENT_HEARTBEAT_PREFIX が s3 のときだけ S3）`() {
        start(awsProps("AGENT_HEARTBEAT_PREFIX=s3://jrabot-ops-state/agent/")).use { ctx ->
            assertIs<StaticTokenVerifier>(ctx.getBean(InternalCallerVerifier::class.java))
            assertIs<S3HeartbeatMirror>(ctx.getBean(HeartbeatMirror::class.java))
        }
        start(awsProps()).use { ctx -> assertIs<UnavailableHeartbeatMirror>(ctx.getBean(HeartbeatMirror::class.java)) }
        assertFailsWith<Exception> { start(awsProps("AGENT_HEARTBEAT_PREFIX=https://example.com/agent/")).close() }
        assertFailsWith<Exception> { start(awsProps().filterNot { it.startsWith("INTERNAL_OPS_TOKEN_FILE=") }.toTypedArray()).close() }
    }

    @Test
    fun `HTTP - 内部 API はトークンが一致したときだけ 200`() {
        start(awsProps(), WebApplicationType.SERVLET).use { ctx ->
            val port = requireNotNull(ctx.environment.getProperty("local.server.port")).toInt()
            val http = HttpClient.newHttpClient()

            fun get(auth: String?): Int {
                val b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/internal/health")).GET()
                auth?.let { b.header("Authorization", it) }
                return http.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
            }
            assertEquals(200, get("Bearer $token"))
            assertEquals(401, get(null))
            assertEquals(401, get("Bearer x$token"))
        }
    }
}
