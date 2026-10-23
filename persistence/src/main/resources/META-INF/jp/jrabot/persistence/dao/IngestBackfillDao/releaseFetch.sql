UPDATE ingest_backfill
SET status = 'REQUESTED',
    fetch_id = NULL,
    fail_reason = /* reason */'x',
    updated_at = /* now */'2000-01-01'
WHERE tenant_id = /* tenantId */0
  AND id = /* id */0
  AND status = 'IN_PROGRESS'
  AND fetch_id = /* fetchId */'x'
