INSERT INTO results (tenant_id, race_id, umaban, finish, payout, created_at, updated_at)
VALUES (
  /* rows.tenantId */0, /* rows.raceId */'x', /* rows.umaban */0, /* rows.finish */0, /* rows.payout */0, /* rows.createdAt */'2000-01-01', /* rows.updatedAt */'2000-01-01'
) AS new
ON DUPLICATE KEY UPDATE
  updated_at = IF(NOT (results.finish <=> new.finish), new.updated_at, results.updated_at),
  finish = new.finish
