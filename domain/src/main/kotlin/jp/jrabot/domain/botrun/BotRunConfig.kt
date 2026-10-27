package jp.jrabot.domain.botrun

import jp.jrabot.domain.bot.LeaseSettings
import jp.jrabot.domain.calendar.CalendarSettings
import jp.jrabot.domain.decision.WinDecisionConfig
import jp.jrabot.domain.ml.pipeline.ProbabilityModelType
import java.time.Duration

/**
 * bot-run の設定（T3.8）。**運用値の既定を持たない**: 監視ウィンドウ・リース・サイクル間隔・オッズの鮮度・最大稼働時間・判定の閾値は
 * 設計の案はあっても運用値は未確定（D-016・D-122・T1.20）なので、呼び出し側（環境変数の読込み・テスト）が明示的に渡す。
 *
 * @property polling 評価サイクルの間隔。発走までの残り時間で変える（旧 `get_polling_interval`: 30 分超 far / 5 分超 mid / それ以下 near）。
 * @property snapshotHistoryLimit 急落・人気急上昇の判定に使う直近スナップショットの件数（旧 `snapshot_history_limit`、旧の既定は 3）。
 * @property snapshot 評価・通知のタイミング（旧 `main_snapshot_minutes` / `extra_snapshot_minutes`）。
 * @property maxOddsAge 単勝・組み合わせオッズの鮮度の上限。観測時刻がこれより古いオッズでは**通知しない**（RACE_CALENDAR_DESIGN §6.3）。ちょうどは新鮮。
 * @property maxRuntime 1 回の bot-run の最大稼働時間（暴走防止。契約の上限は 14 時間）。
 * @property staleClaimAfter 起動時に `recoverStaleClaims` が回収する、claim したまま結果が記録されていない Signal の古さ（送信に掛かり得る最長の時間より長く）。
 * @property maxConsecutiveUnknown 運用モードが UNKNOWN の連続回数の上限（CONTRACTS §5.3 は 3）。
 * @property maxConsecutiveCycleFailures 連続して失敗したサイクルの上限（DB 障害など）。超えたら終了する。
 * @property mlModelType ML を使う種別。null は ML 無効（市場確率。旧 `ml_cfg.enabled=false`）。パイプラインは **legacy 固定**（v2 の本番有効化は未承認）。
 * @property decision 判定の設定（旧 `evaluate_odds` の引数。数値の意味を変えない）。`fixes`（未承認の数値変更）は [WinDecisionConfig] が拒否する。
 */
data class BotRunConfig(
    val lease: LeaseSettings,
    val calendar: CalendarSettings,
    val polling: PollingIntervals,
    val snapshotHistoryLimit: Int,
    val snapshot: SnapshotPolicy,
    val maxOddsAge: Duration,
    val maxRuntime: Duration,
    val staleClaimAfter: Duration,
    val maxConsecutiveUnknown: Int,
    val maxConsecutiveCycleFailures: Int,
    val mlModelType: ProbabilityModelType?,
    val decision: WinDecisionConfig,
) {
    init {
        require(snapshotHistoryLimit in 2..100) { "snapshotHistoryLimit は 2〜100（急落の判定は 2 件以上で働く）" }
        require(maxOddsAge.isPositive) { "maxOddsAge は正: $maxOddsAge" }
        require(maxRuntime.isPositive && maxRuntime <= MAX_RUNTIME_LIMIT) { "maxRuntime は正で $MAX_RUNTIME_LIMIT 以下: $maxRuntime" }
        require(staleClaimAfter.isPositive) { "staleClaimAfter は正: $staleClaimAfter" }
        require(maxConsecutiveUnknown >= 1) { "maxConsecutiveUnknown は 1 以上" }
        require(maxConsecutiveCycleFailures >= 1) { "maxConsecutiveCycleFailures は 1 以上" }
    }

    companion object {
        /** 契約（RACE_CALENDAR_DESIGN §6.3）の最大稼働の上限。 */
        val MAX_RUNTIME_LIMIT: Duration = Duration.ofHours(14)
    }
}

/**
 * ポーリング間隔（旧 `get_polling_interval`）。旧の分岐をそのまま移す: 発走まで **30 分超 = far、5 分超 = mid、それ以下 = near**。
 * **間隔の運用値は未確定**（旧の既定は 300/60/30 秒だが、運用値は T1.20・ユーザー確認の対象）なので既定を持たない。
 */
data class PollingIntervals(
    val far: Duration,
    val mid: Duration,
    val near: Duration,
) {
    init {
        require(far.isPositive && mid.isPositive && near.isPositive) { "ポーリング間隔は正" }
    }

    fun forMinutesToPost(minutesToPost: Double): Duration =
        when {
            minutesToPost > 30.0 -> far
            minutesToPost > 5.0 -> mid
            else -> near
        }
}

/**
 * 評価・通知のタイミング。[minutesBeforePost] の各値について、**発走までの残りがその分数以下になった最初のサイクルで 1 回だけ**評価して通知する
 * （旧は『各タイミングで評価し、レースごとにベスト 1 件のみ通知』。再起動をまたいだ重複を防ぐため、発火済みを checkpoint に記録する: 新しい仕様、未承認の運用値）。
 *
 * [tightenNear] は旧の『5 分前の場合はフィルタを強化』（`drop_threshold × 0.7`、単勝・ワイド・三連複の `*_max_over_market × 0.8`（正の値のみ））を再現するか。
 * 旧の判定 `current_snapshot_minutes <= 5.0` は、**タイミングを大きい順に並べて最初に `残り <= タイミング` を満たすもの**（= 最大のタイミング）で決まる
 * 旧の癖をそのまま移す（最大のタイミングが 5 分以下のときだけ強化。タイミングが [30, 10, 5] なら強化は働かない）。**既定を持たない**（運用値は未確定）。
 */
data class SnapshotPolicy(
    val minutesBeforePost: List<Double>,
    val tightenNear: Boolean,
) {
    init {
        require(minutesBeforePost.isNotEmpty()) { "評価のタイミングが空" }
        require(minutesBeforePost.all { it.isFinite() && it > 0.0 && it <= 1440.0 }) { "評価のタイミングは 0 超 1440 分以下" }
        require(minutesBeforePost.toSet().size == minutesBeforePost.size) { "評価のタイミングが重複している" }
    }

    /** 大きい順。 */
    val descending: List<Double> = minutesBeforePost.sortedDescending()

    /** 旧 `current_snapshot_minutes`: 大きい順に最初に `残り <= タイミング` を満たすもの。無ければ null（まだ評価の時間帯でない）。 */
    fun legacyCurrent(minutesToPost: Double): Double? = descending.firstOrNull { minutesToPost <= it }

    fun isNear(minutesToPost: Double): Boolean = tightenNear && (legacyCurrent(minutesToPost)?.let { it <= NEAR_MINUTES } ?: false)

    companion object {
        const val NEAR_MINUTES = 5.0
    }
}
