SELECT /*%expand*/*
FROM race_schedule_event
WHERE tenant_id = /* tenantId */0
  AND race_date = /* raceDate */'2000-01-01'
ORDER BY id
