package jp.jrabot.bot

import jp.jrabot.domain.bot.LaunchableJob
import jp.jrabot.domain.ops.AppEnv

/**
 * Cloud Run Jobs の起動先（calendar-check が bot-run などを起動する: R2）。環境変数 `RUN_JOBS_PROJECT_ID` / `RUN_JOBS_REGION` / `RUN_JOBS_PREFIX`（既定 `jra-bot`）。
 * Job 名は `<prefix>-<task>`（infra の命名: `jra-bot-bot-run` など）。**本番の calendar-check では必須**（無ければ設定エラー = 終了コード 2。起動できないまま『成功』にしない）。
 * ローカルでは任意（無ければ起動基盤は未設定の fail closed のまま。試験は Fake を差し込む）。
 */
data class RunJobsSettings(
    val projectId: String,
    val region: String,
    val prefix: String,
) {
    fun jobNames(): Map<LaunchableJob, String> =
        listOf(LaunchableJob.BOT_RUN, LaunchableJob.RECONCILE, LaunchableJob.RETENTION, LaunchableJob.CALENDAR_CHECK, LaunchableJob.EXPORT_DATASET)
            .associateWith { "$prefix-${it.cliName}" }

    companion object {
        fun fromEnv(
            env: Map<String, String>,
            appEnv: AppEnv,
        ): RunJobsSettings? {
            val project = env["RUN_JOBS_PROJECT_ID"]?.trim().orEmpty()
            val region = env["RUN_JOBS_REGION"]?.trim().orEmpty()
            val prefix = env["RUN_JOBS_PREFIX"]?.trim()?.takeIf { it.isNotEmpty() } ?: "jra-bot"
            if (project.isEmpty() && region.isEmpty()) {
                check(appEnv != AppEnv.PROD) { "RUN_JOBS_PROJECT_ID と RUN_JOBS_REGION を指定してください（本番の calendar-check は bot-run を起動できる必要があります）" }
                return null
            }
            check(project.isNotEmpty() && region.isNotEmpty()) { "RUN_JOBS_PROJECT_ID と RUN_JOBS_REGION は両方指定してください" }
            check(Regex("[a-z][a-z0-9-]{4,28}[a-z0-9]").matches(project)) { "RUN_JOBS_PROJECT_ID の形式が不正です" }
            check(Regex("[a-z]+-[a-z]+[0-9]+").matches(region)) { "RUN_JOBS_REGION の形式が不正です" }
            check(Regex("[a-z][a-z0-9-]{0,30}").matches(prefix)) { "RUN_JOBS_PREFIX の形式が不正です" }
            return RunJobsSettings(project, region, prefix)
        }
    }
}
