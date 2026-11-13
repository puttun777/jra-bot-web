package jp.jrabot.platform.auth

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * 内部 API（`/internal` 配下）の呼び出し元を、**同じホストの運用エージェントだけが持つ乱数のトークン**で確かめる（AWS 構成。D-281）。
 * `/internal` は外向きのリバースプロキシで遮断しており、ホストの中からだけ届く。そのうえでトークンが一致しなければ拒否する（多層の防御）。
 *
 * - 比較は SHA-256 の値どうしを [MessageDigest.isEqual]（長さ・内容に依存しない時間）で行う。トークンそのものはメモリに残さない（ハッシュだけ保持）。
 * - トークンの形式: Base64url の字母だけで [MIN_LENGTH]〜[MAX_LENGTH] 文字（32 バイト以上の乱数を想定）。短い・形式違いは起動失敗。
 * - 失敗の理由は固定語彙で、トークンの値をログ・応答に出さない。
 */
class StaticTokenVerifier(
    expected: String,
) : InternalCallerVerifier {
    private val expectedHash: ByteArray

    init {
        val v = expected.trim()
        require(v.length in MIN_LENGTH..MAX_LENGTH && TOKEN.matches(v)) { "内部 API のトークンの形式が不正（Base64url の $MIN_LENGTH〜$MAX_LENGTH 文字）" }
        expectedHash = sha256(v)
    }

    override fun verify(token: String?): CallerVerification {
        if (token.isNullOrEmpty()) return CallerVerification.Rejected(RejectReason.MISSING)
        if (token.length > MAX_LENGTH || !TOKEN.matches(token)) return CallerVerification.Rejected(RejectReason.MALFORMED)
        return if (MessageDigest.isEqual(sha256(token), expectedHash)) {
            CallerVerification.Verified(PRINCIPAL)
        } else {
            CallerVerification.Rejected(RejectReason.INVALID)
        }
    }

    companion object {
        const val MIN_LENGTH = 43 // 32 バイトの Base64url（パディングなし）
        const val MAX_LENGTH = 256

        /** 検証済みの主体の表示名（メールアドレスではない。人の認可には使わない）。 */
        const val PRINCIPAL = "ops-host"
        private val TOKEN = Regex("^[A-Za-z0-9_-]+$")

        private fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.US_ASCII))

        /**
         * ファイル（`INTERNAL_OPS_TOKEN_FILE`。ホストの運用エージェントが作り、コンテナへ読取り専用で渡す）から作る。読めない・形式違いは
         * [IllegalStateException]（起動失敗。メッセージにパス・値を含めない）。
         */
        fun fromFile(path: String): StaticTokenVerifier {
            val text =
                try {
                    Files.readString(Path.of(path)).take(MAX_LENGTH + 2)
                } catch (e: Exception) {
                    throw IllegalStateException("INTERNAL_OPS_TOKEN_FILE を読めない(${e.javaClass.simpleName})")
                }
            return try {
                StaticTokenVerifier(text)
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException(e.message)
            }
        }
    }
}

/**
 * 署名付きトークン・内部トークンを、どの HTTP ヘッダーから取るか（実行基盤ごとに固定。D-281）。値を加工せずに返し、検証は各 verifier が行う。
 */
enum class TokenHeader(
    val header: String,
) {
    /** GCP: IAP が付ける JWT（値がそのままトークン）。 */
    IAP_ASSERTION("X-Goog-IAP-JWT-Assertion"),

    /** AWS（と GCP の ingest の内部 API）: `Authorization: Bearer <token>`。スキームの大小は問わず、区切りは空白 1 つ、トークンの中に空白は不可。 */
    AUTHORIZATION_BEARER("Authorization"),
    ;

    fun extract(get: (String) -> String?): String? {
        val raw = get(header) ?: return null
        if (this == IAP_ASSERTION) return raw
        if (raw.length < 8 || !raw.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) return null
        val token = raw.substring(7)
        return token.takeIf { it.isNotEmpty() && it.none { c -> c.isWhitespace() } }
    }
}
