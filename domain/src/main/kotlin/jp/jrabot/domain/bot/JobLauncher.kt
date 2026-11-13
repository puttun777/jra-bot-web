package jp.jrabot.domain.bot

import jp.jrabot.domain.tenant.TenantId

/** 起動できる Job（CONTRACTS §9。bot イメージの `--task=` と `ml-train`）。 */
enum class LaunchableJob(
    val cliName: String,
) {
    BOT_RUN("bot-run"),
    CALENDAR_CHECK("calendar-check"),
    RECONCILE("reconcile"),
    RETENTION("retention"),
    EXPORT_DATASET("export-dataset"),

    /** バックテスト（R16。D-265）。本人が手動で実行する（calendar-check は起動しない）。 */
    BACKTEST("backtest"),

    /** 先の開催予定の書出し（AWS の自動起動の計画。読取りだけ・通知しない。D-283）。calendar-check は起動しない。 */
    CALENDAR_PLAN("calendar-plan"),
    ML_TRAIN("ml-train"),
}

/**
 * Job の起動依頼。[params] は引数の上書き（`date`、`trigger` など）で、**秘密を入れない**（キーは `[a-z][a-z0-9-]*`）。
 * Job は `--tenant-id` を持つテナント単位の実行なので [tenantId] は必須。
 */
data class JobLaunchRequest(
    val tenantId: TenantId,
    val job: LaunchableJob,
    val params: Map<String, String> = emptyMap(),
) {
    init {
        require(params.keys.all { KEY.matches(it) }) { "params のキーが不正: ${params.keys}" }
        require(params.values.all { it.isNotEmpty() && '\n' !in it && '\r' !in it && it.length <= 256 }) { "params の値が不正（空でない・改行なし・256 文字以下）" }
        require(params.keys.none { it in RESERVED }) { "params に予約済みのキー（task・tenant-id）は使えない（起動側が必ず付ける）" }
    }

    private companion object {
        val KEY = Regex("[a-z][a-z0-9-]{0,31}")
        val RESERVED = setOf("task", "tenant-id")
    }
}

sealed interface LaunchResult {
    /** 起動を受け付けた。[executionId] は実行基盤の識別子（ログ・通知用の不透明な文字列）。 */
    data class Launched(
        val executionId: String,
    ) : LaunchResult

    /** 起動できなかった。[transient] が true なら次の定期起動などで再試行してよい（Job としては exit 75 相当）。 */
    data class Failed(
        val reason: String,
        val transient: Boolean,
    ) : LaunchResult
}

/**
 * Job を起動する抽象（クラウド固有の API に依存しない。実装は `:cloud-gcp` の Cloud Run Jobs 版とローカル版: T2.7 以降）。
 *
 * - 責務: 依頼された Job の実行を 1 つ作る。完了待ちや結果の取得はしない。
 * - エラー: 起動の失敗は [LaunchResult.Failed] で返す。例外は設定の誤りなど、再試行しても直らないプログラムの欠陥だけ。
 * - 冪等性: **冪等ではない**（呼ぶたびに実行が増え得る）。二重実行は起動された側のリース（`bot_run` の UNIQUE・D-026）が防ぐ。
 *   呼び出し側はモードが [jp.jrabot.domain.ops.OpsMode.NORMAL] のときだけ呼ぶ。
 * - テナント境界: [JobLaunchRequest.tenantId] を Job の引数（`--tenant-id`）として必ず渡す。
 */
interface JobLauncher {
    fun launch(request: JobLaunchRequest): LaunchResult
}
