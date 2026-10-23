package jp.jrabot.domain.tenant

import java.util.Locale

/**
 * 利用者の識別に使うメールアドレス（IAP の JWT `email` と `app_user` の突合。CONTRACTS §11.1）。
 * 前後の空白を除き小文字（Locale.ROOT）に正規化して保持する（D-062）。形式検査は最小限（`@` が 1 つ、両側が空でない、空白・制御文字なし、254 文字以下）。
 */
@JvmInline
value class EmailAddress private constructor(
    val value: String,
) {
    override fun toString(): String = "EmailAddress($value)"

    companion object {
        private const val MAX_LENGTH = 254

        fun ofOrNull(raw: String): EmailAddress? {
            val v = raw.trim().lowercase(Locale.ROOT)
            if (v.isEmpty() || v.length > MAX_LENGTH) return null
            if (v.any { it.isWhitespace() || it.isISOControl() }) return null
            val at = v.indexOf('@')
            if (at <= 0 || at != v.lastIndexOf('@') || at == v.length - 1) return null
            return EmailAddress(v)
        }

        fun of(raw: String): EmailAddress = requireNotNull(ofOrNull(raw)) { "メールアドレスの形式が不正" }
    }
}
