package jp.jrabot.domain.ml.json

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class StrictJsonTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "   ", "{", "[1,]", "{\"a\":1,}", "{\"a\":1 \"b\":2}", "{'a':1}", "[01]", "[1.]", "[.5]", "[+1]", "[1e]", "[--1]",
            "NaN", "[NaN]", "[Infinity]", "[-Infinity]", "[1e400]", "[9223372036854775808]", "[-9223372036854775809]",
            "{\"a\":1,\"a\":2}", "[\"\\ud800\"]", "[\"\\udc00x\"]", "[\"a\nb\"]", "[\"\\x41\"]", "[\"\\u12G4\"]", "[1] x", "// c\n[1]", "[true false]", "tru",
        ],
    )
    fun `不正・曖昧な JSON は拒否する`(text: String) {
        assertFailsWith<JsonFormatException> { StrictJson.parse(text) }
    }

    @Test
    fun `BOM と不正な UTF-8 は拒否する`() {
        assertFailsWith<JsonFormatException> { StrictJson.parse(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), '1'.code.toByte())) }
        assertFailsWith<JsonFormatException> { StrictJson.parse(byteArrayOf('"'.code.toByte(), 0xC3.toByte(), '"'.code.toByte())) }
    }

    @Test
    fun `深すぎるネストと大きすぎる入力は拒否する`() {
        assertFailsWith<JsonFormatException> { StrictJson.parse("[".repeat(StrictJson.MAX_DEPTH + 2) + "]".repeat(StrictJson.MAX_DEPTH + 2)) }
        assertFailsWith<JsonFormatException> { StrictJson.parse(ByteArray(11) { ' '.code.toByte() }, maxBytes = 10) }
    }

    @Test
    fun `整数と浮動小数を区別して読む`() {
        assertEquals(JsonInt(0), StrictJson.parse("-0"))
        assertEquals(JsonInt(Long.MAX_VALUE), StrictJson.parse("9223372036854775807"))
        assertIs<JsonFloat>(StrictJson.parse("1.0"))
        assertIs<JsonFloat>(StrictJson.parse("1e0"))
        assertEquals(JsonFloat(100.0), StrictJson.parse("1E2"))
    }

    @Test
    fun `文字列と構造を読む`() {
        val v = StrictJson.parse("{\"k\":[true,false,null,\"\\u3042\\ud83d\\udc0e\\n\"],\"e\":{},\"a\":[]}") as JsonObject
        assertEquals(JsonString("あ\uD83D\uDC0E\n"), ((v["k"] as JsonArray).items[3]))
        assertEquals(setOf("k", "e", "a"), v.fields.keys)
    }
}
