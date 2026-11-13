package jp.jrabot.cloudgcp.auth

import com.google.api.client.json.webtoken.JsonWebSignature
import com.google.auth.oauth2.TokenVerifier
import jp.jrabot.domain.time.Clock
import jp.jrabot.platform.auth.CallerVerification
import jp.jrabot.platform.auth.InternalCallerVerifier
import jp.jrabot.platform.auth.PersonVerifier
import jp.jrabot.platform.auth.RejectReason
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Google が署名したトークン（IAP JWT / Google ID トークン）の検証（CONTRACTS §11.1・§11.2）。署名の検証と鍵の取得（キャッシュ付き）は
 * google-auth-library の [TokenVerifier]、audience・有効期限もそこで確認する。ここで追加するのは次の検査で、**すべて満たしたときだけ**
 * [CallerVerification.Verified] を返す（fail closed。例外はすべて拒否に変換する）。
 *
 * - 署名アルゴリズムを種別で固定（IAP=ES256、ID トークン=RS256）。ライブラリの「RS256 か ES256」より厳しい。
 * - issuer を許可リストと完全一致（IAP=`https://cloud.google.com/iap`、ID トークン=`https://accounts.google.com` または `accounts.google.com`）。
 * - `exp` 必須、`iat` が未来すぎない（許容 [SKEW]）。時計は注入した [Clock]。
 * - `email` が [opsServiceAccountEmail] と完全一致。ID トークンは `email_verified=true` も必須。
 *
 * 鍵の取得先は既定で Google の公開鍵。テストはローカルの HTTP サーバの URL（[certificatesLocation]）を渡す（本番の構成にバイパスは無い）。
 * 実 Google の鍵・実トークンでの検証は未実施（検証ゲートは docs/STAGE2_HANDOFF.md §14.5）。
 */
class GoogleSignedTokenVerifier private constructor(
    private val kind: Kind,
    audience: String,
    private val opsServiceAccountEmail: String,
    private val clock: Clock,
    certificatesLocation: String?,
    private val log: Logger,
) : InternalCallerVerifier,
    PersonVerifier {
    enum class Kind(
        val algorithm: String,
        val issuers: Set<String>,
        val requiresVerifiedEmail: Boolean,
    ) {
        /** dashboard: `X-Goog-IAP-JWT-Assertion`（ES256、JWK）。 */
        IAP("ES256", setOf("https://cloud.google.com/iap"), false),

        /** ingest: `Authorization: Bearer <Google ID トークン>`（RS256）。 */
        GOOGLE_ID_TOKEN("RS256", setOf("https://accounts.google.com", "accounts.google.com"), true),
    }

    // issuer の確認は下で行う（IAP/ID トークンで許可リストが違い、ライブラリは 1 つしか持てないため）
    private val delegate: TokenVerifier =
        TokenVerifier
            .newBuilder()
            .setAudience(audience)
            .setClock { clock.now().toEpochMilli() }
            .also { if (certificatesLocation != null) it.setCertificatesLocation(certificatesLocation) }
            .build()

    override fun verify(token: String?): CallerVerification = verifyToken(token, requireOps = true)

    override fun verifyPerson(token: String?): CallerVerification = verifyToken(token, requireOps = false)

    private fun verifyToken(
        token: String?,
        requireOps: Boolean,
    ): CallerVerification {
        if (token.isNullOrBlank()) return reject(RejectReason.MISSING)
        if (token.length > MAX_TOKEN_LENGTH) return reject(RejectReason.MALFORMED)
        return try {
            check(delegate.verify(token), requireOps)
        } catch (_: TokenVerifier.VerificationException) {
            reject(RejectReason.INVALID)
        } catch (_: RuntimeException) {
            reject(RejectReason.MALFORMED)
        }
    }

    private fun check(
        jws: JsonWebSignature,
        requireOps: Boolean,
    ): CallerVerification {
        if (jws.header.algorithm != kind.algorithm) return reject(RejectReason.ALGORITHM)
        val payload = jws.payload
        if (payload.issuer !in kind.issuers) return reject(RejectReason.ISSUER)
        val now = clock.now().epochSecond
        val exp = payload.expirationTimeSeconds ?: return reject(RejectReason.TIME)
        val iat = payload.issuedAtTimeSeconds
        if (now > exp + SKEW.seconds || (iat != null && iat > now + SKEW.seconds)) return reject(RejectReason.TIME)
        val email = payload["email"] as? String
        if (email.isNullOrEmpty()) return reject(RejectReason.EMAIL)
        if (kind.requiresVerifiedEmail && payload["email_verified"] != true) return reject(RejectReason.EMAIL)
        if (requireOps && email != opsServiceAccountEmail) return reject(RejectReason.NOT_OPS_PRINCIPAL)
        return CallerVerification.Verified(email)
    }

    private fun reject(reason: RejectReason): CallerVerification {
        log.info("internal auth rejected: kind={} reason={}", kind, reason)
        return CallerVerification.Rejected(reason)
    }

    companion object {
        /** `exp`/`iat` の許容（CONTRACTS §11.1: 30 秒）。 */
        val SKEW: Duration = Duration.ofSeconds(30)
        const val MAX_TOKEN_LENGTH = 16 * 1024
        const val IAP_HEADER = "X-Goog-IAP-JWT-Assertion"

        /** 設定の不備（空の audience・ops の email）。メッセージに値を含めない。 */
        private fun nonBlank(
            value: String,
            name: String,
        ): String {
            val v = value.trim()
            if (v.isEmpty() || v.any { it.isWhitespace() }) throw IllegalArgumentException("$name が未設定または不正")
            return v
        }

        fun iap(
            audience: String,
            opsServiceAccountEmail: String,
            clock: Clock,
            certificatesLocation: String? = null,
        ): GoogleSignedTokenVerifier = create(Kind.IAP, audience, opsServiceAccountEmail, clock, certificatesLocation)

        fun googleIdToken(
            audience: String,
            opsServiceAccountEmail: String,
            clock: Clock,
            certificatesLocation: String? = null,
        ): GoogleSignedTokenVerifier = create(Kind.GOOGLE_ID_TOKEN, audience, opsServiceAccountEmail, clock, certificatesLocation)

        private fun create(
            kind: Kind,
            audience: String,
            email: String,
            clock: Clock,
            certificatesLocation: String?,
        ) = GoogleSignedTokenVerifier(
            kind,
            nonBlank(audience, "audience"),
            nonBlank(email, "OPS_SA_EMAIL").also { if ('@' !in it) throw IllegalArgumentException("OPS_SA_EMAIL がメールアドレスではない") },
            clock,
            certificatesLocation,
            LoggerFactory.getLogger(GoogleSignedTokenVerifier::class.java),
        )
    }
}
