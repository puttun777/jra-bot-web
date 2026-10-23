UPDATE ingest_backfill
SET status = 'CANCELLED',
    completed_at = /* now */'2000-01-01',
    updated_at = /* now */'2000-01-01'
WHERE tenant_id = /* tenantId */0
  AND id = /* id */0
  AND status IN ('REQUESTED', 'IN_PROGRESS')
