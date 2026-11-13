package jp.jrabot.bot

import jp.jrabot.bot.discord.DiscordSettings
import jp.jrabot.domain.calendar.CalendarSettings
import jp.jrabot.domain.ops.AppEnv
import jp.jrabot.persistence.jdbc.DbSettings
import jp.jrabot.platform.ops.OpsModeProviders

/**
 * bot の環境変数による設定（CONTRACTS §2）。足りない・不正なら [IllegalStateException]/[IllegalArgumentException]（終了コード 2）。
 * メッセージには変数名だけを含め、値（パスワード・URL）は含めない。ここでは DB に接続しない（設定の検証だけ）。
 */
class BotSettings(
    val appEnv: AppEnv,
    val db: DbSettings,
    /** `OpsModeProviders.fromEnvironment` に渡す環境（`OPS_MODE_SOURCE`、`APP_ENV`）。 */
    val opsEnv: Map<String, String>,
    val releaseIdOverride: String?,
    /** `calendar-check` と `reconcile` が必要とする設定（他のタスクでは null）。環境変数は [CalendarSettingsParser]。 */
    val calendar: CalendarSettings? = null,
    /** `export-dataset` だけが必要とする保管先（`MODEL_STORE_URI`: `gs://<bucket>[/<prefix>]`、`APP_ENV=local` では `file:///絶対パス`）。他のタスクでは null。 */
    val modelStoreUri: String? = null,
    /** `bot-run` だけが使う Discord の設定（`DISCORD_WEBHOOK_URL` ほか。いずれも任意）。他のタスクでは null。 */
    val discord: DiscordSettings? = null,
    /** `bot-run` と `backtest` が必要とする設定（業務ループ・ML・監視ウィンドウ）。他のタスクでは null。環境変数は [BotRunSettingsParser]。 */
    val botRun: BotRunSettings? = null,
    /** `retention` のときだけ非 null（すべて任意。既定はドライラン）。 */
    val retention: RetentionSettings? = null,
    /** `calendar-check` のとき、Cloud Run Jobs の起動先（GCP。本番は `JOB_LAUNCHER=spool` でなければ必須。ローカルは任意で、無ければ null = 未設定の起動基盤）。 */
    val runJobs: RunJobsSettings? = null,
    /** `calendar-check` のとき、`JOB_LAUNCHER=spool`（AWS: 1 台の EC2）の起動依頼の置き場（`JOB_SPOOL_DIR`）。D-283。 */
    val spoolDir: java.nio.file.Path? = null,
    /** `OPS_WORKLOAD_GATE=required`（AWS）なら、ops-state の `workloads` でタスクの種類が許されているときだけ動く（D-282）。既定は確認しない（GCP）。 */
    val workloadGate: Boolean = false,
) {
    companion object {
        const val DB_USER = "bot" // CONTRACTS §8.2: bot（全 --task）

        fun from(
            env: Map<String, String>,
            task: BotTask,
        ): BotSettings {
            val appEnv = AppEnv.parseOrNull(env["APP_ENV"]) ?: throw IllegalStateException("APP_ENV は local または prod を指定してください")
            val db = DbSettings.fromEnv(env, task.defaultPoolMax)
            // 最小権限: 本番では bot 用のユーザー以外（migrate など強い権限）で動かさない。ローカルは任意のユーザーを許す
            check(appEnv == AppEnv.LOCAL || db.user == DB_USER) { "DB_USER が bot 用のユーザー($DB_USER)ではない" }
            val opsEnv = env.filterKeys { it == OpsModeProviders.ENV_SOURCE || it == OpsModeProviders.ENV_APP }
            val calendar =
                if (task == BotTask.CALENDAR_CHECK || task == BotTask.RECONCILE || task == BotTask.CALENDAR_PLAN) CalendarSettingsParser.fromEnv(env) else null
            val storeUri =
                if (task == BotTask.EXPORT_DATASET) {
                    val uri = env["MODEL_STORE_URI"]?.trim().orEmpty()
                    check(uri.isNotEmpty()) { "MODEL_STORE_URI がありません（export-dataset の保管先）" }
                    check(uri.startsWith("gs://") || uri.startsWith("s3://") || (appEnv == AppEnv.LOCAL && uri.startsWith("file:///"))) {
                        "MODEL_STORE_URI は gs://・s3://（本番）または file:///（APP_ENV=local のみ）の形式にしてください"
                    }
                    uri
                } else {
                    null
                }
            val discord = if (task == BotTask.BOT_RUN) DiscordSettings.fromEnv(env) else null
            // backtest は bot-run と同じ判定の設定（BOT_RUN_*・ML）を使う（同じ判定を過去の時点で再計算するため。D-265）
            val botRun = if (task == BotTask.BOT_RUN || task == BotTask.BACKTEST) BotRunSettingsParser.fromEnv(env, appEnv) else null
            val retention = if (task == BotTask.RETENTION) RetentionSettings.fromEnv(env) else null
            val launcherKind = env["JOB_LAUNCHER"]?.trim().orEmpty()
            check(launcherKind in setOf("", "cloud-run", "spool")) { "JOB_LAUNCHER は cloud-run または spool です" }
            val spoolDir =
                if (task == BotTask.CALENDAR_CHECK && launcherKind == "spool") {
                    SpoolJobLauncher.validateDir(
                        env["JOB_SPOOL_DIR"]?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("JOB_SPOOL_DIR を指定してください（JOB_LAUNCHER=spool）"),
                    )
                } else {
                    null
                }
            val runJobs = if (task == BotTask.CALENDAR_CHECK && spoolDir == null) RunJobsSettings.fromEnv(env, appEnv) else null
            val gate =
                when (env["OPS_WORKLOAD_GATE"]?.trim().orEmpty()) {
                    "", "off" -> false
                    "required" -> true
                    else -> throw IllegalStateException("OPS_WORKLOAD_GATE は required または off です")
                }
            return BotSettings(appEnv, db, opsEnv, env["RELEASE_ID"], calendar, storeUri, discord, botRun, retention, runJobs, spoolDir, gate)
        }
    }
}
