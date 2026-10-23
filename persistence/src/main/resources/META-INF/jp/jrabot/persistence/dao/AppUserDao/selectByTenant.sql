SELECT /*%expand*/*
FROM app_user
WHERE tenant_id = /* tenantId */0
ORDER BY email
