package jp.jrabot.domain.botrun

import jp.jrabot.domain.calendar.EffectiveDay
import jp.jrabot.domain.calendar.WatchWindow
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.race.RaceScheduleStatus
import java.time.Instant

/** 1 サイクルの計画。[stages] は有効な全レースの段階、[monitoring] は今サイクルで評価するレース（発走の早い順）。 */
data class MonitorPlan(
    val stages: Map<RaceId, RaceStage>,
    val monitoring: List<RaceId>,
    /** 全レースが終わった（すべて [RaceStage.OFF] または [RaceStage.CANCELLED]。1 レース以上ある）。 */
    val allDone: Boolean,
    /** 監視の開始時刻（最初の発走 − watchStart）。ウィンドウが作れないときは null。 */
    val watchStartsAt: Instant?,
    /** 発走時刻の不明・信頼できない予定で、まだ判断できないレースがある。 */
    val unresolved: List<RaceId>,
)

/**
 * 有効な開催日（[EffectiveDay]。手動補正を適用済み）と現在時刻から、今サイクルの監視対象を決める**純粋な関数**。
 * **曜日・祝日は使わない**。発走時刻が不明のレースには時刻を補完せず、評価も通知もしない（[RaceStage.UNKNOWN_TIME]）。
 * 毎サイクル再導出するので、発走時刻の変更・中止・手動補正は次のサイクルに反映される（再起動しない: RACE_CALENDAR_DESIGN §6.2）。
 *
 * | 条件（上から） | 段階 |
 * |---|---|
 * | 有効な状態が中止 | CANCELLED |
 * | 発走時刻が不明 | UNKNOWN_TIME |
 * | 発走時刻 ≤ 現在（ちょうども OFF: asOf は発走より前でなければならない） | OFF |
 * | 起動ウィンドウに入らない（確認が古い場など） | UNTRUSTED |
 * | 現在 < 監視の開始時刻 | PENDING |
 * | それ以外 | MONITORING |
 *
 * 状態が中止・OFF の判定を信頼できない予定より先に置くのは、発走を過ぎたレースを『信頼できない』のまま待ち続けないため。
 */
object RaceMonitorPlanner {
    fun plan(
        day: EffectiveDay,
        window: WatchWindow?,
        now: Instant,
    ): MonitorPlan {
        val stages = LinkedHashMap<RaceId, RaceStage>()
        val monitoring = mutableListOf<Pair<Instant, RaceId>>()
        val unresolved = mutableListOf<RaceId>()
        for (race in day.races.sortedBy { it.raceId }) {
            val postAt = race.postAt
            val stage =
                when {
                    race.status == RaceScheduleStatus.CANCELLED -> RaceStage.CANCELLED
                    postAt == null -> RaceStage.UNKNOWN_TIME
                    !now.isBefore(postAt) -> RaceStage.OFF
                    !race.inWindow || window == null -> RaceStage.UNTRUSTED
                    now.isBefore(window.watchStartsAt) -> RaceStage.PENDING
                    else -> RaceStage.MONITORING
                }
            stages[race.raceId] = stage
            if (stage == RaceStage.MONITORING) monitoring += (postAt!! to race.raceId)
            if (stage == RaceStage.UNKNOWN_TIME || stage == RaceStage.UNTRUSTED) unresolved += race.raceId
        }
        val done = stages.isNotEmpty() && stages.values.all { it == RaceStage.OFF || it == RaceStage.CANCELLED }
        return MonitorPlan(
            stages = stages,
            monitoring = monitoring.sortedWith(compareBy({ it.first }, { it.second })).map { it.second },
            allDone = done,
            watchStartsAt = window?.watchStartsAt,
            unresolved = unresolved,
        )
    }
}
