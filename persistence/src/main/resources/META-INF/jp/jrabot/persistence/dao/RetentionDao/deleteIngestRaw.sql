DELETE FROM ingest_raw WHERE tenant_id = /* tenantId */0 AND received_at < /* before */'2000-01-01 00:00:00' AND parse_status <> 'PENDING' LIMIT /* limit */1
