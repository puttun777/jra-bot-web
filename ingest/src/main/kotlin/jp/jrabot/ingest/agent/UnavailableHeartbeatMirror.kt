package jp.jrabot.ingest.agent

import jp.jrabot.domain.agent.AgentHeartbeat
import jp.jrabot.domain.agent.HeartbeatMirror
import jp.jrabot.domain.agent.MirrorOutcome
import jp.jrabot.domain.tenant.TenantId

/**
 * 最終接続の GCS への写し（CONTRACTS §5.4）の **GCS 実装は未実装**（`:cloud-gcp` の担当は後続: PROGRESS の未解決表）。それまでは何も書かず
 * [MirrorOutcome.SKIPPED] を返す（書いたことにしない）。DB の `agent_heartbeat` が正本で、heartbeat の応答には影響しない。
 */
class UnavailableHeartbeatMirror : HeartbeatMirror {
    override fun record(
        tenantId: TenantId,
        heartbeat: AgentHeartbeat,
    ): MirrorOutcome = MirrorOutcome.SKIPPED
}
