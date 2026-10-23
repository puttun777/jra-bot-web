package jp.jrabot.domain.calendar

import jp.jrabot.domain.race.RaceScheduleStatus
import jp.jrabot.domain.race.Venue
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 有効な開催日の導出（C01〜C04・C06〜C09・C12・C14・C15・C17〜C19・C24 の導出部分）。 */
class EffectiveCalendarTest {
    private val d = Cal.DATE
    private val morning = Cal.jst(d, 7, 0)
    private val fresh = Cal.jst(d, 6, 0)

    private fun venueDay(
        venue: Venue = Venue.TOKYO,
        confirmedAt: Instant? = fresh,
        status: RaceDayStatus = RaceDayStatus.CONFIRMED,
    ) = Cal.day(venue, d, status, confirmedAt)

    @Test
    fun `C01 通常の開催（3 場 12 レース、時刻あり）は CONFIRMED で最初・最後の発走が分かる`() {
        val venues = listOf(Venue.TOKYO, Venue.KYOTO, Venue.SAPPORO)
        val day = Cal.derive(morning, venues.map { venueDay(it) }, venues.flatMap { Cal.races(it) })
        assertEquals(DayVerdict.CONFIRMED, day.verdict)
        assertEquals(VerdictBasis.SOURCE, day.basis)
        assertEquals(36, day.races.size)
        assertEquals(Cal.jst(d, 9, 55), day.firstPost)
        assertEquals(Cal.jst(d, 9, 55).plus(Duration.ofMinutes(25 * 11L)), day.lastPost)
        assertEquals(DayProgress.NOT_STARTED, day.progress)
        assertTrue(day.conflicts.isEmpty())
    }

    @Test
    fun `C02・C03 曜日と祝日に依らず同じ結果（7 日分と祝日）`() {
        fun shape(date: LocalDate): List<Any?> {
            val at = Cal.jst(date, 7, 0)
            val day =
                Cal.derive(
                    at,
                    listOf(Cal.day(Venue.TOKYO, date, RaceDayStatus.CONFIRMED, Cal.jst(date, 6, 0))),
                    Cal.races(Venue.TOKYO, date),
                    date = date,
                )
            return listOf(day.verdict, day.basis, day.races.size, Duration.between(day.firstPost, day.lastPost))
        }
        val week = (19..25).map { LocalDate.of(2026, 10, it) } + LocalDate.of(2026, 11, 3) + LocalDate.of(2026, 1, 1)
        assertEquals(1, week.map(::shape).toSet().size)
    }

    @Test
    fun `曜日・祝日では判定しない（土曜でもデータが無ければ不明、平日でも取得の事実があれば開催なしと確認）`() {
        val saturday = LocalDate.of(2026, 10, 24)
        val monday = LocalDate.of(2026, 10, 26)
        val unknown = Cal.derive(Cal.jst(saturday, 7, 0), emptyList(), emptyList(), date = saturday)
        assertEquals(DayVerdict.UNKNOWN, unknown.verdict)
        val noRace = Cal.derive(Cal.jst(monday, 7, 0), emptyList(), emptyList(), fact = ScheduleFetchFact(monday, Cal.jst(monday, 6, 30)), date = monday)
        assertEquals(DayVerdict.NO_RACE_CONFIRMED, noRace.verdict)
        val noRaceSaturday =
            Cal.derive(
                Cal.jst(saturday, 7, 0),
                emptyList(),
                emptyList(),
                fact = ScheduleFetchFact(saturday, Cal.jst(saturday, 6, 30)),
                date = saturday,
            )
        assertEquals(DayVerdict.NO_RACE_CONFIRMED, noRaceSaturday.verdict)
    }

    @Test
    fun `C04 行が無くても取得の事実が新鮮でなければ開催なしにしない`() {
        val stale = Cal.derive(morning, emptyList(), emptyList(), fact = ScheduleFetchFact(d, Cal.jst(d, 4, 59)))
        assertEquals(DayVerdict.STALE, stale.verdict)
        assertEquals(DayVerdict.UNKNOWN, Cal.derive(morning, emptyList(), emptyList()).verdict)
    }

