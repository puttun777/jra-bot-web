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
            // 途中の状態を飛ばさず順にたどる（CONFIRMED → RUNNING → SETTLING → 目的）。以前の更新が失敗して取り残された日も、次の呼出しで追いつく
            for (step in path(day.status, target)) {
                when (updater.advance(tenantId, date, day.venue, step, "$dedupKey:${day.venue.code}:$step")) {
                    is ApplyResult.Applied -> {
                        changed++
                    }

                    is ApplyResult.NoChange, is ApplyResult.Rejected -> {}

                    ApplyResult.Conflict -> {
                        throw IllegalStateException("race_day の更新が競合し続けた: ${day.venue.code}")
                    }
                }
            }
        }
        return changed
    }

    companion object {
        /** [from] から [target] までに通る状態（目的を含む）。到達できない・既に同じなら空。 */
        fun path(
            from: RaceDayStatus,
            target: RaceDayStatus,
        ): List<RaceDayStatus> {
            if (from == target) return emptyList()
            if (RaceDayTransitions.canTransition(from, target)) return listOf(target)
            val chain = listOf(RaceDayStatus.CONFIRMED, RaceDayStatus.RUNNING, RaceDayStatus.SETTLING)
            val at = chain.indexOf(from)
            val viaSettling = target == RaceDayStatus.COMPLETED || target == RaceDayStatus.INCOMPLETE
            if (at < 0 || !(target == RaceDayStatus.SETTLING || viaSettling)) return emptyList()
            return chain.drop(at + 1) + (if (viaSettling) listOf(target) else emptyList())
        }
    }
}
