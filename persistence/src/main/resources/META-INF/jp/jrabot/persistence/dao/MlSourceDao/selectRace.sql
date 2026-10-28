SELECT tenant_id, race_id, race_date, post_time, post_time_unknown, distance_m, track_type_cd, course_kubun_cd, tenko_cd, shiba_baba_cd, dirt_baba_cd, updated_at
FROM races
WHERE tenant_id = /* tenantId */0
  AND race_id = /* raceId */'x'
