SELECT /*%expand*/*
FROM bot_run
WHERE tenant_id = /* tenantId */0
  AND run_id = /* runId */'x'
FOR UPDATE
