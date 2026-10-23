package jp.jrabot.domain.race

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaceIdTest {
    @Test
    fun `参照元の例 202401010611 を分解できる`() {
        val id = RaceId.parse("202401010611")
        assertEquals(LocalDate.of(2024, 1, 1), id.date)
        assertEquals(Venue.NAKAYAMA, id.venue)
        assertEquals(11, id.raceNo)
        assertEquals("202401010611", id.value)
    }

    @Test
    fun `日付と場とレース番号から組み立てると parse と往復する`() {
        val id = RaceId.of(LocalDate.of(2026, 11, 1), Venue.TOKYO, 5)
        assertEquals("202611010505", id.value)
        assertEquals(id, RaceId.parse(id.value))
        for (v in Venue.entries) assertEquals(v, RaceId.of(LocalDate.of(2025, 2, 3), v, 1).venue)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "2024010106", "20240101061", "2024010106111", // 桁数
            "20240101061a", "2024-01-0106", " 202401010611", "202401010611 ", "+02401010611", // 数字以外
            "２０２４０１０１０６１１", // 全角数字
            "202413010611", "202400010611", "202401320611", "202401000611", // 月・日の範囲
            "202302290611", "210002290611", // 閏年でない 2/29
            "000000000611", "189912310611", // 年の下限（1900 未満）
            "202401010011", "202401010a11", "202401011111", "202401019911", // 場コード（01〜10 以外）
            "202401010600", // レース番号 00
        ],
    )
    fun `不正な race_id は拒否する`(text: String) {
        assertNull(RaceId.parseOrNull(text), text)
        assertFailsWith<IllegalArgumentException> { RaceId.parse(text) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["202402290601", "200002290601", "190001010101", "202412311099", "202401010601", "202401010612"])
    fun `境界の有効な値は受け入れる`(text: String) {
        assertEquals(text, RaceId.parse(text).value)
    }

    @Test
    fun `回と日目が無い旧データの形式のまま扱え 16 桁へ導出できる`() {
        val id = RaceId.parse("202401010611")
        assertEquals("2024010106" + "03" + "04" + "11", id.rtKey16(kai = 3, nichime = 4))
        assertEquals(16, id.rtKey16(1, 1).length)
        assertFailsWith<IllegalArgumentException> { id.rtKey16(0, 1) }
        assertFailsWith<IllegalArgumentException> { id.rtKey16(1, 100) }
    }

    @Test
    fun `of は範囲外の引数を拒否する`() {
        assertFailsWith<IllegalArgumentException> { RaceId.of(LocalDate.of(2024, 1, 1), Venue.TOKYO, 0) }
        assertFailsWith<IllegalArgumentException> { RaceId.of(LocalDate.of(2024, 1, 1), Venue.TOKYO, 100) }
        assertFailsWith<IllegalArgumentException> { RaceId.of(LocalDate.of(1899, 12, 31), Venue.TOKYO, 1) }
    }

    @Test
    fun `等価性 hashCode 順序`() {
        assertEquals(RaceId.parse("202401010611"), RaceId.parse("202401010611"))
        assertEquals(RaceId.parse("202401010611").hashCode(), RaceId.parse("202401010611").hashCode())
        assertNotEquals(RaceId.parse("202401010611"), RaceId.parse("202401010612"))
        val sorted = listOf("202401020101", "202401010612", "202401010511", "202401010611").map(RaceId::parse).sorted()
        assertEquals(listOf("202401010511", "202401010611", "202401010612", "202401020101"), sorted.map { it.value })
        assertTrue(RaceId.parse("202401010611") < RaceId.parse("202401020101"))
    }

    @Test
    fun `場コード表は 01 から 10 で重複がない`() {
        assertEquals((1..10).map { "%02d".format(it) }, Venue.entries.map { it.code })
        assertEquals("中山", Venue.fromCode("06").nameJa)
        assertNull(Venue.fromCodeOrNull("11"))
        assertFailsWith<IllegalArgumentException> { Venue.fromCode("00") }
    }
}
