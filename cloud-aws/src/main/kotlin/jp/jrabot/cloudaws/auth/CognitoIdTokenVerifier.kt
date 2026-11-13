package jp.jrabot.cloudaws.auth

import jp.jrabot.domain.time.Clock
import jp.jrabot.platform.auth.CallerVerification
import jp.jrabot.platform.auth.PersonVerifier
import jp.jrabot.platform.auth.RejectReason
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Amazon Cognito（ユーザープール）の **ID トークン**を検証し、検証済みのメールアドレスを返す（CONTRACTS §11.1 の AWS 版。D-281）。
 * dashboard の前段の oauth2-proxy がログインを行い、`Authorization: Bearer <ID トークン>` で渡す。**ヘッダーがあるだけでは認めない**:
 * 署名を Cognito の公開鍵で検証し、次をすべて満たしたときだけ [CallerVerification.Verified] を返す（fail closed。例外はすべて拒否に変換する）。
 *
 * - 形式: `header.payload.signature` の Base64url（パディング・字母外の文字は不可）、全体で [MAX_TOKEN_LENGTH] 以内。
 * - ヘッダー: `alg` は **RS256 だけ**（`none`・HS256 などは拒否）、`kid` 必須。`jku`・`x5u`・`jwk`・`crit` があれば拒否（鍵はトークンから取らない）。
 * - 鍵: `<issuer>/.well-known/jwks.json` の RSA 鍵（`use` は無いか `sig`、`alg` は無いか RS256、2048 ビット以上）。[jwksTtl] キャッシュ。
 *   知らない `kid` は取り直す（最短 [refetchInterval] 間隔）。取得に失敗したら拒否（キャッシュが空なら必ず拒否。古い鍵は [maxStale] まで使う）。
 * - クレーム: `iss` が [issuer] と完全一致、`token_use` が `id`（アクセストークンは不可）、`aud` が [clientId] と完全一致（文字列）、
 *   `exp` 必須で期限内、`iat` 必須で未来すぎない、`nbf` があれば過ぎている（いずれも許容 [SKEW]）、`email` があり `email_verified` が真偽値の true。
 *
 * 人の認可（`ALLOWED_USERS`・`app_user`・ロール）はこの後の [jp.jrabot.domain.tenant.HumanAuthorizer] が行う（ここではメールを返すだけ）。
 * 拒否の理由は固定語彙で、トークンやクレームの値をログに出さない。実 Cognito での検証は未実施（合成の鍵と偽の JWKS サーバで試験）。
 */
