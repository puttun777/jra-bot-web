UPDATE ingest_backfill
SET status = 'IN_PROGRESS',
    fetch_id = /* fetchId */'x',
    updated_at = /* now */'2000-01-01'
WHERE tenant_id = /* tenantId */0
  AND id = /* id */0
  AND status IN ('REQUESTED', 'IN_PROGRESS')
  AND CASE
        WHEN fetch_id IS NULL THEN 1
        WHEN fetch_id = /* fetchId */'x' THEN 1
        WHEN updated_at < /* takeoverBefore */'2000-01-01' THEN 1
        ELSE 0
      END = 1
