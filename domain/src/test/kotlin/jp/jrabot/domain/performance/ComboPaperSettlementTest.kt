package jp.jrabot.domain.performance

import jp.jrabot.domain.ingest.StoredPayout
import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.race.Venue
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComboPaperSettlementTest {
    private val race = RaceId.of(LocalDate.of(2026, 10, 24), Venue.TOKYO, 11)
    private val post = Instant.parse("2026-10-24T06:00:00Z")

    private fun ticket(
        market: Market,
        kumi: String,
        stake: Double = 300.0,
        observed: Instant = post.minusSeconds(300),
    ) = ComboTicketInput(race, market, kumi, stake, observed, post)

    private fun pay(
        key: String,
        v: Double,
        provisional: Boolean = false,
    ) = StoredPayout(key, v, provisional)

    @Test
    fun `表示名から組み合わせキーを読む 昇順・0 埋め・BOX の接頭辞や区切りの違いを吸収し 数が合わないものは読まない`() {
        assertEquals("02-03", ComboPaperSettlement.comboKey(Market.WIDE, "3-2"))
        assertEquals("02-03", ComboPaperSettlement.comboKey(Market.WIDE, "[BOX]02-03"))
        assertEquals("01-02-12", ComboPaperSettlement.comboKey(Market.SANRENPUKU, "12-01-02"))
        assertNull(ComboPaperSettlement.comboKey(Market.WIDE, "01-02-03"))
        assertNull(ComboPaperSettlement.comboKey(Market.SANRENPUKU, "01-02"))
        assertNull(ComboPaperSettlement.comboKey(Market.WIDE, "05-05"))
        assertNull(ComboPaperSettlement.comboKey(Market.WIDE, "00-02"))
        assertNull(ComboPaperSettlement.comboKey(Market.WIN, "01"))
    }

    @Test
    fun `ワイドは 3 着までの任意の 2 頭が的中で 払戻の行にある組み合わせが正（旧実装の 1・2 着だけは引き継がない）`() {
        // 1 着 3 番・2 着 7 番・3 着 5 番 → ワイドの払戻は 03-07 / 03-05 / 05-07 の 3 行
        val payouts = listOf(pay("03-07", 450.0), pay("03-05", 280.0), pay("05-07", 1200.0))
        val hit = ComboPaperSettlement.settle(ticket(Market.WIDE, "5-7"), payouts) as ComboOutcome.Settled
        assertTrue(hit.hit)
        assertEquals(3600.0, hit.actualPayout) // 300 × 1200 / 100
        assertEquals(3300.0, hit.actualProfit)
        val miss = ComboPaperSettlement.settle(ticket(Market.WIDE, "3-9"), payouts) as ComboOutcome.Settled
        assertEquals(false, miss.hit)
        assertEquals(0.0, miss.actualPayout)
        assertEquals(-300.0, miss.actualProfit)
    }

    @Test
    fun `同着で払戻の行が増えても 行の有無で判定する`() {
        val payouts = listOf(pay("03-07", 450.0), pay("03-05", 280.0), pay("05-07", 1200.0), pay("03-08", 300.0), pay("07-08", 900.0), pay("05-08", 600.0))
        assertEquals(true, (ComboPaperSettlement.settle(ticket(Market.WIDE, "08-03"), payouts) as ComboOutcome.Settled).hit)
    }

    @Test
    fun `三連複の的中と外れ`() {
        val payouts = listOf(pay("03-05-07", 8200.0))
        val hit = ComboPaperSettlement.settle(ticket(Market.SANRENPUKU, "7-3-5", stake = 100.0), payouts) as ComboOutcome.Settled
        assertEquals(8200.0, hit.actualPayout)
        assertEquals(false, (ComboPaperSettlement.settle(ticket(Market.SANRENPUKU, "03-05-08"), payouts) as ComboOutcome.Settled).hit)
    }

    @Test
    fun `確定した払戻が無い間は判定しない 速報だけでも外れにしない`() {
        assertEquals(ComboOutcome.Unsettled(ComboUnsettledReason.NO_CONFIRMED_PAYOUT), ComboPaperSettlement.settle(ticket(Market.WIDE, "3-9"), emptyList()))
        assertEquals(
            ComboOutcome.Unsettled(ComboUnsettledReason.NO_CONFIRMED_PAYOUT),
            ComboPaperSettlement.settle(ticket(Market.WIDE, "3-9"), listOf(pay("03-07", 450.0, provisional = true))),
        )
        // 速報の的中は実払戻に使わない（確定で変わり得る）
        assertEquals(
            ComboOutcome.Unsettled(ComboUnsettledReason.NO_CONFIRMED_PAYOUT),
            ComboPaperSettlement.settle(ticket(Market.WIDE, "3-7"), listOf(pay("03-07", 450.0, provisional = true))),
        )
    }

    @Test
    fun `見送り・読めない買い目・対象外の賭式は損益の対象にしない`() {
        val payouts = listOf(pay("03-07", 450.0))
        assertEquals(ComboOutcome.Unsettled(ComboUnsettledReason.NOT_A_BET), ComboPaperSettlement.settle(ticket(Market.WIDE, "3-7", stake = 0.0), payouts))
        assertEquals(ComboOutcome.Unsettled(ComboUnsettledReason.UNPARSEABLE_KUMI), ComboPaperSettlement.settle(ticket(Market.WIDE, "3"), payouts))
        assertEquals(ComboOutcome.Unsettled(ComboUnsettledReason.UNSUPPORTED_MARKET), ComboPaperSettlement.settle(ticket(Market.WIN, "3"), payouts))
    }

    @Test
    fun `代表集計は同じ組み合わせの複数タイミングを二重に数えず 発走後の観測を数えず 賭式別に出す`() {
        val s = ComboOutcome.Settled(true, 300.0, 3600.0)
        val m = ComboOutcome.Settled(false, 300.0, 0.0)

        fun c(
            market: Market,
            key: String,
            at: Long,
            o: ComboOutcome.Settled,
        ) = SettledCombo(race, market, key, post.plusSeconds(at), post, o)
        val items =
            listOf(
                c(Market.WIDE, "05-07", -1800, s), // 30 分前
                c(Market.WIDE, "05-07", -300, s), // 5 分前（代表はこちらだけ）
                c(Market.WIDE, "05-07", 60, s), // 発走後は数えない
                c(Market.WIDE, "03-09", -300, m),
                c(Market.SANRENPUKU, "03-05-07", -300, ComboOutcome.Settled(true, 100.0, 8200.0)),
            )
        val r = ComboPerformance.representative(items.shuffled())
        val wide = r.single { it.market == Market.WIDE }
        assertEquals(2, wide.count)
        assertEquals(1, wide.hits)
        assertEquals(600.0, wide.totalStake)
        assertEquals(3600.0, wide.actualPayout)
        assertEquals(3000.0, wide.actualProfit)
        assertEquals(6.0, wide.actualRoi)
        assertEquals(0.5, wide.hitRate)
        assertEquals(8200.0, r.single { it.market == Market.SANRENPUKU }.actualPayout)
        assertEquals(emptyList(), ComboPerformance.representative(emptyList()))
    }
}
