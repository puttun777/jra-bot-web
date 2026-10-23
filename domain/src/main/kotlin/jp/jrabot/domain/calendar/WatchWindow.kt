package jp.jrabot.domain.calendar

import java.time.Duration
import java.time.Instant

/**
 * 監視ウィンドウの設定（RACE_CALENDAR_DESIGN §6.2）。**既定値を持たない**: 参照元は `watch_start` が 240 分と 120 分で食い違っており
 * （D-016）、運用値の確定は T3.8 でユーザー確認の対象。呼び出し側（bot の環境変数・テスト）が明示的に渡す。
 *
 * @property watchStart 最初の発走の何分前から監視するか。
 * @property prestart 監視の開始より**さらに前**に起動する余裕（Job の起動時間の見込み。0 でもよい）。起動してよい時刻は `最初の発走 - watchStart - prestart`。
 *   設計の「first_post - watch_start (+ 起動余裕 prestart)」の読み方の 1 つで、一次の確認はユーザー（D-122）。
 * @property settleDeadline 最後の発走の何分後まで起動してよいか（それ以降は reconcile の担当）。
 */
data class WatchSettings(
    val watchStart: Duration,
    val prestart: Duration,
    val settleDeadline: Duration,
) {
    init {
        require(watchStart.isPositive) { "watchStart は正: $watchStart" }
        require(!prestart.isNegative) { "prestart は 0 以上: $prestart" }
        require(settleDeadline.isPositive) { "settleDeadline は正: $settleDeadline" }
    }
}

enum class WindowPhase { BEFORE, OPEN, AFTER }

/**
 * 1 日の起動ウィンドウ。[firstPost]・[lastPost] は有効なレースの最初と最後の発走時刻（UTC の瞬間。JST への変換はここでは不要）。
 * 境界は**両端を含む**（`launchOpensAt <= now <= settleDeadlineAt` が [WindowPhase.OPEN]。設計の `now >= … かつ now <= …` どおり）。
 */
data class WatchWindow(
    val firstPost: Instant,
    val lastPost: Instant,
    val settings: WatchSettings,
) {
    init {
        require(!lastPost.isBefore(firstPost)) { "lastPost が firstPost より前" }
    }

    /** bot-run を起動してよくなる時刻。 */
    val launchOpensAt: Instant get() = firstPost - settings.watchStart - settings.prestart

    /** bot-run が監視を始める時刻。 */
    val watchStartsAt: Instant get() = firstPost - settings.watchStart

    /** この時刻を過ぎたら新しく起動しない。 */
    val settleDeadlineAt: Instant get() = lastPost + settings.settleDeadline

    fun phase(now: Instant): WindowPhase =
        when {
            now < launchOpensAt -> WindowPhase.BEFORE
            now > settleDeadlineAt -> WindowPhase.AFTER
            else -> WindowPhase.OPEN
        }
}
