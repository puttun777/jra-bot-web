package jp.jrabot.domain.ml.json

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 厳密な JSON パーサ（RFC 8259 の文法のうち、モデル成果物に必要な範囲。[JsonValue] の注意を参照）。
 * 入力は UTF-8 のバイト列で、BOM と不正なバイト列は拒否する。ネストは [MAX_DEPTH]、全体の大きさは [maxBytes] で制限する（展開爆弾・スタック枯渇の防止）。
 */
object StrictJson {
    const val MAX_DEPTH = 32

    fun parse(
        bytes: ByteArray,
        maxBytes: Int = 4 * 1024 * 1024,
    ): JsonValue {
        if (bytes.size > maxBytes) throw JsonFormatException("JSON が大きすぎる (${bytes.size} > $maxBytes)")
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            throw JsonFormatException("BOM は許可しない")
        }
        val text =
            try {
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (e: CharacterCodingException) {
                throw JsonFormatException("UTF-8 として不正")
            }
        return parse(text)
    }

    fun parse(text: String): JsonValue {
        val p = Parser(text)
        p.skipWs()
        val v = p.value(0)
        p.skipWs()
        if (!p.atEnd()) throw JsonFormatException("末尾に余分な内容", p.pos)
        return v
    }

    private class Parser(
        private val s: String,
    ) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\t' || s[pos] == '\n' || s[pos] == '\r')) pos++
        }

        private fun fail(msg: String): Nothing = throw JsonFormatException(msg, pos)

        fun value(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) fail("ネストが深すぎる")
            if (atEnd()) fail("入力が途中で終わった")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> JsonString(str())
                't' -> literal("true", JsonBool(true))
                'f' -> literal("false", JsonBool(false))
                'n' -> literal("null", JsonNull)
                else -> if (c == '-' || c in '0'..'9') number() else fail("予期しない文字")
            }
        }

        private fun literal(
            word: String,
            v: JsonValue,
        ): JsonValue {
            if (!s.startsWith(word, pos)) fail("不正なリテラル")
            pos += word.length
            return v
        }

        private fun obj(depth: Int): JsonObject {
            pos++ // {
            val m = LinkedHashMap<String, JsonValue>()
            skipWs()
            if (peek() == '}') {
                pos++
                return JsonObject(m)
            }
            while (true) {
                skipWs()
                if (peek() != '"') fail("キーは文字列")
                val k = str()
                skipWs()
                if (peek() != ':') fail("':' が必要")
                pos++
                skipWs()
                val v = value(depth + 1)
                if (m.put(k, v) != null) fail("キーが重複")
                skipWs()
                when (peek()) {
                    ',' -> {
                        pos++
                    }

                    '}' -> {
                        pos++
                        return JsonObject(m)
                    }

                    else -> {
                        fail("',' または '}' が必要")
                    }
                }
            }
        }

        private fun arr(depth: Int): JsonArray {
            pos++ // [
            val l = ArrayList<JsonValue>()
            skipWs()
            if (peek() == ']') {
                pos++
                return JsonArray(l)
            }
            while (true) {
                skipWs()
                l += value(depth + 1)
                skipWs()
                when (peek()) {
                    ',' -> {
                        pos++
                    }

                    ']' -> {
                        pos++
                        return JsonArray(l)
                    }

                    else -> {
                        fail("',' または ']' が必要")
                    }
                }
            }
        }

        private fun peek(): Char = if (pos < s.length) s[pos] else fail("入力が途中で終わった")

        private fun str(): String {
            pos++ // 開き引用符
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("文字列が閉じていない")
                val c = s[pos++]
                when {
                    c == '"' -> {
                        checkNoLoneSurrogate(sb)
                        return sb.toString()
                    }

                    c == '\\' -> {
                        if (atEnd()) fail("不正なエスケープ")
                        when (val e = s[pos++]) {
                            '"' -> {
                                sb.append('"')
                            }

                            '\\' -> {
                                sb.append('\\')
                            }

                            '/' -> {
                                sb.append('/')
                            }

                            'b' -> {
                                sb.append('\b')
                            }

                            'f' -> {
                                sb.append('\u000C')
                            }

                            'n' -> {
                                sb.append('\n')
                            }

                            'r' -> {
                                sb.append('\r')
                            }

                            't' -> {
                                sb.append('\t')
                            }

                            'u' -> {
                                if (pos + 4 > s.length) fail("不正な \\u エスケープ")
                                val hex = s.substring(pos, pos + 4)
                                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail("不正な \\u エスケープ")
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }

                            else -> {
                                fail("不正なエスケープ '\\$e'")
                            }
                        }
                    }

                    c < ' ' -> {
                        fail("文字列中の制御文字")
                    }

                    else -> {
                        sb.append(c)
                    }
                }
            }
        }

        private fun checkNoLoneSurrogate(sb: CharSequence) {
            var i = 0
            while (i < sb.length) {
                val ch = sb[i]
                if (Character.isHighSurrogate(ch)) {
                    if (i + 1 < sb.length && Character.isLowSurrogate(sb[i + 1])) i++ else fail("孤立サロゲートは不可")
                } else if (Character.isLowSurrogate(ch)) {
                    fail("孤立サロゲートは不可")
                }
                i++
            }
        }

        private fun number(): JsonValue {
            val start = pos
            if (s[pos] == '-') pos++
            if (atEnd()) fail("不正な数値")
            if (s[pos] == '0') {
                pos++
            } else if (s[pos] in '1'..'9') {
                while (!atEnd() && s[pos] in '0'..'9') pos++
            } else {
                fail("不正な数値")
            }
            var isFloat = false
            if (!atEnd() && s[pos] == '.') {
                isFloat = true
                pos++
                val d = pos
                while (!atEnd() && s[pos] in '0'..'9') pos++
                if (pos == d) fail("小数点の後に数字が必要")
            }
            if (!atEnd() && (s[pos] == 'e' || s[pos] == 'E')) {
                isFloat = true
                pos++
                if (!atEnd() && (s[pos] == '+' || s[pos] == '-')) pos++
                val d = pos
                while (!atEnd() && s[pos] in '0'..'9') pos++
                if (pos == d) fail("指数に数字が必要")
            }
            if (!atEnd() && (s[pos].isLetterOrDigit() || s[pos] == '.')) fail("数値の直後に不正な文字")
            val token = s.substring(start, pos)
            return if (isFloat) {
                val d = token.toDouble()
                if (!d.isFinite()) throw JsonFormatException("数値が範囲外 (Infinity)", start)
                JsonFloat(d)
            } else {
                val big = BigInteger(token)
                if (big.bitLength() > 63) throw JsonFormatException("整数が Long の範囲外", start)
                JsonInt(big.toLong())
            }
        }
    }
}
