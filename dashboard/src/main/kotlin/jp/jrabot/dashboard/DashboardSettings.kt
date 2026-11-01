package jp.jrabot.dashboard

import jp.jrabot.cloudgcp.ops.OpsModeProviders
import jp.jrabot.domain.ops.AppEnv
import jp.jrabot.domain.tenant.EmailAddress
import jp.jrabot.domain.tenant.HumanAuthorizer
import jp.jrabot.persistence.jdbc.DbSettings

/**
 * dashboard の環境変数による設定（CONTRACTS §2）。足りない・不正なら起動を失敗させる（既定値に黙って落とさない）。
 * 例外のメッセージには変数名だけを含め、値（パスワード・URL）は含めない。
 */
class DashboardSettings(
    val appEnv: AppEnv,
    val db: DbSettings,
    /** `OpsModeProviders.fromEnvironment` に渡す環境（`OPS_MODE_SOURCE`、`APP_ENV`）。 */
    val opsEnv: Map<String, String>,
    val iapAudience: String,
    val opsServiceAccountEmail: String,
    val releaseIdOverride: String?,
    /** `ALLOWED_USERS`（空なら全拒否）。 */
    val allowedUsers: List<EmailAddress> = emptyList(),
    /** `ADMIN_USERS`（ロール ADMIN の行は、ここにも載っているときだけ認める）。 */
    val adminUsers: List<EmailAddress> = emptyList(),
) {
    companion object {
        const val DEFAULT_POOL_MAX = 3 // CONTRACTS §8.3: dashboard
        const val DB_USER = "app" // CONTRACTS §8.2

        fun from(get: (String) -> String?): DashboardSettings {
            val appEnv =
                AppEnv.parseOrNull(get("APP_ENV"))
                    ?: throw IllegalStateException("APP_ENV は local または prod を指定してください")
            val db =
                DbSettings.fromEnv(
                    listOf("DB_URL", "DB_USER", "DB_PASSWORD", "DB_POOL_MAX")
                        .mapNotNull { k ->
                            get(k)?.let { k to it }
                        }.toMap(),
                    DEFAULT_POOL_MAX,
                )
            // 最小権限: 本番では dashboard 用（app）のユーザー以外（migrate など強い権限）で動かさない。ローカルは任意のユーザーを許す
            check(appEnv == AppEnv.LOCAL || db.user == DB_USER) { "DB_USER が dashboard 用のユーザー($DB_USER)ではない" }
            val opsEnv = listOf(OpsModeProviders.ENV_SOURCE, OpsModeProviders.ENV_APP).mapNotNull { k -> get(k)?.let { k to it } }.toMap()
            val audience = get("IAP_AUDIENCE")?.trim().orEmpty()
            check(audience.isNotEmpty()) { "IAP_AUDIENCE が未設定" }
            val opsSa = get("OPS_SA_EMAIL")?.trim().orEmpty()
            check(opsSa.isNotEmpty()) { "OPS_SA_EMAIL が未設定" }
            return DashboardSettings(
                appEnv,
                db,
                opsEnv,
                audience,
                opsSa,
                get("RELEASE_ID"),
                HumanAuthorizer.parseList("ALLOWED_USERS", get("ALLOWED_USERS")),
                HumanAuthorizer.parseList("ADMIN_USERS", get("ADMIN_USERS")),
            )
        }
    }
}
