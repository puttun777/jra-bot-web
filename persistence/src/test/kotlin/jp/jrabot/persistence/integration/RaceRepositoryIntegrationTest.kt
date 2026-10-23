package jp.jrabot.persistence.integration

import jp.jrabot.domain.race.Venue
import jp.jrabot.domain.repository.UpsertOutcome
import jp.jrabot.domain.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("integration")
class RaceRepositoryIntegrationTest {
    companion object {
        private val db = MySqlSupport.migratedDatabase()
        private val h = RepositoryHarness(db, poolMax = 8)

        @JvmStatic
        @AfterAll
        fun close() = h.close()
    }

    @BeforeEach
    fun reset() = db.resetData()

    @Test
    fun `無いレースは null`() {
        assertNull(h.races.find(T1, Fixtures.raceId()))
        assertEquals(emptyList(), h.races.listByDate(T1, Fixtures.DATE))
    }

    @Test
    fun `挿入すると読み戻せて、同じ内容の再実行は UNCHANGED で updated_at も変わらない`() {
        h.clock.instant = Instant.parse("2026-10-23T03:00:00.100000Z")
        assertEquals(UpsertOutcome.CREATED, h.races.upsert(Fixtures.race()))
        assertEquals(Fixtures.race(), h.races.find(T1, Fixtures.raceId()))
        val before = db.scalar("SELECT CONCAT(created_at, '|', updated_at) FROM races")

        h.clock.instant = Instant.parse("2026-10-23T04:00:00Z")
        assertEquals(UpsertOutcome.UNCHANGED, h.races.upsert(Fixtures.race()))
        assertEquals(before, db.scalar("SELECT CONCAT(created_at, '|', updated_at) FROM races"))
        assertEquals(1, db.count("SELECT COUNT(*) FROM races"))
    }

    @Test
    fun `内容が変わると UPDATED で updated_at だけが進み、created_at と親のキーは変わらない`() {
        h.clock.instant = Instant.parse("2026-10-23T03:00:00Z")
        h.races.upsert(Fixtures.race())
        val created = db.scalar("SELECT created_at FROM races")

        h.clock.instant = Instant.parse("2026-10-23T05:00:00Z")
        assertEquals(UpsertOutcome.UPDATED, h.races.upsert(Fixtures.race(name = "改名後", distance = 1600)))
        val read = checkNotNull(h.races.find(T1, Fixtures.raceId()))
        assertEquals("改名後", read.name)
        assertEquals(1600, read.distanceMeters)
        assertEquals(created, db.scalar("SELECT created_at FROM races"))
        assertEquals("2026-10-23 05:00:00.000000", db.scalar("SELECT DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s.%f') FROM races"))
        assertEquals(1, db.count("SELECT COUNT(*) FROM races"))
    }

    @Test
    fun `発走時刻が null なら不明として保存し、null で読み戻す。他の null 列もそのまま`() {
        val race = Fixtures.race(postTime = null, name = null, distance = null)
        assertEquals(UpsertOutcome.CREATED, h.races.upsert(race))
        assertEquals("1", db.scalar("SELECT post_time_unknown FROM races"))
        assertNull(db.scalar("SELECT post_time FROM races"))
        assertEquals(race, h.races.find(T1, race.id))
    }

    @Test
    fun `日付の一覧は場コードとレース番号の昇順で、別の日は含まない`() {
        val ids =
            listOf(
                Fixtures.raceId(Venue.KYOTO, 2),
                Fixtures.raceId(Venue.TOKYO, 12),
                Fixtures.raceId(Venue.TOKYO, 1),
                Fixtures.raceId(Venue.SAPPORO, 5),
            )
        ids.forEach { h.races.upsert(Fixtures.race(id = it)) }
        h.races.upsert(Fixtures.race(id = Fixtures.raceId(date = LocalDate.of(2026, 10, 25))))
        assertEquals(
            listOf("202610240105", "202610240501", "202610240512", "202610240802"),
            h.races.listByDate(T1, Fixtures.DATE).map { it.id.value },
        )
    }

    @Test
    fun `同じレース ID でもテナントごとに別の行で、互いに見えず上書きもしない`() {
        h.races.upsert(Fixtures.race(T1, name = "T1のレース"))
        assertNull(h.races.find(T2, Fixtures.raceId()))
        assertEquals(emptyList(), h.races.listByDate(T2, Fixtures.DATE))

        assertEquals(UpsertOutcome.CREATED, h.races.upsert(Fixtures.race(T2, name = "T2のレース")))
        assertEquals("T1のレース", h.races.find(T1, Fixtures.raceId())?.name)
        assertEquals("T2のレース", h.races.find(T2, Fixtures.raceId())?.name)
        assertEquals(2, db.count("SELECT COUNT(*) FROM races"))
    }

    @Test
    fun `存在しないテナントへの書込みは FK で拒否され、読取りは空（全件へフォールバックしない）`() {
        h.races.upsert(Fixtures.race(T1))
        val unknown = TenantId.of(99)
        assertEquals(emptyList(), h.races.listByDate(unknown, Fixtures.DATE))
        assertNull(h.races.find(unknown, Fixtures.raceId()))
        assertSqlError(MySqlError.FK_CHILD) { h.races.upsert(Fixtures.race(unknown)) }
        assertEquals(1, db.count("SELECT COUNT(*) FROM races"))
    }

    @Test
    fun `同時に同じレースを upsert しても例外にならず、挿入は 1 回だけ`() {
        val results = runConcurrently(6) { h.races.upsert(Fixtures.race()) }
        val outcomes = results.map { it.getOrThrow() }
        assertEquals(1, outcomes.count { it == UpsertOutcome.CREATED }, "$outcomes")
        assertTrue(outcomes.all { it != UpsertOutcome.UPDATED }, "$outcomes")
        assertEquals(1, db.count("SELECT COUNT(*) FROM races"))
    }

    @Test
    fun `別のレースは別の行になる`() {
        h.races.upsert(Fixtures.race(id = Fixtures.raceId(no = 1)))
        h.races.upsert(Fixtures.race(id = Fixtures.raceId(no = 2)))
        assertNotEquals(h.races.find(T1, Fixtures.raceId(no = 1)), h.races.find(T1, Fixtures.raceId(no = 2)))
    }
}
