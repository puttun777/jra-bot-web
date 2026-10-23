SELECT /*%expand*/*
FROM race_day
WHERE tenant_id = /* tenantId */0
  AND race_date = /* raceDate */'2000-01-01'
  AND venue_code = /* venueCode */'x'
