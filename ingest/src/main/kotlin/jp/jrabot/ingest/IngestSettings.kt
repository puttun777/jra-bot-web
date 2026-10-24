package jp.jrabot.ingest

import jp.jrabot.cloudgcp.ops.OpsModeProviders
import jp.jrabot.domain.ingest.KeyEncryptionKeys
import jp.jrabot.domain.ingest.OddsBoundaryPolicy
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
    /** 署名鍵を暗号化する KEK（Secret Manager 由来）。null なら**エージェント API は認証できず 503**（未認証で業務データを保存しない）。 */
    val agentKeys: KeyEncryptionKeys?,
    val ingest: IngestOptions,
) {
    companion object {
        const val DEFAULT_POOL_MAX = 3 // CONTRACTS §8.3: ingest
        const val DB_USER = "ingest" // CONTRACTS §8.2

        /** 値の例外メッセージに秘密を含めないよう、変数名だけを報告する。 */
        fun from(get: (String) -> String?): IngestSettings {
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
            // 最小権限: 本番では ingest 用のユーザー以外（migrate など強い権限）で動かさない。ローカルは任意のユーザーを許す
            check(appEnv == AppEnv.LOCAL || db.user == DB_USER) { "DB_USER が ingest 用のユーザー($DB_USER)ではない" }
            val opsEnv = listOf(OpsModeProviders.ENV_SOURCE, OpsModeProviders.ENV_APP).mapNotNull { k -> get(k)?.let { k to it } }.toMap()
            val audience = get("INTERNAL_ID_TOKEN_AUDIENCE")?.trim().orEmpty()
            check(audience.isNotEmpty()) { "INTERNAL_ID_TOKEN_AUDIENCE が未設定" }
            val opsSa = get("OPS_SA_EMAIL")?.trim().orEmpty()
            check(opsSa.isNotEmpty()) { "OPS_SA_EMAIL が未設定" }
            val keysSpec = get("AGENT_KEK_KEYS")?.trim().orEmpty()
            val activeKid = get("AGENT_KEK_ACTIVE_KID")?.trim().orEmpty()
            val keys =
                if (keysSpec.isEmpty() && activeKid.isEmpty()) {
                    check(appEnv == AppEnv.LOCAL) { "AGENT_KEK_KEYS / AGENT_KEK_ACTIVE_KID が未設定（本番では必須）" }
                    null
                } else {
                    check(keysSpec.isNotEmpty() && activeKid.isNotEmpty()) { "AGENT_KEK_KEYS と AGENT_KEK_ACTIVE_KID は両方設定する" }
                    try {
                        KeyEncryptionKeys.parse(keysSpec, activeKid)
                    } catch (_: IllegalArgumentException) {
                        throw IllegalStateException("AGENT_KEK_KEYS / AGENT_KEK_ACTIVE_KID の形式が不正")
                    }
                }
            return IngestSettings(appEnv, db, opsEnv, audience, opsSa, get("RELEASE_ID"), keys, IngestOptions.from(appEnv, get))
        }
    }
}

/** 取込の挙動の設定（環境変数。いずれも既定は安全側）。 */
class IngestOptions(
    val oddsBelowPar: OddsBoundaryPolicy,
    val storeComboOdds: Boolean,
    /** 合成の正規化 JSON レコードを解析する（**ローカル・テスト専用**。本番では有効にできない: 実データの取込を偽装しない）。 */
    val acceptNormalizedRecords: Boolean,
    val maxBodyBytes: Int,
    val maxInflatedBytes: Int,
    /** 取得計画の項目（JSON 配列。運用者が明示したものだけ。既定は空 = idle）。 */
    val planItemsJson: String = "",
    /** 公式仕様で確認済みの取得（`dataspec:option` または速報は `dataspec`）。**既定は空**: ここに無い項目は計画に載せない（未確認の仕様を配らない）。 */
    val verifiedFetchSpecs: Set<String> = emptySet(),
    /** 署名済みの更新 manifest のファイル（オフラインで署名したもの。サーバは署名鍵を持たない）。未設定なら更新なし。 */
    val updateManifestPath: String = "",
) {
    companion object {
        const val DEFAULT_MAX_BODY = 1024 * 1024
        const val DEFAULT_MAX_INFLATED = 32 * 1024 * 1024

        fun from(
            appEnv: AppEnv,
            get: (String) -> String?,
        ): IngestOptions {
            val below =
                when (val v = get("INGEST_ODDS_BELOW_PAR")?.trim()?.lowercase()) {
                    null, "", "quarantine" -> OddsBoundaryPolicy.QUARANTINE_BELOW_PAR
                    "accept" -> OddsBoundaryPolicy.ACCEPT_ALL
                    else -> throw IllegalStateException("INGEST_ODDS_BELOW_PAR は quarantine または accept: '$v'")
                }
            val normalized = bool(get, "INGEST_ACCEPT_NORMALIZED_RECORDS")
            check(!normalized || appEnv == AppEnv.LOCAL) { "INGEST_ACCEPT_NORMALIZED_RECORDS は APP_ENV=local のときだけ有効にできる" }
            return IngestOptions(
                below,
                bool(get, "INGEST_STORE_COMBO_ODDS"),
                normalized,
                int(get, "INGEST_MAX_BODY_BYTES", DEFAULT_MAX_BODY, 1024, 8 * 1024 * 1024),
                int(get, "INGEST_MAX_INFLATED_BYTES", DEFAULT_MAX_INFLATED, 1024, 128 * 1024 * 1024),
                get("INGEST_PLAN_ITEMS_JSON")?.trim().orEmpty(),
                get("INGEST_VERIFIED_FETCH_SPECS")
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.toSet()
                    .orEmpty(),
                get("INGEST_UPDATE_MANIFEST_PATH")?.trim().orEmpty(),
            )
        }

        private fun bool(
            get: (String) -> String?,
            name: String,
        ): Boolean =
            when (val v = get(name)?.trim()?.lowercase()) {
                null, "", "false" -> false
                "true" -> true
                else -> throw IllegalStateException("$name は true または false")
            }

        private fun int(
            get: (String) -> String?,
            name: String,
            default: Int,
            min: Int,
            max: Int,
        ): Int {
            val raw = get(name)?.trim().orEmpty()
            if (raw.isEmpty()) return default
            val v = raw.toIntOrNull() ?: throw IllegalStateException("$name は整数")
            check(v in min..max) { "$name は $min〜$max" }
            return v
        }

        val DEFAULT = IngestOptions(OddsBoundaryPolicy.QUARANTINE_BELOW_PAR, false, false, DEFAULT_MAX_BODY, DEFAULT_MAX_INFLATED)
    }
}
