package jp.jrabot.domain.ml.features

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** 期待値は旧 `jraev.ml.features._hash_bucket / _hash_sign`（Python 3.13・固定コミット f7dbf69 の実装）で計算した整数・符号。 */
class PedigreeHashTest {
    @ParameterizedTest
    @CsvSource(
        // 名前, bucket16, sign, bucket1, bucket64
        "'', 4, -1, 0, 20",
        "a, 12, 1, 0, 12",
        "Alpha, 1, 1, 0, 33",
        "alpha, 12, 1, 0, 44",
        "ＡＢＣ, 12, -1, 0, 28",
        "ABC, 0, 1, 0, 16",
        "シンセティックサイアー001, 3, 1, 0, 3",
        "漢字名称, 14, -1, 0, 30",
        "ひらがなのなまえ, 11, -1, 0, 59",
        "dup, 14, -1, 0, 14",
        "NFCé, 13, -1, 0, 61",
    )
    fun `旧 Python と bucket と符号が完全一致する`(
        name: String,
        b16: Int,
        sign: Int,
        b1: Int,
        b64: Int,
    ) {
        assertEquals(b16, PedigreeHash.bucket(name, 16))
        assertEquals(sign.toDouble(), PedigreeHash.sign(name))
        assertEquals(b1, PedigreeHash.bucket(name, 1))
        assertEquals(b64, PedigreeHash.bucket(name, 64))
    }

    @Test
    fun `全角と半角・かなと漢字は別物で、Unicode 正規化をしない`() {
        assertEquals(12, PedigreeHash.bucket("ＡＢＣ", 16))
        assertEquals(0, PedigreeHash.bucket("ABC", 16))
        // 合成済みの é（U+00E9）と、e + 結合アクセント（U+0301）は別の名前（bucket 13 と 8）
        assertEquals(13, PedigreeHash.bucket("NFC\u00E9", 16))
        assertEquals(3, PedigreeHash.bucket("NFD é", 16))
        assertEquals(10, PedigreeHash.bucket("emoji🐎x", 16)) // サロゲートペア（絵文字）は 1 文字として符号化
        // 全角スペースは名前の一部（　 を trim しない）
        assertEquals(4, PedigreeHash.bucket("合成母父　テスト", 16))
    }

    @Test
    fun `孤立サロゲートは errors=ignore と同じく捨てて符号化する`() {
        // Python: "\ud800x".encode("utf-8", errors="ignore") == b"x" → bucket 13。"x" だけの名前と同じ
        assertEquals(PedigreeHash.bucket("x", 16), PedigreeHash.bucket("\uD800x", 16))
        assertEquals(13, PedigreeHash.bucket("\uD800x", 16))
        assertEquals(14, PedigreeHash.bucket("x\uDC00y", 16))
        assertEquals(PedigreeHash.bucket("xy", 16), PedigreeHash.bucket("x\uDC00y", 16))
        assertEquals(1.0, PedigreeHash.sign("\uD800x"))
    }

    @Test
    fun `dim が 0 以下でも max(1, dim) で bucket 0`() {
        assertEquals(0, PedigreeHash.bucket("x", 0))
        assertEquals(0, PedigreeHash.bucket("x", -3))
    }

    @Test
    fun `同一 bucket への衝突は符号つきで足し合わせる`() {
        // a・alpha・ＡＢＣ はすべて bucket 12。符号は +1 +1 -1 → +1
        val v = PedigreeHash.accumulate(listOf("a", "alpha", "ＡＢＣ"), 16)
        assertEquals(1.0, v[12])
        assertEquals(1.0, v.sum())
    }

    @Test
    fun `16 次元の集計は旧 Python と一致し、重複は重複したまま足す`() {
        val names = listOf("シンセティックサイアー001", "ＡＢＣ", "ABC", "dup", "dup", "漢字名称", "ひらがなのなまえ", "a", "b")
        val v = PedigreeHash.accumulate(names, 16)
        // 旧実装は先頭 8 件（"b" は 9 件目で対象外）。dup は 2 回 → -2 ではなく bucket14 は 漢字名称(-1) と dup(-1)×2 の -3
        val expected = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, -3.0, 0.0)
        // bucket12 は a(+1)・ＡＢＣ(-1) で相殺されて 0
        assertContentEquals(expected, v)
    }

    @Test
    fun `空の名前リストは 0 ベクトル、9 件目以降は無視`() {
        assertContentEquals(DoubleArray(16), PedigreeHash.accumulate(emptyList(), 16))
        val nine = (0 until 9).map { "horse$it" }
        assertContentEquals(PedigreeHash.accumulate(nine.take(8), 16), PedigreeHash.accumulate(nine, 16))
    }
}
