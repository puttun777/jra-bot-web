package jp.jrabot.domain.calendar

import jp.jrabot.domain.bot.BotRun
import jp.jrabot.domain.bot.BotRunReader
import jp.jrabot.domain.bot.BotRunState
import jp.jrabot.domain.bot.BotRunTrigger
import jp.jrabot.domain.bot.JobLaunchRequest
import jp.jrabot.domain.bot.JobLauncher
import jp.jrabot.domain.bot.LaunchResult
import jp.jrabot.domain.bot.LaunchableJob
import jp.jrabot.domain.bot.RunId
import jp.jrabot.domain.ops.OpsMode
import jp.jrabot.domain.race.RaceScheduleStatus
import jp.jrabot.domain.race.Venue
import jp.jrabot.domain.tenant.TenantId
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarCheckPolicyTest {
    private val d = Cal.DATE
    private val races = Cal.races()
    private val confirmed = Cal.derive(Cal.jst(d, 7, 0), listOf(Cal.day(Venue.TOKYO, d)), races)
    private val first = Cal.jst(d, 9, 55)
    private val last = Cal.jst(d, 9, 55).plus(Duration.ofMinutes(275))
    private val opens = first - Duration.ofMinutes(130)
    private val closes = last + Duration.ofMinutes(90)

    private fun decide(
        now: Instant,
        day: EffectiveDay = confirmed,
        mode: OpsMode = OpsMode.NORMAL,
        active: ActiveRun = ActiveRun.None,
        evaluated: LocalDate = d,
        today: LocalDate = d,
    ) = CalendarCheckPolicy.decide(now, evaluated, today, mode, day, active, Cal.WATCH)

    @Test
    fun `起動条件がすべて揃うと LAUNCH_ELIGIBLE`() {
        val r = decide(Cal.jst(d, 8, 0))
        assertEquals(CalendarCheckReason.LAUNCH_ELIGIBLE, r.reason)
        assertEquals(CheckOutcome.LAUNCH, r.outcome)
        assertEquals(DayVerdict.CONFIRMED, r.verdict)
        assertEquals(WatchWindow(first, last, Cal.WATCH), r.window)
    }

    @Test
    fun `C01 窓の境界（開始 1 秒前・ちょうど・1 秒後、終了 1 秒前・ちょうど・1 秒後）`() {
        assertEquals(CalendarCheckReason.BEFORE_WATCH_WINDOW, decide(opens.minusSeconds(1)).reason)
        assertEquals(CalendarCheckReason.LAUNCH_ELIGIBLE, decide(opens).reason)
        assertEquals(CalendarCheckReason.LAUNCH_ELIGIBLE, decide(opens.plusSeconds(1)).reason)
        assertEquals(CalendarCheckReason.LAUNCH_ELIGIBLE, decide(closes.minusSeconds(1)).reason)
        assertEquals(CalendarCheckReason.LAUNCH_ELIGIBLE, decide(closes).reason)
        assertEquals(CalendarCheckReason.AFTER_SETTLE_DEADLINE, decide(closes.plusSeconds(1)).reason)
    }

    @Test
    fun `運用モードが NORMAL 以外なら起動しない（確定した非 NORMAL は正常、UNKNOWN は要確認）`() {
        for (m in listOf(OpsMode.STARTING, OpsMode.STOPPING, OpsMode.STOPPED)) {
            val r = decide(Cal.jst(d, 8, 0), mode = m)
            assertEquals(CalendarCheckReason.OPS_MODE_NOT_NORMAL, r.reason)
            assertEquals(CheckOutcome.NO_LAUNCH, r.outcome)
        }
        val u = decide(Cal.jst(d, 8, 0), mode = OpsMode.UNKNOWN)
        assertEquals(CalendarCheckReason.OPS_MODE_UNKNOWN, u.reason)
        assertEquals(CheckOutcome.ATTENTION, u.outcome)
    }

    @Test
    fun `今日でない日は起動しない`() {
        assertEquals(CalendarCheckReason.DATE_NOT_TODAY, decide(Cal.jst(d, 8, 0), today = d.plusDays(1)).reason)
    }

    @Test
    fun `C04 開催なしの確認は NOT_A_RACE_DAY（正常）、不明・古い・未確認は要確認`() {
        val noRace = Cal.derive(Cal.jst(d, 7, 0), emptyList(), emptyList(), fact = ScheduleFetchFact(d, Cal.jst(d, 6, 30)))
        decide(Cal.jst(d, 8, 0), day = noRace).also {
            assertEquals(CalendarCheckReason.NOT_A_RACE_DAY, it.reason)
            assertEquals(CheckOutcome.NO_LAUNCH, it.outcome)
        }
        val unknown = Cal.derive(Cal.jst(d, 7, 0), emptyList(), emptyList())
        decide(Cal.jst(d, 8, 0), day = unknown).also {
            assertEquals(CalendarCheckReason.SCHEDULE_UNKNOWN, it.reason)
            assertEquals(CheckOutcome.ATTENTION, it.outcome)
        }
        val stale = Cal.derive(Cal.jst(d, 9, 0), listOf(Cal.day(Venue.TOKYO, d, confirmedAt = Cal.jst(d, 6, 0))), races)
        assertEquals(CalendarCheckReason.SCHEDULE_STALE, decide(Cal.jst(d, 9, 0), day = stale).reason)
        val unconfirmed = Cal.derive(Cal.jst(d, 7, 0), listOf(Cal.day(Venue.TOKYO, d, RaceDayStatus.PLANNED)), emptyList())
        decide(Cal.jst(d, 8, 0), day = unconfirmed).also {
            assertEquals(CalendarCheckReason.SCHEDULE_UNCONFIRMED, it.reason)
            assertEquals(CheckOutcome.ATTENTION, it.outcome)
        }
    }

    @Test
    fun `中止・延期は起動しない（延期先不明は要確認）`() {
        val cancelled = Cal.derive(Cal.jst(d, 7, 0), listOf(Cal.day(Venue.TOKYO, d)), Cal.races(status = RaceScheduleStatus.CANCELLED))
        assertEquals(CalendarCheckReason.DAY_CANCELLED, decide(Cal.jst(d, 8, 0), day = cancelled).reason)
        val postponed = Cal.derive(Cal.jst(d, 7, 0), listOf(Cal.day(Venue.TOKYO, d, RaceDayStatus.POSTPONED, replacedBy = d.plusDays(1))), emptyList())
        assertEquals(CalendarCheckReason.DAY_POSTPONED, decide(Cal.jst(d, 8, 0), day = postponed).reason)
        val unknownTarget = Cal.derive(Cal.jst(d, 7, 0), listOf(Cal.day(Venue.TOKYO, d, RaceDayStatus.POSTPONED)), emptyList())
        val r = decide(Cal.jst(d, 8, 0), day = unknownTarget)
        assertEquals(CalendarCheckReason.POSTPONED_TARGET_UNKNOWN, r.reason)
        assertEquals(CheckOutcome.ATTENTION, r.outcome)
    }

    @Test
    fun `C19 DENY_RUN は ALLOW_RUN があっても起動を拒否する`() {
        val day =
            Cal.derive(
                Cal.jst(d, 7, 0),
                listOf(Cal.day(Venue.TOKYO, d)),
                races,
                listOf(Cal.override(OverrideAction.ALLOW_RUN), Cal.override(OverrideAction.DENY_RUN)),
            )
        assertEquals(CalendarCheckReason.DENIED_BY_OVERRIDE, decide(Cal.jst(d, 8, 0), day = day).reason)
    }

    @Test
    fun `ALLOW_RUN は不明・古い・未確認・開催なしを覆すが、時刻とウィンドウは必要`() {
        val allow = Cal.override(OverrideAction.ALLOW_RUN)
        val withTimes = listOf(Cal.race(no = 1))
        for (
        day in
        listOf(
            Cal.derive(Cal.jst(d, 9, 0), listOf(Cal.day(Venue.TOKYO, d, confirmedAt = Cal.jst(d, 1, 0))), withTimes, listOf(allow)),
        )
        ) {
            assertEquals(DayVerdict.STALE, day.verdict)
            assertEquals(CalendarCheckReason.LAUNCH_ELIGIBLE, decide(Cal.jst(d, 8, 30), day = day).reason)
            assertEquals(CalendarCheckReason.BEFORE_WATCH_WINDOW, decide(Cal.jst(d, 5, 0), day = day).reason)
        }
        // 時刻が無ければ許可されても起動できない
        val noTimes = Cal.derive(Cal.jst(d, 7, 0), emptyList(), emptyList(), listOf(allow))
        assertEquals(DayVerdict.UNKNOWN, noTimes.verdict)
        val r = decide(Cal.jst(d, 8, 0), day = noTimes)
        assertEquals(CalendarCheckReason.NO_POST_TIME, r.reason)
        assertEquals(CheckOutcome.ATTENTION, r.outcome)
    }

    @Test
    fun `ALLOW_RUN は手動の開催なし・中止・延期と、元の予定の中止を覆さない`() {
        val allow = Cal.override(OverrideAction.ALLOW_RUN)
        val manualNoRace = Cal.derive(Cal.jst(d, 7, 0), listOf(Cal.day(Venue.TOKYO, d)), races, listOf(allow, Cal.override(OverrideAction.MARK_NO_RACE)))
        assertEquals(CalendarCheckReason.NOT_A_RACE_DAY, decide(Cal.jst(d, 8, 0), day = manualNoRace).reason)
        val manualCancel = Cal.derive(Cal.jst(d, 7, 0), listOf(Cal.day(Venue.TOKYO, d)), races, listOf(allow, Cal.override(OverrideAction.CANCEL)))
        assertEquals(CalendarCheckReason.DAY_CANCELLED, decide(Cal.jst(d, 8, 0), day = manualCancel).reason)
        val sourceCancel = Cal.derive(Cal.jst(d, 7, 0), listOf(Cal.day(Venue.TOKYO, d)), Cal.races(status = RaceScheduleStatus.CANCELLED), listOf(allow))
        assertEquals(CalendarCheckReason.DAY_CANCELLED, decide(Cal.jst(d, 8, 0), day = sourceCancel).reason)
    }

    @Test
    fun `C17 手動の開催日と発走時刻で起動できる`() {
        val day =
            Cal.derive(
                Cal.jst(d, 7, 0),
                emptyList(),
                listOf(Cal.race(no = 1, status = RaceScheduleStatus.ANNOUNCED, post = null)),
                listOf(Cal.override(OverrideAction.MARK_RACE_DAY), Cal.override(OverrideAction.SET_POST_TIME, raceNo = 1)),
            )
        assertEquals(CalendarCheckReason.LAUNCH_ELIGIBLE, decide(Cal.jst(d, 8, 0), day = day).reason)
    }

    @Test
    fun `C10 発走時刻が変更されると窓が動く`() {
        val later = races.map { if (it.raceId.raceNo == 1) it.copy(scheduledPostAt = Cal.jst(d, 12, 0)) else it }
        val day = Cal.derive(Cal.jst(d, 7, 0), listOf(Cal.day(Venue.TOKYO, d)), later)
        assertEquals(Cal.jst(d, 10, 20), day.firstPost)
        // 変更前の窓では起動できた 07:45 が、変更後（最初の発走が後ろへ）は早すぎる
        assertEquals(CalendarCheckReason.LAUNCH_ELIGIBLE, decide(Cal.jst(d, 7, 45)).reason)
        assertEquals(CalendarCheckReason.BEFORE_WATCH_WINDOW, decide(Cal.jst(d, 7, 45), day = day).reason)
    }

    @Test
    fun `C20 有効なリースの実行があれば ALREADY_ACTIVE、期限切れのリースは起動を妨げない`() {
        val now = Cal.jst(d, 8, 0)
        val rid = RunId.of(UUID.randomUUID())
        assertEquals(CalendarCheckReason.ALREADY_ACTIVE, decide(now, active = ActiveRun.LeaseValid(rid, now.plusSeconds(1))).reason)
        val expired = decide(now, active = ActiveRun.LeaseExpired(rid, now))
        assertEquals(CalendarCheckReason.LAUNCH_ELIGIBLE, expired.reason)
        assertTrue(expired.expiredLeasePresent)
        assertFalse(decide(now).expiredLeasePresent)
    }

    @Test
    fun `完了済み・要 reconcile の日は起動しない`() {
        val done = Cal.derive(Cal.jst(d, 20, 0), listOf(Cal.day(Venue.TOKYO, d, RaceDayStatus.COMPLETED)), Cal.races(status = RaceScheduleStatus.SETTLED))
        assertEquals(CalendarCheckReason.ALREADY_COMPLETED, decide(Cal.jst(d, 14, 0), day = done).reason)
        val inc =
            Cal.derive(
                Cal.jst(d, 20, 0),
                listOf(Cal.day(Venue.TOKYO, d, RaceDayStatus.INCOMPLETE)),
                Cal.races(status = RaceScheduleStatus.RESULT_PENDING),
            )
        assertEquals(CalendarCheckReason.NEEDS_RECONCILE, decide(Cal.jst(d, 14, 0), day = inc).reason)
    }

    @Test
    fun `すべての理由に分類があり、LAUNCH は 1 つだけ`() {
        assertEquals(listOf(CalendarCheckReason.LAUNCH_ELIGIBLE), CalendarCheckReason.entries.filter { it.outcome == CheckOutcome.LAUNCH })
        val attention = CalendarCheckReason.entries.filter { it.outcome == CheckOutcome.ATTENTION }.toSet()
        assertEquals(
            setOf(
                CalendarCheckReason.OPS_MODE_UNKNOWN,
                CalendarCheckReason.SCHEDULE_UNKNOWN,
                CalendarCheckReason.SCHEDULE_STALE,
                CalendarCheckReason.SCHEDULE_UNCONFIRMED,
                CalendarCheckReason.POSTPONED_TARGET_UNKNOWN,
                CalendarCheckReason.NO_POST_TIME,
            ),
            attention,
        )
    }

    @Test
    fun `JST 0 時の前後の日付境界で今日が切り替わる（コンテナが UTC でも）`() {
        val clock = FixedClock(Instant.parse("2026-10-24T14:59:59Z")) // JST 23:59:59 (10-24)
        assertEquals(LocalDate.of(2026, 10, 24), clock.today())
        clock.instant = Instant.parse("2026-10-24T15:00:00Z") // JST 00:00:00 (10-25)
        assertEquals(LocalDate.of(2026, 10, 25), clock.today())
        clock.instant = Instant.parse("2026-10-24T15:00:01Z")
        assertEquals(LocalDate.of(2026, 10, 25), clock.today())
    }
}

