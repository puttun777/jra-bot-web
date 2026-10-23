SELECT /*%expand*/*
FROM race_schedule_event
WHERE tenant_id = /* tenantId */0
  AND dedup_hash = /* dedupHash */'x'
