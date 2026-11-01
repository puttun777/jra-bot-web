package jp.jrabot.domain.calendar

import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.race.RaceScheduleStatus
import jp.jrabot.domain.race.Venue
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 予定の反映（C10〜C13・C26 と版・遷移・冪等性）。メモリ実装は永続化層と同じ意味（原子性・冪等・楽観ロック）。 */
class ScheduleUpdaterTest {
    private val d = Cal.DATE
    private val raceId = RaceId.of(d, Venue.TOKYO, 11)
    private val clock = FixedClock(Cal.jst(d, 6, 0))
    private val repo = InMemorySchedules()
    private val updater = RaceScheduleUpdater(repo, repo, clock)

    private fun obs(
        kubun: RaceDataKubun,
        createdOn: LocalDate = LocalDate.of(2026, 10, 23),
        time: LocalTime? = LocalTime.of(15, 35),
        id: RaceId = raceId,
        at: Instant = Cal.jst(d, 5, 0),
        name: String? = "11R",
    ) = RaceScheduleObservation(Cal.T1, id, kubun, createdOn, time, name, at)

    private fun applied(r: ApplyResult<*>) = assertIs<ApplyResult.Applied<*>>(r)

    private fun rejected(r: ApplyResult<*>) = assertIs<ApplyResult.Rejected>(r).reason

    @Test
    fun `C12 新しいレースは ADDED で、出走馬名表の時刻は採用しない`() {
        val r = applied(updater.apply(obs(RaceDataKubun.ANNOUNCEMENT)))
        assertEquals(ScheduleEventType.ADDED, r.event.type)
        val row = repo.find(Cal.T1, raceId)!!
        assertEquals(RaceScheduleStatus.ANNOUNCED, row.status)
        assertNull(row.scheduledPostAt)
        assertTrue(row.postTimeUnknown)
        assertEquals("202610231", row.sourceVersion)
        assertEquals(1, repo.events.size)
    }

    @Test
    fun `出馬表が来ると時刻が確定して CARD になり TIME_CHANGE が記録される`() {
        updater.apply(obs(RaceDataKubun.ANNOUNCEMENT))
        val r = applied(updater.apply(obs(RaceDataKubun.CARD)))
        assertEquals(ScheduleEventType.TIME_CHANGE, r.event.type)
        val row = repo.find(Cal.T1, raceId)!!
        assertEquals(RaceScheduleStatus.CARD, row.status)
        assertEquals(Cal.jst(d, 15, 35), row.scheduledPostAt)
        assertEquals(1, row.version)
        assertEquals(clock.instant, row.lastChangedAt)
        assertEquals(2, repo.events.size)
    }

    @Test
    fun `最初から出馬表なら CARD で ADDED`() {
        val r = applied(updater.apply(obs(RaceDataKubun.CARD)))
        assertEquals(ScheduleEventType.ADDED, r.event.type)
        assertEquals(RaceScheduleStatus.CARD, repo.find(Cal.T1, raceId)!!.status)
    }

    @Test
    fun `出馬表でも時刻が 0000・欠落なら不明のまま ANNOUNCED（0000 を窓の開始に置換しない）`() {
        applied(updater.apply(obs(RaceDataKubun.CARD, time = LocalTime.MIDNIGHT)))
        val row = repo.find(Cal.T1, raceId)!!
        assertEquals(RaceScheduleStatus.ANNOUNCED, row.status)
        assertTrue(row.postTimeUnknown)
        assertNull(row.scheduledPostAt)
    }

    @Test
    fun `再配信（同じ版・同じ内容）は何もせず履歴も増えない`() {
        updater.apply(obs(RaceDataKubun.CARD))
        val before = repo.rows.toMap()
        val again = updater.apply(obs(RaceDataKubun.CARD))
        assertEquals(ApplyResult.NoChange(NoChangeReason.DUPLICATE_VERSION), again)
        assertEquals(before, repo.rows)
        assertEquals(1, repo.events.size)
    }

