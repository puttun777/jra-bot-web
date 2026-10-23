package jp.jrabot.bot

import jp.jrabot.cloudgcp.ops.OpsModeProviders
import jp.jrabot.domain.calendar.CalendarSettings
import jp.jrabot.domain.ops.AppEnv
import jp.jrabot.persistence.jdbc.DbSettings

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
    /** `calendar-check` だけが必要とする設定（他のタスクでは null）。環境変数は [CalendarSettingsParser]。 */
    val calendar: CalendarSettings? = null,
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
            val calendar = if (task == BotTask.CALENDAR_CHECK) CalendarSettingsParser.fromEnv(env) else null
            return BotSettings(appEnv, db, opsEnv, env["RELEASE_ID"], calendar)
        }
    }
}
