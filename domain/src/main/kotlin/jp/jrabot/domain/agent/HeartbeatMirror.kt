package jp.jrabot.domain.agent

import jp.jrabot.domain.tenant.TenantId
import java.time.Instant

/** エージェントの ID（`ag_…`）。保存先のパスやログに出るので、英数字・`_`・`-` の 1〜64 文字。 */
@JvmInline
value class AgentId private constructor(
    val value: String,
) {
    override fun toString(): String = "AgentId($value)"

    companion object {
        private val pattern = Regex("[A-Za-z0-9_-]{1,64}")

        fun ofOrNull(text: String): AgentId? = if (pattern.matches(text)) AgentId(text) else null

        fun of(text: String): AgentId = requireNotNull(ofOrNull(text)) { "agent_id が不正: '$text'" }
    }
}

/**
 * エージェントの最終接続。外部ストアへ書くのは**時刻・ID・版だけ**（CONTRACTS §5.4。データ本文・トークン・IP・メールアドレスは持てない型にしている）。
 */
data class AgentHeartbeat(
    val agentId: AgentId,
    val lastSeenAt: Instant,
    val agentVersion: String,
) {
    init {
        require(VERSION.matches(agentVersion)) { "agent_version が不正: '$agentVersion'" }
    }

    private companion object {
        val VERSION = Regex("[0-9A-Za-z][0-9A-Za-z._+-]{0,31}")
    }
}

enum class MirrorOutcome {
    WRITTEN,

    /** 書込みの条件（前回から 5 分以上、または版の変更）を満たさず、書かなかった。 */
    SKIPPED,

    /** 失敗した（警告ログだけにする。heartbeat の応答には影響させない）。 */
    FAILED,
}

/**
 * エージェントの最終接続を、DB 停止中の status 用に外部ストア（GCS）へ写す口（CONTRACTS §5.4。実装は `:cloud-gcp`）。
 *
 * - 責務: 認証済みエージェントのテナントのドキュメント `heartbeat-<tenant_id>.json` を更新する。DB の `agent_heartbeat` が正本で、これは写し。
 * - テナント境界: 書込み先は **[TenantId] だけから組み立てる**（リクエストの値からパスを作らない）。他テナントのドキュメントには触れない。
 * - エラー: **例外を投げない**。失敗は [MirrorOutcome.FAILED]。
 * - 冪等性: 同じ入力の再実行で結果が変わらない（書込み条件を満たさなければ [MirrorOutcome.SKIPPED]、競合は再読込して 1 回だけ再試行）。
 */
interface HeartbeatMirror {
    fun record(
        tenantId: TenantId,
        heartbeat: AgentHeartbeat,
    ): MirrorOutcome
}
