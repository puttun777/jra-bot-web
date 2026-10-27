package jp.jrabot.bot.discord

import java.net.URI
import java.net.URISyntaxException

/**
 * 検証済みの Discord Webhook URL（SSRF 対策）。**任意の URL へ送れないようにする**:
 * - 本番: `https://discord.com/api/webhooks/<数字 ID>/<トークン>` だけ（ホストは discord.com / discordapp.com とそのサブドメイン `ptb` `canary`。ポートは 443 のみ、
 *   ユーザー情報・クエリ・フラグメントなし、IP リテラル不可）。リダイレクトは辿らない（[JdkWebhookTransport]）。
 * - テスト専用: [forLocalTest] だけが `http://127.0.0.1:<port>/…`（ループバック）を許す。呼び出しは明示的で、環境変数や設定からは有効にならない。
 * URL 全体が秘密（トークンを含む）なので、[toString] とエラーメッセージには**値を出さない**。
 */
class DiscordWebhookUrl private constructor(
    val uri: URI,
) {
    override fun toString(): String = "DiscordWebhookUrl(<redacted>)"

    override fun equals(other: Any?): Boolean = other is DiscordWebhookUrl && other.uri == uri

    override fun hashCode(): Int = uri.hashCode()

    companion object {
        private val HOSTS = setOf("discord.com", "ptb.discord.com", "canary.discord.com", "discordapp.com", "ptb.discordapp.com", "canary.discordapp.com")
        private val PATH = Regex("/api/webhooks/[0-9]{1,25}/[A-Za-z0-9_-]{1,256}")
        private const val MAX_LENGTH = 512

        /** 不正なら [IllegalArgumentException]（メッセージに値を含めない）。 */
        fun parse(raw: String): DiscordWebhookUrl {
            val uri = parseUri(raw)
            require(uri.scheme == "https") { "Webhook URL は https のみ" }
            require(uri.host?.lowercase() in HOSTS) { "Webhook URL のホストは Discord のものだけ" }
            require(uri.port == -1 || uri.port == 443) { "Webhook URL のポートは 443 のみ" }
            requireCommon(uri)
            return DiscordWebhookUrl(uri)
        }

        /** ローカルのテスト用 Fake サーバ向け。`http://127.0.0.1:<port>/api/webhooks/<id>/<token>` のループバックだけ。本番の経路から呼ばない。 */
        fun forLocalTest(raw: String): DiscordWebhookUrl {
            val uri = parseUri(raw)
            require(uri.scheme == "http") { "テスト用 URL は http のみ" }
            require(uri.host == "127.0.0.1" || uri.host == "localhost") { "テスト用 URL はループバックのみ" }
            require(uri.port in 1..65535) { "テスト用 URL にはポートが必要" }
            requireCommon(uri)
            return DiscordWebhookUrl(uri)
        }

        private fun parseUri(raw: String): URI {
            require(raw.isNotEmpty() && raw.length <= MAX_LENGTH && raw.all { it.code in 0x21..0x7E }) { "Webhook URL の形式が不正" }
            return try {
                URI(raw)
            } catch (e: URISyntaxException) {
                throw IllegalArgumentException("Webhook URL の形式が不正")
            }
        }

        private fun requireCommon(uri: URI) {
            require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "Webhook URL にユーザー情報・クエリ・フラグメントは付けられない" }
            require(PATH.matches(uri.rawPath ?: "")) { "Webhook URL のパスが不正" }
        }
    }
}
