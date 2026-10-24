package jp.jrabot.ingest

import jp.jrabot.cloudgcp.auth.GoogleSignedTokenVerifier
import jp.jrabot.cloudgcp.auth.InternalCallerVerifier
import jp.jrabot.cloudgcp.auth.testing.Claims
import jp.jrabot.cloudgcp.auth.testing.FakeJwksServer
import jp.jrabot.cloudgcp.auth.testing.FixedClock
import jp.jrabot.cloudgcp.auth.testing.TestSigner
import jp.jrabot.domain.ops.InspectableModeProvider
import jp.jrabot.domain.ops.ModeSnapshot
import jp.jrabot.domain.ops.OpsMode
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/** テスト専用の認証の材料（Google の鍵ではない）。検証器は本番と同じクラスで、鍵の取得先だけがこのテストのローカル HTTP サーバ。 */
object TestAuth {
    const val AUDIENCE = "https://ingest-test.example.run.app"
    const val OPS_EMAIL = "ops@test-proj.iam.gserviceaccount.com"
    val signer: TestSigner = TestSigner.rs256("ingest-test-key")
    val jwks = FakeJwksServer(listOf(signer))
    val clock = FixedClock()

    fun validToken(): String = signer.token(Claims.idToken(clock.instant, audience = AUDIENCE, email = OPS_EMAIL))

    fun token(
        audience: String = AUDIENCE,
        email: String? = OPS_EMAIL,
        emailVerified: Boolean? = true,
        issuer: String = "https://accounts.google.com",
        expiresInSeconds: Long = 600,
    ): String = signer.token(Claims.idToken(clock.instant, audience, email, emailVerified, issuer, expiresInSeconds))
}

@TestConfiguration(proxyBeanMethods = false)
class TestAuthConfig {
    /** 本番と同じ [GoogleSignedTokenVerifier]。鍵の取得先だけを偽の JWKS サーバにする（本番の設定に近道は無い）。 */
    @Bean
    @Primary
    fun testInternalCallerVerifier(): InternalCallerVerifier =
        GoogleSignedTokenVerifier.googleIdToken(TestAuth.AUDIENCE, TestAuth.OPS_EMAIL, TestAuth.clock, TestAuth.jwks.url)
}

/** モードをテストから切り替えられる ModeProvider。呼ばれた回数を数える。 */
class ControllableModeProvider(
    @Volatile var mode: OpsMode = OpsMode.NORMAL,
) : InspectableModeProvider {
    val calls = AtomicInteger()

    override fun current(): OpsMode = mode.also { calls.incrementAndGet() }

    override fun snapshot(): ModeSnapshot = if (mode == OpsMode.UNKNOWN) ModeSnapshot.unknown() else ModeSnapshot(mode, mode, 1, 0, "gcs")
}

/** ガードの先に到達したか（= DB に触れ得る処理が呼ばれたか）を数える受け皿。T3.5 で本物のハンドラができたため増えない（常に 0 を確認する旧来の検査は残す）。 */
class AgentProbe {
    val reached = AtomicInteger()
}

@TestConfiguration(proxyBeanMethods = false)
class AgentGuardTestConfig {
    @Bean
    fun agentProbe() = AgentProbe()

    @Bean
    @Primary
    fun controllableModeProvider() = ControllableModeProvider()
}

class Resp(
    val status: Int,
    val body: String,
    private val headers: Map<String, List<String>>,
) {
    fun header(name: String): String? =
        headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
}

/** 実際のサーバ（ランダムポート）への HTTP クライアント。パスは正規化せずそのまま送る。 */
class Http(
    private val port: Int,
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    fun send(
        method: String,
        rawPath: String,
        bearer: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Resp {
        val b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$rawPath")).timeout(Duration.ofSeconds(30))
        if (bearer != null) b.header("Authorization", "Bearer $bearer")
        headers.forEach { (k, v) -> b.header(k, v) }
        b.method(method, if (method == "POST" || method == "PUT") HttpRequest.BodyPublishers.ofString("{}") else HttpRequest.BodyPublishers.noBody())
        val r = client.send(b.build(), HttpResponse.BodyHandlers.ofString())
        return Resp(r.statusCode(), r.body(), r.headers().map())
    }

    fun get(
        rawPath: String,
        bearer: String? = null,
    ) = send("GET", rawPath, bearer)
}

fun Environment.serverPort(): Int = requireNotNull(getProperty("local.server.port")).toInt()

/** 合成データだけの ops-state.json を置く一時ファイル（APP_ENV=local のときだけ使える）。 */
fun writeOpsState(
    dir: Path,
    seq: Long = 12,
    mode: String = "NORMAL",
    desired: String = "NORMAL",
): Path =
    dir.resolve("ops-state.json").also {
        Files.writeString(
            it,
            """{"schema_version":1,"seq":$seq,"mode":"$mode","desired":{"mode":"$desired","set_by":"github:synthetic",""" +
                """"set_at":"2026-11-01T10:00:00Z","reason":"test"},""" +
                """"operation":{"id":"op-1","type":"stop","status":"SUCCEEDED","force":false}}""",
        )
    }

/** 起動に必要な設定の一式（DB は接続を受け付けないポート 1）。 */
fun baseProperties(opsFile: Path): Array<String> =
    arrayOf(
        "APP_ENV=local",
        "DB_URL=jdbc:mysql://127.0.0.1:1/jrabot",
        "DB_USER=ingest",
        "DB_PASSWORD=synthetic-password-never-logged",
        "OPS_MODE_SOURCE=${opsFile.toAbsolutePath()}",
        "INTERNAL_ID_TOKEN_AUDIENCE=${TestAuth.AUDIENCE}",
        "OPS_SA_EMAIL=${TestAuth.OPS_EMAIL}",
    )
