package jp.jrabot.domain.calendar

import jp.jrabot.domain.bot.BotRunReader
import jp.jrabot.domain.bot.JobLaunchRequest
import jp.jrabot.domain.bot.JobLauncher
import jp.jrabot.domain.bot.LaunchResult
import jp.jrabot.domain.bot.LaunchableJob
import jp.jrabot.domain.bot.RunId
import jp.jrabot.domain.ops.ModeProvider
import jp.jrabot.domain.ops.OpsMode
import jp.jrabot.domain.race.RaceScheduleReader
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Clock
import java.time.Instant
import java.time.LocalDate

/** 判断の分類。Job の終了コードの違いになる（[LAUNCH]=起動を要求する、[NO_LAUNCH]=起動しないのが正常、[ATTENTION]=起動しないが要確認）。 */
enum class CheckOutcome { LAUNCH, NO_LAUNCH, ATTENTION }

/**
 * calendar-check の判断の理由（RACE_CALENDAR_DESIGN §6.2）。**起動の可否を判定しただけ**で、実際に起動を要求したか・受け付けられたかは別
 * （[CalendarCheckReport.launch]）。起動しないことが正常な理由（[CheckOutcome.NO_LAUNCH]）は異常（[CheckOutcome.ATTENTION]）と区別する。
 */
enum class CalendarCheckReason(
    val outcome: CheckOutcome,
) {
    /** すべての条件を満たし、起動を要求してよい。 */
    LAUNCH_ELIGIBLE(CheckOutcome.LAUNCH),

    /** 運用モードが確定した非 NORMAL（STARTING・STOPPING・STOPPED）。何もしない（正常）。 */
    OPS_MODE_NOT_NORMAL(CheckOutcome.NO_LAUNCH),

    /** 運用モードを確認できない。NORMAL とみなさない（fail closed）。 */
    OPS_MODE_UNKNOWN(CheckOutcome.ATTENTION),

    /** 評価した日が今日（JST）ではない。診断のための日付指定では起動しない。 */
    DATE_NOT_TODAY(CheckOutcome.NO_LAUNCH),

    /** 有効な DENY_RUN がある。 */
    DENIED_BY_OVERRIDE(CheckOutcome.NO_LAUNCH),

    /** 開催なしと確認できた（新しい取得の事実がある、または手動で開催なし）。 */
    NOT_A_RACE_DAY(CheckOutcome.NO_LAUNCH),

    /** データが無く、開催の有無が不明（開催なしの確認ではない）。 */
    SCHEDULE_UNKNOWN(CheckOutcome.ATTENTION),

    /** データが古く信頼できない。 */
    SCHEDULE_STALE(CheckOutcome.ATTENTION),

    /** 予定はあるが出走表（発走時刻）が確認できていない。 */
    SCHEDULE_UNCONFIRMED(CheckOutcome.ATTENTION),

    DAY_CANCELLED(CheckOutcome.NO_LAUNCH),

    /** 延期（振替先が分かっている）。振替先の日は別に評価される。 */
    DAY_POSTPONED(CheckOutcome.NO_LAUNCH),

    /** 延期だが振替先が不明。手動補正で解消する。 */
    POSTPONED_TARGET_UNKNOWN(CheckOutcome.ATTENTION),

    /** 開催日だが有効な発走時刻が 1 つも無い（時刻の確認が必要）。 */
    NO_POST_TIME(CheckOutcome.ATTENTION),

    BEFORE_WATCH_WINDOW(CheckOutcome.NO_LAUNCH),
    AFTER_SETTLE_DEADLINE(CheckOutcome.NO_LAUNCH),

    /** 有効なリースを持つ bot-run が実行中。 */
    ALREADY_ACTIVE(CheckOutcome.NO_LAUNCH),

    /** その日の bot-run は完了済み。 */
    ALREADY_COMPLETED(CheckOutcome.NO_LAUNCH),

    /** 結果の欠損があり reconcile の担当（bot-run は起動しない）。 */
    NEEDS_RECONCILE(CheckOutcome.NO_LAUNCH),
}

