package jp.jrabot.persistence.integration

import jp.jrabot.domain.bot.AcquireResult
import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.race.Venue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** 時刻は UTC の `DATETIME(6)`。マイクロ秒の往復、7 桁目以降の切り捨て、JVM の既定タイムゾーン(夏時間の欠落時刻を含む)への非依存を確かめる(G-VER-3)。 */
@Tag("integration")
class TimeConversionIntegrationTest {
    companion object {
        private val db = MySqlSupport.migratedDatabase()

        @JvmStatic
        @AfterAll
        fun close() = Unit
    }

    @BeforeEach
    fun reset() = db.resetData()

    private val rawFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC)

    private fun raw(sql: String) = checkNotNull(db.scalar(sql))

    @Test
    fun `マイクロ秒まで往復し、7 桁目以降は切り捨てる（丸めない）`() {
        RepositoryHarness(db).use { h ->
            val withNanos = Instant.parse("2026-10-24T00:00:00.123456999Z")
            val saved = assertIs<AcquireResult.Acquired>(h.botRuns.acquire(Fixtures.lease(now = withNanos))).run
            assertEquals(Instant.parse("2026-10-24T00:00:00.123456Z"), saved.startedAt)
            assertEquals(Instant.parse("2026-10-24T00:05:00.123456Z"), saved.leaseExpiresAt)
            assertEquals("2026-10-24 00:00:00.123456", raw("SELECT DATE_FORMAT(started_at, '%Y-%m-%d %H:%i:%s.%f') FROM bot_run"))
            // 切り捨て済みの値は完全に往復する
            assertEquals(saved, h.botRuns.find(T1, saved.runId))
        }
    }

    @Test
    fun `DB の壁時計は UTC で、JVM のタイムゾーンが何でも同じ（夏時間で存在しない現地時刻も壊れない）`() {
        val original = TimeZone.getDefault()
        // 各ゾーンの夏時間の欠落時刻(現地時刻としては存在しない)に当たる UTC の壁時計を含める
        val instants =
            listOf(
                Instant.parse("2026-03-08T02:30:00.000001Z"), // America/New_York の欠落(02:00→03:00)
                Instant.parse("2026-09-27T02:30:00.999999Z"), // Pacific/Auckland の欠落
                Instant.parse("2026-03-29T01:30:00.500000Z"), // Europe/London の欠落(01:00→02:00)
                Instant.parse("2026-10-24T15:00:00Z"), // JST の日付境界(UTC では同じ日)
                Instant.parse("2026-12-31T23:59:59.999999Z"),
            )
        try {
            for (zone in listOf("Asia/Tokyo", "America/New_York", "Pacific/Auckland", "Europe/London", "UTC")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                db.resetData()
                RepositoryHarness(db).use { h ->
                    // 新しい接続を、そのタイムゾーンで開く
                    h.races.upsert(Fixtures.race())
                    instants.forEachIndexed { i, instant ->
                        val umaban = i + 1
                        h.odds.upsert(Fixtures.odds(observedAt = instant, values = arrayOf(umaban to 2.0)))
                        val read = h.odds.history(T1, Fixtures.raceId(), Market.WIN).first { it.observedAt == instant }
                        assertEquals(instant, read.observedAt, "zone=$zone")
                    }
                    assertEquals(instants.sorted(), h.odds.history(T1, Fixtures.raceId(), Market.WIN).map { it.observedAt }, "zone=$zone")
                }
                val rawTimes = db.query("SELECT DATE_FORMAT(observed_at, '%Y-%m-%dT%H:%i:%s.%fZ') FROM odds_snapshots ORDER BY observed_at") { it.getString(1) }
                assertEquals(instants.sorted().map(rawFormat::format), rawTimes, "zone=$zone: DB の壁時計が UTC のままではない")
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `日付と時刻(LocalDate・LocalTime)も JVM のタイムゾーンに依存しない`() {
        val original = TimeZone.getDefault()
        try {
            for (zone in listOf("Asia/Tokyo", "America/New_York", "Pacific/Auckland")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                db.resetData()
                RepositoryHarness(db).use { h ->
                    for ((date, time) in listOf(
                        LocalDate.of(2026, 3, 8) to LocalTime.of(2, 30),
                        LocalDate.of(2026, 9, 27) to LocalTime.of(2, 30),
                        LocalDate.of(2026, 12, 31) to LocalTime.of(0, 0),
                    )) {
                        val id = RaceId.of(date, Venue.TOKYO, 1)
                        h.races.upsert(Fixtures.race(id = id, postTime = time))
                        val read = checkNotNull(h.races.find(T1, id))
                        assertEquals(time, read.postTime, "zone=$zone $date")
                        assertEquals(date, read.date, "zone=$zone $date")
                        assertEquals(listOf(id), h.races.listByDate(T1, date).map { it.id })
                    }
                }
                assertEquals(
                    "2026-03-08|2026-09-27|2026-12-31",
                    db.query("SELECT race_date FROM races ORDER BY race_date") { it.getString(1) }.joinToString("|"),
                )
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `接続のセッション time_zone は +00_00 に固定されている`() {
        RepositoryHarness(db).use { h ->
            h.dataSource.connection.use { c ->
                c.createStatement().use { s ->
                    s.executeQuery("SELECT @@session.time_zone").use {
                        it.next()
                        assertEquals("+00:00", it.getString(1))
                    }
                }
            }
        }
    }
}
