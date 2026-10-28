SELECT r.record_hash, r.batch_id, b.batch_class, b.observed_at, r.dataspec, r.record_id, r.payload
FROM ingest_raw r
JOIN ingest_batch b ON b.tenant_id = r.tenant_id AND b.batch_id = r.batch_id
WHERE r.tenant_id = /* tenantId */0
  AND b.tenant_id = /* tenantId */0
  AND r.parse_status = 'PENDING'
  AND r.parse_error = /* reason */'x'
ORDER BY r.received_at, r.batch_id, r.seq
LIMIT /* limit */200
