package jp.jrabot.dashboard

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `/api` の認証・認可（T4.1）。実際の Tomcat と、本番と同じ JWT 検証器（鍵の取得先だけ偽）で、deny by default を確認する。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestAuthConfig::class)
class DashboardApiAuthTest {
    companion object {
        private val dir = Files.createTempDirectory("dashboard-api-test")
        private val ops = writeOpsState(dir)

        @JvmStatic
        @DynamicPropertySource
        fun props(r: DynamicPropertyRegistry) {
            baseProperties(ops).forEach { kv -> r.add(kv.substringBefore('=')) { kv.substringAfter('=') } }
            r.add("ALLOWED_USERS") { "me@example.com, viewer@example.com, off@example.com, t2@example.com, admin-unlisted@example.com, ${TestAuth.OPS_EMAIL}" }
            r.add("ADMIN_USERS") { "me@example.com" }
        }
    }

    @Autowired lateinit var env: Environment

    private val http get() = Http(env.serverPort())

    private fun me(email: String?) = http.get("/api/v1/me", email?.let { TestAuth.token(email = it) })

    @Test
    fun `許可された管理者は自分のロールとテナントを見られる`() {
        val r = me("me@example.com")
        assertEquals(200, r.status)
        assertTrue(r.body.contains("\"role\":\"ADMIN\"") && r.body.contains("\"tenantId\":1") && r.body.contains("me@example.com"), r.body)
        assertEquals("no-store", r.header("Cache-Control"))
    }

    @Test
    fun `閲覧者は VIEWER で テナントは行から決まる`() {
        assertTrue(me("viewer@example.com").body.contains("\"role\":\"VIEWER\""))
        assertTrue(me("t2@example.com").body.contains("\"tenantId\":2"))
    }

    @Test
    fun `トークンが無い 壊れている 別の audience は 401`() {
        assertEquals(401, me(null).status)
        assertEquals(401, http.get("/api/v1/me", "not-a-jwt").status)
        assertEquals(401, http.get("/api/v1/me", TestAuth.token(audience = "/projects/1/locations/x/services/other", email = "me@example.com")).status)
        assertEquals(401, http.get("/api/v1/me", TestAuth.token(issuer = "https://evil.example", email = "me@example.com")).status)
        assertEquals(401, http.get("/api/v1/me", TestAuth.token(expiresInSeconds = -600, email = "me@example.com")).status)
    }

    @Test
    fun `許可リストに無い 無効な行 ADMIN_USERS に無い ADMIN 行 ops SA の人としての利用は 403`() {
        assertEquals(403, me("stranger@example.com").status)
        assertEquals(403, me("off@example.com").status, "無効な行")
        assertEquals(403, me("admin-unlisted@example.com").status, "ADMIN 行が ADMIN_USERS に無い")
        assertEquals(403, me(TestAuth.OPS_EMAIL).status, "ops SA は app_user に行が無いので人としては使えない")
    }

    @Test
    fun `読取り以外のメソッドは管理者だけ 閲覧者は既定で 403`() {
        assertEquals(403, http.send("POST", "/api/v1/me", TestAuth.human("viewer@example.com")).status)
        assertEquals(403, http.send("DELETE", "/api/v1/anything", TestAuth.human("viewer@example.com")).status)
        // 管理者は認可を通る（書込みのルートが無いので 404/405。通ったことの確認）
        assertTrue(http.send("POST", "/api/v1/me", TestAuth.human("me@example.com")).status in setOf(404, 405, 409))
    }

    @Test
    fun `403 と 401 の本文に理由や値を出さない`() {
        val r = me("stranger@example.com")
        assertEquals("""{"error":"forbidden"}""", r.body)
        assertFalse(r.body.contains("stranger"))
        assertEquals("""{"error":"unauthorized"}""", me(null).body)
    }

    @Test
    fun `パスの正規化を使った素通りを許さない`() {
        for (p in listOf("/api/v1/me/", "/API/v1/me", "//api/v1/me", "/api/../api/v1/me", "/api/v1/me;x=1", "/%61pi/v1/me")) {
            assertTrue(http.get(p).status in setOf(401, 404, 400), "$p が認証なしで通った")
        }
    }

    @Test
    fun `internal は ops SA だけ 人の API に ops のトークンだけでは入れない`() {
        assertEquals(200, http.get("/internal/health", TestAuth.validToken()).status)
        assertEquals(403, http.get("/internal/health", TestAuth.token(email = "me@example.com")).status, "人は internal に入れない")
    }
}
