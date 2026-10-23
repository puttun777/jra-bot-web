UPDATE race_day
SET last_confirmed_at = /* entity.lastConfirmedAt */'2000-01-01',
    version = version + 1
WHERE tenant_id = /* entity.tenantId */0
  AND race_date = /* entity.raceDate */'2000-01-01'
  AND venue_code = /* entity.venueCode */'x'
  AND COALESCE(last_confirmed_at, '1000-01-01 00:00:00') < /* entity.lastConfirmedAt */'2000-01-01'
