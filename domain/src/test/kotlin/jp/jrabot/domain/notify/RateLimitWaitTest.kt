package jp.jrabot.domain.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RateLimitWaitTest {
    private fun parse(
        body: String? = null,
        retryAfter: String? = null,
        reset: String? = null,
    ) = RateLimitWait.parseSeconds(body, retryAfter, reset)

    @Test
    fun `本文の retry_after を最優先する`() {
        assertEquals(2.5, parse("""{"retry_after": 2.5, "global": false}""", "9", "8"))
        assertEquals(3.0, parse("""{"retry_after": 3}""", "9"))
        assertEquals(1.25, parse("""{"retry_after": "1.25"}"""))
    }

    @Test
    fun `本文が使えなければ Retry-After 次に X-RateLimit-Reset-After`() {
        assertEquals(4.0, parse("""{"message":"x"}""", "4"))
        assertEquals(4.5, parse(null, " 4.5 ", "9"))
        assertEquals(6.0, parse("not json", null, "6"))
        assertEquals(7.0, parse("""{"retry_after": 0}""", "abc", "7"))
    }

    @Test
    fun `どれも無い・0 以下・数値でないときは 1 秒`() {
        assertEquals(1.0, parse())
        assertEquals(1.0, parse("""{"retry_after": -3}""", "0", "-1"))
        assertEquals(1.0, parse("[]", "", ""))
        assertEquals(1.0, parse("""{"retry_after": [1]}""", "x1", "1x"))
        assertEquals(1.0, parse("""{"retry_after": "nan"}"""))
    }

    @Test
    fun `旧 Python の float の癖を再現する`() {
        // True は 1.0、無限大は正（呼び出し側が上限で扱う）
        assertEquals(1.0, parse("""{"retry_after": true}""", "5"))
        assertEquals(Double.POSITIVE_INFINITY, parse("""{"retry_after": "inf"}"""))
        assertEquals(1.0, parse("""{"retry_after": false}"""))
    }

    @Test
    fun `scope は既知の値だけ`() {
        assertEquals("user", RateLimitWait.scope(" USER "))
        assertEquals("global", RateLimitWait.scope("global"))
        assertEquals("shared", RateLimitWait.scope("shared"))
        assertEquals("other", RateLimitWait.scope("whatever-secret"))
        assertNull(RateLimitWait.scope(null))
        assertNull(RateLimitWait.scope("  "))
    }
}
