SELECT /*%expand*/*
FROM signals
WHERE tenant_id = /* tenantId */0
  AND race_id = /* raceId */'x'
ORDER BY observed_at, market, umaban
