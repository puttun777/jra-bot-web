package jp.jrabot.bot

import jp.jrabot.bot.discord.StopFlag
import jp.jrabot.domain.bot.BotRunTrigger
import jp.jrabot.domain.bot.RunId
import jp.jrabot.domain.botrun.BotRunRunner
import jp.jrabot.domain.botrun.RunRequest
import jp.jrabot.domain.notify.NotificationOrigin
import jp.jrabot.domain.time.Clock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.UUID

/**
 * `--task=bot-run`（T3.8）。引数を [RunRequest] に直し、SIGTERM の取扱いを設置して [BotRunRunner]（本番は `BotRunOrchestrator`）を 1 回走らせ、結果を [JobResult] にする。
 * 業務の順序・終了条件は [BotRunOrchestrator] の KDoc。ここは接続だけ。
 *
 * - `--trigger` → 通知の出どころ: `SCHEDULER` / `MANUAL` = LIVE、`BACKFILL` / `RECONCILE` = BACKFILL（通知しない）、指定なし = UNDETERMINED（判定不能。通知しない）。
 *   `bot_run.trigger_type` は指定なしのとき MANUAL（人が直接起動した扱い）。
 * - `--date` が無ければ今日（JST）。
 * - SIGTERM: [SignalInstaller] が [StopFlag] を立てる（新しいサイクルを始めず、処理中のレースが終わったら checkpoint を保存して INTERRUPTED、リース解放、契約の終了コード）。
 */
class BotRunTaskHandler(
    private val runnerFactory: () -> BotRunRunner,
    private val clock: Clock,
    private val stop: StopFlag,
    private val signals: SignalInstaller,
    private val ownerId: () -> String = ::defaultOwner,
    private val newRunId: () -> RunId = { RunId.of(UUID.randomUUID()) },
    private val log: Logger = LoggerFactory.getLogger(BotRunTaskHandler::class.java),
) : BotTaskHandler {
    override fun execute(command: BotCommand): JobResult {
        val trigger = command.trigger
        val request =
            RunRequest(
                tenantId = command.tenantId,
                date = command.date ?: clock.today(),
                trigger = trigger?.let { BotRunTrigger.valueOf(it.name) } ?: BotRunTrigger.MANUAL,
                origin = originOf(trigger),
                owner = ownerId(),
                runId = newRunId(),
            )
        log.info("BOT_RUN start tenant={} date={} trigger={} origin={}", request.tenantId.value, request.date, request.trigger, request.origin)
        val restore = signals.install { stop.request() }
        val outcome =
            try {
                runnerFactory().run(request)
            } finally {
                restore.run()
            }
        log.info("BOT_RUN end exit={} reason={} state={} run={}", outcome.exitCode, outcome.reason, outcome.state, outcome.runId?.value)
        return JobResult(outcome.exitCode, outcome.reason)
    }

    companion object {
        fun originOf(trigger: TriggerType?): NotificationOrigin =
            when (trigger) {
                TriggerType.SCHEDULER, TriggerType.MANUAL -> NotificationOrigin.LIVE
                TriggerType.BACKFILL, TriggerType.RECONCILE -> NotificationOrigin.BACKFILL
                null -> NotificationOrigin.UNDETERMINED
            }

        /** Cloud Run Jobs の execution 名があればそれ。ローカルは `local`。プロセスごとに一意にする（リースの所有者の識別。1〜100 文字）。 */
        fun defaultOwner(): String {
            val base = (System.getenv("CLOUD_RUN_EXECUTION")?.takeIf { it.isNotBlank() } ?: "local").take(80)
            return "$base:${UUID.randomUUID().toString().take(8)}"
        }

        /** 一時的な障害（再試行で直り得る）: 接続の失敗、デッドロック（1213）、ロック待ちの超過（1205）、直列化の失敗（SQLSTATE 40001）。 */
        fun isTransient(e: Throwable): Boolean =
            DbFailures.isConnectionFailure(e) ||
                generateSequence(e) { it.cause }.any { it is SQLException && (it.errorCode == 1213 || it.errorCode == 1205 || it.sqlState == "40001") }
    }
}

/** シグナルの受信の設置（テストでは偽物で呼び出す）。戻り値は元に戻す処理。 */
fun interface SignalInstaller {
    fun install(onTerm: Runnable): Runnable

    companion object {
        /**
         * JVM の既定の SIGTERM（即座にシャットダウンして終了コード 143）を、**停止の旗を立てるだけ**の処理に置き換える。これにより、処理中のレースを終えて
         * checkpoint を保存し、リースを解放して、契約の終了コード（0 / 75）で終われる（CONTRACTS §9.2）。設置できない環境では何もしない。
         */
        val SIGTERM: SignalInstaller = handling("TERM", strict = false)

        val NONE = SignalInstaller { Runnable { } }

        /** [name]（`TERM` など。`SIG` なし）の受信を [onTerm] に置き換える。[strict] が false なら設置に失敗しても何もしない（本番の SIGTERM）、true なら例外（テスト用）。 */
        fun handling(
            name: String,
            strict: Boolean,
        ): SignalInstaller =
            SignalInstaller { onTerm ->
                try {
                    val previous = sun.misc.Signal.handle(sun.misc.Signal(name)) { onTerm.run() }
                    Runnable { sun.misc.Signal.handle(sun.misc.Signal(name), previous) }
                } catch (e: Throwable) {
                    if (strict) throw e
                    Runnable { }
                }
            }
    }
}
