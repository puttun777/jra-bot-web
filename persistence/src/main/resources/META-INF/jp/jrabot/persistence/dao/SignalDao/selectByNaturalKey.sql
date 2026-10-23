SELECT /*%expand*/*
FROM signals
WHERE tenant_id = /* tenantId */0
  AND race_id = /* raceId */'x'
  AND market = /* market */'win'
  AND umaban = /* umaban */0
  AND observed_at = /* observedAt */'2000-01-01'
