package jp.jrabot.dashboard

import jp.jrabot.cloudaws.auth.CognitoIdTokenVerifier
import jp.jrabot.cloudgcp.auth.testing.FakeJwksServer
import jp.jrabot.cloudgcp.auth.testing.FixedClock
import jp.jrabot.cloudgcp.auth.testing.TestSigner
import jp.jrabot.domain.tenant.AppUser
import jp.jrabot.domain.tenant.AppUserRepository
import jp.jrabot.domain.tenant.AppUserRole
import jp.jrabot.domain.tenant.EmailAddress
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.platform.auth.PersonVerifier
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AWS（CloudFront → Caddy → oauth2-proxy → dashboard。I-050・D-288）の**人の認証・認可**を、実際の Tomcat と本番と同じ Cognito の
 * ID トークンの検証器（鍵の取得先だけ偽の JWKS）で確かめる。oauth2-proxy が付ける `Authorization: Bearer <ID トークン>` を模す。
 * 本人（ALLOWED_USERS・app_user・ADMIN_USERS）だけが通り、Cognito でログインできても本人以外は 403、改変・期限切れ・別の client は 401。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AwsPersonAuthTestConfig::class)
class DashboardAwsPersonAuthTest {
    companion object {
        const val ISSUER = "https://cognito-idp.ap-northeast-1.amazonaws.com/ap-northeast-1_SyntheticPool"
        const val CLIENT = "syntheticclientid0123456789"
        val signer: TestSigner = TestSigner.rs256("cognito-test-key")
        val jwks = FakeJwksServer(listOf(signer))
        val clock = FixedClock()
        private val dir = Files.createTempDirectory("dashboard-aws-person")
        private val ops = writeOpsState(dir)
        private val tokenFile = dir.resolve("internal-ops-token").also { Files.writeString(it, "Zr8vQh2Lx5mN9pW3kT7yB1cD4fG6jH0sA_uE-iO2qRw\n") }

        @JvmStatic
        @DynamicPropertySource
        fun props(r: DynamicPropertyRegistry) {
            baseProperties(ops)
                .filterNot { it.startsWith("IAP_AUDIENCE=") || it.startsWith("OPS_SA_EMAIL=") }
                .forEach { kv -> r.add(kv.substringBefore('=')) { kv.substringAfter('=') } }
            r.add("RUNTIME_PLATFORM") { "aws" }
            r.add("COGNITO_ISSUER") { ISSUER }
            r.add("COGNITO_CLIENT_ID") { CLIENT }
            r.add("INTERNAL_OPS_TOKEN_FILE") { tokenFile.toString() }
            // infra が渡すのと同じ: 本人のメールだけ（pulumi-aws の renderAppEnv）
            r.add("ALLOWED_USERS") { "me@example.com" }
            r.add("ADMIN_USERS") { "me@example.com" }
        }

        /** Cognito の ID トークンの claim（合成）。 */
        fun idToken(
            email: String,
            emailVerified: Boolean = true,
            audience: String = CLIENT,
            issuer: String = ISSUER,
            tokenUse: String = "id",
            expiresInSeconds: Long = 600,
            by: TestSigner = signer,
        ): String {
            val now = clock.instant.epochSecond
            return by.token(
                listOf(
                    """"iss":"$issuer"""",
                    """"aud":"$audience"""",
                    """"sub":"00000000-0000-4000-8000-000000000001"""",
                    """"token_use":"$tokenUse"""",
                    """"email":"$email"""",
                    """"email_verified":$emailVerified""",
                    """"auth_time":$now""",
                    """"iat":$now""",
                    """"exp":${now + expiresInSeconds}""",
                ).joinToString(","),
            )
        }
    }

    @Autowired lateinit var env: Environment

    private val http get() = Http(env.serverPort())

    private fun me(token: String?) = http.send("GET", "/api/v1/me", headers = token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap())

    @Test
    fun `本人は Cognito の ID トークンで入れる（ADMIN・テナント 1）。応答はキャッシュさせない`() {
        val r = me(idToken("me@example.com"))
        assertEquals(200, r.status, r.body)
        assertTrue(r.body.contains("\"role\":\"ADMIN\"") && r.body.contains("\"tenantId\":1") && r.body.contains("me@example.com"), r.body)
        assertEquals("no-store", r.header("Cache-Control"))
    }

