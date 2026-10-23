INSERT INTO ingest_fetch (
  tenant_id, fetch_id, agent_id, dataspec, option_no, fromtime, origin, backfill_id, target_from, target_to, last_file_timestamp, status, fail_reason,
  next_seq, record_count, unparsed_count, failed_parse_count, max_race_date, covered_from, covered_to, started_at, last_batch_at, completed_at
) VALUES (
  /* entity.tenantId */0, /* entity.fetchId */'x', /* entity.agentId */'x', /* entity.dataspec */'x', /* entity.optionNo */0, /* entity.fromtime */'x',
  /* entity.origin */'LIVE', /* entity.backfillId */0, /* entity.targetFrom */'2000-01-01', /* entity.targetTo */'2000-01-01',
  /* entity.lastFileTimestamp */'x', /* entity.status */'IN_PROGRESS', /* entity.failReason */'x', /* entity.nextSeq */0, /* entity.recordCount */0,
  /* entity.unparsedCount */0, /* entity.failedParseCount */0, /* entity.maxRaceDate */'2000-01-01', /* entity.coveredFrom */'2000-01-01',
  /* entity.coveredTo */'2000-01-01', /* entity.startedAt */'2000-01-01', /* entity.lastBatchAt */'2000-01-01', /* entity.completedAt */'2000-01-01'
)
ON DUPLICATE KEY UPDATE tenant_id = tenant_id
