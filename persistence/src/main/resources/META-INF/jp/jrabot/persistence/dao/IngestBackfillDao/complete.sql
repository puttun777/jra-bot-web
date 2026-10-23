UPDATE ingest_backfill
SET status = 'COMPLETED',
    completed_at = /* now */'2000-01-01',
    updated_at = /* now */'2000-01-01',
    fail_reason = NULL
WHERE tenant_id = /* tenantId */0
  AND id = /* id */0
  AND status = 'IN_PROGRESS'
  AND fetch_id = /* fetchId */'x'
