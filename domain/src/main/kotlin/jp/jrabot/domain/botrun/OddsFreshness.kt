package jp.jrabot.domain.botrun

import java.time.Duration
import java.time.Instant

/** オッズの鮮度の判定（RACE_CALENDAR_DESIGN §6.3 の『取得時刻が max_odds_age を超えたら通知しない』）。 */
enum class OddsFreshness {
    /** 観測時刻から [maxAge] 以内（ちょうども新鮮）。 */
    FRESH,

    /** [maxAge] を超えて古い。評価しても通知しない。 */
    STALE,

    /** 観測時刻が現在より未来（時計のずれ・データの異常）。信頼しない。 */
    FUTURE,

    /** オッズが無い。 */
    MISSING,
    ;

    companion object {
        fun of(
            observedAt: Instant?,
            now: Instant,
            maxAge: Duration,
        ): OddsFreshness =
            when {
                observedAt == null -> MISSING
                observedAt.isAfter(now) -> FUTURE
                Duration.between(observedAt, now) > maxAge -> STALE
                else -> FRESH
            }
    }
}
