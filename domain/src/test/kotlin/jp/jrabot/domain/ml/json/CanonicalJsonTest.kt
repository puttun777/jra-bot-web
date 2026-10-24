package jp.jrabot.domain.ml.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CanonicalJsonTest {
    private fun canon(text: String) = CanonicalJson.encode(StrictJson.parse(text))

    @Test
    fun `キーはコードポイント順で空白は無い`() {
        assertEquals("""{"a":1,"b":[1,2,{"c":null}],"z":true}""", canon("""{ "z": true, "b": [1, 2, {"c": null}], "a": 1 }"""))
    }

    @Test
    fun `キーの順序は UTF-16 ではなくコードポイントで決まる`() {
        // U+FF5E（全角チルダ。UTF-16 で 0xFF5E）と U+1F40E（絵文字。UTF-16 は 0xD83D 0xDC0E）。コードポイント順は FF5E < 1F40E
        val obj = JsonObject.of("🐎" to JsonInt(1), "～" to JsonInt(2))
        assertEquals("{\"～\":2,\"🐎\":1}", CanonicalJson.encode(obj))
    }

    @Test
    fun `整数と浮動小数は区別され Python の repr と同じ文字列になる`() {
        assertEquals(
            "[1,1.0,0.0001,1e-05,1e+16,1e+22,-0.0,123456789012345.0,0.1,100.0,1.5e-07]",
            canon("[1,1.0,1e-4,1e-5,1e16,1e22,-0.0,123456789012345.0,0.1,1e2,1.5e-7]"),
        )
        assertEquals("[0]", canon("[-0]"))
        assertTrue(CanonicalJson.encode(JsonInt(1)) != CanonicalJson.encode(JsonFloat(1.0)))
    }

    @Test
    fun `文字列のエスケープは Python の ensure_ascii=False と同じ`() {
        assertEquals(
            "\"a\\\"b\\\\c\\n\\r\\t\\b\\f\\u0001\\u001f/\u007f日本語🐎\"",
            canon("\"a\\\"b\\\\c\\n\\r\\t\\b\\f\\u0001\\u001F\\/\u007f日本語\\ud83d\\udc0e\""),
        )
    }

    @Test
    fun `sha256 は正準形から決まり、キーの順序や空白に依らない`() {
        val a = StrictJson.parse("""{"x": 1, "y": [1.5, "é"]}""")
        val b = StrictJson.parse("{\"y\":[1.5,\"\u00e9\"],\"x\":1}")
        assertEquals(CanonicalJson.sha256Hex(a), CanonicalJson.sha256Hex(b))
        // Python: hashlib.sha256(json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()
        assertEquals("698505d26707912b0a7e424c7b8810aeed20c48afbcaaa31c887511126418cf8", CanonicalJson.sha256Hex(a))
    }
}
