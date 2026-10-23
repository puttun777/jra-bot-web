package jp.jrabot.domain.value

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyTest {
    @Test
    fun `0 円以上の整数を円として生成できる`() {
        assertEquals(0L, Money.ofYen(0).yen)
        assertEquals(Long.MAX_VALUE, Money.ofYen(Long.MAX_VALUE).yen)
        assertEquals(Money.ZERO, Money.ofYen(0))
        assertTrue(Money.ZERO.isZero)
    }

    @ParameterizedTest
    @ValueSource(longs = [-1, -100, Long.MIN_VALUE])
    fun `負の金額は拒否する`(yen: Long) {
        assertNull(Money.ofYenOrNull(yen))
        assertFailsWith<IllegalArgumentException> { Money.ofYen(yen) }
    }

    @Test
    fun `加算`() {
        assertEquals(Money.ofYen(1500), Money.ofYen(1000) + Money.ofYen(500))
        assertEquals(Money.ofYen(Long.MAX_VALUE), Money.ofYen(Long.MAX_VALUE - 1) + Money.ofYen(1))
    }

    @Test
    fun `加算の桁あふれは例外になり 黙って折り返さない`() {
        assertFailsWith<ArithmeticException> { Money.ofYen(Long.MAX_VALUE) + Money.ofYen(1) }
    }

    @Test
    fun `減算は結果が負になるなら例外 損益は符号付きで得る`() {
        assertEquals(Money.ofYen(300), Money.ofYen(1000) - Money.ofYen(700))
        assertEquals(Money.ZERO, Money.ofYen(5) - Money.ofYen(5))
        assertFailsWith<ArithmeticException> { Money.ofYen(1) - Money.ofYen(2) }
        assertEquals(-100L, Money.ofYen(900).differenceYen(Money.ofYen(1000)))
        assertEquals(Long.MAX_VALUE, Money.ofYen(Long.MAX_VALUE).differenceYen(Money.ZERO))
        assertEquals(-Long.MAX_VALUE, Money.ZERO.differenceYen(Money.ofYen(Long.MAX_VALUE)))
    }

    @Test
    fun `整数倍と桁あふれ`() {
        assertEquals(Money.ofYen(500), Money.ofYen(100) * 5)
        assertEquals(Money.ZERO, Money.ofYen(Long.MAX_VALUE) * 0)
        assertEquals(Money.ofYen(Long.MAX_VALUE), Money.ofYen(Long.MAX_VALUE) * 1)
        assertFailsWith<ArithmeticException> { Money.ofYen(Long.MAX_VALUE) * 2 }
        assertFailsWith<ArithmeticException> { Money.ofYen(Long.MAX_VALUE / 2 + 1) * 2 }
        assertFailsWith<IllegalArgumentException> { Money.ofYen(100) * -1 }
    }

    @Test
    fun `合計と桁あふれ`() {
        assertEquals(Money.ofYen(600), Money.sum(listOf(Money.ofYen(100), Money.ofYen(200), Money.ofYen(300))))
        assertEquals(Money.ZERO, Money.sum(emptyList()))
        assertFailsWith<ArithmeticException> { Money.sum(listOf(Money.ofYen(Long.MAX_VALUE), Money.ofYen(1))) }
    }

    @Test
    fun `等価性 hashCode 順序`() {
        assertEquals(Money.ofYen(100), Money.ofYen(100))
        assertEquals(Money.ofYen(100).hashCode(), Money.ofYen(100).hashCode())
        assertNotEquals(Money.ofYen(100), Money.ofYen(101))
        assertTrue(Money.ofYen(99) < Money.ofYen(100))
    }
}
