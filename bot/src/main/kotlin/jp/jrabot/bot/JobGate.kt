package jp.jrabot.bot

import jp.jrabot.domain.ops.OpsMode

/**
 * Job の開始時の運用モード判定（CONTRACTS §5.3・§9）。**DB に接続する前**に行う純粋な判定。
 *
 * | モード | 判定 |
 * |---|---|
 * | NORMAL | [Proceed]（次はスキーマ版の確認 → タスクの実行） |
 * | STARTING / STOPPING / STOPPED | [Skip]: 何もせず終了コード 0、理由 `MODE_<mode>` |
 * | UNKNOWN | [Unavailable]: 何もせず終了コード 75（異常として通知される。NORMAL とみなさない） |
 */
sealed interface JobGate {
    data object Proceed : JobGate

    data class Skip(
        val result: JobResult,
    ) : JobGate

    data class Unavailable(
        val result: JobResult,
    ) : JobGate

    companion object {
        fun decide(mode: OpsMode): JobGate =
            when (mode) {
                OpsMode.NORMAL -> Proceed
                OpsMode.STARTING, OpsMode.STOPPING, OpsMode.STOPPED -> Skip(JobResult(JobExit.OK, "MODE_${mode.name}"))
                OpsMode.UNKNOWN -> Unavailable(JobResult(JobExit.TEMPFAIL, "MODE_UNKNOWN"))
            }
    }
}
