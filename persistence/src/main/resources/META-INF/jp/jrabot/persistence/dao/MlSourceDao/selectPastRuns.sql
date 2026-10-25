SELECT tenant_id, ketto_num, race_id, run_date, finish
FROM horse_past_runs
WHERE tenant_id = /* tenantId */0
  AND ketto_num = /* kettoNum */'x'
  AND finish IS NOT NULL
  AND finish > 0
/*%if before != null */
  AND run_date < /* before */'2026-01-01'
/*%end*/
ORDER BY run_date DESC, race_id DESC
LIMIT /* limit */5
