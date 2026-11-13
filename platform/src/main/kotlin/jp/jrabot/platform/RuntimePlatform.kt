package jp.jrabot.platform

/**
 * 実行基盤（環境変数 `RUNTIME_PLATFORM`。D-281）。人の認証・内部 API の認証の方式を決める（保存先は URI のスキームで決まる）。
 *
 * - [GCP]（既定。未設定も GCP）: dashboard は IAP の JWT、内部 API は IAP の JWT（dashboard）・Google ID トークン（ingest）。
 * - [AWS]: dashboard の人は Cognito の ID トークン（前段の oauth2-proxy が `Authorization: Bearer` で渡す）、内部 API は同じホストの
 *   運用エージェントだけが持つトークン（`INTERNAL_OPS_TOKEN_FILE`）。外向きのプロキシは `/internal` を通さない。
 *
 * 不明な値は起動失敗（黙って GCP に倒さない）。
 */
enum class RuntimePlatform(
    val value: String,
) {
    GCP("gcp"),
    AWS("aws"),
    ;

    companion object {
        const val ENV = "RUNTIME_PLATFORM"

        fun fromEnv(raw: String?): RuntimePlatform =
            when (raw?.trim().orEmpty()) {
                "", "gcp" -> GCP
                "aws" -> AWS
                else -> throw IllegalStateException("$ENV は gcp または aws を指定してください")
            }
    }
}
