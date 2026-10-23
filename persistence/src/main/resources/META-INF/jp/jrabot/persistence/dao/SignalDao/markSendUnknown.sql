UPDATE signals
SET notify_state = 'SEND_UNKNOWN',
    notify_updated_at = /* entity.notifyUpdatedAt */'2000-01-01'
WHERE tenant_id = /* entity.tenantId */0
  AND id = /* entity.id */0
  AND notify_state = 'CLAIMED'
