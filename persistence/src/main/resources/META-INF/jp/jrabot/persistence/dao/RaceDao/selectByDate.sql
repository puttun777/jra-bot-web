SELECT /*%expand*/*
FROM races
WHERE tenant_id = /* tenantId */0
  AND race_date = /* date */'2000-01-01'
ORDER BY venue_code, race_no
