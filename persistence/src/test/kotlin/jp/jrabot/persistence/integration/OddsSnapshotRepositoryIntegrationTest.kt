package jp.jrabot.persistence.integration

import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.odds.Odds
import jp.jrabot.domain.odds.OddsEntry
import jp.jrabot.domain.odds.OddsSnapshot
import jp.jrabot.domain.race.HorseNumber
import jp.jrabot.domain.repository.UpsertOutcome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Tag("integration")
class OddsSnapshotRepositoryIntegrationTest {
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
    fun `無ければ null と空`() {
        assertNull(h.odds.latest(T1, raceId, Market.WIN))
        assertEquals(emptyList(), h.odds.history(T1, raceId, Market.WIN))
    }

    @Test
    fun `保存して最新と履歴を正確なドメイン値で読み戻せる`() {
        val first = Fixtures.odds(observedAt = Instant.parse("2026-10-24T00:00:00Z"))
        val second = Fixtures.odds(observedAt = Instant.parse("2026-10-24T00:05:00.500000Z"), values = arrayOf(1 to 2.4, 2 to 11.5, 3 to 3.1))
        assertEquals(UpsertOutcome.CREATED, h.odds.upsert(first))
        assertEquals(UpsertOutcome.CREATED, h.odds.upsert(second))

        assertEquals(second, h.odds.latest(T1, raceId, Market.WIN))
        assertEquals(listOf(first, second), h.odds.history(T1, raceId, Market.WIN))
        assertEquals(6, db.count("SELECT COUNT(*) FROM odds_snapshots"))
    }

    @Test
    fun `同じ観測の再送は行を増やさず UNCHANGED、内容が違えば UPDATED、一部だけ新しければ CREATED`() {
        val snapshot = Fixtures.odds()
        assertEquals(UpsertOutcome.CREATED, h.odds.upsert(snapshot))
        assertEquals(UpsertOutcome.UNCHANGED, h.odds.upsert(snapshot))
        assertEquals(3, db.count("SELECT COUNT(*) FROM odds_snapshots"))

        assertEquals(UpsertOutcome.UPDATED, h.odds.upsert(Fixtures.odds(values = arrayOf(1 to 2.5, 2 to 10.5, 3 to 3.3))))
        assertEquals(
            10.5,
            checkNotNull(h.odds.latest(T1, raceId, Market.WIN))
                .entries
                .first { it.umaban == HorseNumber.of(2) }
                .odds.value,
        )

        val withNewHorse = Fixtures.odds(values = arrayOf(1 to 2.5, 2 to 10.5, 3 to 3.3, 4 to 50.0))
        assertEquals(UpsertOutcome.CREATED, h.odds.upsert(withNewHorse))
        assertEquals(4, db.count("SELECT COUNT(*) FROM odds_snapshots"))
    }

    @Test
    fun `市場ごとに別で、浮動小数点の値はそのまま往復する`() {
        val wide = Fixtures.odds(market = Market.WIDE, values = arrayOf(1 to 1.0000001, 2 to 123456.78901234))
        h.odds.upsert(wide)
        h.odds.upsert(Fixtures.odds(market = Market.WIN))
        assertEquals(wide, h.odds.latest(T1, raceId, Market.WIDE))
        assertNull(h.odds.latest(T1, raceId, Market.SANRENPUKU))
        assertEquals(1, h.odds.history(T1, raceId, Market.WIDE).size)
    }

    @Test
    fun `観測時刻は 7 桁目以降を切り捨ててマイクロ秒で保存する`() {
        h.odds.upsert(Fixtures.odds(observedAt = Instant.parse("2026-10-24T00:30:00.123456789Z")))
        assertEquals(Instant.parse("2026-10-24T00:30:00.123456Z"), checkNotNull(h.odds.latest(T1, raceId, Market.WIN)).observedAt)
        // 切り捨て後に同じ値になる再送は同じ行(冪等)
        assertEquals(UpsertOutcome.UNCHANGED, h.odds.upsert(Fixtures.odds(observedAt = Instant.parse("2026-10-24T00:30:00.123456999Z"))))
    }

    @Test
    fun `他テナントのオッズは見えず、他テナントのレースへの保存は複合 FK で拒否される`() {
        val snapshot = Fixtures.odds(T1)
        h.odds.upsert(snapshot)
        assertNull(h.odds.latest(T2, raceId, Market.WIN))
        assertEquals(emptyList(), h.odds.history(T2, raceId, Market.WIN))

        // T2 が持たないレース(T1 にだけある)へ T2 としてオッズを書く
        val onlyT1 = Fixtures.raceId(no = 7)
        h.races.upsert(Fixtures.race(T1, id = onlyT1))
        assertSqlError(MySqlError.FK_CHILD) { h.odds.upsert(Fixtures.odds(T2, raceId = onlyT1)) }
        assertEquals(3, db.count("SELECT COUNT(*) FROM odds_snapshots"))
    }

    @Test
    fun `途中の行が DB に拒否されたら、そのスナップショットは 1 行も残らない`() {
        val bad =
            OddsSnapshot(
                T1,
                raceId,
                Market.WIN,
                Fixtures.OBSERVED,
                listOf(
                    OddsEntry(HorseNumber.of(1), Odds.of(2.0), 1),
                    OddsEntry(HorseNumber.of(2), Odds.of(3.0), 70000), // SMALLINT UNSIGNED の範囲外
                ),
            )
        assertSqlError(MySqlError.OUT_OF_RANGE) { h.odds.upsert(bad) }
        assertEquals(0, db.count("SELECT COUNT(*) FROM odds_snapshots"))
    }

    @Test
    fun `人気順位は null と 0 を区別して保存する`() {
        val snapshot =
            OddsSnapshot(
                T1,
                raceId,
                Market.WIN,
                Fixtures.OBSERVED,
                listOf(OddsEntry(HorseNumber.of(1), Odds.of(2.0), null), OddsEntry(HorseNumber.of(2), Odds.of(3.0), 0)),
            )
        h.odds.upsert(snapshot)
        assertEquals(snapshot, h.odds.latest(T1, raceId, Market.WIN))
    }
}
