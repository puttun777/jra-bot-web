SELECT rh.race_id, rh.umaban
FROM race_horses rh
JOIN races r ON r.tenant_id = rh.tenant_id AND r.race_id = rh.race_id
WHERE rh.tenant_id = /* tenantId */0
  AND r.tenant_id = /* tenantId */0
  AND r.race_date >= /* from */'2026-01-01'
  AND r.race_date <= /* to */'2026-01-31'
ORDER BY r.race_date, rh.race_id, rh.umaban
