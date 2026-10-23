SELECT /*%expand*/*
FROM races
WHERE tenant_id = /* tenantId */0
  AND race_id = /* raceId */'x'
