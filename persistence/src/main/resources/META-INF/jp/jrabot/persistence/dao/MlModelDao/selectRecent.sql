SELECT tenant_id, model_id, kind, schema_version, feature_schema_hash, artifact_uri, sha256, signature_sha256, status, trained_from, trained_to, n_train, created_at, activated_at, activated_by, previous_model_id, metrics_json
FROM ml_model
WHERE tenant_id = /* tenantId */0
ORDER BY (status = 'ACTIVE') DESC, created_at DESC, model_id ASC
LIMIT /* limit */50
