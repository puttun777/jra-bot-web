package jp.jrabot.persistence.mapping

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MappingTest {
    @Test
    fun `Instant は UTC の壁時計になり、7 桁目以降は切り捨てる`() {
        assertEquals(LocalDateTime.of(2026, 10, 24, 0, 30, 0, 123_456_000), Instant.parse("2026-10-24T00:30:00.123456999Z").toDbTime())
        assertEquals(LocalDateTime.of(2026, 10, 24, 0, 30, 0, 999_999_000), Instant.parse("2026-10-24T00:30:00.999999999Z").toDbTime())
    }

    @Test
    fun `往復して一致する（マイクロ秒の値）`() {
        val instant = Instant.parse("2026-03-08T02:30:00.000001Z")
        assertEquals(instant, instant.toDbTime().toInstantUtc())
    }

    @Test
    fun `JVM の既定タイムゾーンに依存しない`() {
        val original = TimeZone.getDefault()
        try {
            val results =
                listOf("Asia/Tokyo", "America/New_York", "Pacific/Auckland", "UTC").map {
                    TimeZone.setDefault(TimeZone.getTimeZone(it))
                    Instant.parse("2026-03-08T02:30:00.5Z").toDbTime()
                }
            assertEquals(1, results.toSet().size)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `ENUM の値がドメインに無ければ例外（黙って丸めない）`() {
        assertEquals(java.time.DayOfWeek.MONDAY, enumFromDb<java.time.DayOfWeek>("MONDAY", "t.c"))
        assertFailsWith<IllegalStateException> { enumFromDb<java.time.DayOfWeek>("FUNDAY", "t.c") }
    }
}
