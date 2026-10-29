SELECT r.record_hash, r.batch_id, b.batch_class, b.observed_at, r.dataspec, r.record_id, r.payload, r.received_at, r.seq
FROM ingest_raw r
JOIN ingest_batch b ON b.tenant_id = r.tenant_id AND b.batch_id = r.batch_id
WHERE r.tenant_id = /* tenantId */0
  AND b.tenant_id = /* tenantId */0
  AND r.parse_status = 'PENDING'
  AND r.parse_error IN /* reasons */('x')
/*%if hasAfter */
  AND (r.received_at, r.batch_id, r.seq) < (/* afterReceivedAt */'2000-01-01', /* afterBatchId */'x', /* afterSeq */0)
/*%end*/
ORDER BY r.received_at DESC, r.batch_id DESC, r.seq DESC
LIMIT /* limit */200
