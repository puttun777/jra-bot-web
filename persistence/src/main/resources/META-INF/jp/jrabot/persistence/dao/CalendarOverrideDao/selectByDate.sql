SELECT /*%expand*/*
FROM calendar_override
WHERE tenant_id = /* tenantId */0
  AND race_date = /* raceDate */'2000-01-01'
ORDER BY id
