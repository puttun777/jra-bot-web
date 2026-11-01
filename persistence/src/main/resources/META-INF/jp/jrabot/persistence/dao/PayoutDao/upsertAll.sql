INSERT INTO payouts (tenant_id, race_id, market, combo_key, payout, is_provisional, created_at, updated_at)
VALUES (
  /* rows.tenantId */0, /* rows.raceId */'x', /* rows.market */'win', /* rows.comboKey */'x', /* rows.payout */0, /* rows.isProvisional */0,
  /* rows.createdAt */'2000-01-01', /* rows.updatedAt */'2000-01-01'
) AS new
ON DUPLICATE KEY UPDATE
  updated_at = IF(NOT (payouts.is_provisional = 0 AND new.is_provisional = 1)
                  AND (payouts.payout <> new.payout OR payouts.is_provisional <> new.is_provisional), new.updated_at, payouts.updated_at),
  payout = IF(payouts.is_provisional = 0 AND new.is_provisional = 1, payouts.payout, new.payout),
  is_provisional = IF(payouts.is_provisional = 0 AND new.is_provisional = 1, payouts.is_provisional, new.is_provisional)
