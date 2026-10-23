SELECT /*%expand*/*
FROM ingest_backfill
WHERE tenant_id = /* tenantId */0
  AND dataspec = /* dataspec */'x'
  AND option_key = /* optionKey */0
  AND fromtime = /* fromtime */'x'
  AND active_slot = 1
