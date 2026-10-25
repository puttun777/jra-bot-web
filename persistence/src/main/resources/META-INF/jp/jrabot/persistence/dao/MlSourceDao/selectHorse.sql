SELECT tenant_id, ketto_num, birth_date, pedigree_json, style_tendency_json, chaku_by_surface_json, chaku_by_condition_json, chaku_by_distance_json, updated_at
FROM horses
WHERE tenant_id = /* tenantId */0
  AND ketto_num = /* kettoNum */'x'
