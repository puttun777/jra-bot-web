INSERT INTO ingest_raw (tenant_id, record_hash, batch_id, seq, dataspec, record_id, payload, received_at, parse_status, parse_error)
VALUES (
  /* entity.tenantId */0, /* entity.recordHash */'x', /* entity.batchId */'x', /* entity.seq */0, /* entity.dataspec */'x', /* entity.recordId */'x',
  /* entity.payload */X'00', /* entity.receivedAt */'2000-01-01', /* entity.parseStatus */'PENDING', /* entity.parseError */'x'
)
ON DUPLICATE KEY UPDATE tenant_id = tenant_id
