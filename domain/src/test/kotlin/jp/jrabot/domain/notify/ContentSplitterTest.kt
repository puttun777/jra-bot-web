package jp.jrabot.domain.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContentSplitterTest {
    private fun assertLossless(
        text: String,
        chunks: List<String>,
        limit: Int,
        unit: LengthUnit = LengthUnit.UTF16_UNITS,
    ) {
        assertEquals(text, chunks.joinToString(""), "連結すると元の文字列に戻る（欠落・重複なし）")
        for (c in chunks) assertTrue(ContentSplitter.length(c, unit) <= limit, "各断片は $limit 以下: ${ContentSplitter.length(c, unit)}")
        for (c in chunks) assertTrue(!c.isEmpty() || chunks.size == 1, "空の断片を作らない")
        for (c in chunks) {
            assertTrue(!(c.isNotEmpty() && Character.isLowSurrogate(c.first())), "サロゲートペアの途中で始まらない")
            assertTrue(!(c.isNotEmpty() && Character.isHighSurrogate(c.last())), "サロゲートペアの途中で終わらない")
        }
    }

    @Test
    fun `空文字と 1 文字は 1 件`() {
        assertEquals(listOf(""), ContentSplitter.split(""))
        assertEquals(listOf("a"), ContentSplitter.split("a"))
        assertEquals(listOf("あ"), ContentSplitter.split("あ"))
    }

    @Test
    fun `1900 文字ちょうどは 1 件で 1901 文字は 2 件`() {
        val s1900 = "a".repeat(1900)
        assertEquals(listOf(s1900), ContentSplitter.split(s1900))
        val s1901 = "a".repeat(1901)
        val chunks = ContentSplitter.split(s1901)
        assertEquals(listOf(1900, 1), chunks.map { it.length })
        assertLossless(s1901, chunks, 1900)
    }

    @Test
    fun `2000 文字と 2001 文字は Discord の上限の内側に分ける`() {
        for (n in listOf(2000, 2001, 3800, 3801, 5700)) {
            val s = "あ".repeat(n)
            val chunks = ContentSplitter.split(s)
            assertLossless(s, chunks, 1900)
            assertTrue(chunks.all { it.length <= ContentSplitter.MESSAGE_LIMIT })
            assertEquals((n + 1899) / 1900, chunks.size)
        }
    }

    @Test
    fun `改行を含む長文は行の境界で分け、行を割らない`() {
        val line = "x".repeat(99) + "\n" // 100 文字
        val s = line.repeat(50) // 5000 文字
        val chunks = ContentSplitter.split(s)
        assertLossless(s, chunks, 1900)
        assertTrue(chunks.all { it.endsWith("\n") }, "各断片は行末で終わる")
        assertEquals(19 * 100, chunks.first().length)
    }

    @Test
    fun `改行のない長文は強制分割し最後の断片は次と結合しない`() {
        val s = "a".repeat(4000) + "\n" + "b"
        val chunks = ContentSplitter.split(s)
        assertLossless(s, chunks, 1900)
        // 旧実装: 長すぎる行は 1900 ごとに強制分割し、行末の断片（200 文字 + 改行）は buf に戻さず独立の断片になる
        assertEquals(listOf(1900, 1900, 201, 1), chunks.map { it.length })
    }

    @Test
    fun `日本語の長文`() {
        val s = "競馬の期待値通知です。".repeat(400)
        assertLossless(s, ContentSplitter.split(s), 1900)
    }

    @Test
    fun `絵文字とサロゲートペアの境界で壊さない`() {
        // 🎯 は U+1F3AF（UTF-16 で 2 単位）。1899 文字の後に置くと境界をまたぐ
        val s = "a".repeat(1899) + "🎯" + "b".repeat(10)
        val chunks = ContentSplitter.split(s)
        assertLossless(s, chunks, 1900)
        assertEquals(1899, chunks[0].length)
        assertTrue(chunks[1].startsWith("🎯"))
        val onlyEmoji = "🐴".repeat(3000) // 6000 単位
        val c2 = ContentSplitter.split(onlyEmoji)
        assertLossless(onlyEmoji, c2, 1900)
        assertTrue(c2.all { it.codePointCount(0, it.length) * 2 == it.length }, "どの断片も絵文字が完全")
        assertEquals(950, c2.first().codePointCount(0, c2.first().length))
    }

    @Test
    fun `コードポイント単位は旧 Python の len と同じで UTF-16 単位は余裕がある`() {
        val emoji = "🐴".repeat(1900)
        assertEquals(listOf(emoji), ContentSplitter.split(emoji, 1900, LengthUnit.CODE_POINTS))
        assertEquals(2, ContentSplitter.split(emoji, 1900, LengthUnit.UTF16_UNITS).size)
        val over = "🐴".repeat(1901)
        val cp = ContentSplitter.split(over, 1900, LengthUnit.CODE_POINTS)
        assertEquals(listOf(1900, 1), cp.map { it.codePointCount(0, it.length) })
    }

    @Test
    fun `Python の splitlines と同じ行区切り`() {
        assertEquals(listOf("a\r\n", "b\r", "c\u000B", "d\u000C", "e\u001C", "f\u001D", "g\u001E", "h\u0085", "i ", "j ", "k"), ContentSplitter.splitLinesKeepEnds("a\r\nb\rc\u000Bd\u000Ce\u001Cf\u001Dg\u001Eh\u0085i j k"))
        assertEquals(listOf("\n", "\n"), ContentSplitter.splitLinesKeepEnds("\n\n"))
        assertEquals(emptyList(), ContentSplitter.splitLinesKeepEnds(""))
    }

    @Test
    fun `limit が小さすぎると拒否`() {
        assertFailsWith<IllegalArgumentException> { ContentSplitter.split("abc", 1) }
    }

    @Test
    fun `複数の買い目の行が境界をまたいでも各行は欠けない`() {
        val lines = (1..120).map { "    - 01-02-%02d odds=12.3（推定） p=3.45%% EV=12.3%%\n".format(it % 100) }
        val s = lines.joinToString("")
        val chunks = ContentSplitter.split(s)
        assertLossless(s, chunks, 1900)
        assertEquals(lines.sorted(), chunks.flatMap { ContentSplitter.splitLinesKeepEnds(it) }.sorted())
    }
}
