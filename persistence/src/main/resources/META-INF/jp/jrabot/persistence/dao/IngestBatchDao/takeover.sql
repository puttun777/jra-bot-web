UPDATE ingest_batch
SET status = 'PROCESSING',
    received_at = /* newReceivedAt */'2000-01-01',
    completed_at = NULL,
    fail_reason = NULL
WHERE tenant_id = /* tenantId */0
  AND batch_id = /* batchId */'x'
  AND status = /* expectedStatus */'FAILED'
  AND received_at = /* expectedReceivedAt */'2000-01-01'
