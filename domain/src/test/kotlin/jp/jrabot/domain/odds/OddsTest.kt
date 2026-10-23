package jp.jrabot.domain.odds

import jp.jrabot.domain.race.HorseNumber
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.tenant.TenantId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OddsTest {
    @ParameterizedTest
    @ValueSource(doubles = [1.0, 1.01, 1.1, 2.5, 999.9, 99999.0, Double.MIN_VALUE, Double.MAX_VALUE])
    fun `有限な正の数は生成できる（1_0 も有効）`(value: Double) {
        assertEquals(value, Odds.of(value).value)
    }

    @Test
    fun `1_0 は有効なオッズであり PAR と等しい`() {
        assertEquals(Odds.PAR, Odds.of(1.0))
        assertEquals(1.0, Odds.PAR.value)
    }

    @ParameterizedTest
    @ValueSource(doubles = [0.0, -0.0, -1.0, -0.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -Double.MAX_VALUE])
    fun `0 負数 NaN Infinity は拒否する`(value: Double) {
        assertNull(Odds.ofOrNull(value))
        assertFailsWith<IllegalArgumentException> { Odds.of(value) }
    }

    @Test
    fun `等価性 hashCode 順序`() {
        assertEquals(Odds.of(2.5), Odds.of(2.5))
        assertEquals(Odds.of(2.5).hashCode(), Odds.of(2.5).hashCode())
        assertNotEquals(Odds.of(2.5), Odds.of(2.6))
        assertTrue(Odds.of(1.0) < Odds.of(1.01))
        assertEquals(listOf(1.0, 2.0, 30.0), listOf(30.0, 1.0, 2.0).map(Odds::of).sorted().map { it.value })
    }

    private val tenant = TenantId.of(1)
    private val raceId = RaceId.parse("202401010611")
    private val at = Instant.parse("2026-11-01T01:00:00Z")

    @Test
    fun `OddsSnapshot は馬番の重複と空を拒否し 人気 0 を保持する`() {
        val a = OddsEntry(HorseNumber.of(1), Odds.of(1.0), popularity = 0)
        val b = OddsEntry(HorseNumber.of(2), Odds.of(3.4))
        val snap = OddsSnapshot(tenant, raceId, Market.WIN, at, listOf(a, b))
        assertEquals(0, snap.entries[0].popularity)
        assertNull(snap.entries[1].popularity)
        assertFailsWith<IllegalArgumentException> { OddsSnapshot(tenant, raceId, Market.WIN, at, emptyList()) }
        assertFailsWith<IllegalArgumentException> { OddsSnapshot(tenant, raceId, Market.WIN, at, listOf(a, a.copy(odds = Odds.of(5.0)))) }
        assertFailsWith<IllegalArgumentException> { OddsEntry(HorseNumber.of(3), Odds.of(2.0), popularity = -1) }
    }

    @Test
    fun `Market のコードは参照元の値`() {
        assertEquals(listOf("win", "wide", "sanrenpuku"), Market.entries.map { it.code })
        assertEquals(Market.WIDE, Market.fromCodeOrNull("wide"))
        assertNull(Market.fromCodeOrNull("WIN"))
    }
}