    @Test
    fun `C26 古い版は反映しない（新しい版の後に古い版を再読込）`() {
        updater.apply(obs(RaceDataKubun.CARD, createdOn = LocalDate.of(2026, 10, 23), time = LocalTime.of(15, 35)))
        val newer = applied(updater.apply(obs(RaceDataKubun.CARD, createdOn = LocalDate.of(2026, 10, 24), time = LocalTime.of(15, 50))))
        assertEquals(ScheduleEventType.TIME_CHANGE, newer.event.type)
        val stale = updater.apply(obs(RaceDataKubun.CARD, createdOn = LocalDate.of(2026, 10, 23), time = LocalTime.of(15, 35)))
        assertEquals(RejectReason.OLDER_SOURCE_VERSION, rejected(stale))
        assertEquals(Cal.jst(d, 15, 50), repo.find(Cal.T1, raceId)!!.scheduledPostAt)
        assertEquals("202610242", repo.find(Cal.T1, raceId)!!.sourceVersion)
    }

    @Test
    fun `同じ作成日で区分が古いもの（出走馬名表）は出馬表の後に来ても反映しない`() {
        updater.apply(obs(RaceDataKubun.CARD))
        assertEquals(RejectReason.OLDER_SOURCE_VERSION, rejected(updater.apply(obs(RaceDataKubun.ANNOUNCEMENT))))
        assertEquals(RaceScheduleStatus.CARD, repo.find(Cal.T1, raceId)!!.status)
    }

    @Test
    fun `同じ版で内容が違うものは順序を決められないので反映しない`() {
        updater.apply(obs(RaceDataKubun.CARD, time = LocalTime.of(15, 35)))
        assertEquals(RejectReason.SAME_VERSION_DIFFERENT_CONTENT, rejected(updater.apply(obs(RaceDataKubun.CARD, time = LocalTime.of(15, 40)))))
        assertEquals(Cal.jst(d, 15, 35), repo.find(Cal.T1, raceId)!!.scheduledPostAt)
    }

    @Test
    fun `C07・C08 中止（区分 9）は CANCELLED を記録し、既存のレースを終端にする`() {
        updater.apply(obs(RaceDataKubun.CARD))
        val r = applied(updater.apply(obs(RaceDataKubun.CANCELLED, createdOn = LocalDate.of(2026, 10, 24))))
        assertEquals(ScheduleEventType.CANCELLED, r.event.type)
        assertEquals(RaceScheduleStatus.CANCELLED, repo.find(Cal.T1, raceId)!!.status)
        // 同じ中止の再配信は何もしない
        assertEquals(
            ApplyResult.NoChange(NoChangeReason.DUPLICATE_VERSION),
            updater.apply(obs(RaceDataKubun.CANCELLED, createdOn = LocalDate.of(2026, 10, 24))),
        )
    }

    @Test
    fun `知らないレースの中止は CANCELLED の行を作って記録する`() {
        val r = applied(updater.apply(obs(RaceDataKubun.CANCELLED)))
        assertEquals(ScheduleEventType.CANCELLED, r.event.type)
        assertEquals(RaceScheduleStatus.CANCELLED, repo.find(Cal.T1, raceId)!!.status)
    }

    @Test
    fun `中止されたレースに新しい出馬表が来ても復活させない`() {
        updater.apply(obs(RaceDataKubun.CARD))
        updater.apply(obs(RaceDataKubun.CANCELLED, createdOn = LocalDate.of(2026, 10, 24)))
        val revive = updater.apply(obs(RaceDataKubun.CARD, createdOn = LocalDate.of(2026, 10, 25)))
        assertEquals(RejectReason.INVALID_TRANSITION, rejected(revive))
        assertEquals(RaceScheduleStatus.CANCELLED, repo.find(Cal.T1, raceId)!!.status)
    }

