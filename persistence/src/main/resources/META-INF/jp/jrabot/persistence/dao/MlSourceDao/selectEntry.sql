SELECT tenant_id, race_id, umaban, ketto_num
FROM race_horses
WHERE tenant_id = /* tenantId */0
  AND race_id = /* raceId */'x'
  AND umaban = /* umaban */1
