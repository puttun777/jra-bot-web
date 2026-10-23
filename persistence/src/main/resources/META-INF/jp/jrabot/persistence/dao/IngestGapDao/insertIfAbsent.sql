INSERT INTO ingest_gap (tenant_id, agent_id, dataspec, reason, gap_from, gap_to, detected_at, resolved_at, detail)
VALUES (
  /* entity.tenantId */0, /* entity.agentId */'x', /* entity.dataspec */'x', /* entity.reason */'OTHER', /* entity.gapFrom */'2000-01-01',
  /* entity.gapTo */'2000-01-01', /* entity.detectedAt */'2000-01-01', /* entity.resolvedAt */'2000-01-01', /* entity.detail */'x'
)
ON DUPLICATE KEY UPDATE tenant_id = tenant_id
