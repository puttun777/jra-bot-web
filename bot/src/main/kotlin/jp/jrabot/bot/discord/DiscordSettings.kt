package jp.jrabot.bot.discord

import jp.jrabot.domain.notify.MentionPolicy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Discord 通知の設定（CONTRACTS §2。値は Secret Manager から環境変数で渡る）。いずれも**任意**: 未設定ならその宛先へは送らない（[DiscordNotifier.canDeliver] が false）。
 * 不正な値は [IllegalArgumentException]（終了コード 2）。**メッセージには変数名だけ**を含め、値（URL・ID）は含めない。
 */
class DiscordSettings(
    val signalUrl: DiscordWebhookUrl?,
    val errorUrl: DiscordWebhookUrl?,
    val mention: MentionPolicy,
) {
    override fun toString(): String = "DiscordSettings(signal=${signalUrl != null}, error=${errorUrl != null}, mention=$mention)"

    companion object {
        const val ENV_WEBHOOK = "DISCORD_WEBHOOK_URL"
        const val ENV_ERROR_WEBHOOK = "DISCORD_ERROR_WEBHOOK_URL"
        const val ENV_MENTION_USER_ID = "DISCORD_MENTION_USER_ID"

        val NONE = DiscordSettings(null, null, MentionPolicy.NONE)

        fun fromEnv(env: Map<String, String>): DiscordSettings =
            DiscordSettings(
                signalUrl = url(env, ENV_WEBHOOK),
                errorUrl = url(env, ENV_ERROR_WEBHOOK),
                mention =
                    try {
                        MentionPolicy.fromSetting(env[ENV_MENTION_USER_ID])
                    } catch (e: IllegalArgumentException) {
                        throw IllegalArgumentException("$ENV_MENTION_USER_ID が不正です（ASCII 数字 17〜20 桁）")
                    },
            )

        private fun url(
            env: Map<String, String>,
            name: String,
        ): DiscordWebhookUrl? {
            val raw = env[name]?.trim().orEmpty()
            if (raw.isEmpty()) return null
            return try {
                DiscordWebhookUrl.parse(raw)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("$name が不正です（Discord の Webhook URL ではありません）")
            }
        }
    }
}

/**
 * 停止要求（SIGTERM など）の旗。通知の送信は、立っている間は新しい送信を始めない。**シグナルハンドラの設置は bot-run（T3.8）**で、ここは旗だけ。
 */
class StopFlag {
    private val flag = AtomicBoolean(false)

    fun request() = flag.set(true)

    fun isRequested(): Boolean = flag.get()
}
