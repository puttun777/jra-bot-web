SELECT /*%expand*/*
FROM race_schedule_event
WHERE tenant_id = /* tenantId */0
  AND race_id = /* raceId */'x'
ORDER BY id
