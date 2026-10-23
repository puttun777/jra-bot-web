package jp.jrabot.domain.calendar

import jp.jrabot.domain.bot.BotRunTrigger

enum class ManualStartDenial {
    /** 有効な DENY_RUN がある。 */
    DENIED_BY_OVERRIDE,

    /** 開催日（CONFIRMED）ではない（`403 NOT_A_RACE_DAY`。例外は ALLOW_RUN）。 */
    NOT_A_RACE_DAY,
}

sealed interface ManualStartDecision {
    data class Allowed(
        val viaAllowRun: Boolean,
    ) : ManualStartDecision

    data class Denied(
        val reason: ManualStartDenial,
        val verdict: DayVerdict,
    ) : ManualStartDecision
}

/**
 * bot-run の手動起動の検証（RACE_CALENDAR_DESIGN §6.3「手動起動は必ず calendar を検証」。管理 API は Stage 4 でこれを呼ぶ。
 * ここでは**判断だけ**で、認可（管理者であること）も起動もしない）。
 *
 * - DENY_RUN があれば、どの起動理由でも拒否（ALLOW_RUN があっても）。
 * - [BotRunTrigger.BACKFILL]・[BotRunTrigger.RECONCILE] は過去日の取得・照合（通知しない）なので開催日の検証を免除する。
 * - それ以外（[BotRunTrigger.MANUAL]・[BotRunTrigger.SCHEDULER]）は開催日（CONFIRMED）だけ許可。ALLOW_RUN は元の予定が不明・古い・未確認・開催なしのときだけ覆す
 *   （手動の中止・延期・開催なしと、元の予定の中止・延期は覆さない。[CalendarCheckPolicy] と同じ規則）。
 */
object ManualStartPolicy {
    fun check(
        trigger: BotRunTrigger,
        day: EffectiveDay,
    ): ManualStartDecision {
        if (day.denyRun) return ManualStartDecision.Denied(ManualStartDenial.DENIED_BY_OVERRIDE, day.verdict)
        if (trigger == BotRunTrigger.BACKFILL || trigger == BotRunTrigger.RECONCILE) return ManualStartDecision.Allowed(viaAllowRun = false)
        if (day.verdict == DayVerdict.CONFIRMED) return ManualStartDecision.Allowed(viaAllowRun = false)
        val liftable =
            day.allowRun &&
                day.basis == VerdictBasis.SOURCE &&
                day.verdict in setOf(DayVerdict.UNKNOWN, DayVerdict.STALE, DayVerdict.UNCONFIRMED, DayVerdict.NO_RACE_CONFIRMED)
        return if (liftable) {
            ManualStartDecision.Allowed(viaAllowRun = true)
        } else {
            ManualStartDecision.Denied(ManualStartDenial.NOT_A_RACE_DAY, day.verdict)
        }
    }
}
