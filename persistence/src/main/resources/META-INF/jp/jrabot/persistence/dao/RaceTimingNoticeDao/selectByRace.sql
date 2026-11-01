SELECT /*%expand*/*
FROM race_timing_notice
WHERE tenant_id = /* tenantId */0 AND race_id = /* raceId */'x'
ORDER BY timing_centi
