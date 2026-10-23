UPDATE bot_run
SET state = /* entity.state */'x',
    lease_owner = /* entity.leaseOwner */'x',
    lease_expires_at = /* entity.leaseExpiresAt */'2000-01-01',
    heartbeat_at = /* entity.heartbeatAt */'2000-01-01',
    active_slot = /* entity.activeSlot */0,
    checkpoint = /* entity.checkpoint */'x',
    exit_reason = /* entity.exitReason */'x',
    finished_at = /* entity.finishedAt */'2000-01-01',
    version = version + 1
WHERE tenant_id = /* entity.tenantId */0
  AND run_id = /* entity.runId */'x'
  AND version = /* entity.version */0
