SELECT /*%expand*/*
FROM race_evaluations
WHERE tenant_id = /* tenantId */0
  AND race_id = /* raceId */'x'
ORDER BY snapshot_at, id