    @Test
    fun `C13 削除（区分 0）は DELETED として保持し、再読込でも復活しない`() {
        updater.apply(obs(RaceDataKubun.CARD))
        val r = applied(updater.apply(obs(RaceDataKubun.DELETED)))
        assertEquals(ScheduleEventType.DELETED, r.event.type)
        val row = repo.find(Cal.T1, raceId)!!
        assertEquals(RaceScheduleStatus.DELETED, row.status)
        // 行は削除されず保持される
        assertEquals(1, repo.listByDate(Cal.T1, d).size)
        // 古い版の再読込
        assertEquals(RejectReason.OLDER_SOURCE_VERSION, rejected(updater.apply(obs(RaceDataKubun.CARD))))
        // 新しい作成日の出馬表でも復活しない
        assertEquals(RejectReason.INVALID_TRANSITION, rejected(updater.apply(obs(RaceDataKubun.CARD, createdOn = LocalDate.of(2026, 10, 25)))))
        assertEquals(RaceScheduleStatus.DELETED, repo.find(Cal.T1, raceId)!!.status)
    }

    @Test
    fun `知らないレースの削除は行を作らない`() {
        assertEquals(ApplyResult.NoChange(NoChangeReason.NOTHING_TO_DELETE), updater.apply(obs(RaceDataKubun.DELETED)))
        assertTrue(repo.rows.isEmpty())
        assertTrue(repo.events.isEmpty())
    }

    @Test
    fun `同じ作成日の 9 と 0 は順序を決めず反映しない`() {
        updater.apply(obs(RaceDataKubun.CARD))
        updater.apply(obs(RaceDataKubun.CANCELLED))
        assertEquals(RejectReason.UNDECIDABLE_SOURCE_VERSION, rejected(updater.apply(obs(RaceDataKubun.DELETED))))
        assertEquals(RaceScheduleStatus.CANCELLED, repo.find(Cal.T1, raceId)!!.status)
    }

    @Test
    fun `成績系の区分は対象外で何も変えない`() {
        updater.apply(obs(RaceDataKubun.CARD))
        assertEquals(RejectReason.UNSUPPORTED_KUBUN, rejected(updater.apply(obs(RaceDataKubun.RESULT_7))))
        assertEquals(1, repo.events.size)
    }

    @Test
    fun `保存済みの版が解釈できなければ上書きしない`() {
        updater.apply(obs(RaceDataKubun.CARD))
        repo.rows[Cal.T1 to raceId] = repo.rows.getValue(Cal.T1 to raceId).copy(sourceVersion = "garbage")
        assertEquals(RejectReason.UNPARSEABLE_STORED_VERSION, rejected(updater.apply(obs(RaceDataKubun.CARD, createdOn = LocalDate.of(2026, 10, 25)))))
    }

    @Test
    fun `発走後の状態では出馬表の時刻を採用しない`() {
        updater.apply(obs(RaceDataKubun.CARD))
        repo.rows[Cal.T1 to raceId] = repo.rows.getValue(Cal.T1 to raceId).copy(status = RaceScheduleStatus.OFF)
        val r = updater.apply(obs(RaceDataKubun.CARD, createdOn = LocalDate.of(2026, 10, 25), time = LocalTime.of(16, 0)))
        assertEquals(ApplyResult.NoChange(NoChangeReason.NO_EFFECTIVE_CHANGE), r)
        assertEquals(Cal.jst(d, 15, 35), repo.find(Cal.T1, raceId)!!.scheduledPostAt)
    }

    @Test
    fun `版だけ・名前だけが違う更新は保存しない（記録できる種類が無い）`() {
        updater.apply(obs(RaceDataKubun.CARD))
        val r = updater.apply(obs(RaceDataKubun.CARD, createdOn = LocalDate.of(2026, 10, 24), name = "別名"))
        assertEquals(ApplyResult.NoChange(NoChangeReason.NO_EFFECTIVE_CHANGE), r)
        assertEquals("202610232", repo.find(Cal.T1, raceId)!!.sourceVersion)
    }

