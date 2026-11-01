SELECT /*%expand*/*
FROM payouts
WHERE tenant_id = /* tenantId */0
  AND race_id = /* raceId */'x'
  AND market = /* market */'win'
ORDER BY combo_key
