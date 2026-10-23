package jp.jrabot.domain.calendar

import jp.jrabot.domain.race.RaceScheduleStatus
import jp.jrabot.domain.race.RaceScheduleStatus.ANNOUNCED
import jp.jrabot.domain.race.RaceScheduleStatus.CANCELLED
import jp.jrabot.domain.race.RaceScheduleStatus.CARD
import jp.jrabot.domain.race.RaceScheduleStatus.DELETED
import jp.jrabot.domain.race.RaceScheduleStatus.FINALIZED
import jp.jrabot.domain.race.RaceScheduleStatus.MONITORING
import jp.jrabot.domain.race.RaceScheduleStatus.OFF
import jp.jrabot.domain.race.RaceScheduleStatus.RESULT_PARTIAL
import jp.jrabot.domain.race.RaceScheduleStatus.RESULT_PENDING
import jp.jrabot.domain.race.RaceScheduleStatus.SETTLED

/**
 * レース（`race_schedule.status`）の状態遷移表（RACE_CALENDAR_DESIGN §2.2）。許可する辺だけを列挙し、それ以外は拒否する。
 *
 * - 同じ状態への「遷移」は状態の変更ではない（[canTransition] は false）。発走時刻の変更など、状態を変えない更新は別に扱う。
 * - [CANCELLED]・[DELETED]・[FINALIZED] は終端。ここから戻る辺は無い（中止・削除されたレースを新しい版が来ても復活させない）。
 * - 設計の図は `CARD → DELETED` だけだが、出走馬名表の段階（[ANNOUNCED]）の提供ミス削除も同じ扱いにする
 *   （一次仕様で未確認の拡張。発走前の状態に限る。MONITORING 以降の削除は手動補正）。
 * - 時刻で進む遷移（[MONITORING]・[OFF]）や成績・決済で進む遷移（RESULT_* ・[SETTLED]・[FINALIZED]）は bot-run（T3.8）・決済（T3.9）が呼ぶ。
 *   T3.3 はこの表の検証までで、これらの遷移を DB へ反映する経路は持たない。
 */
object RaceScheduleTransitions {
    private val allowed: Map<RaceScheduleStatus, Set<RaceScheduleStatus>> =
        mapOf(
            ANNOUNCED to setOf(CARD, CANCELLED, DELETED),
            CARD to setOf(MONITORING, CANCELLED, DELETED),
            MONITORING to setOf(OFF, CANCELLED),
            OFF to setOf(RESULT_PENDING),
            RESULT_PENDING to setOf(RESULT_PARTIAL, SETTLED),
            RESULT_PARTIAL to setOf(SETTLED),
            SETTLED to setOf(FINALIZED),
            FINALIZED to emptySet(),
            CANCELLED to emptySet(),
            DELETED to emptySet(),
        )

    fun canTransition(
        from: RaceScheduleStatus,
        to: RaceScheduleStatus,
    ): Boolean = to in allowed.getValue(from)

    fun isTerminal(status: RaceScheduleStatus): Boolean = allowed.getValue(status).isEmpty()

    /** 発走時刻の変更（TC）を受け付けてよい状態。発走前（[OFF] より前）で、中止・削除されていないもの。 */
    fun acceptsPostTimeChange(status: RaceScheduleStatus): Boolean = status == ANNOUNCED || status == CARD || status == MONITORING

    /** 開催のレースとして有効（中止・削除ではない）。 */
    fun isActive(status: RaceScheduleStatus): Boolean = status != CANCELLED && status != DELETED

    /** 発走時刻を確定させる出馬表（区分 2）以降の状態。出走馬名表（[ANNOUNCED]）は時刻を採用しない（一次仕様未確認）。 */
    fun hasCard(status: RaceScheduleStatus): Boolean = isActive(status) && status != ANNOUNCED
}

/** 開催日（`race_day.status`）。RACE_CALENDAR_DESIGN §2.1。 */
enum class RaceDayStatus {
    UNPLANNED,
    PLANNED,
    UNCONFIRMED,
    CONFIRMED,
    RUNNING,
    SETTLING,
    COMPLETED,
    INCOMPLETE,
    CANCELLED,
    POSTPONED,
}

/**
 * 開催日の状態遷移表（§2.1）。初期状態は [RaceDayStatus.PLANNED]（YSCH に日程あり）・[RaceDayStatus.UNPLANNED]・[RaceDayStatus.CONFIRMED]（RA が先に来た場合）。
 * [RaceDayStatus.POSTPONED]・[RaceDayStatus.CANCELLED]・[RaceDayStatus.COMPLETED] は終端（振替先の開催は別の日の別の行）。
 */
object RaceDayTransitions {
    private val allowed: Map<RaceDayStatus, Set<RaceDayStatus>> =
        mapOf(
            RaceDayStatus.UNPLANNED to setOf(RaceDayStatus.PLANNED, RaceDayStatus.CONFIRMED),
            RaceDayStatus.PLANNED to
                setOf(RaceDayStatus.CONFIRMED, RaceDayStatus.CANCELLED, RaceDayStatus.POSTPONED, RaceDayStatus.UNCONFIRMED),
            RaceDayStatus.UNCONFIRMED to setOf(RaceDayStatus.CONFIRMED, RaceDayStatus.CANCELLED),
            RaceDayStatus.CONFIRMED to setOf(RaceDayStatus.CANCELLED, RaceDayStatus.POSTPONED, RaceDayStatus.RUNNING),
            RaceDayStatus.RUNNING to setOf(RaceDayStatus.SETTLING),
            RaceDayStatus.SETTLING to setOf(RaceDayStatus.COMPLETED, RaceDayStatus.INCOMPLETE),
            RaceDayStatus.INCOMPLETE to setOf(RaceDayStatus.COMPLETED),
            RaceDayStatus.COMPLETED to emptySet(),
            RaceDayStatus.CANCELLED to emptySet(),
            RaceDayStatus.POSTPONED to emptySet(),
        )

    val initialStatuses: Set<RaceDayStatus> = setOf(RaceDayStatus.PLANNED, RaceDayStatus.UNPLANNED, RaceDayStatus.CONFIRMED)

    fun canTransition(
        from: RaceDayStatus,
        to: RaceDayStatus,
    ): Boolean = to in allowed.getValue(from)

    fun isTerminal(status: RaceDayStatus): Boolean = allowed.getValue(status).isEmpty()
}
