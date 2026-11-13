package jp.jrabot.domain.ops

/**
 * 起動目的ごとに許された処理（AWS 構成の ops-state の `workloads`。CONTRACTS §5.2・D-282）。運用モード（[OpsMode]）とは別の情報で、
 * EC2 を閲覧だけの目的で起動したときに bot・Discord 通知・重い処理を始めないための、**アプリ側の 2 つ目の柵**（ホスト側では起動もしない）。
 *
 * - [raceday]: 開催日の処理（calendar-check・bot-run・reconcile・retention）。Discord の通知は bot-run だけが送る。
 * - [batch]: 重い処理（backtest・export-dataset・ml-train）。開催日の処理と同時には許さない（ホストのメモリ。infra の制御が排他にする）。
 */
sealed interface WorkloadStatus {
    /** 正本に `workloads` が無い（GCP の構成。従来どおり運用モードだけで判断する）。 */
    data object Unspecified : WorkloadStatus

    /** 正本を確認できない（運用モードが UNKNOWN）。 */
    data object Unknown : WorkloadStatus

    data class Specified(
        val raceday: Boolean,
        val batch: Boolean,
    ) : WorkloadStatus
}

/** Job が必要とする処理の種類。 */
enum class Workload { RACEDAY, BATCH, ANY }