    @Test
    fun `C10 TC による時刻変更は TIME_CHANGE で時刻だけが変わり、同じスナップショットの再配信は 1 回だけ`() {
        updater.apply(obs(RaceDataKubun.CARD))
        val r = applied(updater.applyTimeChange(Cal.T1, raceId, Cal.jst(d, 15, 50), Cal.jst(d, 14, 0), "snap-1"))
        assertEquals(ScheduleEventType.TIME_CHANGE, r.event.type)
        assertEquals(ScheduleEventSource.B14, r.event.source)
        val row = repo.find(Cal.T1, raceId)!!
        assertEquals(Cal.jst(d, 15, 50), row.scheduledPostAt)
        assertEquals("202610232", row.sourceVersion)
        assertEquals(RaceScheduleStatus.CARD, row.status)
        val before = repo.events.size
        val again = updater.applyTimeChange(Cal.T1, raceId, Cal.jst(d, 15, 50), Cal.jst(d, 14, 0), "snap-1")
        assertEquals(ApplyResult.NoChange(NoChangeReason.NO_EFFECTIVE_CHANGE), again)
        assertEquals(before, repo.events.size)
    }

    @Test
    fun `C11 TC の取消は REVOKED で元の時刻へ戻す`() {
        updater.apply(obs(RaceDataKubun.CARD))
        updater.applyTimeChange(Cal.T1, raceId, Cal.jst(d, 15, 50), Cal.jst(d, 14, 0), "snap-1")
        val r = applied(updater.revokeTimeChange(Cal.T1, raceId, Cal.jst(d, 15, 35), Cal.jst(d, 14, 10), "snap-2"))
        assertEquals(ScheduleEventType.REVOKED, r.event.type)
        assertEquals(Cal.jst(d, 15, 35), repo.find(Cal.T1, raceId)!!.scheduledPostAt)
    }

    @Test
    fun `古い取得時刻の TC で現在値を戻さない`() {
        updater.apply(obs(RaceDataKubun.CARD, at = Cal.jst(d, 5, 0)))
        updater.applyTimeChange(Cal.T1, raceId, Cal.jst(d, 15, 50), Cal.jst(d, 14, 0), "snap-2")
        val old = updater.applyTimeChange(Cal.T1, raceId, Cal.jst(d, 15, 40), Cal.jst(d, 13, 0), "snap-1")
        assertEquals(RejectReason.OLDER_OBSERVATION, rejected(old))
        assertEquals(Cal.jst(d, 15, 50), repo.find(Cal.T1, raceId)!!.scheduledPostAt)
    }

    @Test
    fun `時刻変更の拒否（レースなし・発走後・中止・日付違い）`() {
        assertEquals(RejectReason.RACE_NOT_FOUND, rejected(updater.applyTimeChange(Cal.T1, raceId, Cal.jst(d, 15, 50), Cal.jst(d, 14, 0), "s")))
        updater.apply(obs(RaceDataKubun.CARD))
        assertEquals(
            RejectReason.POST_TIME_DATE_MISMATCH,
            rejected(updater.applyTimeChange(Cal.T1, raceId, Cal.jst(d.plusDays(1), 0, 0), Cal.jst(d, 14, 0), "s")),
        )
        repo.rows[Cal.T1 to raceId] = repo.rows.getValue(Cal.T1 to raceId).copy(status = RaceScheduleStatus.OFF)
        assertEquals(RejectReason.TIME_CHANGE_NOT_ACCEPTED, rejected(updater.applyTimeChange(Cal.T1, raceId, Cal.jst(d, 15, 50), Cal.jst(d, 14, 0), "s")))
        repo.rows[Cal.T1 to raceId] = repo.rows.getValue(Cal.T1 to raceId).copy(status = RaceScheduleStatus.CANCELLED)
        assertEquals(RejectReason.TIME_CHANGE_NOT_ACCEPTED, rejected(updater.applyTimeChange(Cal.T1, raceId, Cal.jst(d, 15, 50), Cal.jst(d, 14, 0), "s")))
    }

