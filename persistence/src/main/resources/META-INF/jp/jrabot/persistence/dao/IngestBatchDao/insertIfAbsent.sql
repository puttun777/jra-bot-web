INSERT INTO ingest_batch (
  tenant_id, batch_id, agent_id, batch_class, dataspec, option_no, fromtime, last_file_timestamp, eof, observed_at, received_at, payload_sha256,
  record_count, accepted_count, duplicate_count, status, completed_at, fetch_id, fetch_seq, fail_reason
) VALUES (
  /* entity.tenantId */0, /* entity.batchId */'x', /* entity.agentId */'x', /* entity.batchClass */'S', /* entity.dataspec */'x', /* entity.optionNo */0,
  /* entity.fromtime */'x', /* entity.lastFileTimestamp */'x', /* entity.eof */TRUE, /* entity.observedAt */'2000-01-01', /* entity.receivedAt */'2000-01-01',
  /* entity.payloadSha256 */'x', /* entity.recordCount */0, /* entity.acceptedCount */0, /* entity.duplicateCount */0, /* entity.status */'PROCESSING',
  /* entity.completedAt */'2000-01-01', /* entity.fetchId */'x', /* entity.fetchSeq */0, /* entity.failReason */'x'
)
ON DUPLICATE KEY UPDATE tenant_id = tenant_id
