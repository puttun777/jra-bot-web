UPDATE ingest_gap
SET resolved_at = /* now */'2000-01-01',
    gap_to = COALESCE(gap_to, GREATEST(gap_from, /* now */'2000-01-01'))
WHERE tenant_id = /* tenantId */0
  AND id = /* id */0
  AND resolved_at IS NULL
