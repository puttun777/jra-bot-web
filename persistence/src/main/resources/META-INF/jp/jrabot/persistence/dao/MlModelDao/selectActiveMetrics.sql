SELECT model_id, metrics_json
FROM ml_model
WHERE tenant_id = /* tenantId */0
  AND kind = /* kind */'calibration'
  AND status = 'ACTIVE'
  AND active_flag = 1
