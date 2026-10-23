UPDATE race_day
SET status = /* entity.status */'x',
    replaced_by_date = /* entity.replacedByDate */'2000-01-01',
    last_confirmed_at = /* entity.lastConfirmedAt */'2000-01-01',
    version = version + 1
WHERE tenant_id = /* entity.tenantId */0
  AND race_date = /* entity.raceDate */'2000-01-01'
  AND venue_code = /* entity.venueCode */'x'
  AND version = /* entity.version */0
