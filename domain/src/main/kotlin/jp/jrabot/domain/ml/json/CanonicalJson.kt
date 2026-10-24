package jp.jrabot.domain.ml.json

import jp.jrabot.domain.decision.PythonCompat
import java.security.MessageDigest

/**
 * 正準 JSON（canonical JSON）の定義。モデル JSON の `sha256`、`feature_schema` のハッシュ、署名の対象バイト列はすべてこの形式から作る。
 * Python 側は次の呼び出しと**バイト単位で同じ**になる（`tools/ml/canonical.py`、CI で相互検証）:
 *
 * ```python
 * json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False).encode("utf-8")
 * ```
 *
 * 規則:
 * 1. 空白なし。UTF-8（BOM なし）。
 * 2. オブジェクトのキーは **Unicode のコードポイント順**（UTF-16 のコード単位順ではない。Python の `sort_keys` と同じ）。
 * 3. 文字列は `"` `\` と制御文字（U+0000〜U+001F）だけをエスケープする: `\b \f \n \r \t` は短縮形、それ以外の制御文字は `\u00xx`（小文字 16 進 4 桁）。
 *    `/`・U+007F・非 ASCII はそのまま出力する。
 * 4. 整数（[JsonInt]）は 10 進、先頭ゼロなし、`-0` は `0`。
 * 5. 浮動小数（[JsonFloat]）は Python の `repr(float)`（最短の往復表現。`1.0`、`0.0001`、`1e-05`、`1e+16`、`-0.0`）。**整数と浮動小数は別物**:
 *    `1` と `1.0` は異なる正準形（ハッシュも異なる）なので、浮動小数のフィールドは常に小数点または指数付きで書く。
 * 6. NaN / Infinity は表現できない（型で作れない）。
 */
object CanonicalJson {
    fun encode(value: JsonValue): String = StringBuilder().also { write(value, it) }.toString()

    fun encodeBytes(value: JsonValue): ByteArray = encode(value).toByteArray(Charsets.UTF_8)

    fun sha256Hex(value: JsonValue): String = hex(MessageDigest.getInstance("SHA-256").digest(encodeBytes(value)))

    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"

    private fun write(
        v: JsonValue,
        out: StringBuilder,
    ) {
        when (v) {
            JsonNull -> {
                out.append("null")
            }

            is JsonBool -> {
                out.append(if (v.value) "true" else "false")
            }

            is JsonInt -> {
                out.append(v.value.toString())
            }

            is JsonFloat -> {
                out.append(PythonCompat.repr(v.value))
            }

            is JsonString -> {
                writeString(v.value, out)
            }

            is JsonArray -> {
                out.append('[')
                v.items.forEachIndexed { i, x ->
                    if (i > 0) out.append(',')
                    write(x, out)
                }
                out.append(']')
            }

            is JsonObject -> {
                out.append('{')
                v.fields.entries.sortedWith { a, b -> compareCodePoints(a.key, b.key) }.forEachIndexed { i, (k, x) ->
                    if (i > 0) out.append(',')
                    writeString(k, out)
                    out.append(':')
                    write(x, out)
                }
                out.append('}')
            }
        }
    }

    private fun writeString(
        s: String,
        out: StringBuilder,
    ) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    /** Unicode のコードポイント順（サロゲートペアは 1 文字として比べる）。 */
    internal fun compareCodePoints(
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
}
