package jp.jrabot.domain.decision

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 期待値はすべて CPython 3.13（`sum` は 3.12 以降の補償加算）で実際に評価した結果。 */
class PythonCompatTest {
    @Test
    fun `sum は Python 3_12 以降の補償加算で、単純な左から右の加算とは最後の桁が違う`() {
        assertEquals(1.0, PythonCompat.sum(List(10) { 0.1 }))
        assertEquals(0.9999999999999999, List(10) { 0.1 }.fold(0.0) { a, b -> a + b }) // 単純加算はこうなる（比較のための確認）
        assertEquals(0.6, PythonCompat.sum(listOf(0.1, 0.2, 0.3)))
        assertEquals(1.0, PythonCompat.sum(listOf(1e100, 1.0, -1e100)))
        assertEquals(0.0, PythonCompat.sum(emptyList()))
    }

    @Test
    fun `min と max は先頭の引数を返し、NaN と符号付きゼロの扱いが Python と同じ`() {
        assertEquals(0.0, PythonCompat.min(0.0, -0.0))
        assertTrue(1 / PythonCompat.min(0.0, -0.0) > 0) // 0.0 のまま（-0.0 に置き換えない）
        assertTrue(1 / PythonCompat.max(-0.0, 0.0) < 0) // -0.0 のまま
        assertTrue(PythonCompat.min(Double.NaN, 1.0).isNaN())
        assertEquals(1.0, PythonCompat.min(1.0, Double.NaN))
        assertTrue(PythonCompat.max(Double.NaN, 1.0).isNaN())
        assertEquals(1.0, PythonCompat.max(1.0, Double.NaN))
        assertEquals(0.5, PythonCompat.clamp(0.5, 0.0, 1.0))
        assertEquals(1.0, PythonCompat.clamp(7.0, 0.0, 1.0))
        assertEquals(0.0, PythonCompat.clamp(-7.0, 0.0, 1.0))
    }

    @ParameterizedTest
    @CsvSource(
        "0.0, 0.0",
        "1.0, 1.0",
        "3.0, 3.0",
        "0.1, 0.1",
        "1.0E15, 1000000000000000.0",
        "1.0E16, 1e+16",
        "1.5E16, 1.5e+16",
        "1.2345678901234568E17, 1.2345678901234568e+17",
        "1.0E-4, 0.0001",
        "1.0E-5, 1e-05",
        "0.00012345, 0.00012345",
        "2.5E-7, 2.5e-07",
        "100.0, 100.0",
        "12345.678, 12345.678",
        "-2.5, -2.5",
        "1.7976931348623157E308, 1.7976931348623157e+308",
        "0.30000000000000004, 0.30000000000000004",
        "1.0E22, 1e+22",
        "1.0E21, 1e+21",
    )
    fun `repr は Python の repr(float) と同じ文字列`(
        value: Double,
        expected: String,
    ) {
        assertEquals(expected, PythonCompat.repr(value))
    }

    @Test
    fun `repr の特殊値と1の3分の1`() {
        assertEquals("-0.0", PythonCompat.repr(-0.0))
        assertEquals("0.3333333333333333", PythonCompat.repr(1.0 / 3))
        assertEquals("nan", PythonCompat.repr(Double.NaN))
        assertEquals("inf", PythonCompat.repr(Double.POSITIVE_INFINITY))
        assertEquals("-inf", PythonCompat.repr(Double.NEGATIVE_INFINITY))
    }

    @ParameterizedTest
    @CsvSource(
        // 2 進数の正確な値に対する偶数丸め（Java の %.2f は 0.125 を 0.13 にする）
        "0.125, 2, 0.12",
        "0.375, 2, 0.38",
        "2.675, 2, 2.67",
        "0.5, 0, 0",
        "1.5, 0, 2",
        "2.5, 0, 2",
        "-0.04, 1, -0.0",
        "0.05, 1, 0.1",
        "0.15, 1, 0.1",
        "0.25, 1, 0.2",
        "0.35, 1, 0.3",
        "9.995, 2, 9.99",
        "1.0E-7, 2, 0.00",
        "123456789.125, 2, 123456789.12",
        "0.0, 2, 0.00",
        "-1.005, 2, -1.00",
    )
    fun `fixed は Python の format の f と同じ文字列`(
        value: Double,
        digits: Int,
        expected: String,
    ) {
        assertEquals(expected, PythonCompat.fixed(value, digits))
    }

    @Test
    fun `fixed は負のゼロを Python と同じく符号付きで出す`() {
        assertEquals("-0.0", PythonCompat.fixed(-0.0, 1))
    }

    @ParameterizedTest
    @CsvSource(
        "0.18165696849645063, 18.2%",
        "1.0, 100.0%",
        "0.0, 0.0%",
        "0.00049, 0.0%",
        "0.0005, 0.1%",
        "0.12345, 12.3%",
        "2.0, 200.0%",
        "-0.5, -50.0%",
        "0.0125, 1.2%",
        "0.07, 7.0%",
        "1.9699999999999998, 197.0%",
        "3.32, 332.0%",
    )
    fun `percent は Python の format の 1 桁パーセントと同じ文字列`(
        value: Double,
        expected: String,
    ) {
        assertEquals(expected, PythonCompat.percent(value, 1))
    }
}
