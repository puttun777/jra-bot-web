SELECT /*%expand*/*
FROM bot_run
WHERE tenant_id = /* tenantId */0
  AND run_date = /* runDate */'2000-01-01'
ORDER BY started_at DESC, run_id DESC
