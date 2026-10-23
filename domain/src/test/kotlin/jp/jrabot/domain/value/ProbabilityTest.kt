package jp.jrabot.domain.value

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProbabilityTest {
    @ParameterizedTest
    @ValueSource(doubles = [0.0, 1.0, 0.5, 1e-300, Double.MIN_VALUE, 0.9999999999999999])
    fun `0 以上 1 以下の有限値は生成できる（両端を含む）`(value: Double) {
        assertEquals(value, Probability.of(value).value)
    }

    @ParameterizedTest
    @ValueSource(
        doubles = [-1e-300, -Double.MIN_VALUE, 1.0000000000000002, 1.5, 100.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY],
    )
    fun `範囲外 NaN Infinity は拒否し 黙って丸めない`(value: Double) {
        assertNull(Probability.ofOrNull(value))
        assertFailsWith<IllegalArgumentException> { Probability.of(value) }
    }

    @Test
    fun `負のゼロは 0 に正規化され 等価で hashCode も一致する`() {
        val negativeZero = Probability.of(-0.0)
        assertEquals(Probability.ZERO, negativeZero)
        assertEquals(Probability.ZERO.hashCode(), negativeZero.hashCode())
        assertEquals(0, negativeZero.compareTo(Probability.ZERO))
        assertTrue(1.0 / negativeZero.value > 0, "符号付きゼロが残っていない")
    }

    @Test
    fun `境界の判定`() {
        assertTrue(Probability.ZERO.isZero)
        assertFalse(Probability.ZERO.isOne)
        assertTrue(Probability.ONE.isOne)
        assertFalse(Probability.of(0.5).isZero || Probability.of(0.5).isOne)
    }

    @Test
    fun `余事象と積は常に有効な確率になる`() {
        assertEquals(Probability.ONE, Probability.ZERO.complement())
        assertEquals(Probability.ZERO, Probability.ONE.complement())
        assertEquals(0.75, Probability.of(0.25).complement().value)
        assertEquals(0.125, (Probability.of(0.5) * Probability.of(0.25)).value)
        assertEquals(Probability.ZERO, Probability.ZERO * Probability.ONE)
        val awkward = Probability.of(0.9999999999999999)
        assertTrue((awkward * awkward).value <= 1.0)
    }

    @Test
    fun `順序と等価性`() {
        assertTrue(Probability.of(0.2) < Probability.of(0.3))
        assertEquals(Probability.of(0.3), Probability.of(0.3))
        assertNotEquals(Probability.of(0.3), Probability.of(0.30000000000000004))
    }

    @Test
    fun `normalize は合計 1 の確率にする`() {
        val result = Probability.normalize(listOf(1.0, 1.0, 2.0))
        assertEquals(listOf(0.25, 0.25, 0.5), result.map { it.value })
        assertTrue(abs(Probability.normalize(listOf(0.1, 0.2, 0.3, 0.4, 0.7)).sumOf { it.value } - 1.0) < 1e-12)
        assertEquals(listOf(Probability.ONE), Probability.normalize(listOf(1e-300)))
        assertEquals(listOf(Probability.ZERO, Probability.ONE), Probability.normalize(listOf(0.0, 5.0)))
    }

    @Test
    fun `normalize は不正な入力を拒否する`() {
        val bad =
            listOf(
                emptyList(),
                listOf(0.0),
                listOf(0.0, 0.0),
                listOf(1.0, -0.5),
                listOf(1.0, Double.NaN),
                listOf(1.0, Double.POSITIVE_INFINITY),
                listOf(Double.MAX_VALUE, Double.MAX_VALUE), // 合計がオーバーフロー
            )
        for (weights in bad) assertFailsWith<IllegalArgumentException>("$weights") { Probability.normalize(weights) }
    }
}
