SELECT /*%expand*/*
FROM ingest_gap
WHERE tenant_id = /* tenantId */0 AND resolved_at IS NULL
ORDER BY gap_from, id