class CalendarCheckServiceTest {
    private val d = Cal.DATE
    private val clock = FixedClock(Cal.jst(d, 8, 0))
    private val mode = FixedMode(OpsMode.NORMAL)
    private val days = InMemoryDays()
    private val schedules = InMemorySchedules()
    private val overrides = InMemoryOverrides()
    private var fact: ScheduleFetchFact? = null
    private var activeRun: BotRun? = null
    private val launches = mutableListOf<JobLaunchRequest>()
    private var launchResult: LaunchResult = LaunchResult.Launched("exec-1")
    private var modeAfterFirstCall: OpsMode? = null

    private val runs =
        object : BotRunReader {
            override fun find(
                tenantId: TenantId,
                runId: RunId,
            ) = null

            override fun findActive(
                tenantId: TenantId,
                runDate: LocalDate,
            ) = activeRun?.takeIf { it.tenantId == tenantId && it.runDate == runDate }

            override fun listByDate(
                tenantId: TenantId,
                runDate: LocalDate,
            ) = emptyList<BotRun>()

            override fun listRecent(
                tenantId: TenantId,
                limit: Int,
            ) = emptyList<BotRun>()
        }
    private val launcher =
        object : JobLauncher {
            override fun launch(request: JobLaunchRequest): LaunchResult {
                launches += request
                return launchResult
            }
        }

