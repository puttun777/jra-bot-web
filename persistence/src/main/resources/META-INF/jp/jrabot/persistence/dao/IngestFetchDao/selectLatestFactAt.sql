SELECT MAX(completed_at)
FROM ingest_fetch
WHERE tenant_id = /* tenantId */0
  AND dataspec = /* dataspec */'YSCH'
  AND status = 'SUCCEEDED'
  AND covered_from <= /* date */'2000-01-01'
  AND covered_to >= /* date */'2000-01-01'
