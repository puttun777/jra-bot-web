DELETE FROM bot_run WHERE tenant_id = /* tenantId */0 AND started_at < /* before */'2000-01-01 00:00:00' AND active_slot IS NULL LIMIT /* limit */1