    private fun service() =
        CalendarCheckService(clock, mode, days, schedules, overrides, { t, date -> fact?.takeIf { it.date == date } }, runs, launcher, Cal.SETTINGS)

    private fun seedRaceDay() {
        days.rows[Triple(Cal.T1, d, Venue.TOKYO)] = Cal.day(Venue.TOKYO, d)
        Cal.races().forEach { schedules.rows[Cal.T1 to it.raceId] = it }
    }

    @Test
    fun `開催日で窓の中なら bot-run の起動を要求し、日付と起動理由を渡す`() {
        seedRaceDay()
        val r = service().check(Cal.T1)
        assertEquals(CalendarCheckReason.LAUNCH_ELIGIBLE, r.decision.reason)
        assertEquals(LaunchResult.Launched("exec-1"), r.launch)
        val req = launches.single()
        assertEquals(LaunchableJob.BOT_RUN, req.job)
        assertEquals(Cal.T1, req.tenantId)
        assertEquals(mapOf("date" to "2026-10-24", "trigger" to "SCHEDULER"), req.params)
    }

    @Test
    fun `判定しただけでは launch は null（起動を要求していない）`() {
        seedRaceDay()
        clock.instant = Cal.jst(d, 4, 0)
        val r = service().check(Cal.T1)
        assertEquals(CalendarCheckReason.BEFORE_WATCH_WINDOW, r.decision.reason)
        assertNull(r.launch)
        assertTrue(launches.isEmpty())
    }