    @Test
    fun `取得の事実の鮮度の境界（ちょうど 2 時間は新鮮、1 マイクロ秒超えたら古い）`() {
        val exactly = Cal.derive(Cal.jst(d, 8, 0), emptyList(), emptyList(), fact = ScheduleFetchFact(d, Cal.jst(d, 6, 0)))
        assertEquals(DayVerdict.NO_RACE_CONFIRMED, exactly.verdict)
        val over = Cal.derive(Cal.jst(d, 8, 0).plusNanos(1000), emptyList(), emptyList(), fact = ScheduleFetchFact(d, Cal.jst(d, 6, 0)))
        assertEquals(DayVerdict.STALE, over.verdict)
    }

    @Test
    fun `C15 開催日の行の確認が古い（3 時間前）と STALE で、時刻があっても CONFIRMED にしない`() {
        val day = Cal.derive(Cal.jst(d, 9, 0), listOf(venueDay(confirmedAt = Cal.jst(d, 6, 0))), Cal.races())
        assertEquals(DayVerdict.STALE, day.verdict)
        assertEquals(setOf(Venue.TOKYO), day.staleVenues)
        assertNull(day.firstPost)
    }

    @Test
    fun `開催日の行の確認の鮮度の境界`() {
        val ok = Cal.derive(Cal.jst(d, 8, 0), listOf(venueDay(confirmedAt = Cal.jst(d, 6, 0))), Cal.races())
        assertEquals(DayVerdict.CONFIRMED, ok.verdict)
        val over = Cal.derive(Cal.jst(d, 8, 0).plusNanos(1000), listOf(venueDay(confirmedAt = Cal.jst(d, 6, 0))), Cal.races())
        assertEquals(DayVerdict.STALE, over.verdict)
    }

    @Test
    fun `確認の記録が無い（行なし・時刻なし）は古い扱い`() {
        assertEquals(DayVerdict.STALE, Cal.derive(morning, emptyList(), Cal.races()).verdict)
        assertEquals(DayVerdict.STALE, Cal.derive(morning, listOf(venueDay(confirmedAt = null)), Cal.races()).verdict)
    }

    @Test
    fun `C14 日程はあるが出走表が無い（PLANNED だけ・出走馬名表だけ）は UNCONFIRMED`() {
        assertEquals(DayVerdict.UNCONFIRMED, Cal.derive(morning, listOf(venueDay(status = RaceDayStatus.PLANNED)), emptyList()).verdict)
        val announced = Cal.races(status = RaceScheduleStatus.ANNOUNCED).map { it.copy(scheduledPostAt = null, postTimeUnknown = true) }
        assertEquals(DayVerdict.UNCONFIRMED, Cal.derive(morning, listOf(venueDay(status = RaceDayStatus.PLANNED)), announced).verdict)
        // 出走馬名表（ANNOUNCED）に時刻が入っていても採用しない
        val announcedWithTime = Cal.races(status = RaceScheduleStatus.ANNOUNCED)
        val day = Cal.derive(morning, listOf(venueDay(status = RaceDayStatus.PLANNED)), announcedWithTime)
        assertEquals(DayVerdict.UNCONFIRMED, day.verdict)
        assertTrue(day.races.all { it.postAt == null })
    }

    @Test
    fun `C14 RA が到着すると CONFIRMED になる`() {
        val before = Cal.derive(morning, listOf(venueDay(status = RaceDayStatus.PLANNED)), emptyList())
        assertEquals(DayVerdict.UNCONFIRMED, before.verdict)
        val after = Cal.derive(morning.plusSeconds(60), listOf(venueDay(status = RaceDayStatus.CONFIRMED, confirmedAt = morning)), Cal.races())
        assertEquals(DayVerdict.CONFIRMED, after.verdict)
    }

    @Test
    fun `C07 全レースが中止なら CANCELLED で窓は作らない`() {
        val races = Cal.races(status = RaceScheduleStatus.CANCELLED)
        val day = Cal.derive(morning, listOf(venueDay()), races)
        assertEquals(DayVerdict.CANCELLED, day.verdict)
        assertNull(day.firstPost)
        assertEquals(DayVerdict.CANCELLED, Cal.derive(morning, listOf(venueDay(status = RaceDayStatus.CANCELLED)), Cal.races()).verdict)
    }

