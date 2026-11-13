package jp.jrabot.dashboard

import jp.jrabot.domain.ops.AppEnv
import jp.jrabot.domain.tenant.EmailAddress
import jp.jrabot.domain.tenant.HumanAuthorizer
import jp.jrabot.persistence.jdbc.DbSettings
import jp.jrabot.platform.RuntimePlatform
import jp.jrabot.platform.ops.OpsModeProviders

/**
 * dashboard の環境変数による設定（CONTRACTS §2）。足りない・不正なら起動を失敗させる（既定値に黙って落とさない）。
 * 例外のメッセージには変数名だけを含め、値（パスワード・URL）は含めない。
 */
class DashboardSettings(
    val appEnv: AppEnv,
    val db: DbSettings,
    /** `OpsModeProviders.fromEnvironment` に渡す環境（`OPS_MODE_SOURCE`、`APP_ENV`）。 */
    val opsEnv: Map<String, String>,
    /** 実行基盤（D-281）。GCP は [iapAudience]・[opsServiceAccountEmail]、AWS は [cognito]・[internalOpsTokenFile] が必須。 */
    val platform: RuntimePlatform,
    val iapAudience: String,
    val opsServiceAccountEmail: String,
    val releaseIdOverride: String?,
    /** `ALLOWED_USERS`（空なら全拒否）。 */
    val allowedUsers: List<EmailAddress> = emptyList(),
    /** `ADMIN_USERS`（ロール ADMIN の行は、ここにも載っているときだけ認める）。 */
    val adminUsers: List<EmailAddress> = emptyList(),
    /** AWS: Cognito のユーザープールの issuer と app client id（`COGNITO_ISSUER`・`COGNITO_CLIENT_ID`）。GCP では null。 */
    val cognito: CognitoSettings? = null,
    /** AWS: 内部 API のトークンのファイル（`INTERNAL_OPS_TOKEN_FILE`）。GCP では null。 */
    val internalOpsTokenFile: String? = null,
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
            val platform = RuntimePlatform.fromEnv(get(RuntimePlatform.ENV))
            val audience = get("IAP_AUDIENCE")?.trim().orEmpty()
            val opsSa = get("OPS_SA_EMAIL")?.trim().orEmpty()
            var cognito: CognitoSettings? = null
            var tokenFile: String? = null
            when (platform) {
                RuntimePlatform.GCP -> {
                    check(audience.isNotEmpty()) { "IAP_AUDIENCE が未設定" }
                    check(opsSa.isNotEmpty()) { "OPS_SA_EMAIL が未設定" }
                }

                RuntimePlatform.AWS -> {
                    val issuer = get("COGNITO_ISSUER")?.trim().orEmpty()
                    val clientId = get("COGNITO_CLIENT_ID")?.trim().orEmpty()
                    check(issuer.isNotEmpty() && clientId.isNotEmpty()) { "COGNITO_ISSUER と COGNITO_CLIENT_ID が未設定（RUNTIME_PLATFORM=aws）" }
                    cognito = CognitoSettings(issuer, clientId)
                    tokenFile = get("INTERNAL_OPS_TOKEN_FILE")?.trim()?.takeIf { it.isNotEmpty() }
                        ?: throw IllegalStateException("INTERNAL_OPS_TOKEN_FILE が未設定（RUNTIME_PLATFORM=aws）")
                }
            }
            return DashboardSettings(
                appEnv,
                db,
                opsEnv,
                platform,
                audience,
                opsSa,
                get("RELEASE_ID"),
                HumanAuthorizer.parseList("ALLOWED_USERS", get("ALLOWED_USERS")),
                HumanAuthorizer.parseList("ADMIN_USERS", get("ADMIN_USERS")),
                cognito,
                tokenFile,
            )
        }
    }
}

/** Cognito のユーザープール（AWS。D-281）。形式の検査は検証器の生成時（不正なら起動失敗）。 */
data class CognitoSettings(
    val issuer: String,
    val clientId: String,
)
