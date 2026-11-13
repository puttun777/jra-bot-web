package jp.jrabot.dashboard

import jp.jrabot.cloudaws.auth.CognitoIdTokenVerifier
import jp.jrabot.cloudgcp.auth.GoogleSignedTokenVerifier
import jp.jrabot.platform.auth.InternalCallerVerifier
import jp.jrabot.platform.auth.PersonVerifier
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
import kotlin.test.assertTrue

/**
 * `RUNTIME_PLATFORM=aws`（D-281）の**本番と同じ配線**: 人は Cognito の ID トークン、`/internal` はホストのトークン。IAP のヘッダーは
 * 認証の根拠にしない（AWS ではクライアントが付けられるただのヘッダー）。DB には接続しない。実 Cognito には接続しない（偽のトークンは署名検証で拒否される）。
 */
class DashboardAwsWiringTest {
    private val token = "Zr8vQh2Lx5mN9pW3kT7yB1cD4fG6jH0sA_uE-iO2qRw" // 合成値

    private fun awsProps(): Array<String> {
        val dir = Files.createTempDirectory("dashboard-aws")
        val ops = writeOpsState(dir)
        val tokenFile = dir.resolve("internal-ops-token").also { Files.writeString(it, token + "\n") }
        return baseProperties(ops).filterNot { it.startsWith("IAP_AUDIENCE=") || it.startsWith("OPS_SA_EMAIL=") }.toTypedArray() +
            arrayOf(
                "RUNTIME_PLATFORM=aws",
                "COGNITO_ISSUER=https://cognito-idp.ap-northeast-1.amazonaws.com/ap-northeast-1_SyntheticPool",
                "COGNITO_CLIENT_ID=syntheticclientid0123456789",
                "INTERNAL_OPS_TOKEN_FILE=$tokenFile",
            )
    }

    private fun start(
        props: Array<String>,
        web: WebApplicationType,
    ): ConfigurableApplicationContext =
        SpringApplicationBuilder(DashboardApplication::class.java)
            .web(web)
            .properties(*(props + "server.port=0"))
            .run(*props.map { "--$it" }.toTypedArray())

    @Test
    fun `AWS の配線では人は Cognito、内部 API はホストのトークンの検証器で、IAP の検証器は作らない`() {
        start(awsProps(), WebApplicationType.NONE).use { ctx ->
            assertIs<CognitoIdTokenVerifier>(ctx.getBean(PersonVerifier::class.java))
            assertIs<StaticTokenVerifier>(ctx.getBean(InternalCallerVerifier::class.java))
            assertTrue(ctx.getBeansOfType(GoogleSignedTokenVerifier::class.java).isEmpty())
            assertEquals(
                AuthHeaders(jp.jrabot.platform.auth.TokenHeader.AUTHORIZATION_BEARER, jp.jrabot.platform.auth.TokenHeader.AUTHORIZATION_BEARER),
                ctx.getBean(AuthHeaders::class.java),
            )
        }
    }

    @Test
    fun `HTTP - 内部 API はトークンが一致したときだけ。IAP のヘッダーや偽の Bearer では人の API に入れない`() {
        start(awsProps(), WebApplicationType.SERVLET).use { ctx ->
            val port = ctx.environment.serverPort()
            val http = HttpClient.newHttpClient()

            fun get(
                path: String,
                vararg headers: Pair<String, String>,
            ): Int {
                val b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET()
                headers.forEach { (k, v) -> b.header(k, v) }
                return http.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
            }
            assertEquals(200, get("/internal/health", "Authorization" to "Bearer $token"))
            assertEquals(401, get("/internal/health"))
            assertEquals(401, get("/internal/health", "Authorization" to "Bearer ${token.dropLast(1)}x"))
            assertEquals(401, get("/internal/health", "X-Goog-IAP-JWT-Assertion" to token))
            assertEquals(401, get("/api/v1/me"))
            assertEquals(401, get("/api/v1/me", "X-Goog-IAP-JWT-Assertion" to "eyJhbGciOiJub25lIn0.e30."))
            assertEquals(401, get("/api/v1/me", "Authorization" to "Bearer eyJhbGciOiJub25lIn0.eyJlbWFpbCI6Im1lQGV4YW1wbGUuY29tIn0."))
            assertEquals(401, get("/api/v1/me", "Authorization" to "Bearer $token"), "内部 API のトークンは人の認証に使えない")
        }
    }

    @Test
    fun `AWS で Cognito・トークンの設定が欠ければ起動失敗（黙って GCP に倒さない）。不明な基盤も起動失敗`() {
        val props = awsProps()
        for (drop in listOf("COGNITO_ISSUER=", "COGNITO_CLIENT_ID=", "INTERNAL_OPS_TOKEN_FILE=")) {
            assertFailsWith<Exception>(drop) { start(props.filterNot { it.startsWith(drop) }.toTypedArray(), WebApplicationType.NONE).close() }
        }
        assertFailsWith<Exception> {
            start(props.map { if (it.startsWith("RUNTIME_PLATFORM=")) "RUNTIME_PLATFORM=azure" else it }.toTypedArray(), WebApplicationType.NONE).close()
        }
        assertFailsWith<Exception> {
            start(
                props.map { if (it.startsWith("COGNITO_ISSUER=")) "COGNITO_ISSUER=https://evil.example/pool" else it }.toTypedArray(),
                WebApplicationType.NONE,
            ).close()
        }
    }
}