    @Test
    fun `運用モードが NORMAL 以外なら DB を読まず起動もしない`() {
        seedRaceDay()
        for (m in listOf(OpsMode.STOPPED, OpsMode.STARTING, OpsMode.STOPPING)) {
            mode.mode = m
            val r = service().check(Cal.T1)
            assertEquals(CalendarCheckReason.OPS_MODE_NOT_NORMAL, r.decision.reason)
            assertNull(r.day)
            assertNull(r.launch)
        }
        mode.mode = OpsMode.UNKNOWN
        assertEquals(CalendarCheckReason.OPS_MODE_UNKNOWN, service().check(Cal.T1).decision.reason)
        assertTrue(launches.isEmpty())
    }

    @Test
    fun `C05 STOPPED から NORMAL に戻っても、非開催日（開催なしの確認）では起動しない`() {
        fact = ScheduleFetchFact(d, Cal.jst(d, 7, 30))
        mode.mode = OpsMode.STOPPED
        assertEquals(CalendarCheckReason.OPS_MODE_NOT_NORMAL, service().check(Cal.T1).decision.reason)
        mode.mode = OpsMode.NORMAL
        val r = service().check(Cal.T1)
        assertEquals(CalendarCheckReason.NOT_A_RACE_DAY, r.decision.reason)
        assertNull(r.launch)
        assertTrue(launches.isEmpty())
    }

