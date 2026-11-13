package jp.jrabot.cloudgcp.auth

import jp.jrabot.cloudgcp.auth.testing.Claims
import jp.jrabot.cloudgcp.auth.testing.FakeJwksServer
import jp.jrabot.cloudgcp.auth.testing.FixedClock
import jp.jrabot.cloudgcp.auth.testing.TestSigner
import jp.jrabot.platform.auth.CallerVerification
import jp.jrabot.platform.auth.RejectReason
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 署名・issuer・audience・有効期限・主体の検証。鍵は偽の JWKS サーバ（ループバック）から取る。実 Google には接続しない。 */
class GoogleSignedTokenVerifierTest {
    companion object {
        private const val OPS = "ops@proj.iam.gserviceaccount.com"
        private const val IAP_AUD = "/projects/1/locations/asia-northeast1/services/dashboard"
        private const val ID_AUD = "https://ingest-xyz.a.run.app"
        private val ec = TestSigner.es256()
        private val rsa = TestSigner.rs256()
        private val jwks = FakeJwksServer(listOf(ec, rsa))
        private val clock = FixedClock()

        @JvmStatic
        @AfterAll
        fun stop() = jwks.close()
    }

    private fun iap(
        audience: String = IAP_AUD,
        email: String = OPS,
        location: String = jwks.url,
    ) = GoogleSignedTokenVerifier.iap(audience, email, clock, location)

    private fun idToken(
        audience: String = ID_AUD,
        email: String = OPS,
        location: String = jwks.url,
    ) = GoogleSignedTokenVerifier.googleIdToken(audience, email, clock, location)

    private fun assertRejected(
        expected: RejectReason?,
        result: CallerVerification,
    ) {
        val r = assertIs<CallerVerification.Rejected>(result, "拒否されるべき")
        if (expected != null) assertEquals(expected, r.reason)
    }

    @Test
    fun `IAP の正しい JWT は ops サービスアカウントとして認められる`() {
        assertEquals(CallerVerification.Verified(OPS), iap().verify(ec.token(Claims.iap())))
    }

    @Test
    fun `Google ID トークンの正しいトークンは ops サービスアカウントとして認められる`() {
        assertEquals(CallerVerification.Verified(OPS), idToken().verify(rsa.token(Claims.idToken())))
    }

    @Test
    fun `トークンが無い・空は MISSING`() {
        assertRejected(RejectReason.MISSING, iap().verify(null))
        assertRejected(RejectReason.MISSING, iap().verify(""))
        assertRejected(RejectReason.MISSING, idToken().verify("   "))
    }

    @Test
    fun `JWT の形でない文字列・大きすぎるトークンは拒否される`() {
        assertRejected(null, iap().verify("not-a-jwt"))
        assertRejected(null, iap().verify("a.b.c"))
        assertRejected(RejectReason.MALFORMED, iap().verify("x".repeat(GoogleSignedTokenVerifier.MAX_TOKEN_LENGTH + 1)))
    }

    @Test
    fun `別の鍵で署名されたトークンは同じ kid でも拒否される`() {
        val forged =
            TestSigner(
                ec.kid,
                "ES256",
                java.security.KeyPairGenerator
                    .getInstance("EC")
                    .apply { initialize(256) }
                    .generateKeyPair(),
            )
        assertRejected(RejectReason.INVALID, iap().verify(forged.token(Claims.iap())))
    }

    @Test
    fun `本文を書き換えたトークンは拒否される`() {
        val parts = ec.token(Claims.iap()).split('.')
        val tamperedPayload =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString("{${Claims.iap(email = "attacker@example.com")}}".toByteArray())
        assertRejected(RejectReason.INVALID, iap().verify("${parts[0]}.$tamperedPayload.${parts[2]}"))
    }

    @Test
    fun `alg が none のトークン・署名なしは拒否される`() {
        val header = """{"alg":"none","typ":"JWT"}"""
        val b64 = { s: String ->
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(s.toByteArray())
        }
        assertRejected(null, iap().verify("${b64(header)}.${b64("{${Claims.iap()}}")}."))
        assertRejected(null, iap().verify(ec.token(Claims.iap(), header = header)))
    }

    @Test
    fun `知らない kid は拒否される`() {
        val other = TestSigner.es256("unknown-kid")
        assertRejected(RejectReason.INVALID, iap().verify(other.token(Claims.iap())))
    }

    @Test
    fun `audience が違うトークンは拒否される`() {
        assertRejected(RejectReason.INVALID, iap().verify(ec.token(Claims.iap(audience = "/projects/2/locations/asia-northeast1/services/dashboard"))))
        assertRejected(RejectReason.INVALID, idToken().verify(rsa.token(Claims.idToken(audience = "https://other.a.run.app"))))
    }

