SELECT tenant_id, race_id, umaban, finish
FROM results
WHERE tenant_id = /* tenantId */0
  AND race_id = /* raceId */'x'
ORDER BY umaban
