UPDATE ingest_raw
SET parse_status = /* entity.parseStatus */'PARSED',
    parse_error = /* entity.parseError */'x'
WHERE tenant_id = /* entity.tenantId */0
  AND record_hash = /* entity.recordHash */'x'
  AND parse_status = 'PENDING'
