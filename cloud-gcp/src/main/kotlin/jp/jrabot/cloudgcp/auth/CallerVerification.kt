package jp.jrabot.cloudgcp.auth

/** 内部 API（`/internal` 配下）の呼び出し元の検証結果（CONTRACTS §11）。拒否の理由は固定語彙で、トークンや claim の値を含めない。 */
sealed interface CallerVerification {
    /** 署名・issuer・audience・有効期限・主体のすべてを満たした ops 用サービスアカウント。 */
    data class Verified(
        val email: String,
    ) : CallerVerification

    data class Rejected(
        val reason: RejectReason,
    ) : CallerVerification
}

enum class RejectReason {
    /** トークンが無い。 */
    MISSING,

    /** 形式が不正・大きすぎる。 */
    MALFORMED,

    /** 署名・audience・有効期限・鍵の取得など、ライブラリの検証に失敗した。 */
    INVALID,

    /** 署名アルゴリズムが想定と違う（IAP は ES256、Google ID トークンは RS256 だけ）。 */
    ALGORITHM,

    /** issuer が想定と違う。 */
    ISSUER,

    /** `exp` が無い・過ぎている、または `iat` が未来（許容 30 秒）。 */
    TIME,

    /** `email` が無い、または `email_verified` が true ではない（ID トークン）。 */
    EMAIL,

    /** 署名は正しいが、ops 用サービスアカウントではない。 */
    NOT_OPS_PRINCIPAL,
}

/** `/internal` 配下の呼び出し元を検証する（署名付きトークンだけを信用する。ヘッダーが存在するだけでは認めない）。 */
fun interface InternalCallerVerifier {
    fun verify(token: String?): CallerVerification
}

/**
 * 人の利用者の署名付きトークン（dashboard: IAP の JWT）を検証し、**検証済みのメールアドレス**を返す（CONTRACTS §11.1）。署名・issuer・audience・有効期限は
 * [InternalCallerVerifier] と同じ。**ops 用サービスアカウントかどうかは見ない**（人の認可は [jp.jrabot.domain.tenant.HumanAuthorizer] が
 * `ALLOWED_USERS`・`app_user`・ロールで行う）。ヘッダーが存在するだけでは認めない。
 */
fun interface PersonVerifier {
    fun verifyPerson(token: String?): CallerVerification
}
