package jp.jrabot.domain.botrun

import jp.jrabot.domain.race.RaceId

/** 全レースが発走を過ぎた（または中止）時点での、レース 1 つの評価の結果（run の終了状態を決めるための分類）。 */
enum class RaceDisposition {
    /** 設定した評価のタイミングをすべて発火した（評価した結果、買い目・通知対象が 0 件でも。これは正常）。 */
    EVALUATED,

    /** 中止（元の予定または手動）。評価しないのが正しい。 */
    CANCELLED,

    /** この run が一度も監視していない（最初に見たときには既に発走後。遅い起動・再開）。運用契約上の評価対象にしない。 */
    NOT_OBSERVED,

    /** 予定を信頼できず（確認が古い・発走時刻不明）、必要なタイミングを逃した。**評価機会の喪失**。 */
    LOST_CALENDAR,

    /** 監視はしていたが、オッズが無い・古いなどで必要なタイミングを逃した。**評価機会の喪失**。 */
    LOST_DATA,

    /** checkpoint の不整合で通知を止め（二重通知を避けるため）、必要なタイミングを逃した。**評価機会の喪失**。 */
    LOST_QUARANTINED,
}

/**
 * 『全レースが OFF または中止』になったときの分類。**時刻が過ぎたことだけで正常終了にしない**（C1/C2）。
 * [lost] が 1 つでもあれば FAILED・exit 1（D-19。回復できない評価機会の喪失。契約上の一時障害〈exit 75〉とは別）。
 * 評価した結果の買い目 0 件、正常な見送り、開催なし・中止、運用契約上の評価対象にしないレース〈[RaceDisposition.NOT_OBSERVED]〉は正常。
 */
data class CompletionAssessment(
    val dispositions: Map<RaceId, RaceDisposition>,
) {
    val calendarLost: List<RaceId> get() = of(RaceDisposition.LOST_CALENDAR)
    val dataLost: List<RaceId> get() = of(RaceDisposition.LOST_DATA)
    val quarantinedLost: List<RaceId> get() = of(RaceDisposition.LOST_QUARANTINED)
    val lost: List<RaceId> get() = calendarLost + dataLost + quarantinedLost

    private fun of(d: RaceDisposition) = dispositions.filterValues { it == d }.keys.toList()

    companion object {
        /**
         * @param previous 直前のサイクル終了時の checkpoint のレース別の記録（今サイクルの段階で上書きする前のもの。DB の確定記録を合わせてある）
         * @param stages 今サイクルの各レースの段階
         * @param timings 設定した評価のタイミング（発走の何分前か）
         * @param quarantined checkpoint を信頼できず通知を止めたレース
         */
        fun assess(
            previous: Map<RaceId, RaceCheckpoint>,
            stages: Map<RaceId, RaceStage>,
            timings: List<Double>,
            quarantined: Set<RaceId>,
        ): CompletionAssessment {
            val out = LinkedHashMap<RaceId, RaceDisposition>()
            for ((id, now) in stages) {
                val prev = previous[id]
                val allFired = prev != null && timings.all { it in prev.firedMinutes }
                out[id] =
                    when {
                        now == RaceStage.CANCELLED || prev?.stage == RaceStage.CANCELLED -> RaceDisposition.CANCELLED
                        allFired -> RaceDisposition.EVALUATED
                        id in quarantined -> RaceDisposition.LOST_QUARANTINED
                        prev == null -> RaceDisposition.NOT_OBSERVED
                        prev.stage == RaceStage.UNTRUSTED || prev.stage == RaceStage.UNKNOWN_TIME -> RaceDisposition.LOST_CALENDAR
                        else -> RaceDisposition.LOST_DATA
                    }
            }
            return CompletionAssessment(out)
        }
    }
}