    @Test
    fun `起動を要求する直前にモードが STOPPED になっていたら要求しない`() {
        seedRaceDay()
        val flipping =
            object : jp.jrabot.domain.ops.ModeProvider {
                var n = 0

                override fun current(): OpsMode = if (n++ == 0) OpsMode.NORMAL else OpsMode.STOPPED
            }
        val svc = CalendarCheckService(clock, flipping, days, schedules, overrides, NoScheduleFetchFacts, runs, launcher, Cal.SETTINGS)
        val r = svc.check(Cal.T1)
        assertEquals(CalendarCheckReason.OPS_MODE_NOT_NORMAL, r.decision.reason)
        assertNull(r.launch)
        assertTrue(launches.isEmpty())
    }

    @Test
    fun `C20 実行中の bot-run があれば要求しない、連続実行しても同じ判断（冪等）`() {
        seedRaceDay()
        activeRun = Cal.run(expiresAt = clock.instant.plusSeconds(120))
        repeat(3) { assertEquals(CalendarCheckReason.ALREADY_ACTIVE, service().check(Cal.T1).decision.reason) }
        assertTrue(launches.isEmpty())
    }

    @Test
    fun `連続実行で起動要求が重複し得る（要求は冪等ではない。二重処理は bot-run のリースが止める）`() {
        seedRaceDay()
        service().check(Cal.T1)
        service().check(Cal.T1)
        assertEquals(2, launches.size)
    }

    @Test
    fun `期限切れのリースの実行が残っていても起動を要求する（新しい実行が奪取する）`() {
        seedRaceDay()
        activeRun = Cal.run(expiresAt = clock.instant)
        val r = service().check(Cal.T1)
        assertEquals(CalendarCheckReason.LAUNCH_ELIGIBLE, r.decision.reason)
        assertTrue(r.decision.expiredLeasePresent)
        assertEquals(1, launches.size)
    }

    @Test
    fun `起動基盤の失敗は結果として返る（起動したことにしない）`() {
        seedRaceDay()
        launchResult = LaunchResult.Failed("quota", transient = true)
        val r = service().check(Cal.T1)
        assertEquals(CalendarCheckReason.LAUNCH_ELIGIBLE, r.decision.reason)
        assertEquals(LaunchResult.Failed("quota", true), r.launch)
    }

    @Test
    fun `date を指定した診断では今日でなければ起動しない`() {
        seedRaceDay()
        val r = service().check(Cal.T1, d.minusDays(1))
        assertEquals(CalendarCheckReason.DATE_NOT_TODAY, r.decision.reason)
        assertTrue(launches.isEmpty())
    }

    @Test
    fun `別テナントのデータでは起動しない`() {
        seedRaceDay()
        val r = service().check(TenantId.of(2))
        assertEquals(CalendarCheckReason.SCHEDULE_UNKNOWN, r.decision.reason)
        assertTrue(launches.isEmpty())
    }

