package jp.jrabot.bot

import jp.jrabot.domain.ops.OpsMode
import jp.jrabot.domain.ops.Workload
import jp.jrabot.domain.ops.WorkloadStatus

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

/**
 * 起動目的の柵（D-282）。運用モードの判定（[JobGate]）の**後**、DB に接続する前に行う。`OPS_WORKLOAD_GATE=required`（AWS）のときだけ確認する。
 *
 * | ops-state の `workloads` | タスクの種類が許されている | 判定 |
 * |---|---|---|
 * | 許されている（ANY は常に） | — | [JobGate.Proceed] |
 * | 許されていない（閲覧用の起動など） | — | [JobGate.Skip]: 終了コード 0、理由 `WORKLOAD_<種類>_DISABLED`（正常。何もしない） |
 * | 無い（`workloads` が書かれていない） | — | [JobGate.Unavailable]: 終了コード 75、理由 `WORKLOAD_UNSPECIFIED`（AWS の正本が壊れている。許可に倒さない） |
 * | 確認できない | — | [JobGate.Unavailable]: 終了コード 75、理由 `WORKLOAD_UNKNOWN` |
 */
object WorkloadGate {
    fun decide(
        needed: Workload,
        status: WorkloadStatus,
        required: Boolean,
    ): JobGate {
        if (!required || needed == Workload.ANY) return JobGate.Proceed
        return when (status) {
            is WorkloadStatus.Specified -> {
                val allowed = if (needed == Workload.RACEDAY) status.raceday else status.batch
                if (allowed) JobGate.Proceed else JobGate.Skip(JobResult(JobExit.OK, "WORKLOAD_${needed.name}_DISABLED"))
            }

            WorkloadStatus.Unspecified -> {
                JobGate.Unavailable(JobResult(JobExit.TEMPFAIL, "WORKLOAD_UNSPECIFIED"))
            }

            WorkloadStatus.Unknown -> {
                JobGate.Unavailable(JobResult(JobExit.TEMPFAIL, "WORKLOAD_UNKNOWN"))
            }
        }
    }
}
