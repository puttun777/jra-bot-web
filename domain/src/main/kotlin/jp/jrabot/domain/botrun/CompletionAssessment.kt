package jp.jrabot.domain.botrun

import jp.jrabot.domain.race.RaceId

/** 全レースが発走を過ぎた時点での、レース 1 つの評価の結果（run の終了状態を決めるための分類）。 */
enum class RaceDisposition {
    /** 設定した評価のタイミングをすべて発火した（評価した結果、通知対象が無かった場合を含む。これは正常）。 */
    EVALUATED,

    /** 中止（元の予定または手動）。評価しないのが正しい。 */
    CANCELLED,

    /** この run が一度も監視の対象として見ていない（最初に見たときには既に発走後。遅い起動・再開）。run の異常とは扱わない。 */
    NOT_OBSERVED,

    /** 発走時刻が不明、または確認が古く（UNTRUSTED）、必要な評価のタイミングを逃した。**予定を信頼できなかったための評価機会の喪失**。 */
    LOST_CALENDAR,

    /** 監視はしていたが、オッズが無い・古いなどで評価のタイミングの一部またはすべてを発火できなかった。 */
    LOST_DATA,

    /** checkpoint の欠損・破損で発火済みのタイミングを確認できず、二重通知を避けるため通知しなかった（[BotRunOrchestrator] の復元）。 */
    QUARANTINED,
}

/**
 * 『全レースが OFF または中止』になったときの分類。**時刻が過ぎたことだけで正常終了にしない**（C1/C2）:
 * 予定を信頼できなかったために評価できなかったレースが 1 つでもあれば異常（FAILED）、データ不足などで評価できなかったレースだけなら
 * 正常終了（SUCCEEDED）に警告を付ける。評価 0 件という事実だけでは異常にしない（開催が無い・中止・通知対象なしは別に扱う）。
 */
data class CompletionAssessment(
    val dispositions: Map<RaceId, RaceDisposition>,
) {
    val calendarLost: List<RaceId> get() = of(RaceDisposition.LOST_CALENDAR)
    val dataGaps: List<RaceId> get() = dispositions.filterValues { it == RaceDisposition.LOST_DATA || it == RaceDisposition.QUARANTINED }.keys.toList()

    private fun of(d: RaceDisposition) = dispositions.filterValues { it == d }.keys.toList()

    companion object {
        /**
         * @param previous 直前のサイクル終了時の checkpoint のレース別の記録（今サイクルの段階で上書きする前のもの）
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
                out[id] =
                    when {
                        now == RaceStage.CANCELLED || prev?.stage == RaceStage.CANCELLED -> RaceDisposition.CANCELLED
                        id in quarantined -> RaceDisposition.QUARANTINED
                        prev != null && timings.all { it in prev.firedMinutes } -> RaceDisposition.EVALUATED
                        prev == null -> RaceDisposition.NOT_OBSERVED
                        prev.stage == RaceStage.UNTRUSTED || prev.stage == RaceStage.UNKNOWN_TIME -> RaceDisposition.LOST_CALENDAR
                        else -> RaceDisposition.LOST_DATA
                    }
            }
            return CompletionAssessment(out)
        }
    }
}
