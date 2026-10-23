SELECT /*%expand*/*
FROM ingest_fetch
WHERE tenant_id = /* tenantId */0 AND fetch_id = /* fetchId */'x'
FOR UPDATE