    @Test
    fun `C08 一部のレース中止は当該レースだけ除外し、他は通常`() {
        val races = Cal.races().map { if (it.raceId.raceNo == 1) it.copy(status = RaceScheduleStatus.CANCELLED) else it }
        val day = Cal.derive(morning, listOf(venueDay()), races)
        assertEquals(DayVerdict.CONFIRMED, day.verdict)
        assertEquals(RaceScheduleStatus.CANCELLED, day.races.first { it.raceId.raceNo == 1 }.status)
        assertFalse(day.races.first { it.raceId.raceNo == 1 }.inWindow)
        assertEquals(Cal.jst(d, 10, 20), day.firstPost)
    }

    @Test
    fun `削除（区分 0）のレースは有効なレースに含めない`() {
        val races = Cal.races().map { if (it.raceId.raceNo == 12 || it.raceId.raceNo == 1) it.copy(status = RaceScheduleStatus.DELETED) else it }
        val day = Cal.derive(morning, listOf(venueDay()), races)
        assertEquals(10, day.races.size)
        assertEquals(Cal.jst(d, 10, 20), day.firstPost)
    }

    @Test
    fun `C06 延期は POSTPONED で振替先を持ち、振替先の日は別に導出される`() {
        val next = d.plusDays(1)
        val postponed = Cal.derive(morning, listOf(Cal.day(Venue.TOKYO, d, RaceDayStatus.POSTPONED, fresh, replacedBy = next)), emptyList())
        assertEquals(DayVerdict.POSTPONED, postponed.verdict)
        assertEquals(next, postponed.postponedTo)
        val nextDay =
            Cal.derive(
                Cal.jst(next, 7, 0),
                listOf(Cal.day(Venue.TOKYO, next, RaceDayStatus.CONFIRMED, Cal.jst(next, 6, 0))),
                Cal.races(date = next),
                date = next,
            )
        assertEquals(DayVerdict.CONFIRMED, nextDay.verdict)
    }

    @Test
    fun `C09 延期先が不明なら POSTPONED で振替先は null、手動の POSTPONE_TO で解消できる`() {
        val unknown = Cal.derive(morning, listOf(Cal.day(Venue.TOKYO, d, RaceDayStatus.POSTPONED, fresh)), emptyList())
        assertEquals(DayVerdict.POSTPONED, unknown.verdict)
        assertNull(unknown.postponedTo)
        val manual =
            Cal.derive(
                morning,
                listOf(Cal.day(Venue.TOKYO, d, RaceDayStatus.POSTPONED, fresh)),
                emptyList(),
                listOf(Cal.override(OverrideAction.POSTPONE_TO)),
            )
        assertEquals(d.plusDays(1), manual.postponedTo)
        assertEquals(VerdictBasis.MANUAL, manual.basis)
    }

    @Test
    fun `完了した日は確認が古くても STALE にしない`() {
        val done =
            Cal.derive(
                Cal.jst(d, 20, 0),
                listOf(venueDay(status = RaceDayStatus.COMPLETED, confirmedAt = Cal.jst(d, 6, 0))),
                Cal.races(status = RaceScheduleStatus.SETTLED),
            )
        assertEquals(DayVerdict.CONFIRMED, done.verdict)
        assertEquals(DayProgress.COMPLETED, done.progress)
        val incomplete =
            Cal.derive(
                Cal.jst(d, 20, 0),
                listOf(venueDay(status = RaceDayStatus.INCOMPLETE)),
                Cal.races(status = RaceScheduleStatus.RESULT_PENDING),
            )
        assertEquals(DayProgress.INCOMPLETE, incomplete.progress)
        val running = Cal.derive(Cal.jst(d, 12, 0), listOf(venueDay(status = RaceDayStatus.RUNNING, confirmedAt = Cal.jst(d, 11, 30))), Cal.races())
        assertEquals(DayProgress.RUNNING, running.progress)
        val settling = Cal.derive(Cal.jst(d, 12, 0), listOf(venueDay(status = RaceDayStatus.SETTLING, confirmedAt = Cal.jst(d, 11, 30))), Cal.races())
        assertEquals(DayProgress.SETTLING, settling.progress)
    }

