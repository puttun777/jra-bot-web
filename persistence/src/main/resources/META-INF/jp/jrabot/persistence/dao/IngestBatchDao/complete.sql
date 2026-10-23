UPDATE ingest_batch
SET status = 'COMPLETED',
    accepted_count = /* accepted */0,
    duplicate_count = /* duplicates */0,
    completed_at = /* now */'2000-01-01',
    fail_reason = NULL
WHERE tenant_id = /* tenantId */0
  AND batch_id = /* batchId */'x'
  AND status = 'PROCESSING'
  AND received_at = /* token */'2000-01-01'
