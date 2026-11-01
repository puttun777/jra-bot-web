package jp.jrabot.persistence.integration

import jp.jrabot.domain.ingest.PayoutRow
import jp.jrabot.domain.ingest.ResultEntry
import jp.jrabot.domain.ingest.StoredPayout
import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.repository.UpsertOutcome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** 着順・払戻の保存（T3.9 ②）。取込が書き、冪等で、確定を速報で上書きせず、テナント境界を越えない。 */
@Tag("integration")
class ResultPayoutStoreIntegrationTest {
    companion object {
        private val db = MySqlSupport.migratedDatabase()
        private val h = RepositoryHarness(db)

        @JvmStatic
        @AfterAll
        fun close() = h.close()
    }

    private val raceId = Fixtures.raceId()

    @BeforeEach
    fun reset() {
        db.resetData()
        h.races.upsert(Fixtures.race(T1))
        h.races.upsert(Fixtures.race(T2, name = "T2"))
    }

    @Test
    fun `着順は馬番ごとに保存され 同じ内容の再送は UNCHANGED 変われば UPDATED`() {
        val r = listOf(ResultEntry(1, 2), ResultEntry(2, 1), ResultEntry(3, null))
        assertEquals(UpsertOutcome.CREATED, h.results.upsertAll(T1, raceId, r))
        assertEquals(r, h.results.list(T1, raceId))
        assertEquals(UpsertOutcome.UNCHANGED, h.results.upsertAll(T1, raceId, r))
        assertEquals(UpsertOutcome.UPDATED, h.results.upsertAll(T1, raceId, listOf(ResultEntry(1, 1), ResultEntry(2, 2))))
        assertEquals(listOf(ResultEntry(1, 1), ResultEntry(2, 2), ResultEntry(3, null)), h.results.list(T1, raceId))
        assertEquals(3, db.count("SELECT COUNT(*) FROM results"))
    }

    @Test
    fun `着順は別のテナントから見えない`() {
        h.results.upsertAll(T1, raceId, listOf(ResultEntry(1, 1)))
        assertEquals(emptyList(), h.results.list(T2, raceId))
    }

    @Test
    fun `払戻は賭式ごとに保存され 同じ内容の再送は UNCHANGED`() {
        val rows = listOf(PayoutRow("02-03", 350.0), PayoutRow("01-02", 1280.0))
        assertEquals(UpsertOutcome.CREATED, h.payouts.upsertAll(T1, raceId, Market.WIDE, rows, provisional = false))
        assertEquals(
            listOf(StoredPayout("01-02", 1280.0, false), StoredPayout("02-03", 350.0, false)),
            h.payouts.list(T1, raceId, Market.WIDE),
        )
        assertEquals(emptyList(), h.payouts.list(T1, raceId, Market.WIN))
        assertEquals(UpsertOutcome.UNCHANGED, h.payouts.upsertAll(T1, raceId, Market.WIDE, rows, provisional = false))
    }

    @Test
    fun `確定の払戻を速報で上書きしない 速報から確定へは置き換える`() {
        val row = listOf(PayoutRow("02-03", 350.0))
        h.payouts.upsertAll(T1, raceId, Market.WIDE, row, provisional = true)
        assertEquals(listOf(StoredPayout("02-03", 350.0, true)), h.payouts.list(T1, raceId, Market.WIDE))

        assertEquals(UpsertOutcome.UPDATED, h.payouts.upsertAll(T1, raceId, Market.WIDE, listOf(PayoutRow("02-03", 410.0)), provisional = false))
        assertEquals(listOf(StoredPayout("02-03", 410.0, false)), h.payouts.list(T1, raceId, Market.WIDE))

        // 確定の後に届いた速報は無視（行は変わらず UNCHANGED）
        assertEquals(UpsertOutcome.UNCHANGED, h.payouts.upsertAll(T1, raceId, Market.WIDE, listOf(PayoutRow("02-03", 999.0)), provisional = true))
        assertEquals(listOf(StoredPayout("02-03", 410.0, false)), h.payouts.list(T1, raceId, Market.WIDE))

        // 確定どうしの訂正は上書き
        assertEquals(UpsertOutcome.UPDATED, h.payouts.upsertAll(T1, raceId, Market.WIDE, listOf(PayoutRow("02-03", 420.0)), provisional = false))
        assertEquals(listOf(StoredPayout("02-03", 420.0, false)), h.payouts.list(T1, raceId, Market.WIDE))
    }

    @Test
    fun `払戻は別のテナントから見えない`() {
        h.payouts.upsertAll(T1, raceId, Market.WIN, listOf(PayoutRow("03", 250.0)), provisional = false)
        assertEquals(emptyList(), h.payouts.list(T2, raceId, Market.WIN))
    }
}