    @Test
    fun `C14 朝に RA が無く PLANNED のまま（UNCONFIRMED）は起動せず、RA 到着後に起動する`() {
        days.rows[Triple(Cal.T1, d, Venue.TOKYO)] = Cal.day(Venue.TOKYO, d, RaceDayStatus.PLANNED)
        val before = service().check(Cal.T1)
        assertEquals(CalendarCheckReason.SCHEDULE_UNCONFIRMED, before.decision.reason)
        assertTrue(launches.isEmpty())
        seedRaceDay()
        assertEquals(CalendarCheckReason.LAUNCH_ELIGIBLE, service().check(Cal.T1).decision.reason)
        assertEquals(1, launches.size)
    }

    @Test
    fun `読み取りだけで予定・補正を変更しない`() {
        seedRaceDay()
        val rows = schedules.rows.toMap()
        val dayRows = days.rows.toMap()
        service().check(Cal.T1)
        assertEquals(rows, schedules.rows)
        assertEquals(dayRows, days.rows)
        assertTrue(schedules.events.isEmpty() && days.events.isEmpty())
    }

    @Test
    fun `空の予定でも本番配線（取得の事実なし）では不明のまま`() {
        val svc = CalendarCheckService(clock, mode, days, schedules, overrides, NoScheduleFetchFacts, runs, launcher, Cal.SETTINGS)
        assertEquals(CalendarCheckReason.SCHEDULE_UNKNOWN, svc.check(Cal.T1).decision.reason)
    }

    @Test
    fun `手動起動の検証 C19 は非開催日で NOT_A_RACE_DAY、ALLOW_RUN で許可、DENY_RUN は常に拒否`() {
        val unknown = Cal.derive(clock.instant, emptyList(), emptyList())
        assertEquals(ManualStartDecision.Denied(ManualStartDenial.NOT_A_RACE_DAY, DayVerdict.UNKNOWN), ManualStartPolicy.check(BotRunTrigger.MANUAL, unknown))
        val allowed = Cal.derive(clock.instant, emptyList(), emptyList(), listOf(Cal.override(OverrideAction.ALLOW_RUN)))
        assertEquals(ManualStartDecision.Allowed(viaAllowRun = true), ManualStartPolicy.check(BotRunTrigger.MANUAL, allowed))
        val denied = Cal.derive(clock.instant, emptyList(), emptyList(), listOf(Cal.override(OverrideAction.ALLOW_RUN), Cal.override(OverrideAction.DENY_RUN)))
        for (t in BotRunTrigger.entries) assertIs<ManualStartDecision.Denied>(ManualStartPolicy.check(t, denied))
        assertEquals(ManualStartDecision.Allowed(false), ManualStartPolicy.check(BotRunTrigger.BACKFILL, unknown))
        assertEquals(ManualStartDecision.Allowed(false), ManualStartPolicy.check(BotRunTrigger.RECONCILE, unknown))
        seedRaceDay()
        val confirmed = Cal.derive(clock.instant, days.listByDate(Cal.T1, d), schedules.listByDate(Cal.T1, d))
        assertEquals(ManualStartDecision.Allowed(false), ManualStartPolicy.check(BotRunTrigger.MANUAL, confirmed))
        val manualNoRace =
            Cal.derive(
                clock.instant,
                days.listByDate(Cal.T1, d),
                schedules.listByDate(Cal.T1, d),
                listOf(Cal.override(OverrideAction.MARK_NO_RACE), Cal.override(OverrideAction.ALLOW_RUN)),
            )
        assertIs<ManualStartDecision.Denied>(ManualStartPolicy.check(BotRunTrigger.MANUAL, manualNoRace))
    }
}

/** 手動補正のメモリ実装（読取りだけ使う）。 */
class InMemoryOverrides : CalendarOverrideRepository {
    val rows = mutableListOf<CalendarOverride>()

    override fun find(
        tenantId: TenantId,
        id: Long,
    ) = rows.firstOrNull { it.tenantId == tenantId && it.id == id }

    override fun listByDate(
        tenantId: TenantId,
        date: LocalDate,
    ) = rows.filter { it.tenantId == tenantId && it.raceDate == date }

    override fun create(
        override: CalendarOverride,
        event: ScheduleEvent,
    ): OverrideCreateResult = throw UnsupportedOperationException()

    override fun revoke(
        tenantId: TenantId,
        id: Long,
        revokedBy: String,
        revokedAt: Instant,
        event: ScheduleEvent,
    ): OverrideRevokeResult = throw UnsupportedOperationException()
}