class CognitoIdTokenVerifier(
    issuer: String,
    clientId: String,
    private val clock: Clock,
    private val jwksUri: URI = URI.create(normalizeIssuer(issuer) + "/.well-known/jwks.json"),
    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
    private val jwksTtl: Duration = Duration.ofHours(1),
    private val refetchInterval: Duration = Duration.ofSeconds(60),
    private val maxStale: Duration = Duration.ofHours(24),
    private val log: Logger = LoggerFactory.getLogger(CognitoIdTokenVerifier::class.java),
) : PersonVerifier {
    private val issuer: String = normalizeIssuer(issuer)
    private val clientId: String = clientId.trim().also { require(CLIENT_ID.matches(it)) { "COGNITO_CLIENT_ID の形式が不正" } }
    private val mapper: JsonMapper = JsonMapper.builder().build()

    private class Keys(
        val byKid: Map<String, RSAPublicKey>,
        val fetchedAt: Instant,
    )

    @Volatile
    private var keys: Keys? = null

    @Volatile
    private var lastFetchAttempt: Instant? = null

    override fun verifyPerson(token: String?): CallerVerification {
        if (token.isNullOrBlank()) return reject(RejectReason.MISSING)
        if (token.length > MAX_TOKEN_LENGTH) return reject(RejectReason.MALFORMED)
        return try {
            check(token)
        } catch (_: RuntimeException) {
            reject(RejectReason.MALFORMED)
        }
    }

    private fun check(token: String): CallerVerification {
        val parts = token.split('.')
        if (parts.size != 3 || parts.any { it.isEmpty() || !B64URL.matches(it) }) return reject(RejectReason.MALFORMED)
        val header = json(decode(parts[0])) ?: return reject(RejectReason.MALFORMED)
        if (header.path("alg").asString("") != "RS256") return reject(RejectReason.ALGORITHM)
        if (FORBIDDEN_HEADERS.any { header.has(it) }) return reject(RejectReason.MALFORMED)
        if (header.has("typ") && header.path("typ").asString("") != "JWT") return reject(RejectReason.MALFORMED)
        val kid = header.path("kid").asString("")
        if (!KID.matches(kid)) return reject(RejectReason.MALFORMED)
        val key = keyFor(kid) ?: return reject(RejectReason.INVALID)
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(key)
        verifier.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        if (!verifier.verify(decode(parts[2]))) return reject(RejectReason.INVALID)
        // 署名が正しいときだけクレームを読む
        val claims = json(decode(parts[1])) ?: return reject(RejectReason.MALFORMED)
        if (claims.path("iss").asString("") != issuer || !claims.path("iss").isString) return reject(RejectReason.ISSUER)
        if (claims.path("token_use").asString("") != "id") return reject(RejectReason.INVALID)
        val aud = claims.path("aud")
        if (!aud.isString || aud.asString() != clientId) return reject(RejectReason.INVALID)
        val now = clock.now().epochSecond
        val exp = claims.path("exp")
        val iat = claims.path("iat")
        if (!exp.isIntegralNumber || !iat.isIntegralNumber) return reject(RejectReason.TIME)
        if (now > exp.asLong() + SKEW.seconds || iat.asLong() > now + SKEW.seconds) return reject(RejectReason.TIME)
        val nbf = claims.path("nbf")
        if (!nbf.isMissingNode && (!nbf.isIntegralNumber || nbf.asLong() > now + SKEW.seconds)) return reject(RejectReason.TIME)
        val email = claims.path("email")
        if (!email.isString || email.asString().isEmpty() || email.asString().length > 254) return reject(RejectReason.EMAIL)
        val verified = claims.path("email_verified")
        if (!verified.isBoolean || !verified.asBoolean()) return reject(RejectReason.EMAIL)
        return CallerVerification.Verified(email.asString())
    }

    /** `kid` の鍵。キャッシュが期限内ならそれ、無ければ（または知らない kid なら間隔を空けて）取り直す。取れなければ null（拒否）。 */
    private fun keyFor(kid: String): RSAPublicKey? {
        val now = clock.now()
        val cached = keys
        val fresh = cached != null && Duration.between(cached.fetchedAt, now) < jwksTtl
        cached?.byKid?.get(kid)?.let { if (fresh) return it }
        if (canFetch(now)) {
            fetch(now)?.let { keys = it }
        }
        val current = keys ?: return null
        if (Duration.between(current.fetchedAt, now) > maxStale) return null
        return current.byKid[kid]
    }

    @Synchronized
    private fun canFetch(now: Instant): Boolean {
        val last = lastFetchAttempt
        if (last != null && Duration.between(last, now) < refetchInterval) return false
        lastFetchAttempt = now
        return true
    }

    private fun fetch(now: Instant): Keys? =
        try {
            val res =
                http.send(
                    HttpRequest
                        .newBuilder(jwksUri)
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofInputStream(),
                )
            val body = res.body().use { it.readNBytes(MAX_JWKS_BYTES + 1) }
            if (res.statusCode() != 200 || body.size > MAX_JWKS_BYTES) {
                log.warn("COGNITO_JWKS fetch-rejected status={} bytes={}", res.statusCode(), body.size)
                null
            } else {
                parseJwks(body, now)
            }
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            log.warn("COGNITO_JWKS fetch-failed error={}", e.javaClass.simpleName)
            null
        }

    private fun parseJwks(
        body: ByteArray,
        now: Instant,
    ): Keys? {
        val root = json(body) ?: return null
        val out = LinkedHashMap<String, RSAPublicKey>()
        for (k in root.path("keys")) {
            if (k.path("kty").asString("") != "RSA") continue
            if (k.has("use") && k.path("use").asString("") != "sig") continue
            if (k.has("alg") && k.path("alg").asString("") != "RS256") continue
            val kid = k.path("kid").asString("")
            if (!KID.matches(kid)) continue
            val n = k.path("n").asString("")
            val e = k.path("e").asString("")
            if (!B64URL.matches(n) || !B64URL.matches(e)) continue
            val spec = RSAPublicKeySpec(BigInteger(1, decode(n)), BigInteger(1, decode(e)))
            val key = KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
            if (key.modulus.bitLength() < MIN_RSA_BITS) continue
            out[kid] = key
        }
        if (out.isEmpty()) {
            log.warn("COGNITO_JWKS no-usable-keys")
            return null
        }
        return Keys(out, now)
    }

    private fun json(bytes: ByteArray): JsonNode? = runCatching { mapper.readTree(bytes) }.getOrNull()?.takeIf { it.isObject }

    private fun reject(reason: RejectReason): CallerVerification {
        log.info("cognito auth rejected: reason={}", reason)
        return CallerVerification.Rejected(reason)
    }

    companion object {
        val SKEW: Duration = Duration.ofSeconds(30)
        const val MAX_TOKEN_LENGTH = 16 * 1024
        const val MAX_JWKS_BYTES = 64 * 1024
        const val MIN_RSA_BITS = 2048
        private val B64URL = Regex("^[A-Za-z0-9_-]+$")
        private val KID = Regex("^[A-Za-z0-9+/=._-]{1,128}$")
        private val CLIENT_ID = Regex("^[A-Za-z0-9]{1,128}$")
        private val FORBIDDEN_HEADERS = listOf("jku", "x5u", "jwk", "crit", "x5c")
        private val ISSUER = Regex("^https://cognito-idp\\.[a-z]{2}-[a-z]+-[0-9]\\.amazonaws\\.com/[a-z]{2}-[a-z]+-[0-9]_[A-Za-z0-9]{1,55}$")

        private fun decode(s: String): ByteArray = Base64.getUrlDecoder().decode(s)

        /** `https://cognito-idp.<region>.amazonaws.com/<pool id>`（末尾の `/` は付けない）。形式が違えば [IllegalArgumentException]（値は出さない）。 */
        fun normalizeIssuer(raw: String): String {
            val v = raw.trim().trimEnd('/')
            require(ISSUER.matches(v)) { "COGNITO_ISSUER の形式が不正（https://cognito-idp.<region>.amazonaws.com/<pool id>）" }
            return v
        }
    }
}
