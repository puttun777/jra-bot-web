SELECT /*%expand*/*
FROM combo_odds_snapshots
WHERE tenant_id = /* tenantId */0
  AND race_id = /* raceId */'x'
  AND market = /* market */'wide'
ORDER BY collected_at, combo_key
