package jp.jrabot.domain.agent

import jp.jrabot.domain.ingest.AgentStatus
import jp.jrabot.domain.tenant.TenantId
import java.time.Instant

/** 管理画面のエージェントの 1 行（R20。D-270）。**資格情報（トークンのハッシュ・署名鍵）は含めない**（app の DB ユーザーはその列を読めない）。 */
data class AgentSummary(
    val tenantId: TenantId,
    val agentId: AgentId,
    val name: String,
    val tokenExpiresAt: Instant,
    val status: AgentStatus,
    val createdAt: Instant,
    val createdBy: String?,
    val revokedAt: Instant?,
    val revokedBy: String?,
)

sealed interface AgentRevokeResult {
    data class Revoked(
        val agent: AgentSummary,
    ) : AgentRevokeResult

    /** 既に失効している（何も変えない）。 */
    data object AlreadyRevoked : AgentRevokeResult

    /** このテナントに無い（他テナントのエージェントも NotFound。存在を漏らさない）。 */
    data object NotFound : AgentRevokeResult
}

/**
 * エージェントの一覧と失効（R20。CONTRACTS §3.3「失効・盗難時: 管理者 API で失効 → 以後 403」）。テナントは主体から決める。
 * 失効は ACTIVE のときだけ REVOKED にする条件付き更新（同時の失効・再実行で二重に記録しない）。**再有効化・発行の操作は持たない**
 * （発行は登録コードの設計〔CONTRACTS §3.3.2〕が承認されるまで作らない）。ingest は毎回 DB から読むので、失効は次の要求から 403 になる。
 */
interface AgentAdminStore {
    fun list(tenantId: TenantId): List<AgentSummary>

    fun find(
        tenantId: TenantId,
        agentId: AgentId,
    ): AgentSummary?

    fun revoke(
        tenantId: TenantId,
        agentId: AgentId,
        by: String,
        at: Instant,
    ): AgentRevokeResult
}