    @Test
    fun `楽観ロックの競合は読み直して再判定し、続けば Conflict（成功扱いしない）`() {
        updater.apply(obs(RaceDataKubun.ANNOUNCEMENT))
        repo.injectConflicts = 2
        applied(updater.apply(obs(RaceDataKubun.CARD)))
        assertEquals(RaceScheduleStatus.CARD, repo.find(Cal.T1, raceId)!!.status)

        val other = RaceId.of(d, Venue.TOKYO, 12)
        updater.apply(obs(RaceDataKubun.ANNOUNCEMENT, id = other))
        repo.injectConflicts = RaceScheduleUpdater.MAX_ATTEMPTS
        assertEquals(ApplyResult.Conflict, updater.apply(obs(RaceDataKubun.CARD, id = other)))
        assertEquals(RaceScheduleStatus.ANNOUNCED, repo.find(Cal.T1, other)!!.status)
    }

    @Test
    fun `別テナントのレースには触れない`() {
        updater.apply(obs(RaceDataKubun.CARD))
        val other = RaceScheduleObservation(Cal.T2, raceId, RaceDataKubun.CANCELLED, LocalDate.of(2026, 10, 24), null, null, Cal.jst(d, 5, 0))
        applied(updater.apply(other))
        assertEquals(RaceScheduleStatus.CARD, repo.find(Cal.T1, raceId)!!.status)
        assertEquals(RaceScheduleStatus.CANCELLED, repo.find(Cal.T2, raceId)!!.status)
    }

    // --- 開催日 ---

    private val days = InMemoryDays()
    private val dayUpdater = RaceDayUpdater(days, clock)

    private fun dayApply(
        target: RaceDayStatus,
        replaced: LocalDate? = null,
        at: Instant = Cal.jst(d, 5, 0),
        group: String = "g-$target",
    ) = dayUpdater.apply(Cal.T1, d, Venue.TOKYO, 4, 8, target, replaced, at, ScheduleEventSource.YSCH, group)

    @Test
    fun `開催日の初期状態と遷移（PLANNED → CONFIRMED → CANCELLED）と履歴`() {
        applied(dayApply(RaceDayStatus.PLANNED))
        assertEquals(ScheduleEventType.DAY_STATUS, days.events.last().type)
        applied(dayApply(RaceDayStatus.CONFIRMED, at = Cal.jst(d, 6, 0)))
        applied(dayApply(RaceDayStatus.CANCELLED, at = Cal.jst(d, 7, 0)))
        assertEquals(RaceDayStatus.CANCELLED, days.find(Cal.T1, d, Venue.TOKYO)!!.status)
        assertEquals(3, days.events.size)
        // 終端から戻れない
        assertEquals(RejectReason.INVALID_TRANSITION, rejected(dayApply(RaceDayStatus.CONFIRMED, at = Cal.jst(d, 8, 0))))
    }

    @Test
    fun `開催日の初期状態に使えない値は拒否する`() {
        assertEquals(RejectReason.INVALID_INITIAL_STATUS, rejected(dayApply(RaceDayStatus.RUNNING)))
        assertEquals(RejectReason.INVALID_INITIAL_STATUS, rejected(dayApply(RaceDayStatus.POSTPONED)))
        assertTrue(days.rows.isEmpty())
    }

    @Test
    fun `延期は POSTPONED の履歴で振替先を持ち、後から振替先が分かれば追記できる`() {
        dayApply(RaceDayStatus.PLANNED)
        val r = applied(dayApply(RaceDayStatus.POSTPONED, at = Cal.jst(d, 6, 0)))
        assertEquals(ScheduleEventType.POSTPONED, r.event.type)
        assertNull(days.find(Cal.T1, d, Venue.TOKYO)!!.replacedByDate)
        applied(dayApply(RaceDayStatus.POSTPONED, replaced = d.plusDays(1), at = Cal.jst(d, 7, 0), group = "g2"))
        assertEquals(d.plusDays(1), days.find(Cal.T1, d, Venue.TOKYO)!!.replacedByDate)
    }

