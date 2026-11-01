package jp.jrabot.domain.botrun

import jp.jrabot.domain.bot.BotRunRepository
import jp.jrabot.domain.calendar.CalendarSettings
import jp.jrabot.domain.calendar.RaceDayReader
import jp.jrabot.domain.calendar.RaceDayStatus
import jp.jrabot.domain.race.RaceScheduleReader
import jp.jrabot.domain.race.RaceScheduleStatus
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Clock
import java.time.LocalDate

enum class ReconcileOutcome {
    /** 結果がそろっていたので開催日を COMPLETED にした（bot-run が締切までに終えられなかった日の回復を含む）。 */
    COMPLETED,

    /** 締切を過ぎても結果がそろわないので開催日を INCOMPLETE にした。 */
    MARKED_INCOMPLETE,

    /** まだ結果を待てる、または INCOMPLETE のまま結果が届いていない。変更なし。 */
    WAITING,

    /** bot-run が実行中なので触らない（排他。bot-run が自分で進める）。 */
    SKIPPED_BOT_RUN_ACTIVE,
}

data class DayReconcile(
    val date: LocalDate,
    val outcome: ReconcileOutcome,
    /** 結果がそろっていない（中止を除く）レース数。 */
    val unsettled: Int,
)

data class ReconcileReport(
    val tenantId: TenantId,
    val days: List<DayReconcile>,
) {
    val completed: Int get() = days.count { it.outcome == ReconcileOutcome.COMPLETED }
}

/**
 * `--task=reconcile`（T3.9）。bot-run が結果待ちの締切までに終えられなかった日（INCOMPLETE）・異常終了で RUNNING/SETTLING のまま残った日を、**取り込み済みの結果から回復する**。
 * 対象は基準日とその前 [LOOKBACK_DAYS] 日。各日について: bot-run が実行中なら触らない → 中止を除く全レースの結果（[ResultAvailability]）がそろっていれば COMPLETED、
 * そろっていなくて締切（最終発走 + `settleDeadline`）を過ぎていれば INCOMPLETE、それ以外は何もしない。状態は遷移表を通して履歴付きで進め、**何度実行しても同じ結果**になる（冪等）。
 * 通知は送らない（買い目の通知の再送はしない: D-18）。
 */
class ReconcileService(
    private val clock: Clock,
    private val days: RaceDayReader,
    private val schedules: RaceScheduleReader,
    private val availability: ResultAvailability,
    private val advancer: RaceDayAdvancer,
    private val runs: BotRunRepository,
    private val settings: CalendarSettings,
) {
    fun reconcile(
        tenantId: TenantId,
        anchor: LocalDate,
    ): ReconcileReport {
        val out = mutableListOf<DayReconcile>()
        for (offset in LOOKBACK_DAYS downTo 0) {
            val date = anchor.minusDays(offset.toLong())
            if (days.listByDate(tenantId, date).none { it.status in RECONCILABLE }) continue
            out += reconcileDay(tenantId, date)
        }
        return ReconcileReport(tenantId, out)
    }

    private fun reconcileDay(
        tenantId: TenantId,
        date: LocalDate,
    ): DayReconcile {
        if (runs.findActive(tenantId, date) != null) return DayReconcile(date, ReconcileOutcome.SKIPPED_BOT_RUN_ACTIVE, 0)
        val races = schedules.listByDate(tenantId, date).filter { it.status != RaceScheduleStatus.CANCELLED && it.status != RaceScheduleStatus.DELETED }
        if (races.isEmpty()) return DayReconcile(date, ReconcileOutcome.WAITING, 0)
        val unsettled = races.count { r -> r.scheduledPostAt == null || availability.state(tenantId, r.raceId) != ResultState.COMPLETE }
        val key = "reconcile:$date:${clock.now().toEpochMilli() / 60_000}"
        if (unsettled == 0) {
            advancer.advance(tenantId, date, RaceDayStatus.SETTLING, key)
            advancer.advance(tenantId, date, RaceDayStatus.COMPLETED, key)
            return DayReconcile(date, ReconcileOutcome.COMPLETED, 0)
        }
        val lastPost = races.mapNotNull { it.scheduledPostAt }.maxOrNull()
        val pastDeadline = lastPost != null && clock.now().isAfter(lastPost.plus(settings.watch.settleDeadline))
        val current = days.listByDate(tenantId, date).map { it.status }
        if (pastDeadline && current.any { it == RaceDayStatus.RUNNING || it == RaceDayStatus.SETTLING }) {
            advancer.advance(tenantId, date, RaceDayStatus.SETTLING, key)
            advancer.advance(tenantId, date, RaceDayStatus.INCOMPLETE, key)
            return DayReconcile(date, ReconcileOutcome.MARKED_INCOMPLETE, unsettled)
        }
        return DayReconcile(date, ReconcileOutcome.WAITING, unsettled)
    }

    companion object {
        const val LOOKBACK_DAYS = 7
        private val RECONCILABLE = setOf(RaceDayStatus.RUNNING, RaceDayStatus.SETTLING, RaceDayStatus.INCOMPLETE)
    }
}
