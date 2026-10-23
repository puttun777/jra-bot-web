INSERT INTO agent_heartbeat (
  tenant_id, agent_id, last_seen_at, agent_version, clock_skew_ms, queue_depth, oldest_queued_at, acked_ts, last_jv_result, disk_free_mb, last_error, local_mode
) VALUES (
  /* entity.tenantId */0, /* entity.agentId */'x', /* entity.lastSeenAt */'2000-01-01', /* entity.agentVersion */'x', /* entity.clockSkewMs */0,
  /* entity.queueDepth */0, /* entity.oldestQueuedAt */'2000-01-01', /* entity.ackedTs */'{}', /* entity.lastJvResult */'{}', /* entity.diskFreeMb */0,
  /* entity.lastError */'x', /* entity.localMode */'x'
) AS new
ON DUPLICATE KEY UPDATE
  last_seen_at = new.last_seen_at, agent_version = new.agent_version, clock_skew_ms = new.clock_skew_ms, queue_depth = new.queue_depth,
  oldest_queued_at = new.oldest_queued_at, acked_ts = new.acked_ts, last_jv_result = new.last_jv_result, disk_free_mb = new.disk_free_mb,
  last_error = new.last_error, local_mode = new.local_mode
