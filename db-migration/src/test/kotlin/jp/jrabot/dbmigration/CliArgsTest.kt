package jp.jrabot.dbmigration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CliArgsTest {
    @Test
    fun `3 つのモードを受け付ける`() {
        assertEquals(ParsedArgs.Ok(Mode.INFO), CliArgs.parse(listOf("--mode=info")))
        assertEquals(ParsedArgs.Ok(Mode.VALIDATE), CliArgs.parse(listOf("--mode=validate")))
        assertEquals(ParsedArgs.Ok(Mode.MIGRATE), CliArgs.parse(listOf("--mode=migrate")))
    }

    @Test
    fun `モード未指定は不正で既定値は無い`() {
        val result = assertIs<ParsedArgs.Invalid>(CliArgs.parse(emptyList()))
        assertTrue("必須" in result.message)
    }

    @Test
    fun `不正な引数はすべて不正`() {
        val invalid =
            listOf(
                listOf("--mode="),
                listOf("--mode=INFO"),
                listOf("--mode=repair"),
                listOf("--mode=clean"),
                listOf("--mode", "info"),
                listOf("-mode=info"),
                listOf("info"),
                listOf("--mode=info", "--mode=migrate"),
                listOf("--mode=info", "--force"),
                listOf("--force", "--mode=info"),
                listOf("--mode=info "),
                listOf(""),
            )
        for (args in invalid) {
            assertIs<ParsedArgs.Invalid>(CliArgs.parse(args), "不正のはず: $args")
        }
    }

    @Test
    fun `エラーメッセージに制御文字や長すぎる入力をそのまま出さない`() {
        val result = assertIs<ParsedArgs.Invalid>(CliArgs.parse(listOf("--bogus\u001b[31m" + "x".repeat(500))))
        assertTrue(result.message.length < 120, result.message)
        assertTrue(result.message.none { it.isISOControl() })
    }
}
