SELECT /*%expand*/*
FROM race_schedule
WHERE tenant_id = /* tenantId */0
  AND race_date = /* date */'2000-01-01'
ORDER BY scheduled_post_at IS NULL, scheduled_post_at, race_id
