package jp.jrabot.platform.auth

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class StaticTokenVerifierTest {
    private val token = "q7U9mJx3Hc2bVt0r-LwZp8sA1nYdKe6fG4iO5uRyT_E" // 合成値（43 文字の Base64url）

    @TempDir
    lateinit var dir: Path

    @Test
    fun `一致するトークンだけを認め、欠落・形式違い・不一致は拒否`() {
        val v = StaticTokenVerifier(token)
        assertEquals(CallerVerification.Verified(StaticTokenVerifier.PRINCIPAL), v.verify(token))
        assertEquals(CallerVerification.Rejected(RejectReason.MISSING), v.verify(null))
        assertEquals(CallerVerification.Rejected(RejectReason.MISSING), v.verify(""))
        assertEquals(CallerVerification.Rejected(RejectReason.MALFORMED), v.verify("$token "))
        assertEquals(CallerVerification.Rejected(RejectReason.MALFORMED), v.verify("a".repeat(StaticTokenVerifier.MAX_LENGTH + 1)))
        assertEquals(CallerVerification.Rejected(RejectReason.INVALID), v.verify(token.dropLast(1) + "F"))
        assertEquals(CallerVerification.Rejected(RejectReason.INVALID), v.verify(token.dropLast(1)))
    }

    @Test
    fun `短い・形式違いのトークンは起動失敗（値はメッセージに出さない）`() {
        for (bad in listOf("", "short", "x".repeat(42), "${"a".repeat(43)}!", "a".repeat(StaticTokenVerifier.MAX_LENGTH + 1))) {
            val e = assertFailsWith<IllegalArgumentException> { StaticTokenVerifier(bad) }
            assertFalse(bad.isNotEmpty() && e.message.orEmpty().contains(bad))
        }
    }

    @Test
    fun `ファイルから読む（末尾の改行は無視）。読めない・形式違いは起動失敗`() {
        val f = dir.resolve("token")
        f.writeText("$token\n")
        assertEquals(CallerVerification.Verified(StaticTokenVerifier.PRINCIPAL), StaticTokenVerifier.fromFile(f.toString()).verify(token))
        assertFailsWith<IllegalStateException> { StaticTokenVerifier.fromFile(dir.resolve("none").toString()) }
        f.writeText("short")
        assertFailsWith<IllegalStateException> { StaticTokenVerifier.fromFile(f.toString()) }
    }

    @Test
    fun `ヘッダーからの取り出し（Bearer は空白 1 つ・大小を問わず、トークン内の空白は不可）`() {
        fun bearer(v: String?) = TokenHeader.AUTHORIZATION_BEARER.extract { if (it == "Authorization") v else null }
        assertEquals("abc", bearer("Bearer abc"))
        assertEquals("abc", bearer("bearer abc"))
        assertNull(bearer(null))
        assertNull(bearer("Bearer "))
        assertNull(bearer("Bearer  abc"))
        assertNull(bearer("Bearer a b"))
        assertNull(bearer("Basic abc"))
        assertNull(bearer("Bearerabc"))
        assertEquals("raw", TokenHeader.IAP_ASSERTION.extract { if (it == "X-Goog-IAP-JWT-Assertion") "raw" else null })
    }
}
