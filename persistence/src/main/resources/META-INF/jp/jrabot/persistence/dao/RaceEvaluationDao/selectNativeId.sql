SELECT id
FROM race_evaluations
WHERE tenant_id = /* tenantId */0
  AND race_id = /* raceId */'x'
  AND snapshot_at = /* snapshotAt */'2000-01-01'
  AND legacy_id IS NULL
