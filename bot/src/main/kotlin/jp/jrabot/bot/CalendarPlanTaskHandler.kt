package jp.jrabot.bot

import jp.jrabot.domain.calendar.CalendarOverrideReader
import jp.jrabot.domain.calendar.CalendarSettings
import jp.jrabot.domain.calendar.DayVerdict
import jp.jrabot.domain.calendar.EffectiveCalendar
import jp.jrabot.domain.calendar.EffectiveDay
import jp.jrabot.domain.calendar.RaceDayReader
import jp.jrabot.domain.calendar.ScheduleFetchFactReader
import jp.jrabot.domain.race.RaceScheduleReader
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Clock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.temporal.ChronoUnit

/**
 * `--task=calendar-plan`（D-283）。今日（JST）から [PlanArgs.days] 日先までの**有効な開催予定**（calendar-check と同じ導出: 元の予定 + 手動補正）を
 * JSON に書き出す。AWS ではホストがこれを S3 へ置き、EC2 を止めている間も制御（Lambda）が開催日の起動を計画できるようにする。
 *
 * - 読むだけ（DB に書かない・通知しない・Job を起動しない）。閲覧用の起動でも動いてよい（[jp.jrabot.domain.ops.Workload.ANY]）。
 * - 載せる日: 開催の可能性がある日（CONFIRMED・UNCONFIRMED・STALE）で、DENY_RUN が無い日。開催なし・中止・延期・データ無し（UNKNOWN）は載せない
 *   （UNKNOWN の日を自動で起動しない。必要なら本人が手動で開催日の起動を依頼する）。
 * - 書く値は日付・判定・進行・最初と最後の発走時刻（UTC）だけ（馬・オッズ・成績などの本文は書かない）。
 * - 書き方: 同じディレクトリの一時ファイル → 原子的な改名。終了コード 0（`CALENDAR_PLAN_WRITTEN`）。書けなければ 75（`CALENDAR_PLAN_IO_ERROR`）。
 */
class CalendarPlanTaskHandler(
    private val clock: Clock,
    private val days: RaceDayReader,
    private val schedules: RaceScheduleReader,
    private val overrides: CalendarOverrideReader,
    private val facts: ScheduleFetchFactReader,
    private val settings: CalendarSettings,
    private val log: Logger = LoggerFactory.getLogger(CalendarPlanTaskHandler::class.java),
) : BotTaskHandler {
    override fun execute(command: BotCommand): JobResult {
        val args = checkNotNull(command.plan) { "calendar-plan の引数が無い（BotArgsParser が検証しているはず）" }
        val entries = plan(command.tenantId, args.days)
        val json = render(command.tenantId, entries)
        return try {
            val dir = args.out.parent ?: return JobResult(JobExit.USAGE, "CALENDAR_PLAN_OUT_INVALID")
            val tmp = Files.createTempFile(dir, ".plan-", ".part")
            try {
                Files.writeString(tmp, json)
                Files.move(tmp, args.out, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(tmp)
            }
            log.info("CALENDAR_PLAN tenant={} days={} entries={}", command.tenantId.value, args.days, entries.size)
            JobResult(JobExit.OK, "CALENDAR_PLAN_WRITTEN")
        } catch (e: IOException) {
            log.error("CALENDAR_PLAN io-error error={}", e.javaClass.simpleName)
            JobResult(JobExit.TEMPFAIL, "CALENDAR_PLAN_IO_ERROR")
        }
    }

    internal fun plan(
        tenantId: TenantId,
        horizonDays: Int,
    ): List<EffectiveDay> {
        val now = clock.now()
        val today = clock.today()
        return (0 until horizonDays)
            .map { today.plusDays(it.toLong()) }
            .map { date ->
                EffectiveCalendar.derive(
                    tenantId,
                    date,
                    now,
                    settings,
                    days.listByDate(tenantId, date),
                    schedules.listByDate(tenantId, date),
                    overrides.listByDate(tenantId, date),
                    facts.latestSuccess(tenantId, date),
                )
            }.filter { it.verdict in PLANNABLE && !it.denyRun }
    }

    internal fun render(
        tenantId: TenantId,
        entries: List<EffectiveDay>,
    ): String {
        val rows =
            entries.joinToString(",") { d ->
                val first = d.firstPost?.truncatedTo(ChronoUnit.SECONDS)?.let { "\"$it\"" } ?: "null"
                val last = d.lastPost?.truncatedTo(ChronoUnit.SECONDS)?.let { "\"$it\"" } ?: "null"
                """{"date":"${d.date}","verdict":"${d.verdict.name}","basis":"${d.basis.name}","progress":"${d.progress.name}","first_post":$first,"last_post":$last}"""
            }
        val generated = clock.now().truncatedTo(ChronoUnit.SECONDS)
        return """{"schema_version":1,"tenant_id":${tenantId.value},"generated_at":"$generated","timezone":"Asia/Tokyo","days":[$rows]}""" + "\n"
    }

    companion object {
        val PLANNABLE = setOf(DayVerdict.CONFIRMED, DayVerdict.UNCONFIRMED, DayVerdict.STALE)
    }
}
