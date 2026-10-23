package jp.jrabot.dbmigration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonTest {
    @Test
    fun `契約の info 形式`() {
        assertEquals(
            """{"current":"2","pending":["3"],"failed":[],"latest":"3"}""",
            Cli.infoJson(InfoReport(current = "2", latest = "3", pending = listOf("3"), failed = emptyList())),
        )
    }

    @Test
    fun `DB が空のとき current は null`() {
        assertEquals(
            """{"current":null,"pending":["1","2","R__grants"],"failed":[],"latest":"2"}""",
            Cli.infoJson(InfoReport(current = null, latest = "2", pending = listOf("1", "2", "R__grants"), failed = emptyList())),
        )
    }

    @Test
    fun `文字列の特殊文字をエスケープする`() {
        assertEquals("\"a\\\"b\\\\c\\n\\r\\t\\u0001\\u2028\"", Json.string("a\"b\\c\n\r\t\u0001 "))
        assertEquals("\"日本語\"", Json.string("日本語"))
    }

    @Test
    fun `改行を含む値でも 1 行`() {
        assertEquals(false, Json.obj("a" to "x\ny", "b" to listOf("1\n2")).contains('\n'))
    }

    @Test
    fun `扱えない型は拒否する`() {
        assertFailsWith<IllegalArgumentException> { Json.value(1.5) }
    }
}
