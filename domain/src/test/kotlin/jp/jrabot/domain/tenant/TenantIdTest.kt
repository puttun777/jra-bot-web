package jp.jrabot.domain.tenant

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class TenantIdTest {
    @Test
    fun `1 以上の値を生成できる`() {
        assertEquals(1L, TenantId.of(1).value)
        assertEquals(Long.MAX_VALUE, TenantId.of(Long.MAX_VALUE).value)
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1, Long.MIN_VALUE])
    fun `0 と負数は拒否する`(value: Long) {
        assertFailsWith<IllegalArgumentException> { TenantId.of(value) }
        assertNull(TenantId.ofOrNull(value))
    }

    @Test
    fun `等価性と hashCode は値で決まる`() {
        assertEquals(TenantId.of(7), TenantId.of(7))
        assertEquals(TenantId.of(7).hashCode(), TenantId.of(7).hashCode())
        assertNotEquals(TenantId.of(7), TenantId.of(8))
        assertEquals(setOf(TenantId.of(1), TenantId.of(2)), setOf(TenantId.of(2), TenantId.of(1), TenantId.of(1)))
    }

    @Test
    fun `別の型とは等価にならない`() {
        assertNotEquals<Any>(TenantId.of(1), 1L)
    }

    @Test
    fun `文字列から変換できる`() {
        assertEquals(TenantId.of(12), TenantId.parse("12"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "0", "01", "-1", "+1", " 1", "1 ", "1.0", "abc", "１", "9223372036854775808", "99999999999999999999"])
    fun `不正な文字列は拒否する`(text: String) {
        assertNull(TenantId.parseOrNull(text))
        assertFailsWith<IllegalArgumentException> { TenantId.parse(text) }
    }
}
