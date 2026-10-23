package jp.jrabot.bot

/**
 * Job の終了コード（CONTRACTS §9.1）。`db-migration` の validate が使う 10 は**通常の Job に流用しない**。
 * 例外を無条件に [TEMPFAIL] にしない（再試行しても直らない失敗は [FAILURE]）。
 */
object JobExit {
    /** 正常。何もすることが無かった場合と、確定した非 NORMAL のモードによる計画的なスキップを含む。 */
    const val OK = 0

    /** 失敗（バグ・検証 NG・スキーマ版不足・認証/権限の設定ミス・未実装の業務処理）。通知する。 */
    const val FAILURE = 1

    /** 引数・設定の誤り（`--task` 不明、必須の環境変数なし）。通知する。 */
    const val USAGE = 2

    /** 一時的な失敗（EX_TEMPFAIL）: DB 不達、モード UNKNOWN など。再試行しない（Scheduler の次の起動に任せる）。 */
    const val TEMPFAIL = 75
}

/** Job 1 回の結果。[reason] は固定語彙（`MODE_STOPPED` など）で、値・資格情報を含めない。ログの `JOB_RESULT` 行に出す。 */
data class JobResult(
    val exitCode: Int,
    val reason: String,
)
