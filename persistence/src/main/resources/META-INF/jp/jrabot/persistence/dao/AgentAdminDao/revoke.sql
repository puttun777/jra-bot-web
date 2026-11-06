UPDATE agent
SET status = 'REVOKED',
    revoked_at = /* revokedAt */'2026-01-01 00:00:00',
    revoked_by = /* revokedBy */'x'
WHERE tenant_id = /* tenantId */0
  AND agent_id = /* agentId */'x'
  AND status = 'ACTIVE'
