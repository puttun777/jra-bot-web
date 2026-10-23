package jp.jrabot.cloudgcp.auth.testing

import com.sun.net.httpserver.HttpServer
import jp.jrabot.domain.time.Clock
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/** 固定された Clock（テストが進める）。 */
class FixedClock(
    @Volatile var instant: Instant = DEFAULT_NOW,
) : Clock {
    override fun now(): Instant = instant

    companion object {
        val DEFAULT_NOW: Instant = Instant.parse("2026-11-01T00:00:00Z")
    }
}

/** テスト専用の鍵とトークン。実 Google の鍵・トークンではない。 */
class TestSigner(
    val kid: String,
    val algorithm: String,
    private val keyPair: KeyPair,
) {
    /** JWKS の 1 件分。 */
    fun jwk(): String =
        when (val pub = keyPair.public) {
            is ECPublicKey -> {
                """{"alg":"ES256","crv":"P-256","kid":"$kid","kty":"EC","use":"sig","x":"${b64(fixed(pub.w.affineX, 32))}","y":"${b64(fixed(pub.w.affineY, 32))}"}"""
            }

            is RSAPublicKey -> {
                """{"alg":"RS256","kid":"$kid","kty":"RSA","use":"sig","n":"${b64(unsigned(pub.modulus))}","e":"${b64(unsigned(pub.publicExponent))}"}"""
            }

            else -> {
                error("unsupported")
            }
        }

    /** 署名済みの JWT。[claims] は JSON オブジェクトの本文（`{` `}` を含まない中身）。[header] で alg/kid を上書きできる。 */
    fun token(
        claims: String,
        header: String = """{"alg":"$algorithm","typ":"JWT","kid":"$kid"}""",
    ): String {
        val signingInput = b64(header.toByteArray()) + "." + b64("{$claims}".toByteArray())
        val sig =
            Signature
                .getInstance(if (algorithm == "ES256") "SHA256withECDSAinP1363Format" else "SHA256withRSA")
                .apply {
                    initSign(keyPair.private)
                    update(signingInput.toByteArray())
                }.sign()
        return "$signingInput.${b64(sig)}"
    }

    companion object {
        fun es256(kid: String = "ec-1"): TestSigner =
            TestSigner(
                kid,
                "ES256",
                KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair(),
            )

        fun rs256(kid: String = "rsa-1"): TestSigner = TestSigner(kid, "RS256", KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair())

        private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        private fun unsigned(v: BigInteger): ByteArray = v.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }

        private fun fixed(
            v: BigInteger,
            size: Int,
        ): ByteArray = unsigned(v).let { if (it.size >= size) it else ByteArray(size - it.size) + it }
    }
}

/** ループバックだけで待ち受ける偽の JWKS 配信サーバ。[signers] の公開鍵を返す。取得回数を数える。 */
class FakeJwksServer(
    signers: List<TestSigner>,
) : AutoCloseable {
    @Volatile var signers: List<TestSigner> = signers
    val requests = AtomicInteger()
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/certs") { ex ->
                requests.incrementAndGet()
                val body = """{"keys":[${this@FakeJwksServer.signers.joinToString(",") { it.jwk() }}]}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }

    val url: String get() = "http://${server.address.address.hostAddress}:${server.address.port}/certs"

    override fun close() = server.stop(0)
}

/** 検証に使う標準の claim を組み立てる。 */
object Claims {
    fun iap(
        now: Instant = FixedClock.DEFAULT_NOW,
        audience: String = "/projects/1/locations/asia-northeast1/services/dashboard",
        email: String? = "ops@proj.iam.gserviceaccount.com",
        issuer: String = "https://cloud.google.com/iap",
        expiresInSeconds: Long = 600,
        issuedAtOffsetSeconds: Long = 0,
    ): String =
        listOfNotNull(
            """"iss":"$issuer"""",
            """"aud":"$audience"""",
            """"sub":"accounts.google.com:123"""",
            email?.let { """"email":"$it"""" },
            """"iat":${now.epochSecond + issuedAtOffsetSeconds}""",
            """"exp":${now.epochSecond + expiresInSeconds}""",
        ).joinToString(",")

    fun idToken(
        now: Instant = FixedClock.DEFAULT_NOW,
        audience: String = "https://ingest-xyz.a.run.app",
        email: String? = "ops@proj.iam.gserviceaccount.com",
        emailVerified: Boolean? = true,
        issuer: String = "https://accounts.google.com",
        expiresInSeconds: Long = 600,
        issuedAtOffsetSeconds: Long = 0,
    ): String =
        listOfNotNull(
            """"iss":"$issuer"""",
            """"aud":"$audience"""",
            """"sub":"123"""",
            email?.let { """"email":"$it"""" },
            emailVerified?.let { """"email_verified":$it""" },
            """"iat":${now.epochSecond + issuedAtOffsetSeconds}""",
            """"exp":${now.epochSecond + expiresInSeconds}""",
        ).joinToString(",")
}
