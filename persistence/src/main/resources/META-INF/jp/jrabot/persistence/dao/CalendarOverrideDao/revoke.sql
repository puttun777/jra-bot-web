UPDATE calendar_override
SET revoked_at = /* entity.revokedAt */'2000-01-01',
    revoked_by = /* entity.revokedBy */'x'
WHERE tenant_id = /* entity.tenantId */0
  AND id = /* entity.id */0
  AND revoked_at IS NULL
