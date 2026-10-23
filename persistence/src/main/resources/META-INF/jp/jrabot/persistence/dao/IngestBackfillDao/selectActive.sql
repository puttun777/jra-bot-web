SELECT /*%expand*/*
FROM ingest_backfill
WHERE tenant_id = /* tenantId */0 AND active_slot = 1
ORDER BY requested_at, id