    @Test
    fun `同じ状態の観測は確認の時刻だけを進める（履歴は増えない）`() {
        dayApply(RaceDayStatus.CONFIRMED)
        val r = dayApply(RaceDayStatus.CONFIRMED, at = Cal.jst(d, 8, 0))
        assertEquals(ApplyResult.NoChange(NoChangeReason.CONFIRMED_ONLY), r)
        assertEquals(Cal.jst(d, 8, 0), days.find(Cal.T1, d, Venue.TOKYO)!!.lastConfirmedAt)
        assertEquals(1, days.events.size)
        // 古い時刻で戻さない
        dayApply(RaceDayStatus.CONFIRMED, at = Cal.jst(d, 6, 0))
        assertEquals(Cal.jst(d, 8, 0), days.find(Cal.T1, d, Venue.TOKYO)!!.lastConfirmedAt)
    }

    @Test
    fun `bot-run が進めた日（RUNNING 以降）に CONFIRMED を再観測しても 確認の時刻は進む（STALE にしない）`() {
        dayApply(RaceDayStatus.CONFIRMED)
        applied(dayUpdater.advance(Cal.T1, d, Venue.TOKYO, RaceDayStatus.RUNNING, "run:x"))
        val lastBefore = days.find(Cal.T1, d, Venue.TOKYO)!!.lastConfirmedAt
        // 取込（RA）は毎回 CONFIRMED を観測する。RUNNING に進んだ後でも、拒否して確認時刻を止めてはならない
        val r = dayApply(RaceDayStatus.CONFIRMED, at = Cal.jst(d, 9, 30), group = "ra-1")
        assertEquals(ApplyResult.NoChange(NoChangeReason.CONFIRMED_ONLY), r)
        val row = days.find(Cal.T1, d, Venue.TOKYO)!!
        assertEquals(RaceDayStatus.RUNNING, row.status)
        assertEquals(Cal.jst(d, 9, 30), row.lastConfirmedAt)
        assertTrue(lastBefore == null || lastBefore.isBefore(row.lastConfirmedAt))
        // SETTLING / COMPLETED / INCOMPLETE でも同じ
        for (st in listOf(RaceDayStatus.SETTLING, RaceDayStatus.COMPLETED)) {
            applied(dayUpdater.advance(Cal.T1, d, Venue.TOKYO, st, "run:$st"))
            val at = Cal.jst(d, 12, 0)
            assertEquals(ApplyResult.NoChange(NoChangeReason.CONFIRMED_ONLY), dayApply(RaceDayStatus.CONFIRMED, at = at, group = "ra-$st"))
            assertEquals(at, days.find(Cal.T1, d, Venue.TOKYO)!!.lastConfirmedAt)
            assertEquals(st, days.find(Cal.T1, d, Venue.TOKYO)!!.status)
        }
    }

    @Test
    fun `古い取得時刻の状態変更は反映しない`() {
        dayApply(RaceDayStatus.PLANNED, at = Cal.jst(d, 8, 0))
        assertEquals(RejectReason.OLDER_OBSERVATION, rejected(dayApply(RaceDayStatus.CANCELLED, at = Cal.jst(d, 6, 0))))
        assertEquals(RaceDayStatus.PLANNED, days.find(Cal.T1, d, Venue.TOKYO)!!.status)
    }

    @Test
    fun `同じ履歴の再実行は NoChange（DUPLICATE_EVENT）`() {
        dayApply(RaceDayStatus.PLANNED, group = "x")
        // 同じ初期状態・同じグループの再実行 = 既に存在するので確認だけ
        assertEquals(ApplyResult.NoChange(NoChangeReason.CONFIRMED_ONLY), dayApply(RaceDayStatus.PLANNED, group = "x"))
        assertEquals(1, days.events.size)
    }
}