    @Test
    fun `複数の場で確認が古い場は窓から外し、新しい場だけで CONFIRMED`() {
        val days = listOf(venueDay(Venue.TOKYO, fresh), venueDay(Venue.KYOTO, Cal.jst(d, 1, 0)))
        val races = Cal.races(Venue.TOKYO) + Cal.races(Venue.KYOTO).map { it.copy(scheduledPostAt = Cal.jst(d, 8, 0)) }
        val day = Cal.derive(morning, days, races)
        assertEquals(DayVerdict.CONFIRMED, day.verdict)
        assertEquals(setOf(Venue.KYOTO), day.staleVenues)
        assertEquals(Cal.jst(d, 9, 55), day.firstPost) // 京都の 08:00 は含めない
        assertTrue(day.races.filter { it.raceId.venue == Venue.KYOTO }.none { it.inWindow })
    }

    @Test
    fun `時刻不明のレースは窓に含めず、件数が分かる`() {
        val races = Cal.races().map { if (it.raceId.raceNo >= 11) it.copy(scheduledPostAt = null, postTimeUnknown = true) else it }
        val day = Cal.derive(morning, listOf(venueDay()), races)
        assertEquals(DayVerdict.CONFIRMED, day.verdict)
        assertEquals(2, day.unknownTimeRaces)
        assertEquals(Cal.jst(d, 9, 55).plus(Duration.ofMinutes(225)), day.lastPost)
    }

    @Test
    fun `すべてのレースの時刻が不明なら CONFIRMED にならない（窓も作れない）`() {
        val races = Cal.races().map { it.copy(scheduledPostAt = null, postTimeUnknown = true) }
        val day = Cal.derive(morning, listOf(venueDay()), races)
        assertEquals(DayVerdict.UNCONFIRMED, day.verdict)
        assertNull(day.window(Cal.WATCH))
    }

    // --- 手動補正 ---

    @Test
    fun `C17 手動の開催日と発走時刻で、元が不明でも CONFIRMED（MANUAL）になる`() {
        val races = listOf(Cal.race(no = 1, status = RaceScheduleStatus.ANNOUNCED, post = null))
        val day =
            Cal.derive(
                morning,
                emptyList(),
                races,
                listOf(
                    Cal.override(OverrideAction.MARK_RACE_DAY),
                    Cal.override(OverrideAction.SET_POST_TIME, raceNo = 1, value = OverrideValue.PostAt(Cal.jst(d, 10, 30))),
                ),
            )
        assertEquals(DayVerdict.CONFIRMED, day.verdict)
        assertEquals(VerdictBasis.MANUAL, day.basis)
        assertEquals(Cal.jst(d, 10, 30), day.firstPost)
        assertTrue(day.races.single().postTimeByManual)
    }

    @Test
    fun `手動の発走時刻は元の時刻より優先され、最新の補正が勝つ`() {
        val old = Cal.override(OverrideAction.SET_POST_TIME, raceNo = 1, value = OverrideValue.PostAt(Cal.jst(d, 10, 0)), createdAt = Cal.jst(d, 5, 0))
        val new = Cal.override(OverrideAction.SET_POST_TIME, raceNo = 1, value = OverrideValue.PostAt(Cal.jst(d, 11, 0)), createdAt = Cal.jst(d, 5, 30))
        val day = Cal.derive(morning, listOf(venueDay()), Cal.races(count = 1), listOf(new, old))
        assertEquals(Cal.jst(d, 11, 0), day.races.single().postAt)
    }

    @Test
    fun `C18 手動の開催なしは元の予定があっても NO_RACE_CONFIRMED で、食い違いが記録される`() {
        val ov = Cal.override(OverrideAction.MARK_NO_RACE)
        val day = Cal.derive(morning, listOf(venueDay()), Cal.races(), listOf(ov))
        assertEquals(DayVerdict.NO_RACE_CONFIRMED, day.verdict)
        assertEquals(VerdictBasis.MANUAL, day.basis)
        assertEquals(listOf(OverrideConflict(ConflictKind.MANUAL_NO_RACE_BUT_SOURCE_HAS_RACES, ov.id)), day.conflicts)
    }

    @Test
    fun `手動の開催日が元の中止・開催なしと食い違えば記録する`() {
        val ov = Cal.override(OverrideAction.MARK_RACE_DAY)
        val day = Cal.derive(morning, listOf(venueDay(status = RaceDayStatus.CANCELLED)), emptyList(), listOf(ov))
        assertEquals(DayVerdict.CONFIRMED, day.verdict)
        assertEquals(listOf(OverrideConflict(ConflictKind.MANUAL_RACE_DAY_BUT_SOURCE_HAS_NO_RACE, ov.id)), day.conflicts)
        // 元が不明なら食い違いではない（空白を埋める）
        assertTrue(Cal.derive(morning, emptyList(), emptyList(), listOf(Cal.override(OverrideAction.MARK_RACE_DAY))).conflicts.isEmpty())
    }

