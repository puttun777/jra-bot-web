SELECT /*%expand*/*
FROM odds_snapshots
WHERE tenant_id = /* tenantId */0
  AND race_id = /* raceId */'x'
  AND market = /* market */'win'
ORDER BY observed_at, umaban
