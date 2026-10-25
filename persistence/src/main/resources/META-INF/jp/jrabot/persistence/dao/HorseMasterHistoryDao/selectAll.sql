SELECT tenant_id, ketto_num, known_at, source, style_tendency_json, chaku_by_surface_json, chaku_by_condition_json, chaku_by_distance_json, race_count, created_at
FROM horse_master_history
WHERE tenant_id = /* tenantId */0
  AND ketto_num = /* kettoNum */'x'
ORDER BY known_at
