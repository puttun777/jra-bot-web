package jp.jrabot.persistence.repository

import jp.jrabot.domain.agent.AgentAdminStore
import jp.jrabot.domain.agent.AgentId
import jp.jrabot.domain.agent.AgentRevokeResult
import jp.jrabot.domain.agent.AgentSummary
import jp.jrabot.domain.ingest.AgentStatus
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.persistence.dao.AgentAdminDao
import jp.jrabot.persistence.dao.AgentSummaryEntity
import jp.jrabot.persistence.mapping.enumFromDb
import jp.jrabot.persistence.mapping.toDbTime
import jp.jrabot.persistence.mapping.toInstantUtc
import jp.jrabot.persistence.mapping.toInstantUtcOrNull
import java.time.Instant

/** エージェントの一覧と失効（R20。D-270）。失効は条件付き更新（ACTIVE → REVOKED）で、0 件なら既に失効か無いかを読んで区別する。 */
internal class AgentAdminRepository(
    private val dao: AgentAdminDao,
) : AgentAdminStore {
    override fun list(tenantId: TenantId): List<AgentSummary> = dao.selectByTenant(tenantId.value).map { it.toDomain() }

    override fun find(
        tenantId: TenantId,
        agentId: AgentId,
    ): AgentSummary? = dao.selectOne(tenantId.value, agentId.value)?.toDomain()

    override fun revoke(
        tenantId: TenantId,
        agentId: AgentId,
        by: String,
        at: Instant,
    ): AgentRevokeResult {
        require(by.isNotBlank() && by.length <= 254) { "revoked_by は 1〜254 文字" }
        val updated = dao.revoke(tenantId.value, agentId.value, at.toDbTime(), by)
        val now = dao.selectOne(tenantId.value, agentId.value)?.toDomain() ?: return AgentRevokeResult.NotFound
        return if (updated == 1) AgentRevokeResult.Revoked(now) else AgentRevokeResult.AlreadyRevoked
    }
}

private fun AgentSummaryEntity.toDomain() =
    AgentSummary(
        tenantId = TenantId.of(tenantId),
        agentId = AgentId.ofOrNull(agentId) ?: throw IllegalStateException("agent.agent_id の形式が不正（DDL の CHECK と食い違い）"),
        name = name,
        tokenExpiresAt = tokenExpiresAt.toInstantUtc(),
        status = enumFromDb<AgentStatus>(status, "agent.status"),
        createdAt = createdAt.toInstantUtc(),
        createdBy = createdBy,
        revokedAt = revokedAt.toInstantUtcOrNull(),
        revokedBy = revokedBy,
    )
