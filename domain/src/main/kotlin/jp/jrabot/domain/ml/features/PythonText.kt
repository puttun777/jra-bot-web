package jp.jrabot.domain.ml.features

import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * 旧 Python（`ml/features.py`）の文字列まわりの挙動を、旧互換モードで再現するための小さな関数（`:domain` 内部専用）。
 * 新方式（point-in-time）の入力検証には使わない（そちらは型で受け取る）。
 */
internal object PythonText {
    /** Python の `str.isspace()` が真になる文字（`str.strip()` が削る文字）。Kotlin の `trim()` とは `\u0085` などが違う。 */
    private fun isPySpace(c: Char): Boolean =
        when (c) {
            '\t', '\n', '\u000B', '\u000C', '\r', ' ', '\u001C', '\u001D', '\u001E', '\u001F', '\u0085', ' ',
            ' ', ' ', ' ', ' ', ' ', '　',
            -> true

            else -> c in ' '..' '
        }

    /** Python の `s.strip()`。 */
    fun strip(s: String): String {
        var start = 0
        var end = s.length
        while (start < end && isPySpace(s[start])) start++
        while (end > start && isPySpace(s[end - 1])) end--
        return s.substring(start, end)
    }

    /**
     * `s.encode("utf-8", errors="ignore")`: 孤立サロゲートを**捨てて**符号化する（Kotlin の `toByteArray` は `?` に置換するので別物）。
     */
    fun utf8Ignore(s: String): ByteArray {
        val out = ByteArrayOutputStream(s.length * 3)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val cp: Int
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                    cp = Character.toCodePoint(c, s[i + 1])
                    i += 2
                } else {
                    i++
                    continue
                }
            } else if (Character.isLowSurrogate(c)) {
                i++
                continue
            } else {
                cp = c.code
                i++
            }
            when {
                cp < 0x80 -> {
                    out.write(cp)
                }

                cp < 0x800 -> {
                    out.write(0xC0 or (cp shr 6))
                    out.write(0x80 or (cp and 0x3F))
                }

                cp < 0x10000 -> {
                    out.write(0xE0 or (cp shr 12))
                    out.write(0x80 or ((cp shr 6) and 0x3F))
                    out.write(0x80 or (cp and 0x3F))
                }

                else -> {
                    out.write(0xF0 or (cp shr 18))
                    out.write(0x80 or ((cp shr 12) and 0x3F))
                    out.write(0x80 or ((cp shr 6) and 0x3F))
                    out.write(0x80 or (cp and 0x3F))
                }
            }
        }
        return out.toByteArray()
    }

    private val INT_TEXT = Regex("[+-]?[0-9]+(_[0-9]+)*")

    /**
     * 旧 `_safe_int(text, 0)` を `float(...)` した値。Python の `int(str)` は前後の空白・符号・桁区切りの `_`（数字の間に 1 つ）を受け付ける。
     * 受け付けない文字列（空・小数・文字）は 0。**ASCII の数字だけ**を扱う（全角数字などの Unicode 数字は旧実装では整数になるが、コード類には現れない）。
     */
    fun safeIntAsDouble(text: String?): Double {
        if (text == null) return 0.0
        val t = strip(text)
        if (!INT_TEXT.matches(t)) return 0.0
        return BigInteger(t.replace("_", "").removePrefix("+")).toDouble()
    }
}
