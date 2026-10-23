UPDATE ingest_batch
SET status = 'FAILED',
    fail_reason = /* reason */'x'
WHERE tenant_id = /* tenantId */0
  AND batch_id = /* batchId */'x'
  AND status = 'PROCESSING'
  AND received_at = /* token */'2000-01-01'