    @Test
    fun `優先順位 CANCEL と MARK_NO_RACE は MARK_RACE_DAY と POSTPONE_TO に勝つ（作成の順に依らない）`() {
        val raceDay = Cal.override(OverrideAction.MARK_RACE_DAY, createdAt = Cal.jst(d, 5, 30))
        val cancel = Cal.override(OverrideAction.CANCEL, createdAt = Cal.jst(d, 5, 0))
        val day = Cal.derive(morning, emptyList(), emptyList(), listOf(raceDay, cancel))
        assertEquals(DayVerdict.CANCELLED, day.verdict)
        val noRace = Cal.override(OverrideAction.MARK_NO_RACE, createdAt = Cal.jst(d, 5, 0))
        val postpone = Cal.override(OverrideAction.POSTPONE_TO, createdAt = Cal.jst(d, 5, 30))
        assertEquals(DayVerdict.NO_RACE_CONFIRMED, Cal.derive(morning, emptyList(), emptyList(), listOf(postpone, noRace)).verdict)
    }

    @Test
    fun `同じ階層の補正の競合は作成が新しい方、同時刻は id の大きい方`() {
        val a = Cal.override(OverrideAction.MARK_RACE_DAY, createdAt = Cal.jst(d, 5, 0), id = 10)
        val b = Cal.override(OverrideAction.POSTPONE_TO, createdAt = Cal.jst(d, 5, 10), id = 5)
        assertEquals(DayVerdict.POSTPONED, Cal.derive(morning, emptyList(), emptyList(), listOf(a, b)).verdict)
        val c = Cal.override(OverrideAction.MARK_RACE_DAY, createdAt = Cal.jst(d, 5, 10), id = 6)
        assertEquals(DayVerdict.CONFIRMED, Cal.derive(morning, emptyList(), emptyList(), listOf(b, c)).verdict)
        val cancel = Cal.override(OverrideAction.CANCEL, createdAt = Cal.jst(d, 5, 20), id = 7)
        val noRace = Cal.override(OverrideAction.MARK_NO_RACE, createdAt = Cal.jst(d, 5, 20), id = 8)
        assertEquals(DayVerdict.NO_RACE_CONFIRMED, Cal.derive(morning, emptyList(), emptyList(), listOf(cancel, noRace)).verdict)
    }

    @Test
    fun `入力の並びを変えても結果は同じ（決定的）`() {
        val ovs =
            listOf(
                Cal.override(OverrideAction.MARK_RACE_DAY, createdAt = Cal.jst(d, 5, 0), id = 1),
                Cal.override(OverrideAction.POSTPONE_TO, createdAt = Cal.jst(d, 5, 0), id = 2),
                Cal.override(OverrideAction.ALLOW_RUN, id = 3),
                Cal.override(OverrideAction.SET_POST_TIME, raceNo = 1, createdAt = Cal.jst(d, 5, 0), id = 4),
                Cal.override(OverrideAction.SET_POST_TIME, raceNo = 1, value = OverrideValue.PostAt(Cal.jst(d, 12, 0)), createdAt = Cal.jst(d, 5, 0), id = 5),
            )
        val races = Cal.races(count = 3)
        val expected = Cal.derive(morning, listOf(venueDay()), races, ovs)
        repeat(
            20,
        ) { i ->
            assertEquals(
                expected,
                Cal.derive(morning, listOf(venueDay()), races.shuffled(java.util.Random(i.toLong())), ovs.shuffled(java.util.Random(i.toLong()))),
            )
        }
    }

    @Test
    fun `C19 DENY_RUN があれば ALLOW_RUN があっても拒否が残る`() {
        val day =
            Cal.derive(
                morning,
                listOf(venueDay()),
                Cal.races(),
                listOf(Cal.override(OverrideAction.ALLOW_RUN), Cal.override(OverrideAction.DENY_RUN, createdAt = Cal.jst(d, 4, 0))),
            )
        assertTrue(day.denyRun)
        assertTrue(day.allowRun)
        assertEquals(DayVerdict.CONFIRMED, day.verdict)
    }

