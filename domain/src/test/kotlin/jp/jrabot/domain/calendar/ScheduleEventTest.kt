package jp.jrabot.domain.calendar

import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.race.Venue
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ScheduleEventTest {
    private val raceId = RaceId.of(Cal.DATE, Venue.TOKYO, 11)
    private val t = Instant.parse("2026-10-24T01:00:00Z")

    private fun event(
        type: ScheduleEventType = ScheduleEventType.TIME_CHANGE,
        race: RaceId? = raceId,
        group: String = "g1",
        newValue: String? = EventJson.obj("post_at" to "x"),
    ) = ScheduleEvent.create(Cal.T1, race, Cal.DATE, type, null, newValue, ScheduleEventSource.B14, t, t, group)

    @Test
    fun `レース単位の種類は raceId が必須、日単位の種類は raceId を持てない`() {
        for (type in ScheduleEventType.entries) {
            when (type.raceIdRule) {
                RaceIdRule.REQUIRED -> {
                    assertFailsWith<IllegalArgumentException>("$type") { event(type, race = null) }
                    event(type, race = raceId)
                }

                RaceIdRule.FORBIDDEN -> {
                    assertFailsWith<IllegalArgumentException>("$type") { event(type, race = raceId) }
                    event(type, race = null)
                }

                RaceIdRule.OPTIONAL -> {
                    event(type, race = raceId)
                    event(type, race = null)
                }
            }
        }
    }

    @Test
    fun `種類ごとの規則`() {
        assertEquals(RaceIdRule.REQUIRED, ScheduleEventType.TIME_CHANGE.raceIdRule)
        assertEquals(RaceIdRule.FORBIDDEN, ScheduleEventType.DAY_STATUS.raceIdRule)
        assertEquals(RaceIdRule.FORBIDDEN, ScheduleEventType.FETCH_FAILURE.raceIdRule)
        assertEquals(RaceIdRule.FORBIDDEN, ScheduleEventType.STALE.raceIdRule)
        assertEquals(RaceIdRule.OPTIONAL, ScheduleEventType.MANUAL_OVERRIDE.raceIdRule)
        assertEquals(15, ScheduleEventType.entries.size)
    }

    @Test
    fun `raceDate は raceId の日付と一致しなければならない`() {
        assertFailsWith<IllegalArgumentException> {
            ScheduleEvent.create(Cal.T1, raceId, Cal.DATE.plusDays(1), ScheduleEventType.ADDED, null, null, ScheduleEventSource.RACE, t, t, "g")
        }
    }

    @Test
    fun `同じ内容と dedupGroup なら同じハッシュ、取得時刻は含めない`() {
        val a = event()
        val b =
            ScheduleEvent.create(
                Cal.T1,
                raceId,
                Cal.DATE,
                ScheduleEventType.TIME_CHANGE,
                null,
                EventJson.obj("post_at" to "x"),
                ScheduleEventSource.B14,
                t.plusSeconds(60),
                t.plusSeconds(90),
                "g1",
            )
        assertEquals(a.dedupHash, b.dedupHash)
        assertTrue(Regex("[0-9a-f]{64}").matches(a.dedupHash))
    }

    @Test
    fun `テナント・レース・種類・値・ソース・グループのどれかが違えばハッシュが違う`() {
        val base = event().dedupHash
        assertNotEquals(base, event(group = "g2").dedupHash)
        assertNotEquals(base, event(newValue = EventJson.obj("post_at" to "y")).dedupHash)
        assertNotEquals(base, event(race = RaceId.of(Cal.DATE, Venue.TOKYO, 12)).dedupHash)
        assertNotEquals(base, event(type = ScheduleEventType.REVOKED).dedupHash)
        assertNotEquals(
            base,
            ScheduleEvent
                .create(
                    Cal.T2,
                    raceId,
                    Cal.DATE,
                    ScheduleEventType.TIME_CHANGE,
                    null,
                    EventJson.obj("post_at" to "x"),
                    ScheduleEventSource.B14,
                    t,
                    t,
                    "g1",
                ).dedupHash,
        )
        assertNotEquals(
            base,
            ScheduleEvent
                .create(
                    Cal.T1,
                    raceId,
                    Cal.DATE,
                    ScheduleEventType.TIME_CHANGE,
                    null,
                    EventJson.obj("post_at" to "x"),
                    ScheduleEventSource.RACE,
                    t,
                    t,
                    "g1",
                ).dedupHash,
        )
    }

    @Test
    fun `値の連結で衝突しない（区切りを含む値）`() {
        val a = ScheduleEvent.create(Cal.T1, null, Cal.DATE, ScheduleEventType.STALE, "a", "b", ScheduleEventSource.SYSTEM, t, t, "g")
        val b = ScheduleEvent.create(Cal.T1, null, Cal.DATE, ScheduleEventType.STALE, "a\u001fb", null, ScheduleEventSource.SYSTEM, t, t, "g")
        assertNotEquals(a.dedupHash, b.dedupHash)
    }

    @Test
    fun `batchId と dedupHash の形式を検証する`() {
        assertFailsWith<IllegalArgumentException> {
            ScheduleEvent.create(Cal.T1, null, Cal.DATE, ScheduleEventType.STALE, null, null, ScheduleEventSource.SYSTEM, t, t, "g", batchId = "not-a-uuid")
        }
        assertFailsWith<IllegalArgumentException> {
            ScheduleEvent.create(Cal.T1, null, Cal.DATE, ScheduleEventType.STALE, null, null, ScheduleEventSource.SYSTEM, t, t, "")
        }
    }

    @Test
    fun `EventJson はキー昇順の正規形で escape する`() {
        assertEquals("""{"a":"1","b":null,"c":true,"d":2}""", EventJson.obj("d" to 2, "c" to true, "b" to null, "a" to "1"))
        assertEquals("""{"k":"q\"\\\n\u0001あ"}""".replace("\\n", "\\u000a"), EventJson.obj("k" to "q\"\\\n\u0001あ"))
        assertFailsWith<IllegalArgumentException> { EventJson.obj("a" to 1, "a" to 2) }
        assertFailsWith<IllegalArgumentException> { EventJson.obj("a" to 1.5) }
    }

    @Test
    fun `source の DB 値`() {
        assertEquals("0B14", ScheduleEventSource.B14.dbValue)
        assertEquals(ScheduleEventSource.B15, ScheduleEventSource.fromDbValue("0B15"))
        assertFailsWith<IllegalStateException> { ScheduleEventSource.fromDbValue("X") }
    }
}
