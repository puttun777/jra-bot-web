SELECT /*%expand*/*
FROM race_schedule
WHERE tenant_id = /* tenantId */0
  AND race_id = /* raceId */'x'