    @Test
    fun `issuer が違うトークンは拒否される`() {
        assertRejected(RejectReason.ISSUER, iap().verify(ec.token(Claims.iap(issuer = "https://evil.example.com"))))
        assertRejected(RejectReason.ISSUER, idToken().verify(rsa.token(Claims.idToken(issuer = "https://evil.example.com"))))
        // IAP の issuer を ID トークンの検証に使い回せない
        assertRejected(RejectReason.ISSUER, idToken().verify(rsa.token(Claims.idToken(issuer = "https://cloud.google.com/iap"))))
    }

    @Test
    fun `ID トークンは accounts_google_com の issuer も許可する`() {
        assertEquals(CallerVerification.Verified(OPS), idToken().verify(rsa.token(Claims.idToken(issuer = "accounts.google.com"))))
    }

    @Test
    fun `期限切れのトークンは拒否される`() {
        assertRejected(null, iap().verify(ec.token(Claims.iap(expiresInSeconds = -120))))
        assertRejected(null, idToken().verify(rsa.token(Claims.idToken(expiresInSeconds = -120))))
    }

    @Test
    fun `期限は注入した時計で判定する（進めると失効する）`() {
        val c = FixedClock()
        val v = GoogleSignedTokenVerifier.iap(IAP_AUD, OPS, c, jwks.url)
        val token = ec.token(Claims.iap(expiresInSeconds = 600))
        assertIs<CallerVerification.Verified>(v.verify(token))
        c.instant = c.instant.plus(Duration.ofMinutes(11))
        assertRejected(null, v.verify(token))
    }

    @Test
    fun `exp が無いトークンは拒否される`() {
        val claims =
            Claims
                .iap()
                .split(",")
                .filterNot { it.startsWith("\"exp\"") }
                .joinToString(",")
        assertRejected(null, iap().verify(ec.token(claims)))
    }

    @Test
    fun `iat が未来すぎるトークンは拒否される`() {
        assertRejected(RejectReason.TIME, iap().verify(ec.token(Claims.iap(issuedAtOffsetSeconds = 300))))
    }

    @Test
    fun `iat のずれが許容内(30 秒以内)なら認められる`() {
        assertIs<CallerVerification.Verified>(iap().verify(ec.token(Claims.iap(issuedAtOffsetSeconds = 20))))
    }

    @Test
    fun `ops 以外の主体は署名が正しくても拒否される`() {
        assertRejected(RejectReason.NOT_OPS_PRINCIPAL, iap().verify(ec.token(Claims.iap(email = "user@example.com"))))
        assertRejected(RejectReason.NOT_OPS_PRINCIPAL, idToken().verify(rsa.token(Claims.idToken(email = "user@example.com"))))
    }

    @Test
    fun `主体はメールの完全一致（部分一致・大文字違いは通らない）`() {
        assertRejected(RejectReason.NOT_OPS_PRINCIPAL, iap().verify(ec.token(Claims.iap(email = "x$OPS"))))
        assertRejected(RejectReason.NOT_OPS_PRINCIPAL, iap().verify(ec.token(Claims.iap(email = OPS.uppercase()))))
        assertRejected(RejectReason.NOT_OPS_PRINCIPAL, iap().verify(ec.token(Claims.iap(email = "$OPS "))))
    }

    @Test
    fun `email が無いトークンは拒否される`() {
        assertRejected(RejectReason.EMAIL, iap().verify(ec.token(Claims.iap(email = null))))
        assertRejected(RejectReason.EMAIL, idToken().verify(rsa.token(Claims.idToken(email = null))))
    }

    @Test
    fun `ID トークンは email_verified が true でなければ拒否される`() {
        assertRejected(RejectReason.EMAIL, idToken().verify(rsa.token(Claims.idToken(emailVerified = false))))
        assertRejected(RejectReason.EMAIL, idToken().verify(rsa.token(Claims.idToken(emailVerified = null))))
    }

    @Test
    fun `署名アルゴリズムは種別ごとに固定される（IAP に RS256、ID トークンに ES256 は通らない）`() {
        // 鍵の配信元は同じなので、署名自体は検証できる。アルゴリズムの種別だけで拒否されること。
        assertRejected(RejectReason.ALGORITHM, iap().verify(rsa.token(Claims.iap())))
        assertRejected(RejectReason.ALGORITHM, idToken().verify(ec.token(Claims.idToken())))
    }

    @Test
    fun `鍵の取得先に到達できないときは拒否される（認めない側に倒す）`() {
        val dead = FakeJwksServer(emptyList()).also { it.close() }
        assertRejected(RejectReason.INVALID, iap(location = dead.url).verify(ec.token(Claims.iap())))
    }

    @Test
    fun `設定が空・不正だと作れない（メッセージに値を含めない）`() {
        assertFailsWith<IllegalArgumentException> { GoogleSignedTokenVerifier.iap("", OPS, clock) }
        assertFailsWith<IllegalArgumentException> { GoogleSignedTokenVerifier.iap(IAP_AUD, " ", clock) }
        val e = assertFailsWith<IllegalArgumentException> { GoogleSignedTokenVerifier.googleIdToken(ID_AUD, "not-an-email", clock) }
        assertTrue("not-an-email" !in e.message.orEmpty())
    }
}
