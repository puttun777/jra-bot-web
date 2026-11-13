package jp.jrabot.cloudaws.auth

import com.sun.net.httpserver.HttpServer
import jp.jrabot.platform.auth.CallerVerification
import jp.jrabot.platform.auth.RejectReason
import jp.jrabot.platform.ops.MutableClock
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** 合成の RSA 鍵と偽の JWKS サーバで、Cognito の ID トークンの検証を確かめる（実 Cognito には接続しない）。 */
class CognitoIdTokenVerifierTest {
    private val issuer = "https://cognito-idp.ap-northeast-1.amazonaws.com/ap-northeast-1_AbCdEf123"
    private val clientId = "3n4b5urk1ft4fl3mg5e62d9ado"
    private val now = Instant.parse("2026-11-13T03:00:00Z")
    private val clock = MutableClock(now)
    private val key: KeyPair = rsa(2048)
    private val other: KeyPair = rsa(2048)
    private val fetches = AtomicInteger()

    @Volatile
    private var jwks: String = jwksJson("k1" to key)

    @Volatile
    private var jwksStatus = 200

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/jwks.json") { ex ->
                fetches.incrementAndGet()
                val b = jwks.toByteArray()
                ex.sendResponseHeaders(jwksStatus, b.size.toLong())
                ex.responseBody.use { it.write(b) }
            }
            start()
        }

    @AfterTest
    fun stop() = server.stop(0)

    private fun verifier() = CognitoIdTokenVerifier(issuer, clientId, clock, jwksUri = URI.create("http://127.0.0.1:${server.address.port}/jwks.json"))

    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    private fun claims(
        iss: String = issuer,
        aud: String = "\"$clientId\"",
        tokenUse: String = "id",
        exp: Long = clock.now().epochSecond + 3600,
        iat: Long = clock.now().epochSecond - 10,
        email: String = "\"owner@example.com\"",
        emailVerified: String = "true",
        extra: String = "",
    ) =
        """{"sub":"11111111-2222-3333-4444-555555555555","iss":"$iss","aud":$aud,"token_use":"$tokenUse","exp":$exp,"iat":$iat,"email":$email,"email_verified":$emailVerified$extra}"""

    private fun token(
        payload: String = claims(),
        header: String = """{"kid":"k1","alg":"RS256"}""",
        signer: KeyPair = key,
    ): String {
        val signingInput = b64(header.toByteArray()) + "." + b64(payload.toByteArray())
        val sig =
            Signature
                .getInstance("SHA256withRSA")
                .apply {
                    initSign(signer.private)
                    update(signingInput.toByteArray())
                }.sign()
        return signingInput + "." + b64(sig)
    }

    private fun reason(
        v: CognitoIdTokenVerifier,
        t: String?,
    ): RejectReason = assertIs<CallerVerification.Rejected>(v.verifyPerson(t)).reason

    @Test
    fun `正しい ID トークンは検証済みのメールアドレスを返す`() {
        assertEquals(CallerVerification.Verified("owner@example.com"), verifier().verifyPerson(token()))
    }

    @Test
    fun `アルゴリズムは RS256 だけ（none・HS256・ES256 は拒否。公開鍵を HMAC の鍵にする取り違えも通さない）`() {
        val v = verifier()
        val p = b64(claims().toByteArray())
        assertEquals(RejectReason.MALFORMED, reason(v, b64("""{"kid":"k1","alg":"none"}""".toByteArray()) + "." + p + "."))
        assertEquals(RejectReason.ALGORITHM, reason(v, b64("""{"kid":"k1","alg":"none"}""".toByteArray()) + "." + p + ".AAAA"))
        assertEquals(RejectReason.MALFORMED, reason(v, b64("""{"kid":"k1","alg":"none"}""".toByteArray()) + "." + p + ".x="))
        val hsInput = b64("""{"kid":"k1","alg":"HS256"}""".toByteArray()) + "." + p
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec((key.public as RSAPublicKey).encoded, "HmacSHA256")) }
        assertEquals(RejectReason.ALGORITHM, reason(v, hsInput + "." + b64(mac.doFinal(hsInput.toByteArray()))))
        assertEquals(RejectReason.ALGORITHM, reason(v, token(header = """{"kid":"k1","alg":"ES256"}""")))
        assertEquals(RejectReason.ALGORITHM, reason(v, token(header = """{"kid":"k1","alg":"rs256"}""")))
    }

    @Test
    fun `署名の改ざん・別の鍵・知らない kid・トークン内の鍵の指定は拒否`() {
        val v = verifier()
        val t = token()
        val tampered = t.substringBeforeLast('.') + "." + b64(ByteArray(256) { 7 })
        assertEquals(RejectReason.INVALID, reason(v, tampered))
        val swapped = token(payload = claims(email = "\"attacker@example.com\"")).substringBeforeLast('.') + "." + t.substringAfterLast('.')
        assertEquals(RejectReason.INVALID, reason(v, swapped))
        assertEquals(RejectReason.INVALID, reason(v, token(signer = other)))
        assertEquals(RejectReason.INVALID, reason(v, token(header = """{"kid":"k9","alg":"RS256"}""")))
        assertEquals(RejectReason.MALFORMED, reason(v, token(header = """{"kid":"k1","alg":"RS256","jku":"https://evil.example/jwks"}""")))
        assertEquals(RejectReason.MALFORMED, reason(v, token(header = """{"kid":"k1","alg":"RS256","jwk":{"kty":"RSA"}}""")))
        assertEquals(RejectReason.MALFORMED, reason(v, token(header = """{"alg":"RS256"}""")))
    }

    @Test
    fun `クレーム（iss・aud・token_use の完全一致、期限、メールの確認済み）`() {
        val v = verifier()
        assertEquals(RejectReason.ISSUER, reason(v, token(claims(iss = "$issuer/"))))
        assertEquals(RejectReason.ISSUER, reason(v, token(claims(iss = "https://cognito-idp.ap-northeast-1.amazonaws.com/ap-northeast-1_Other"))))
        assertEquals(RejectReason.INVALID, reason(v, token(claims(aud = "\"other-client\""))))
        assertEquals(RejectReason.INVALID, reason(v, token(claims(aud = "[\"$clientId\"]"))))
        assertEquals(RejectReason.INVALID, reason(v, token(claims(tokenUse = "access"))))
        assertEquals(RejectReason.TIME, reason(v, token(claims(exp = now.epochSecond - 31))))
        assertIs<CallerVerification.Verified>(v.verifyPerson(token(claims(exp = now.epochSecond - 29))))
        assertEquals(RejectReason.TIME, reason(v, token(claims(iat = now.epochSecond + 31))))
        assertEquals(RejectReason.TIME, reason(v, token(claims(extra = ",\"nbf\":${now.epochSecond + 120}"))))
        assertEquals(RejectReason.EMAIL, reason(v, token(claims(emailVerified = "false"))))
        assertEquals(RejectReason.EMAIL, reason(v, token(claims(emailVerified = "\"true\""))))
        assertEquals(RejectReason.EMAIL, reason(v, token(claims(email = "\"\""))))
        assertEquals(RejectReason.EMAIL, reason(v, token(claims(email = "1"))))
    }

    @Test
    fun `形式（欠落・空・大きすぎる・区切りの数・Base64url 以外は拒否）`() {
        val v = verifier()
        assertEquals(RejectReason.MISSING, reason(v, null))
        assertEquals(RejectReason.MISSING, reason(v, " "))
        assertEquals(RejectReason.MALFORMED, reason(v, "a".repeat(CognitoIdTokenVerifier.MAX_TOKEN_LENGTH + 1)))
        assertEquals(RejectReason.MALFORMED, reason(v, "a.b"))
        assertEquals(RejectReason.MALFORMED, reason(v, token() + ".x"))
        assertEquals(RejectReason.MALFORMED, reason(v, token().replaceFirst(".", "=.")))
    }

    @Test
    fun `鍵（2048 ビット未満・用途違いの鍵は使わず、JWKS が取れなければ拒否（fail closed））`() {
        jwks = jwksJson("k1" to rsa(1024))
        assertEquals(RejectReason.INVALID, reason(verifier(), token(signer = key)))
        jwks = """{"keys":[{"kty":"RSA","kid":"k1","use":"enc","n":"${b64((key.public as RSAPublicKey).modulus.toByteArray())}","e":"AQAB"}]}"""
        assertEquals(RejectReason.INVALID, reason(verifier(), token()))
        jwks = jwksJson("k1" to key)
        jwksStatus = 500
        assertEquals(RejectReason.INVALID, reason(verifier(), token()))
    }

    @Test
    fun `鍵のキャッシュ（知らない kid の取り直しは間隔を空け、鍵の更新（ローテーション）に追従する）`() {
        val v = verifier()
        assertIs<CallerVerification.Verified>(v.verifyPerson(token()))
        assertIs<CallerVerification.Verified>(v.verifyPerson(token()))
        assertEquals(1, fetches.get())
        // 新しい鍵が JWKS に追加された
        jwks = jwksJson("k1" to key, "k2" to other)
        assertEquals(RejectReason.INVALID, reason(v, token(header = """{"kid":"k2","alg":"RS256"}""", signer = other)), "60 秒以内は取り直さない")
        assertEquals(1, fetches.get())
        clock.advance(Duration.ofSeconds(61))
        assertIs<CallerVerification.Verified>(v.verifyPerson(token(header = """{"kid":"k2","alg":"RS256"}""", signer = other)))
        assertEquals(2, fetches.get())
        // JWKS が取れなくなっても、24 時間までは手元の鍵で検証できる。それを過ぎたら拒否
        jwksStatus = 500
        clock.advance(Duration.ofHours(2))
        assertIs<CallerVerification.Verified>(v.verifyPerson(token()))
        clock.advance(Duration.ofHours(23))
        assertEquals(RejectReason.INVALID, reason(v, token()))
    }

    @Test
    fun `設定（issuer と client id の形式が違えば起動失敗（値は出さない））`() {
        for (bad in listOf(
            "",
            "http://cognito-idp.ap-northeast-1.amazonaws.com/ap-northeast-1_x",
            "https://evil.example/ap-northeast-1_x",
            "https://cognito-idp.ap-northeast-1.amazonaws.com/",
        )) {
            val e = assertFailsWith<IllegalArgumentException> { CognitoIdTokenVerifier(bad, clientId, clock) }
            assertEquals(false, e.message.orEmpty().contains("evil"))
        }
        assertFailsWith<IllegalArgumentException> { CognitoIdTokenVerifier(issuer, "bad id!", clock) }
        CognitoIdTokenVerifier("$issuer/", clientId, clock)
    }

    private fun jwksJson(vararg keys: Pair<String, KeyPair>): String =
        keys.joinToString(",", """{"keys":[""", "]}") { (kid, kp) ->
            val pub = kp.public as RSAPublicKey
            """{"kty":"RSA","alg":"RS256","use":"sig","kid":"$kid","n":"${b64(
                unsigned(pub.modulus.toByteArray()),
            )}","e":"${b64(unsigned(pub.publicExponent.toByteArray()))}"}"""
        }

    private fun unsigned(b: ByteArray) = if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b

    private companion object {
        fun rsa(bits: Int): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()
    }
}
