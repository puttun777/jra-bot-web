SELECT /*%expand*/*
FROM odds_snapshots
WHERE tenant_id = /* tenantId */0
  AND race_id = /* raceId */'x'
  AND market = /* market */'win'
  AND observed_at = (
    SELECT MAX(observed_at)
    FROM odds_snapshots
    WHERE tenant_id = /* tenantId */0
      AND race_id = /* raceId */'x'
      AND market = /* market */'win'
  )
ORDER BY umaban
