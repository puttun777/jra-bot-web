package jp.jrabot.bot

import jp.jrabot.domain.bot.JobLaunchRequest
import jp.jrabot.domain.bot.JobLauncher
import jp.jrabot.domain.bot.LaunchResult
import jp.jrabot.domain.calendar.CalendarCheckReason
import jp.jrabot.domain.calendar.CalendarCheckReport
import jp.jrabot.domain.calendar.CalendarCheckService
import jp.jrabot.domain.calendar.CalendarSettings
import jp.jrabot.domain.calendar.CheckOutcome
import jp.jrabot.domain.calendar.WatchSettings
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientConnectionException
import java.time.Duration

/**
 * `--task=calendar-check`（T3.3）。現在の予定（DB の有効予定）を調べ、必要なら bot-run の**起動を要求**する。判断は [CalendarCheckService]。
 *
 * 終了コード（CONTRACTS §9。起動しないことが正常なケースを異常と区別する）:
 * | 結果 | 終了コード | `JOB_RESULT` の理由 |
 * |---|---|---|
 * | 起動を要求し、受け付けられた | 0 | `LAUNCH_REQUESTED`（bot-run が**始まった**ことではない。実際の二重処理防止は bot-run のリース） |
 * | 起動しないのが正常（開催なし・窓の外・実行中・中止・延期・拒否・完了済み・確定した非 NORMAL） | 0 | [CalendarCheckReason] の名前 |
 * | 要確認（予定が不明・古い・未確認、延期先不明、発走時刻なし、モード UNKNOWN） | 75 | 同上。起動しない（fail closed）。再試行せず次の定期起動に任せ、通知する |
 * | 起動の要求が一時的に失敗 | 75 | `LAUNCH_FAILED_TRANSIENT` |
 * | 起動の要求が失敗（未設定の起動基盤を含む） | 1 | `LAUNCH_FAILED` |
 * | DB に接続できない（実行の途中） | 75 | `DB_UNAVAILABLE` |
 */
class CalendarCheckTaskHandler(
    private val service: CalendarCheckService,
    private val log: Logger = LoggerFactory.getLogger(CalendarCheckTaskHandler::class.java),
) : BotTaskHandler {
    override fun execute(command: BotCommand): JobResult {
        val report =
            try {
                service.check(command.tenantId, command.date)
            } catch (e: Exception) {
                if (DbFailures.isConnectionFailure(e)) {
                    log.error("CALENDAR_CHECK db-unavailable tenant={} error={}", command.tenantId.value, e.javaClass.simpleName)
                    return JobResult(JobExit.TEMPFAIL, "DB_UNAVAILABLE")
                }
                throw e
            }
        return result(report)
    }

    private fun result(report: CalendarCheckReport): JobResult {
        val d = report.decision
        log.info(
            "CALENDAR_DECISION tenant={} date={} reason={} outcome={} verdict={} basis={} firstPost={} lastPost={} launchOpensAt={} settleDeadlineAt={} " +
                "expiredLeasePresent={} conflicts={}",
            report.tenantId.value,
            report.date,
            d.reason,
            d.outcome,
            d.verdict,
            report.day?.basis,
            report.day?.firstPost,
            report.day?.lastPost,
            d.window?.launchOpensAt,
            d.window?.settleDeadlineAt,
            d.expiredLeasePresent,
            report.day?.conflicts?.size ?: 0,
        )
        return when (d.outcome) {
            CheckOutcome.LAUNCH -> launchResult(report)
            CheckOutcome.NO_LAUNCH -> JobResult(JobExit.OK, d.reason.name)
            CheckOutcome.ATTENTION -> JobResult(JobExit.TEMPFAIL, d.reason.name)
        }
    }

    private fun launchResult(report: CalendarCheckReport): JobResult =
        when (val launch = report.launch) {
            is LaunchResult.Launched -> {
                log.info("CALENDAR_LAUNCH requested executionId={}", launch.executionId)
                JobResult(JobExit.OK, "LAUNCH_REQUESTED")
            }

            is LaunchResult.Failed -> {
                log.error("CALENDAR_LAUNCH failed transient={} reason={}", launch.transient, sanitize(launch.reason))
                if (launch.transient) JobResult(JobExit.TEMPFAIL, "LAUNCH_FAILED_TRANSIENT") else JobResult(JobExit.FAILURE, "LAUNCH_FAILED")
            }

            // 判定は起動可なのに要求していない = モードが直前に変わった場合だけ（サービスが理由を差し替える）。ここへは来ない
            null -> {
                JobResult(JobExit.FAILURE, "LAUNCH_NOT_REQUESTED")
            }
        }

    private fun sanitize(text: String): String = text.filter { it.code in 0x20..0x7e }.take(100)
}

/**
 * 起動基盤（Cloud Run Jobs など）の実装がまだ無い間の、**本番の配線**（fail closed）。常に「起動できなかった（再試行しても直らない）」を返し、
 * 起動したことにしない。GCP 実装（`:cloud-gcp` の `JobLauncher`）は T5 系で追加し、この配線を置き換える。テスト用の Fake とは別物。
 */
class UnconfiguredJobLauncher : JobLauncher {
    override fun launch(request: JobLaunchRequest): LaunchResult = LaunchResult.Failed(REASON, transient = false)

    companion object {
        const val REASON = "LAUNCHER_NOT_CONFIGURED"
    }
}

/** calendar-check の設定（環境変数。CONTRACTS §2）。**既定値を持たない**（運用値は未確定: D-016・D-122）。 */
object CalendarSettingsParser {
    const val WATCH_START = "CALENDAR_WATCH_START_MINUTES"
    const val PRESTART = "CALENDAR_PRESTART_MINUTES"
    const val SETTLE_DEADLINE = "CALENDAR_SETTLE_DEADLINE_MINUTES"
    const val STALE_AFTER = "CALENDAR_STALE_AFTER_MINUTES"

    /** 足りない・不正なら [IllegalStateException]（メッセージは変数名だけで値を含めない。終了コード 2）。 */
    fun fromEnv(env: Map<String, String>): CalendarSettings {
        fun minutes(
            name: String,
            min: Long,
            max: Long,
        ): Duration {
            val raw = env[name]?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("$name を指定してください（分。運用の既定値はありません）")
            val n = raw.takeIf { s -> s.all { it in '0'..'9' } && s.length <= 6 }?.toLongOrNull()
            check(n != null && n in min..max) { "$name は $min〜$max の整数（分）を指定してください" }
            return Duration.ofMinutes(n)
        }
        return CalendarSettings(
            watch =
                WatchSettings(
                    watchStart = minutes(WATCH_START, 1, 1440),
                    prestart = minutes(PRESTART, 0, 1440),
                    settleDeadline = minutes(SETTLE_DEADLINE, 1, 1440),
                ),
            staleAfter = minutes(STALE_AFTER, 1, 10_080),
        )
    }
}

/** DB の例外の分類（接続できない = 一時的な失敗。それ以外はプログラムの欠陥・権限など）。 */
object DbFailures {
    fun isConnectionFailure(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any {
            it is SQLTransientConnectionException ||
                it is SQLNonTransientConnectionException ||
                it is SQLRecoverableException ||
                (it is SQLException && it.sqlState?.startsWith("08") == true)
        }
}
