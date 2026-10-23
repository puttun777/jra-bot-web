SELECT /*%expand*/*
FROM signals
WHERE tenant_id = /* tenantId */0
  AND notify_state = 'UNSENT'
ORDER BY id
LIMIT /* limit */10
