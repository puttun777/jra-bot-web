package jp.jrabot.dashboard

import jp.jrabot.cloudgcp.auth.GoogleSignedTokenVerifier
import jp.jrabot.cloudgcp.auth.testing.Claims
import jp.jrabot.cloudgcp.auth.testing.FakeJwksServer
import jp.jrabot.cloudgcp.auth.testing.FixedClock
import jp.jrabot.cloudgcp.auth.testing.TestSigner
import jp.jrabot.domain.ops.InspectableModeProvider
import jp.jrabot.domain.ops.ModeSnapshot
import jp.jrabot.domain.ops.OpsMode
import jp.jrabot.domain.tenant.AppUser
import jp.jrabot.domain.tenant.AppUserRepository
import jp.jrabot.domain.tenant.AppUserRole
import jp.jrabot.domain.tenant.EmailAddress
import jp.jrabot.domain.tenant.TenantId
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
    const val AUDIENCE = "/projects/123456/locations/asia-northeast1/services/dashboard-test"
    const val OPS_EMAIL = "ops@test-proj.iam.gserviceaccount.com"
    val signer: TestSigner = TestSigner.es256("iap-test-key")
    val jwks = FakeJwksServer(listOf(signer))
    val clock = FixedClock()

    /** 人の利用者のトークン（既定は管理者の合成メール）。 */
    fun human(email: String = "me@example.com"): String = token(email = email)

    fun validToken(): String = signer.token(Claims.iap(clock.instant, audience = AUDIENCE, email = OPS_EMAIL))

    fun token(
        audience: String = AUDIENCE,
        email: String? = OPS_EMAIL,
        issuer: String = "https://cloud.google.com/iap",
        expiresInSeconds: Long = 600,
    ): String = signer.token(Claims.iap(clock.instant, audience, email, issuer, expiresInSeconds))
}

@TestConfiguration(proxyBeanMethods = false)
class TestAuthConfig {
    /** 本番と同じ [GoogleSignedTokenVerifier]。鍵の取得先だけを偽の JWKS サーバにする（本番の設定に近道は無い）。 */
    @Bean
    @Primary
    fun testInternalCallerVerifier(): GoogleSignedTokenVerifier =
        GoogleSignedTokenVerifier.iap(TestAuth.AUDIENCE, TestAuth.OPS_EMAIL, TestAuth.clock, TestAuth.jwks.url)

    /** DB を使わない固定の利用者表（合成）。 */
    @Bean
    @Primary
    fun testUsers(): AppUserRepository =
        object : AppUserRepository {
            private val rows =
                listOf(
                    AppUser(TenantId.of(1), EmailAddress.of("me@example.com"), AppUserRole.ADMIN, true),
                    AppUser(TenantId.of(1), EmailAddress.of("viewer@example.com"), AppUserRole.VIEWER, true),
                    AppUser(TenantId.of(1), EmailAddress.of("off@example.com"), AppUserRole.VIEWER, false),
                    AppUser(TenantId.of(2), EmailAddress.of("t2@example.com"), AppUserRole.VIEWER, true),
                    AppUser(TenantId.of(1), EmailAddress.of("admin-unlisted@example.com"), AppUserRole.ADMIN, true),
                )

            override fun findByEmail(email: EmailAddress) = rows.firstOrNull { it.email == email }

            override fun listByTenant(tenantId: TenantId) = rows.filter { it.tenantId == tenantId }
        }
}

/** モードをテストから切り替えられる ModeProvider。呼ばれた回数を数える。 */
class ControllableModeProvider(
    @Volatile var mode: OpsMode = OpsMode.NORMAL,
) : InspectableModeProvider {
    val calls = AtomicInteger()

    override fun current(): OpsMode = mode.also { calls.incrementAndGet() }

    override fun snapshot(): ModeSnapshot = if (mode == OpsMode.UNKNOWN) ModeSnapshot.unknown() else ModeSnapshot(mode, mode, 1, 0, "gcs")
}

/** Stage 4 の管理操作の代わりに、ガードの先に到達したか（= 書込みの処理が呼ばれたか）を数える受け皿。 */
class AdminProbe {
    val reached = AtomicInteger()
}

@TestConfiguration(proxyBeanMethods = false)
class AdminGuardTestConfig {
    @Bean
    fun adminProbe() = AdminProbe()

    @Bean
    @Primary
    fun controllableModeProvider() = ControllableModeProvider()

    @Bean
    fun probeController(probe: AdminProbe) = jp.jrabot.dashboardtest.ProbeController(probe)
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
        if (bearer != null) b.header("X-Goog-IAP-JWT-Assertion", bearer)
        headers.forEach { (k, v) -> b.header(k, v) }
        b.method(method, if (method in setOf("POST", "PUT", "PATCH")) HttpRequest.BodyPublishers.ofString("{}") else HttpRequest.BodyPublishers.noBody())
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
        "DB_USER=app",
        "DB_PASSWORD=synthetic-password-never-logged",
        "OPS_MODE_SOURCE=${opsFile.toAbsolutePath()}",
        "IAP_AUDIENCE=${TestAuth.AUDIENCE}",
        "OPS_SA_EMAIL=${TestAuth.OPS_EMAIL}",
        "ALLOWED_USERS=me@example.com,viewer@example.com",
        "ADMIN_USERS=me@example.com",
    )
