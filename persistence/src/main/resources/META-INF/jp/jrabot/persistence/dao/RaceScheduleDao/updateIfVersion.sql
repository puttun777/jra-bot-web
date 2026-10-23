UPDATE race_schedule
SET scheduled_post_at = /* entity.scheduledPostAt */'2000-01-01',
    post_time_unknown = /* entity.postTimeUnknown */0,
    status = /* entity.status */'x',
    data_kubun = /* entity.dataKubun */'x',
    source_version = /* entity.sourceVersion */'x',
    race_name = /* entity.raceName */'x',
    last_changed_at = /* entity.lastChangedAt */'2000-01-01',
    version = version + 1
WHERE tenant_id = /* entity.tenantId */0
  AND race_id = /* entity.raceId */'x'
  AND version = /* entity.version */0
