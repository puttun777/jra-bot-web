SELECT /*%expand*/*
FROM ingest_raw
WHERE tenant_id = /* tenantId */0 AND record_hash = /* recordHash */'x'