    @Test
    fun `Cognito でログインできても本人以外は 403（ALLOWED_USERS に無い・行があっても）`() {
        assertEquals(403, me(idToken("stranger@example.com")).status, "Cognito の利用者でも許可リストに無ければ拒否")
        assertEquals(403, me(idToken("viewer@example.com")).status, "app_user の行があっても許可リストに無ければ拒否")
    }

    @Test
    fun `未認証・メール未確認・期限切れ・別の client・別の発行者・access トークン・改変・知らない鍵は 401`() {
        assertEquals(401, me(null).status)
        assertEquals(401, me(idToken("me@example.com", emailVerified = false)).status)
        assertEquals(401, me(idToken("me@example.com", expiresInSeconds = -600)).status)
        assertEquals(401, me(idToken("me@example.com", audience = "anotherclient0123")).status)
        assertEquals(401, me(idToken("me@example.com", issuer = "https://cognito-idp.ap-northeast-1.amazonaws.com/ap-northeast-1_Other")).status)
        assertEquals(401, me(idToken("me@example.com", tokenUse = "access")).status)
        val good = idToken("me@example.com")
        val (h, p, s) = good.split('.')
        val forgedPayload =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    String(
                        java.util.Base64
                            .getUrlDecoder()
                            .decode(p),
                    ).replace("me@example.com", "me@example.co").toByteArray(),
                )
        assertEquals(401, me("$h.$forgedPayload.$s").status, "本文の改変（署名が合わない）")
        assertEquals(401, me(idToken("me@example.com", by = TestSigner.rs256("cognito-test-key"))).status, "公開されていない鍵での署名")
    }

    @Test
    fun `クライアントが付けた識別ヘッダ（IAP・oauth2-proxy の X-Forwarded-Email）では入れない`() {
        assertEquals(
            401,
            http.send("GET", "/api/v1/me", headers = mapOf("X-Forwarded-Email" to "me@example.com", "X-Forwarded-User" to "me@example.com")).status,
        )
        assertEquals(401, http.send("GET", "/api/v1/me", headers = mapOf("X-Goog-IAP-JWT-Assertion" to idToken("me@example.com"))).status)
    }

    @Test
    fun `管理の書込み - CSRF の条件が欠ける・別の Origin は本人でも 403、本人以外は CSRF を満たしても 403`() {
        val owner = mapOf("Authorization" to "Bearer ${idToken("me@example.com")}")
        val noCsrf = http.send("POST", "/api/v1/admin/agents", headers = owner, csrf = false)
        assertEquals(403, noCsrf.status, noCsrf.body)
        val crossOrigin = http.send("POST", "/api/v1/admin/agents", headers = owner + ("Origin" to "https://d9999999999999.cloudfront.net"))
        assertEquals(403, crossOrigin.status, crossOrigin.body)
        assertTrue(crossOrigin.body.contains("csrf"), crossOrigin.body)
        val stranger = http.send("POST", "/api/v1/admin/agents", headers = mapOf("Authorization" to "Bearer ${idToken("stranger@example.com")}"))
        assertEquals(403, stranger.status, stranger.body)
    }
}

@TestConfiguration(proxyBeanMethods = false)
class AwsPersonAuthTestConfig {
    /** 本番と同じ [CognitoIdTokenVerifier]。鍵の取得先だけを偽の JWKS サーバにする（検証の規則に近道は無い）。 */
    @Bean
    @Primary
    fun testCognitoVerifier(): PersonVerifier =
        CognitoIdTokenVerifier(
            DashboardAwsPersonAuthTest.ISSUER,
            DashboardAwsPersonAuthTest.CLIENT,
            DashboardAwsPersonAuthTest.clock,
            jwksUri = URI.create(DashboardAwsPersonAuthTest.jwks.url),
        )

    /** DB を使わない固定の利用者表（合成）。viewer は行があるが ALLOWED_USERS に無い。 */
    @Bean
    @Primary
    fun testAwsUsers(): AppUserRepository =
        object : AppUserRepository {
            private val rows =
                listOf(
                    AppUser(TenantId.of(1), EmailAddress.of("me@example.com"), AppUserRole.ADMIN, true),
                    AppUser(TenantId.of(1), EmailAddress.of("viewer@example.com"), AppUserRole.VIEWER, true),
                )

            override fun findByEmail(email: EmailAddress) = rows.firstOrNull { it.email == email }

            override fun listByTenant(tenantId: TenantId) = rows.filter { it.tenantId == tenantId }
        }
}
