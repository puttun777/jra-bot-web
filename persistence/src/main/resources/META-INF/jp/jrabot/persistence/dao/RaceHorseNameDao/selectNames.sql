SELECT umaban, bamei
FROM race_horses
WHERE tenant_id = /* tenantId */0
  AND race_id = /* raceId */'x'
  AND bamei IS NOT NULL
ORDER BY umaban
