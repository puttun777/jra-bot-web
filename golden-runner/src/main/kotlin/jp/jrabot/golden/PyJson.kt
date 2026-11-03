package jp.jrabot.golden

import tools.jackson.databind.JsonNode
import java.math.BigDecimal

/**
 * 旧 tools/golden の正準形式（Python の `json.dumps(obj, sort_keys=True, indent=2, ensure_ascii=False, allow_nan=False) + "\n"`）と
 * **バイト単位で同じ**文字列を作る（コミット済みのフィクスチャがその形式で書かれていることの検査に使う。D-258）。
 *
 * - キーはコードポイント順（Python の str の比較）。インデント 2、要素の区切りは `,` + 改行、キーと値の区切りは `": "`。空の配列・オブジェクトは `[]`・`{}`。
 * - 文字列は `"` `\` と制御文字（U+0000〜U+001F）だけをエスケープ（`\n` `\r` `\t` `\b` `\f`、他は `\u00xx`）。非 ASCII はそのまま。
 * - 整数は 10 進、浮動小数は Python の `repr`（最短表現。10 進指数が -4 以上 16 未満なら位取り、それ以外は `1e-05` 形式）。非有限は書けない。
 */
object PyJson {
    fun dumps(node: JsonNode): String = StringBuilder().also { write(node, it, 0) }.append('\n').toString()

    private fun write(
        n: JsonNode,
        sb: StringBuilder,
        level: Int,
    ) {
        when {
            n.isNull -> {
                sb.append("null")
            }

            n.isBoolean -> {
                sb.append(if (n.booleanValue()) "true" else "false")
            }

            n.isIntegralNumber -> {
                sb.append(n.bigIntegerValue().toString())
            }

            n.isFloatingPointNumber -> {
                sb.append(repr(n.doubleValue()))
            }

            n.isString -> {
                string(n.stringValue(), sb)
            }

            n.isArray -> {
                if (n.isEmpty) {
                    sb.append("[]")
                    return
                }
                sb.append("[\n")
                n.values().forEachIndexed { i, v ->
                    if (i > 0) sb.append(",\n")
                    indent(sb, level + 1)
                    write(v, sb, level + 1)
                }
                sb.append('\n')
                indent(sb, level)
                sb.append(']')
            }

            n.isObject -> {
                val keys = n.propertyNames().toList().sortedWith(::compareCodePoints)
                if (keys.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append("{\n")
                keys.forEachIndexed { i, k ->
                    if (i > 0) sb.append(",\n")
                    indent(sb, level + 1)
                    string(k, sb)
                    sb.append(": ")
                    write(n.get(k), sb, level + 1)
                }
                sb.append('\n')
                indent(sb, level)
                sb.append('}')
            }

            else -> {
                throw IllegalArgumentException("JSON にできない値: ${n.nodeType}")
            }
        }
    }

    private fun indent(
        sb: StringBuilder,
        level: Int,
    ) {
        repeat(level * 2) { sb.append(' ') }
    }

    /** Python の str の比較（コードポイント順。Java の compareTo は UTF-16 の単位で比べるので、補助文字と U+E000〜U+FFFF の順が違う）。 */
    fun compareCodePoints(
        a: String,
        b: String,
    ): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a.codePointAt(i)
            val cb = b.codePointAt(j)
            if (ca != cb) return ca.compareTo(cb)
            i += Character.charCount(ca)
            j += Character.charCount(cb)
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private fun string(
        s: String,
        sb: StringBuilder,
    ) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
    }

    /** Python の `repr(float)`。JDK 19 以降の `Double.toString` は最短の 10 進表現なので、その桁を Python の書式に並べ直す。 */
    fun repr(v: Double): String {
        require(v.isFinite()) { "非有限の数値は書けない" }
        if (v == 0.0) return if (1.0 / v < 0) "-0.0" else "0.0"
        var bd = BigDecimal(v.toString()).stripTrailingZeros()
        // Double.toString は最短が 1 桁のとき、より近い 2 桁を選ぶことがある（4.9E-324 など。Python は 5e-324）。往復できる限り短くする
        while (bd.precision() > 1) {
            val shorter = bd.round(java.math.MathContext(bd.precision() - 1, java.math.RoundingMode.HALF_EVEN)).stripTrailingZeros()
            if (shorter.toDouble() != v) break
            bd = shorter
        }
        val exp = bd.precision() - bd.scale() - 1
        if (exp in -4..15) {
            val plain = bd.toPlainString()
            return if ('.' in plain) plain else "$plain.0"
        }
        val digits = bd.unscaledValue().abs().toString()
        val mant = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
        val sign = if (bd.signum() < 0) "-" else ""
        return sign + mant + "e" + (if (exp < 0) "-" else "+") + "%02d".format(kotlin.math.abs(exp))
    }
}