/** その日に排他を持つ bot-run の状態（リースの期限は絶対時刻で判定する）。 */
sealed interface ActiveRun {
    data object None : ActiveRun

    data class LeaseValid(
        val runId: RunId,
        val leaseExpiresAt: Instant?,
    ) : ActiveRun

    /** 期限切れのリースを持つ実行（クラッシュの疑い）。新しい実行が奪取できるので、起動を妨げない。 */
    data class LeaseExpired(
        val runId: RunId,
        val leaseExpiresAt: Instant?,
    ) : ActiveRun
}

data class CalendarCheckDecision(
    val reason: CalendarCheckReason,
    /** 判定に至らなかった（運用モードで止めた）ときは null。 */
    val verdict: DayVerdict?,
    val window: WatchWindow?,
    /** 起動対象の日に期限切れのリースを持つ実行が残っている（新しい実行が奪取する）。 */
    val expiredLeasePresent: Boolean,
) {
    val outcome: CheckOutcome get() = reason.outcome
}

/** 起動条件の判定（純粋な関数。RACE_CALENDAR_DESIGN §6.2。すべて冪等: 何度呼んでも同じ入力に同じ判断）。 */
object CalendarCheckPolicy {
    fun decide(
        now: Instant,
        evaluatedDate: LocalDate,
        today: LocalDate,
        mode: OpsMode,
        day: EffectiveDay,
        activeRun: ActiveRun,
        settings: WatchSettings,
    ): CalendarCheckDecision {
        val window = day.window(settings)
        val expired = activeRun is ActiveRun.LeaseExpired

        fun result(reason: CalendarCheckReason) = CalendarCheckDecision(reason, day.verdict, window, expired)
        if (mode == OpsMode.UNKNOWN) return result(CalendarCheckReason.OPS_MODE_UNKNOWN)
        if (!mode.allowsWork) return result(CalendarCheckReason.OPS_MODE_NOT_NORMAL)
        if (evaluatedDate != today) return result(CalendarCheckReason.DATE_NOT_TODAY)
        if (day.denyRun) return result(CalendarCheckReason.DENIED_BY_OVERRIDE)

        // ALLOW_RUN は「元の予定が確認できない」状態だけを覆す。手動の中止・延期・開催なし、および元の予定の中止・延期は覆さない
        val liftable = day.allowRun && day.basis == VerdictBasis.SOURCE
        when (day.verdict) {
            DayVerdict.CONFIRMED -> {}

            DayVerdict.CANCELLED -> {
                return result(CalendarCheckReason.DAY_CANCELLED)
            }

            DayVerdict.POSTPONED -> {
                return result(if (day.postponedTo == null) CalendarCheckReason.POSTPONED_TARGET_UNKNOWN else CalendarCheckReason.DAY_POSTPONED)
            }

            DayVerdict.NO_RACE_CONFIRMED -> {
                if (!liftable) return result(CalendarCheckReason.NOT_A_RACE_DAY)
            }

            DayVerdict.UNKNOWN -> {
                if (!liftable) return result(CalendarCheckReason.SCHEDULE_UNKNOWN)
            }

            DayVerdict.STALE -> {
                if (!liftable) return result(CalendarCheckReason.SCHEDULE_STALE)
            }

            DayVerdict.UNCONFIRMED -> {
                if (!liftable) return result(CalendarCheckReason.SCHEDULE_UNCONFIRMED)
            }
        }
        when (day.progress) {
            DayProgress.COMPLETED -> return result(CalendarCheckReason.ALREADY_COMPLETED)
            DayProgress.INCOMPLETE -> return result(CalendarCheckReason.NEEDS_RECONCILE)
            else -> Unit
        }
        if (window == null) return result(CalendarCheckReason.NO_POST_TIME)
        when (window.phase(now)) {
            WindowPhase.BEFORE -> return result(CalendarCheckReason.BEFORE_WATCH_WINDOW)
            WindowPhase.AFTER -> return result(CalendarCheckReason.AFTER_SETTLE_DEADLINE)
            WindowPhase.OPEN -> Unit
        }
        if (activeRun is ActiveRun.LeaseValid) return result(CalendarCheckReason.ALREADY_ACTIVE)
        return result(CalendarCheckReason.LAUNCH_ELIGIBLE)
    }
}

