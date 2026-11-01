package jp.jrabot.domain.botrun

import jp.jrabot.domain.calendar.ApplyResult
import jp.jrabot.domain.calendar.RaceDayReader
import jp.jrabot.domain.calendar.RaceDayStatus
import jp.jrabot.domain.calendar.RaceDayTransitions
import jp.jrabot.domain.calendar.RaceDayUpdater
import jp.jrabot.domain.tenant.TenantId
import java.time.LocalDate

/**
 * 開催日（`race_day.status`）を RUNNING → SETTLING → COMPLETED / INCOMPLETE と進める（T3.9。履歴は `race_schedule_event` の DAY_STATUS に同じトランザクションで追記）。
 * 遷移表（[RaceDayTransitions]）に無い辺、既に同じ状態、行が無いものは**何もしない**（冪等。再起動・並行する reconcile と競合しても安全）。
 * 楽観ロックの競合が尽きたら例外（サイクルの失敗として再試行される）。
 */
fun interface RaceDayAdvancer {
    /** 戻り値: 実際に状態を変えた開催日（場）の数。 */
    fun advance(
        tenantId: TenantId,
        date: LocalDate,
        target: RaceDayStatus,
        dedupKey: String,
    ): Int

    companion object {
        val NONE = RaceDayAdvancer { _, _, _, _ -> 0 }
    }
}

class RaceDayAdvancerImpl(
    private val days: RaceDayReader,
    private val updater: RaceDayUpdater,
) : RaceDayAdvancer {
    override fun advance(
        tenantId: TenantId,
        date: LocalDate,
        target: RaceDayStatus,
        dedupKey: String,
    ): Int {
        var changed = 0
        for (day in days.listByDate(tenantId, date)) {
            if (day.status == target || !RaceDayTransitions.canTransition(day.status, target)) continue
            when (updater.advance(tenantId, date, day.venue, target, "$dedupKey:${day.venue.code}:$target")) {
                is ApplyResult.Applied -> {
                    changed++
                }

                is ApplyResult.NoChange, is ApplyResult.Rejected -> {}

                ApplyResult.Conflict -> {
                    throw IllegalStateException("race_day の更新が競合し続けた: ${day.venue.code}")
                }
            }
        }
        return changed
    }
}
