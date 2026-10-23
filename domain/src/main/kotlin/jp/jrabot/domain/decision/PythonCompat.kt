package jp.jrabot.domain.decision

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * 旧 Python 実装と**同じ数値・同じ文字列**を得るための小さな互換関数（`:domain` 内部専用）。
 *
 * 旧実装は丸めをせず IEEE754 倍精度の `float` で計算する（MIGRATION_MATRIX §7.4）。Kotlin の `Double` も同じ表現だが、次の点は演算の
 * 結果が変わり得るので、旧実装の呼び出しを素直に写す形で用意する。
 * - `min(a, b)` / `max(a, b)`: Python は **先頭の引数を返し、後ろが厳密に小さい（大きい）ときだけ置き換える**。`kotlin.math.min` は
 *   NaN を伝播し `-0.0 < 0.0` として扱うため、`-0.0` / NaN の入力で結果が異なる。
 * - `sum(list_of_float)`: CPython 3.12 以降は Neumaier の補償加算（`+` を順に足すより最後の数桁が正確）。
 * - `repr(float)` / `format(x, ".2f")` / `format(x, ".1%")`: 通知文（`reason` など）に入る文字列。
 */
internal object PythonCompat {
    /** Python の `min(a, b)`。 */
    fun min(
        a: Double,
        b: Double,
    ): Double = if (b < a) b else a

    /** Python の `max(a, b)`。 */
    fun max(
        a: Double,
        b: Double,
    ): Double = if (b > a) b else a

    /** Python の `min(max(x, lo), hi)`（旧実装のクリップの書き方）。 */
    fun clamp(
        x: Double,
        lo: Double,
        hi: Double,
    ): Double = min(max(x, lo), hi)

    /**
     * Python 3.12 以降の `sum(floats)`（Neumaier の改良 Kahan–Babuška 補償加算）。空は 0.0。
     * 旧実装を動かす Python のバージョンが 3.11 以前のときとは最後の数桁が変わり得るが、差は許容誤差（1e-12）より十分に小さい。
     */
    fun sum(values: List<Double>): Double {
        var result = 0.0
        var c = 0.0
        for (x in values) {
            val t = result + x
            c += if (abs(result) >= abs(x)) (result - t) + x else (x - t) + result
            result = t
        }
        if (c != 0.0 && c.isFinite()) result += c
        return result
    }

    /**
     * Python の `repr(float)`（最短の往復表現。指数表記になる境界は Python に合わせる）。
     * 限界: 非正規化数（約 1e-308 未満）は Java の `Double.toString` が最短より 1 桁多い表現を選ぶため Python と異なり得る（オッズでは起き得ない範囲）。
     */
    fun repr(value: Double): String {
        if (value.isNaN()) return "nan"
        if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
        if (value == 0.0) return if (1.0 / value < 0) "-0.0" else "0.0"
        // JDK 19 以降の Double.toString は最短の往復表現の桁を返す（指数の書き方だけが Python と違う）
        val bd = BigDecimal(value.toString()).stripTrailingZeros()
        val negative = bd.signum() < 0
        val digits = bd.unscaledValue().abs().toString()
        val decpt = digits.length - bd.scale() // 0.d1d2... × 10^decpt
        val body =
            if (decpt in -3..16) {
                when {
                    decpt <= 0 -> "0." + "0".repeat(-decpt) + digits
                    decpt >= digits.length -> digits + "0".repeat(decpt - digits.length) + ".0"
                    else -> digits.substring(0, decpt) + "." + digits.substring(decpt)
                }
            } else {
                val exp = decpt - 1
                val mantissa = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
                val sign = if (exp < 0) "-" else "+"
                mantissa + "e" + sign + abs(exp).toString().padStart(2, '0')
            }
        return if (negative) "-$body" else body
    }

    /** Python の `format(x, ".{digits}f")`。2 進の正確な値に対する偶数丸め（Java の `%.2f` とは半端の扱いが異なる）。 */
    fun fixed(
        value: Double,
        digits: Int,
    ): String {
        if (value.isNaN()) return "nan"
        if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
        val s = BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toPlainString()
        val negative = value < 0.0 || (value == 0.0 && 1.0 / value < 0)
        return if (negative && !s.startsWith("-")) "-$s" else s
    }

    /** Python の `format(x, ".1%")`（`x * 100` を浮動小数で計算してから固定小数点に整形する）。 */
    fun percent(
        value: Double,
        digits: Int,
    ): String = fixed(value * 100.0, digits) + "%"
}
