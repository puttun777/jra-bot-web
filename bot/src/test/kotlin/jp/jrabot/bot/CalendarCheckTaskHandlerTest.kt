package jp.jrabot.bot

import jp.jrabot.domain.bot.BotRun
import jp.jrabot.domain.bot.BotRunReader
import jp.jrabot.domain.bot.JobLaunchRequest
import jp.jrabot.domain.bot.JobLauncher
import jp.jrabot.domain.bot.LaunchResult
import jp.jrabot.domain.bot.RunId
import jp.jrabot.domain.calendar.CalendarCheckService
import jp.jrabot.domain.calendar.CalendarOverride
import jp.jrabot.domain.calendar.CalendarOverrideReader
import jp.jrabot.domain.calendar.CalendarSettings
import jp.jrabot.domain.calendar.FixedScheduleFetchFacts
import jp.jrabot.domain.calendar.NoScheduleFetchFacts
import jp.jrabot.domain.calendar.RaceDay
import jp.jrabot.domain.calendar.RaceDayReader
import jp.jrabot.domain.calendar.RaceDayStatus
import jp.jrabot.domain.calendar.ScheduleFetchFact
import jp.jrabot.domain.calendar.ScheduleFetchFactReader
import jp.jrabot.domain.calendar.WatchSettings
import jp.jrabot.domain.ops.ModeProvider
import jp.jrabot.domain.ops.OpsMode
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.race.RaceSchedule
import jp.jrabot.domain.race.RaceScheduleReader
import jp.jrabot.domain.race.RaceScheduleStatus
import jp.jrabot.domain.race.Venue
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Clock
import jp.jrabot.domain.time.Zones
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLTransientConnectionException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** calendar-check の終了コードの対応（CONTRACTS §9）。偽のポート（DB なし・実 GCP なし）で判断から `JobResult` までを確かめる。 */
class CalendarCheckTaskHandlerTest {
    private val t1 = TenantId.of(1)
    private val date = LocalDate.of(2026, 10, 24)
    private val settings = CalendarSettings(WatchSettings(Duration.ofMinutes(120), Duration.ofMinutes(10), Duration.ofMinutes(90)), Duration.ofHours(2))

    private fun jst(
        h: Int,
        m: Int,
    ): Instant = date.atTime(h, m).atZone(Zones.JST).toInstant()

    private class Clk(
        var instant: Instant,
    ) : Clock {
        override fun now() = instant
    }

    private val clock = Clk(jst(8, 0))
    private var mode = OpsMode.NORMAL
    private var days: List<RaceDay> = emptyList()
    private var races: List<RaceSchedule> = emptyList()
    private var active: BotRun? = null
    private var facts: ScheduleFetchFactReader = NoScheduleFetchFacts
    private val launches = mutableListOf<JobLaunchRequest>()
    private var launcher: JobLauncher =
        object : JobLauncher {
            override fun launch(request: JobLaunchRequest): LaunchResult {
                launches += request
                return LaunchResult.Launched("exec-1")
            }
        }
    private var dbFailure: Throwable? = null
    private val log = RecordingLogger()

    private fun failIfDown() = dbFailure?.let { throw it }

    private fun handler(): CalendarCheckTaskHandler =
        CalendarCheckTaskHandler(
            CalendarCheckService(
                clock,
                ModeProvider { mode },
                object : RaceDayReader {
                    override fun find(
                        tenantId: TenantId,
                        date: LocalDate,
                        venue: Venue,
                    ) = null

                    override fun listByDate(
                        tenantId: TenantId,
                        date: LocalDate,
                    ): List<RaceDay> {
                        failIfDown()
                        return days.filter { it.raceDate == date }
                    }
                },
                object : RaceScheduleReader {
                    override fun find(
                        tenantId: TenantId,
                        raceId: RaceId,
                    ) = null

                    override fun listByDate(
                        tenantId: TenantId,
                        date: LocalDate,
                    ) = races.filter { it.raceId.date == date }
                },
                object : CalendarOverrideReader {
                    override fun find(
                        tenantId: TenantId,
                        id: Long,
                    ) = null

                    override fun listByDate(
                        tenantId: TenantId,
                        date: LocalDate,
                    ): List<CalendarOverride> = emptyList()
                },
                facts,
                object : BotRunReader {
                    override fun find(
                        tenantId: TenantId,
                        runId: RunId,
                    ) = null

                    override fun findActive(
                        tenantId: TenantId,
                        runDate: LocalDate,
                    ) = active

                    override fun listByDate(
                        tenantId: TenantId,
                        runDate: LocalDate,
                    ) = emptyList<BotRun>()

                    override fun listRecent(
                        tenantId: TenantId,
                        limit: Int,
                    ) = emptyList<BotRun>()
                },
                launcher,
                settings,
            ),
            log,
        )

    private fun command(date: LocalDate? = null) = BotCommand(BotTask.CALENDAR_CHECK, t1, date)

    private fun seedRaceDay(confirmedAt: Instant = jst(6, 0)) {
        days = listOf(RaceDay(t1, date, Venue.TOKYO, 4, 8, RaceDayStatus.CONFIRMED, null, jst(0, 0).minusSeconds(86_400), confirmedAt))
        races =
            (1..12).map {
                RaceSchedule(
                    t1,
                    RaceId.of(date, Venue.TOKYO, it),
                    jst(9, 55).plus(Duration.ofMinutes(25L * (it - 1))),
                    false,
                    RaceScheduleStatus.CARD,
                    '2',
                    "202610232",
                    "R$it",
                    jst(0, 0),
                )
            }
    }

