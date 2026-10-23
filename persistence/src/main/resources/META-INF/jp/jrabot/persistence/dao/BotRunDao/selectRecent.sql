SELECT /*%expand*/*
FROM bot_run
WHERE tenant_id = /* tenantId */0
ORDER BY started_at DESC, run_id DESC
LIMIT /* limit */10
