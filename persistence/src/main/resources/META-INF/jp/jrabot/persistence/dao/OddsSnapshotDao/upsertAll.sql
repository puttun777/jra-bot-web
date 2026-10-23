INSERT INTO odds_snapshots (tenant_id, race_id, market, observed_at, umaban, odds, popularity)
VALUES (
  /* rows.tenantId */0, /* rows.raceId */'x', /* rows.market */'win', /* rows.observedAt */'2000-01-01', /* rows.umaban */0,
  /* rows.odds */0, /* rows.popularity */0
) AS new
ON DUPLICATE KEY UPDATE
  odds = new.odds,
  popularity = new.popularity
