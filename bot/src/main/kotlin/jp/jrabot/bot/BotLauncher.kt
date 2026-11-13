package jp.jrabot.bot

import jp.jrabot.cloudaws.ops.S3OpsStateSources
import jp.jrabot.cloudgcp.ops.GcsOpsStateSources
import jp.jrabot.domain.ops.ModeProvider
import jp.jrabot.domain.time.SystemClock
import jp.jrabot.platform.ops.OpsModeConfigurationException
import jp.jrabot.platform.ops.OpsModeProviders
import jp.jrabot.platform.ops.RemoteOpsStateSourceFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** モード確認を通った Job の実行に必要なもの（Spring のコンテキストに登録され、Bean の元になる）。 */
class BotRuntime(
    val command: BotCommand,
    val settings: BotSettings,
    /** モード確認に使ったものと同じインスタンス（キャッシュと seq の検査を引き継ぐ）。 */
    val modeProvider: ModeProvider,
)

/**
 * bot の Job を 1 回実行して終了コードを返す（CONTRACTS §5.3・§9）。順序は固定で、**DB に接続する前に**運用モードで判定する:
 *
 * 1. 引数の解析（不正 → 2。Spring も DB も触らない）
 * 2. 設定の検証（環境変数の不足/不正、`OPS_MODE_SOURCE` の不正 → 2。接続はしない）
 * 3. 運用モードの確認（[ModeProvider.current]。GCS またはローカルファイルだけ。DB に触れない）→ [JobGate]
 *    - STARTING / STOPPING / STOPPED → 0（`MODE_<mode>`）、UNKNOWN → 75。**Spring のコンテキストも DataSource も作らない**
 * 4. NORMAL のときだけ [execute]（Spring のコンテキストを起動し、スキーマ版を確認してタスクを実行）
 *
 * 想定外の例外は **1**（バグ。再試行しても直るとは限らないので 75 にしない）。結果は `JOB_RESULT` の 1 行でログに残す。
 */
class BotLauncher(
    private val modeProviderFactory: (Map<String, String>) -> ModeProvider = { OpsModeProviders.fromEnvironment(it, SystemClock, OPS_STATE_REMOTES) },
    private val execute: (BotRuntime) -> JobResult = SpringJobExecutor::run,
    private val log: Logger = LoggerFactory.getLogger(BotLauncher::class.java),
) {
    fun run(
        args: Array<String>,
        env: Map<String, String>,
    ): Int {
        val command =
            when (val parsed = BotArgsParser.parse(args)) {
                is ParsedArgs.Valid -> {
                    parsed.command
                }

                is ParsedArgs.Invalid -> {
                    log.error("JOB_RESULT exit={} reason=INVALID_ARGUMENTS: {}", JobExit.USAGE, parsed.message)
                    return JobExit.USAGE
                }
            }
        return try {
            result(command, runChecked(command, env))
        } catch (e: Exception) {
            // 想定外（実装の不具合）。例外のメッセージは接続情報を含み得るので出さず、型だけを残す
            log.error("JOB_RESULT task={} exit={} reason=UNEXPECTED_ERROR error={}", command.task.cliName, JobExit.FAILURE, e.javaClass.simpleName)
            JobExit.FAILURE
        }
    }

    private fun runChecked(
        command: BotCommand,
        env: Map<String, String>,
    ): JobResult {
        val settings =
            try {
                BotSettings.from(env, command.task)
            } catch (e: RuntimeException) {
                return JobResult(JobExit.USAGE, "INVALID_CONFIGURATION: ${e.message}")
            }
        val modeProvider =
            try {
                modeProviderFactory(settings.opsEnv)
            } catch (e: OpsModeConfigurationException) {
                return JobResult(JobExit.USAGE, "INVALID_CONFIGURATION: ${e.message}")
            }
        when (val gate = JobGate.decide(modeProvider.current())) {
            JobGate.Proceed -> {}

            is JobGate.Skip -> {
                return gate.result
            }

            is JobGate.Unavailable -> {
                return gate.result
            }
        }
        // 起動目的の柵（AWS: 閲覧用の起動では開催日の処理・重い処理をしない。D-282）。モードと同じく DB に接続する前
        return when (val gate = WorkloadGate.decide(command.task.workload, modeProvider.workloads(), settings.workloadGate)) {
            JobGate.Proceed -> execute(BotRuntime(command, settings, modeProvider))
            is JobGate.Skip -> gate.result
            is JobGate.Unavailable -> gate.result
        }
    }

    private fun result(
        command: BotCommand,
        r: JobResult,
    ): Int {
        log.info("JOB_RESULT task={} tenant={} exit={} reason={}", command.task.cliName, command.tenantId.value, r.exitCode, r.reason)
        return r.exitCode
    }
}

/** 運用モードの正本の遠隔の読取り元（`gs://` = GCS、`s3://` = S3。D-280）。 */
val OPS_STATE_REMOTES: List<RemoteOpsStateSourceFactory> = listOf(GcsOpsStateSources, S3OpsStateSources)
