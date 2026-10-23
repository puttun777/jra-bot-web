INSERT INTO bot_run (
  tenant_id, run_id, run_date, trigger_type, state, lease_owner, lease_expires_at, heartbeat_at, active_slot, checkpoint,
  exit_reason, started_at, finished_at, version
) VALUES (
  /* entity.tenantId */0, /* entity.runId */'x', /* entity.runDate */'2000-01-01', /* entity.triggerType */'x', /* entity.state */'x',
  /* entity.leaseOwner */'x', /* entity.leaseExpiresAt */'2000-01-01', /* entity.heartbeatAt */'2000-01-01', /* entity.activeSlot */0,
  /* entity.checkpoint */'x', /* entity.exitReason */'x', /* entity.startedAt */'2000-01-01', /* entity.finishedAt */'2000-01-01',
  /* entity.version */0
)
