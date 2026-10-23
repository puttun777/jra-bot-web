package jp.jrabot.ingest

import jp.jrabot.cloudgcp.ops.OpsModeProviders
import jp.jrabot.domain.ops.AppEnv
import jp.jrabot.persistence.jdbc.DbSettings

/**
 * ingest の環境変数による設定（CONTRACTS §2）。足りない・不正なら起動を失敗させる（既定値に黙って落とさない）。
 * 例外のメッセージには変数名だけを含め、値（パスワード・URL）は含めない。
 */
class IngestSettings(
    val appEnv: AppEnv,
    val db: DbSettings,
    /** `OpsModeProviders.fromEnvironment` に渡す環境（`OPS_MODE_SOURCE`、`APP_ENV`）。 */
    val opsEnv: Map<String, String>,
    val internalAudience: String,
    val opsServiceAccountEmail: String,
    val releaseIdOverride: String?,
) {
    companion object {
        const val DEFAULT_POOL_MAX = 3 // CONTRACTS §8.3: ingest
        const val DB_USER = "ingest" // CONTRACTS §8.2

        fun from(get: (String) -> String?): IngestSettings {
            val appEnv =
                AppEnv.parseOrNull(get("APP_ENV"))
                    ?: throw IllegalStateException("APP_ENV は local または prod を指定してください")
            val db = DbSettings.fromEnv(listOf("DB_URL", "DB_USER", "DB_PASSWORD", "DB_POOL_MAX").mapNotNull { k -> get(k)?.let { k to it } }.toMap(), DEFAULT_POOL_MAX)
            // 最小権限: 本番では ingest 用のユーザー以外（migrate など強い権限）で動かさない。ローカルは任意のユーザーを許す
            check(appEnv == AppEnv.LOCAL || db.user == DB_USER) { "DB_USER が ingest 用のユーザー($DB_USER)ではない" }
            val opsEnv = listOf(OpsModeProviders.ENV_SOURCE, OpsModeProviders.ENV_APP).mapNotNull { k -> get(k)?.let { k to it } }.toMap()
            val audience = get("INTERNAL_ID_TOKEN_AUDIENCE")?.trim().orEmpty()
            check(audience.isNotEmpty()) { "INTERNAL_ID_TOKEN_AUDIENCE が未設定" }
            val opsSa = get("OPS_SA_EMAIL")?.trim().orEmpty()
            check(opsSa.isNotEmpty()) { "OPS_SA_EMAIL が未設定" }
            return IngestSettings(appEnv, db, opsEnv, audience, opsSa, get("RELEASE_ID"))
        }
    }
}
