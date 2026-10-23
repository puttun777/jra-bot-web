SELECT /*%expand*/*
FROM ingest_batch
WHERE tenant_id = /* tenantId */0 AND batch_id = /* batchId */'x'
FOR UPDATE
