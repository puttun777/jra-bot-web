package jp.jrabot.domain.botrun

import jp.jrabot.domain.calendar.EffectiveDay
import jp.jrabot.domain.calendar.EffectiveRace
import jp.jrabot.domain.calendar.RaceDay
import jp.jrabot.domain.calendar.RaceDayStatus
import jp.jrabot.domain.race.RaceScheduleStatus

/**
 * 結果待ち・reconcile が『結果が来るはずのレース』として数える範囲。**中止（元の予定・手動補正）・削除に加えて、開催日そのものが中止・延期になった場のレースを除く**
 * （場単位の中止にはレースごとの区分 9 が来ないことがあり、除かないと来ない結果を締切まで待ち続けてしまう）。
 */
object SettleScope {
    fun isCancelled(
        race: EffectiveRace,
        raceDays: List<RaceDay>,
    ): Boolean =
        race.status == RaceScheduleStatus.CANCELLED ||
            race.status == RaceScheduleStatus.DELETED ||
            raceDays.any { it.venue == race.raceId.venue && (it.status == RaceDayStatus.CANCELLED || it.status == RaceDayStatus.POSTPONED) }

    fun active(
        day: EffectiveDay,
        raceDays: List<RaceDay>,
    ): List<EffectiveRace> = day.races.filterNot { isCancelled(it, raceDays) }
}
