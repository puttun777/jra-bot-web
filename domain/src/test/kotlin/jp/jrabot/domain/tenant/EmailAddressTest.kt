package jp.jrabot.domain.tenant

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EmailAddressTest {
    @Test
    fun `前後の空白を除き小文字に正規化する`() {
        assertEquals("user@example.com", EmailAddress.of("  User@Example.COM \n").value)
        assertEquals(EmailAddress.of("A@b.c"), EmailAddress.of("a@B.C"))
        assertEquals(EmailAddress.of("A@b.c").hashCode(), EmailAddress.of("a@B.C").hashCode())
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "plain", "@example.com", "user@", "a@b@c", "us er@example.com", "user@exa\tmple.com", "user@\u0000x"])
    fun `不正な形式は拒否する`(raw: String) {
        assertNull(EmailAddress.ofOrNull(raw))
        assertFailsWith<IllegalArgumentException> { EmailAddress.of(raw) }
    }

    @Test
    fun `長さの上限は 254 文字`() {
        val ok = "a".repeat(254 - "@x.jp".length) + "@x.jp"
        assertEquals(254, EmailAddress.of(ok).value.length)
        assertNull(EmailAddress.ofOrNull("a$ok"))
    }
}
