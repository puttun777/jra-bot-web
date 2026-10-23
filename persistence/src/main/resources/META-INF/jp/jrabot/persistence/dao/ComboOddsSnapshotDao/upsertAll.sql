INSERT INTO combo_odds_snapshots (tenant_id, race_id, market, collected_at, combo_key, odds, odds_high, popularity, source, rt_key, created_at)
VALUES (
  /* rows.tenantId */0, /* rows.raceId */'x', /* rows.market */'wide', /* rows.collectedAt */'2000-01-01', /* rows.comboKey */'x', /* rows.odds */0,
  /* rows.oddsHigh */0, /* rows.popularity */0, /* rows.source */'x', /* rows.rtKey */'x', /* rows.createdAt */'2000-01-01'
) AS new
ON DUPLICATE KEY UPDATE
  odds = new.odds,
  odds_high = new.odds_high,
  popularity = new.popularity,
  source = new.source,
  rt_key = new.rt_key
