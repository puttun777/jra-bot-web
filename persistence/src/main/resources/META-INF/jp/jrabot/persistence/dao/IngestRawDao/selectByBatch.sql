SELECT /*%expand*/*
FROM ingest_raw
WHERE tenant_id = /* tenantId */0 AND batch_id = /* batchId */'x'
ORDER BY seq
