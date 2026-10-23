SELECT /*%expand*/*
FROM agent_heartbeat
WHERE tenant_id = /* tenantId */0 AND agent_id = /* agentId */'x'