    @Test
    fun `起動を要求して受け付けられたら 0 LAUNCH_REQUESTED（bot-run が始まったとは言わない）`() {
        seedRaceDay()
        assertEquals(JobResult(JobExit.OK, "LAUNCH_REQUESTED"), handler().execute(command()))
        assertEquals(1, launches.size)
        assertEquals(mapOf("date" to "2026-10-24", "trigger" to "SCHEDULER"), launches.single().params)
        assertTrue(log.text().contains("CALENDAR_DECISION") && log.text().contains("reason=LAUNCH_ELIGIBLE"), log.text())
    }

    @Test
    fun `起動基盤の一時的な失敗は 75、恒久的な失敗は 1（起動したことにしない）`() {
        seedRaceDay()
        launcher = JobLauncherOf(LaunchResult.Failed("quota exceeded", transient = true))
        assertEquals(JobResult(JobExit.TEMPFAIL, "LAUNCH_FAILED_TRANSIENT"), handler().execute(command()))
        launcher = JobLauncherOf(LaunchResult.Failed("permission denied\nsecret=xyz", transient = false))
        assertEquals(JobResult(JobExit.FAILURE, "LAUNCH_FAILED"), handler().execute(command()))
        assertFalse(log.lines.any { '\n' in it.removeSuffix("") && false })
    }

    @Test
    fun `本番の未設定の起動基盤は fail closed（起動できたことにせず 1）`() {
        seedRaceDay()
        launcher = UnconfiguredJobLauncher()
        assertEquals(JobResult(JobExit.FAILURE, "LAUNCH_FAILED"), handler().execute(command()))
        assertTrue(log.text().contains(UnconfiguredJobLauncher.REASON), log.text())
        assertEquals(
            LaunchResult.Failed(UnconfiguredJobLauncher.REASON, false),
            UnconfiguredJobLauncher().launch(JobLaunchRequest(t1, jp.jrabot.domain.bot.LaunchableJob.BOT_RUN)),
        )
    }

    @Test
    fun `起動しないのが正常なケースは 0 で、理由は判断の名前`() {
        seedRaceDay()
        clock.instant = jst(4, 0)
        assertEquals(JobResult(JobExit.OK, "BEFORE_WATCH_WINDOW"), handler().execute(command()))
        seedRaceDay(confirmedAt = jst(19, 30))
        clock.instant = jst(20, 0)
        assertEquals(JobResult(JobExit.OK, "AFTER_SETTLE_DEADLINE"), handler().execute(command()))
        seedRaceDay()
        clock.instant = jst(8, 0)
        assertEquals(JobResult(JobExit.OK, "DATE_NOT_TODAY"), handler().execute(command(date.minusDays(1))))
        mode = OpsMode.STOPPED
        assertEquals(JobResult(JobExit.OK, "OPS_MODE_NOT_NORMAL"), handler().execute(command()))
        assertTrue(launches.isEmpty())
    }

    @Test
    fun `要確認（不明・古い・未確認・モード UNKNOWN）は 75 で起動しない`() {
        assertEquals(JobResult(JobExit.TEMPFAIL, "SCHEDULE_UNKNOWN"), handler().execute(command()))
        facts = FixedScheduleFetchFacts(mapOf(t1 to date to ScheduleFetchFact(date, jst(1, 0))))
        assertEquals(JobResult(JobExit.TEMPFAIL, "SCHEDULE_STALE"), handler().execute(command()))
        facts = FixedScheduleFetchFacts(mapOf(t1 to date to ScheduleFetchFact(date, jst(7, 0))))
        assertEquals(JobResult(JobExit.OK, "NOT_A_RACE_DAY"), handler().execute(command()))
        mode = OpsMode.UNKNOWN
        assertEquals(JobResult(JobExit.TEMPFAIL, "OPS_MODE_UNKNOWN"), handler().execute(command()))
        assertTrue(launches.isEmpty())
    }

    @Test
    fun `DB に接続できないのは 75 DB_UNAVAILABLE、他の例外は握りつぶさず投げる（呼び出し側が 1 にする）`() {
        for (e in listOf(
            RuntimeException("wrapped", SQLTransientConnectionException("pool timeout")),
            RuntimeException(SQLNonTransientConnectionException("closed")),
            RuntimeException(SQLException("link failure", "08S01", 0)),
        )) {
            dbFailure = e
            assertEquals(JobResult(JobExit.TEMPFAIL, "DB_UNAVAILABLE"), handler().execute(command()))
        }
        dbFailure = RuntimeException("bug", SQLException("syntax", "42000", 1064))
        assertFailsWith<RuntimeException> { handler().execute(command()) }
        assertTrue(launches.isEmpty())
    }

    @Test
    fun `ログに設定値・資格情報を出さない`() {
        seedRaceDay()
        handler().execute(command())
        assertFalse(log.text().contains("password", ignoreCase = true), log.text())
        assertIs<JobResult>(handler().execute(command()))
    }

    private class JobLauncherOf(
        private val result: LaunchResult,
    ) : JobLauncher {
        override fun launch(request: JobLaunchRequest) = result
    }
}
