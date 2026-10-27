SELECT /*%expand*/*
FROM signals
WHERE tenant_id = /* tenantId */0
  AND notify_state = 'CLAIMED'
  AND notify_updated_at < /* cutoff */'2000-01-01'
ORDER BY id
LIMIT /* limit */10
