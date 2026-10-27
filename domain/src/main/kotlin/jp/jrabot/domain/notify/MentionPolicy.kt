package jp.jrabot.domain.notify

/**
 * メンションの方針。**本人 1 名の個別メンションだけ**を許し、`@everyone`・`@here`・ロール・他のユーザーは常に許可しない。
 *
 * - 送信時の `allowed_mentions` は常に付ける: メンションなし = `{"parse":[]}`、本人あり = `{"parse":[],"users":["<id>"]}`。
 *   `parse` が空なので、本文に `@everyone` や `<@&ロール>` が含まれても通知は鳴らない（動的な馬名・レース名の対策は [DiscordText.neutralizeMentions] と二重）。
 * - ユーザー ID は Discord の snowflake（ASCII 数字 17〜20 桁）だけ。旧実装は任意の文字列から数字だけを抜き出していたが、**それはしない**（不正な値は拒否）。
 * - 旧 `mention`（自由文字列）は移植しない（`@everyone` などを先頭に付けられるため）。
 */
class MentionPolicy private constructor(
    val userId: String?,
) {
    /** 本文の先頭に付ける `<@id>`。メンションなしなら null。 */
    val prefix: String? get() = userId?.let { "<@$it>" }

    /** 送信 JSON の `allowed_mentions`。 */
    fun allowedMentionsJson(): String = if (userId == null) """{"parse":[]}""" else """{"parse":[],"users":["$userId"]}"""

    override fun toString(): String = if (userId == null) "MentionPolicy(none)" else "MentionPolicy(user)"

    companion object {
        val NONE = MentionPolicy(null)

        private val SNOWFLAKE = Regex("[0-9]{17,20}")

        /** 不正な ID は [IllegalArgumentException]（設定不正。値はメッセージに含めない）。 */
        fun forUser(userId: String): MentionPolicy {
            require(SNOWFLAKE.matches(userId)) { "メンション先のユーザー ID が不正（ASCII 数字 17〜20 桁）" }
            return MentionPolicy(userId)
        }

        /** 未設定・空は [NONE]。設定があれば検証する。 */
        fun fromSetting(raw: String?): MentionPolicy {
            val t = raw?.trim().orEmpty()
            return if (t.isEmpty()) NONE else forUser(t)
        }
    }
}

object DiscordText {
    private const val ZWSP = "​"

    /**
     * 動的な文字列（馬名・レース名・理由・注記）に含まれる `@` の直後にゼロ幅スペースを入れ、メンション表記として解釈されないようにする。
     * `allowed_mentions` が主な防御で、これは表示上の二重の安全策。`@` を含まない文字列は変わらない。
     */
    fun neutralizeMentions(s: String): String = if (s.contains('@')) s.replace("@", "@$ZWSP") else s
}