/** calendar-check 1 回の結果。[launch] は起動を**要求した**ときだけ non-null（要求が受け付けられたかは [LaunchResult]）。判定しただけでは null。 */
data class CalendarCheckReport(
    val tenantId: TenantId,
    val date: LocalDate,
    val decision: CalendarCheckDecision,
    val launch: LaunchResult?,
    val day: EffectiveDay?,
)

/**
 * calendar-check のアプリケーションサービス（`--task=calendar-check`）。現在の予定を調べ、必要なら bot-run の**起動を要求**する。
 *
 * - 運用モードは**最初に**確認し、NORMAL 以外なら DB を読まずに終える。起動を要求する直前にもう一度確認する（途中で STOPPED になっていたら要求しない）。
 * - [JobLauncher.launch] は冪等ではない。連続実行・手動との競合で起動要求が重複し得る。**二重処理は起動された bot-run のリース取得**
 *   （`bot_run` の UNIQUE。D-026）が阻止する（calendar-check 側で要求の一意性は保証しない）。
 * - 読み取りだけで、開催予定・補正・bot_run を変更しない。DB・起動基盤の例外は握りつぶさず呼び出し元へ伝える。
 */
class CalendarCheckService(
    private val clock: Clock,
    private val modes: ModeProvider,
    private val days: RaceDayReader,
    private val schedules: RaceScheduleReader,
    private val overrides: CalendarOverrideReader,
    private val facts: ScheduleFetchFactReader,
    private val runs: BotRunReader,
    private val launcher: JobLauncher,
    private val settings: CalendarSettings,
) {
    /** [date] が null なら今日（JST。[Clock.today]）。 */
    fun check(
        tenantId: TenantId,
        date: LocalDate? = null,
    ): CalendarCheckReport {
        val now = clock.now()
        val today = clock.today()
        val target = date ?: today
        val mode = modes.current()
        if (!mode.allowsWork) {
            val reason = if (mode == OpsMode.UNKNOWN) CalendarCheckReason.OPS_MODE_UNKNOWN else CalendarCheckReason.OPS_MODE_NOT_NORMAL
            return CalendarCheckReport(tenantId, target, CalendarCheckDecision(reason, null, null, false), null, null)
        }
        val day =
            EffectiveCalendar.derive(
                tenantId,
                target,
                now,
                settings,
                days.listByDate(tenantId, target),
                schedules.listByDate(tenantId, target),
                overrides.listByDate(tenantId, target),
                facts.latestSuccess(tenantId, target),
            )
        val active =
            runs.findActive(tenantId, target)?.let { r ->
                val expiresAt = r.leaseExpiresAt
                // 期限の情報が無い行は、期限切れと証明できないので有効として扱う（排他を優先。bot_run の奪取と同じ規則）
                if (expiresAt == null || now < expiresAt) ActiveRun.LeaseValid(r.runId, expiresAt) else ActiveRun.LeaseExpired(r.runId, expiresAt)
            } ?: ActiveRun.None
        val decision = CalendarCheckPolicy.decide(now, target, today, mode, day, active, settings.watch)
        if (decision.reason != CalendarCheckReason.LAUNCH_ELIGIBLE) return CalendarCheckReport(tenantId, target, decision, null, day)

        val latest = modes.current()
        if (!latest.allowsWork) {
            val reason = if (latest == OpsMode.UNKNOWN) CalendarCheckReason.OPS_MODE_UNKNOWN else CalendarCheckReason.OPS_MODE_NOT_NORMAL
            return CalendarCheckReport(tenantId, target, decision.copy(reason = reason), null, day)
        }
        val launch =
            launcher.launch(
                JobLaunchRequest(tenantId, LaunchableJob.BOT_RUN, mapOf("date" to target.toString(), "trigger" to "SCHEDULER")),
            )
        return CalendarCheckReport(tenantId, target, decision, launch, day)
    }
}