    @Test
    fun `レース単位の CANCEL は SET_POST_TIME に勝ち、日単位の CANCEL は日全体を中止にする`() {
        val cancelRace = Cal.override(OverrideAction.CANCEL, OverrideScope.RACE, raceNo = 2)
        val timeRace = Cal.override(OverrideAction.SET_POST_TIME, raceNo = 2, createdAt = Cal.jst(d, 5, 30))
        val day = Cal.derive(morning, listOf(venueDay()), Cal.races(count = 3), listOf(cancelRace, timeRace))
        val r2 = day.races.first { it.raceId.raceNo == 2 }
        assertEquals(RaceScheduleStatus.CANCELLED, r2.status)
        assertTrue(r2.cancelledByManual)
        assertNull(r2.postAt)
        assertFalse(r2.inWindow)
        assertEquals(DayVerdict.CONFIRMED, day.verdict)
        val all = Cal.derive(morning, listOf(venueDay()), Cal.races(count = 3), listOf(Cal.override(OverrideAction.CANCEL)))
        assertEquals(DayVerdict.CANCELLED, all.verdict)
    }

    @Test
    fun `取消済みと期限切れの補正は適用しない（期限ちょうどは期限切れ）`() {
        val revoked = Cal.override(OverrideAction.MARK_NO_RACE, revokedAt = Cal.jst(d, 6, 0))
        val until = Cal.jst(d, 7, 0)
        val expiring = Cal.override(OverrideAction.DENY_RUN, validUntil = until)
        val races = Cal.races()
        assertEquals(DayVerdict.CONFIRMED, Cal.derive(morning, listOf(venueDay()), races, listOf(revoked)).verdict)
        assertTrue(Cal.derive(until.minusNanos(1000), listOf(venueDay()), races, listOf(expiring)).denyRun)
        assertFalse(Cal.derive(until, listOf(venueDay()), races, listOf(expiring)).denyRun)
        assertFalse(Cal.derive(until.plusSeconds(1), listOf(venueDay()), races, listOf(expiring)).denyRun)
    }

    @Test
    fun `手動の時刻で開催日にした日は確認の古い場のレースも窓に使う`() {
        val days = listOf(venueDay(confirmedAt = Cal.jst(d, 0, 0)))
        val stale = Cal.derive(morning, days, Cal.races(count = 2))
        assertEquals(DayVerdict.STALE, stale.verdict)
        val manual = Cal.derive(morning, days, Cal.races(count = 2), listOf(Cal.override(OverrideAction.MARK_RACE_DAY)))
        assertEquals(DayVerdict.CONFIRMED, manual.verdict)
        assertEquals(Cal.jst(d, 9, 55), manual.firstPost)
    }

    @Test
    fun `別のテナント・日付のデータが混ざっていたら拒否する`() {
        assertFailsWith<IllegalArgumentException> { Cal.derive(morning, listOf(Cal.day(tenant = Cal.T2)), emptyList()) }
        assertFailsWith<IllegalArgumentException> { Cal.derive(morning, emptyList(), listOf(Cal.race(tenant = Cal.T2))) }
        assertFailsWith<IllegalArgumentException> { Cal.derive(morning, emptyList(), listOf(Cal.race(date = d.plusDays(1)))) }
        assertFailsWith<IllegalArgumentException> {
            Cal.derive(
                morning,
                emptyList(),
                emptyList(),
                listOf(Cal.override(OverrideAction.DENY_RUN, tenant = Cal.T2)),
            )
        }
        assertFailsWith<IllegalArgumentException> { Cal.derive(morning, emptyList(), emptyList(), fact = ScheduleFetchFact(d.plusDays(1), morning)) }
    }

    @Test
    fun `JVM の既定タイムゾーンに依らない（UTC と Asia-Tokyo）`() {
        val saved = java.util.TimeZone.getDefault()
        try {
            val results =
                listOf("UTC", "Asia/Tokyo", "America/New_York").map { zone ->
                    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone))
                    Cal.derive(morning, listOf(venueDay()), Cal.races())
                }
            assertEquals(1, results.toSet().size)
            assertEquals(Cal.jst(d, 9, 55), results.first().firstPost)
        } finally {
            java.util.TimeZone.setDefault(saved)
        }
    }
}
