INSERT INTO ingest_cursor (tenant_id, data_type, last_file_timestamp, updated_at)
VALUES (/* entity.tenantId */0, /* entity.dataType */'x', /* entity.lastFileTimestamp */'x', /* entity.updatedAt */'2000-01-01') AS new
ON DUPLICATE KEY UPDATE
  updated_at = IF(new.last_file_timestamp > ingest_cursor.last_file_timestamp, new.updated_at, ingest_cursor.updated_at),
  last_file_timestamp = IF(new.last_file_timestamp > ingest_cursor.last_file_timestamp, new.last_file_timestamp, ingest_cursor.last_file_timestamp)
