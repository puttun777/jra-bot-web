SELECT tenant_id, agent_id, name, token_expires_at, status, created_at, created_by, revoked_at, revoked_by
FROM agent
WHERE tenant_id = /* tenantId */0
  AND agent_id = /* agentId */'x'
