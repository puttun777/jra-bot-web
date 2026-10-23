UPDATE ingest_fetch
SET last_file_timestamp = /* entity.lastFileTimestamp */'x',
    status = /* entity.status */'IN_PROGRESS',
    fail_reason = /* entity.failReason */'x',
    next_seq = /* entity.nextSeq */0,
    record_count = /* entity.recordCount */0,
    unparsed_count = /* entity.unparsedCount */0,
    failed_parse_count = /* entity.failedParseCount */0,
    max_race_date = /* entity.maxRaceDate */'2000-01-01',
    covered_from = /* entity.coveredFrom */'2000-01-01',
    covered_to = /* entity.coveredTo */'2000-01-01',
    last_batch_at = /* entity.lastBatchAt */'2000-01-01',
    completed_at = /* entity.completedAt */'2000-01-01'
WHERE tenant_id = /* entity.tenantId */0
  AND fetch_id = /* entity.fetchId */'x'
