SELECT tenant_id, race_id, market, observed_at, umaban, odds, popularity
FROM odds_snapshots
WHERE tenant_id = /* tenantId */0
  AND race_id = /* raceId */'x'
  AND market = 'win'
  AND umaban = /* umaban */1
  AND observed_at <= /* asOf */'2026-01-01 00:00:00'
ORDER BY observed_at
