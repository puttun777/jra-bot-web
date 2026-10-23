-- V2__ops_calendar_ingest_ml.sql  (T2.3 / D-058 / D-063〜D-066)
-- 開催・bot 実行: race_day, race_schedule, race_schedule_event, calendar_override, bot_run
-- 取込: agent, agent_registration_code, agent_heartbeat, agent_nonce, ingest_batch, ingest_raw, ingest_gap
-- ML: ml_model
-- 設計にある表だけを作る。規約は V1 と同じ（tenant_id が先頭、UTC の DATETIME(6)、コード列は ascii_bin、複合 FK）。

-- ---------------------------------------------------------------------------
-- 開催日・レース予定（RACE_CALENDAR_DESIGN §4）
-- ---------------------------------------------------------------------------
CREATE TABLE race_day (
  tenant_id         BIGINT NOT NULL,
  race_date         DATE NOT NULL,
  venue_code        CHAR(2) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  kai               TINYINT UNSIGNED NOT NULL,
  nichime           TINYINT UNSIGNED NOT NULL,
  status            ENUM('UNPLANNED', 'PLANNED', 'UNCONFIRMED', 'CONFIRMED', 'RUNNING', 'SETTLING', 'COMPLETED', 'INCOMPLETE', 'CANCELLED', 'POSTPONED')
                      CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  replaced_by_date  DATE NULL,
  first_seen_at     DATETIME(6) NOT NULL,
  last_confirmed_at DATETIME(6) NULL,
  version           INT NOT NULL DEFAULT 0,
  PRIMARY KEY (tenant_id, race_date, venue_code),
  CONSTRAINT fk_race_day_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id),
  CONSTRAINT fk_race_day_venue FOREIGN KEY (venue_code) REFERENCES code_venue (venue_code),
  CONSTRAINT ck_race_day_kai CHECK (kai BETWEEN 1 AND 99),
  CONSTRAINT ck_race_day_nichime CHECK (nichime BETWEEN 1 AND 99),
  CONSTRAINT ck_race_day_version CHECK (version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- race_schedule は YSCH/RA から races より先に作られることがあるので、races への FK は持たない（race_id の整合は CHECK で保証する）。
CREATE TABLE race_schedule (
  tenant_id         BIGINT NOT NULL,
  race_id           CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  race_date         DATE NOT NULL,
  venue_code        CHAR(2) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  race_no           TINYINT UNSIGNED NOT NULL,
  kai               TINYINT UNSIGNED NULL,
  nichime           TINYINT UNSIGNED NULL,
  scheduled_post_at DATETIME(6) NULL,
  post_time_unknown TINYINT(1) NOT NULL DEFAULT 0,
  status            ENUM('ANNOUNCED', 'CARD', 'MONITORING', 'OFF', 'RESULT_PENDING', 'RESULT_PARTIAL', 'SETTLED', 'FINALIZED', 'CANCELLED', 'DELETED')
                      CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  data_kubun        CHAR(1) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_version    VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  race_name         VARCHAR(100) NULL,
  last_changed_at   DATETIME(6) NOT NULL,
  version           INT NOT NULL DEFAULT 0,
  PRIMARY KEY (tenant_id, race_id),
  KEY ix_race_schedule_day (tenant_id, race_date, scheduled_post_at),
  CONSTRAINT fk_race_schedule_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id),
  CONSTRAINT fk_race_schedule_venue FOREIGN KEY (venue_code) REFERENCES code_venue (venue_code),
  CONSTRAINT ck_race_schedule_race_id_form CHECK (race_id REGEXP '^[0-9]{12}$'),
  CONSTRAINT ck_race_schedule_race_no CHECK (race_no BETWEEN 1 AND 99),
  CONSTRAINT ck_race_schedule_kai CHECK (kai IS NULL OR kai BETWEEN 1 AND 99),
  CONSTRAINT ck_race_schedule_nichime CHECK (nichime IS NULL OR nichime BETWEEN 1 AND 99),
  CONSTRAINT ck_race_schedule_post CHECK (post_time_unknown = 0 OR scheduled_post_at IS NULL),
  CONSTRAINT ck_race_schedule_version CHECK (version >= 0),
  CONSTRAINT ck_race_schedule_source_version CHECK (CHAR_LENGTH(source_version) >= 1),
  CONSTRAINT ck_race_schedule_race_id_parts CHECK (
    race_id = CONCAT(YEAR(race_date) * 10000 + MONTH(race_date) * 100 + DAYOFMONTH(race_date), venue_code, LPAD(race_no, 2, '0'))
  )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- エージェントと取込
-- ---------------------------------------------------------------------------
-- agent_id は domain の AgentId（[A-Za-z0-9_-]{1,64}）に合わせる。token_hash は SHA-256 + ペッパーの 16 進（トークン自体は保存しない）。
CREATE TABLE agent (
  tenant_id        BIGINT NOT NULL,
  agent_id         VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name             VARCHAR(100) NOT NULL,
  token_hash       CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  token_expires_at DATETIME(6) NOT NULL,
  status           ENUM('ACTIVE', 'REVOKED') CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
  created_at       DATETIME(6) NOT NULL,
  created_by       VARCHAR(254) NULL,
  revoked_at       DATETIME(6) NULL,
  revoked_by       VARCHAR(254) NULL,
  PRIMARY KEY (tenant_id, agent_id),
  CONSTRAINT fk_agent_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id),
  CONSTRAINT ck_agent_id_form CHECK (agent_id REGEXP '^[A-Za-z0-9_-]{1,64}$'),
  CONSTRAINT ck_agent_token_hash CHECK (token_hash REGEXP '^[0-9a-f]{64}$'),
  CONSTRAINT ck_agent_revoked CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 登録コード（1 回限り）。登録は「コードだけ」で呼ばれ、コードからテナントを解決するので code_hash は全体で一意（D-065）。
CREATE TABLE agent_registration_code (
  id                BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id         BIGINT NOT NULL,
  code_hash         CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expires_at        DATETIME(6) NOT NULL,
  created_at        DATETIME(6) NOT NULL,
  created_by        VARCHAR(254) NULL,
  used_at           DATETIME(6) NULL,
  used_by_agent_id  VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_agent_registration_code_hash (code_hash),
  KEY ix_agent_registration_code_tenant (tenant_id, expires_at),
  CONSTRAINT fk_agent_registration_code_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id),
  CONSTRAINT fk_agent_registration_code_agent FOREIGN KEY (tenant_id, used_by_agent_id) REFERENCES agent (tenant_id, agent_id),
  CONSTRAINT ck_agent_registration_code_hash CHECK (code_hash REGEXP '^[0-9a-f]{64}$'),
  CONSTRAINT ck_agent_registration_code_used CHECK ((used_at IS NULL) = (used_by_agent_id IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 最終接続の状態（エージェント 1 台につき 1 行を upsert。履歴は持たない）。
CREATE TABLE agent_heartbeat (
  tenant_id        BIGINT NOT NULL,
  agent_id         VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  last_seen_at     DATETIME(6) NOT NULL,
  agent_version    VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  clock_skew_ms    INT NULL,
  queue_depth      INT UNSIGNED NULL,
  oldest_queued_at DATETIME(6) NULL,
  acked_ts         JSON NULL,
  last_jv_result   JSON NULL,
  disk_free_mb     BIGINT UNSIGNED NULL,
  last_error       VARCHAR(500) NULL,
  local_mode       VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
  PRIMARY KEY (tenant_id, agent_id),
  CONSTRAINT fk_agent_heartbeat_agent FOREIGN KEY (tenant_id, agent_id) REFERENCES agent (tenant_id, agent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- リプレイ防止の nonce（10 分保持。期限切れは ingest が DELETE する）。同じ nonce の再送は PK 違反で拒否される。
CREATE TABLE agent_nonce (
  tenant_id  BIGINT NOT NULL,
  agent_id   VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  nonce      VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id, agent_id, nonce),
  KEY ix_agent_nonce_expires (expires_at),
  CONSTRAINT fk_agent_nonce_agent FOREIGN KEY (tenant_id, agent_id) REFERENCES agent (tenant_id, agent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 取込バッチ。batch_id（UUIDv7）が冪等キー: 同じ batchId の再送は PK で検出し、payload_sha256 が違えば不正な再利用として拒否する。
CREATE TABLE ingest_batch (
  tenant_id           BIGINT NOT NULL,
  batch_id            CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  agent_id            VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  batch_class         ENUM('S', 'R') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  dataspec            VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  option_no           SMALLINT NULL,
  fromtime            VARCHAR(14) CHARACTER SET ascii COLLATE ascii_bin NULL,
  last_file_timestamp VARCHAR(14) CHARACTER SET ascii COLLATE ascii_bin NULL,
  eof                 TINYINT(1) NOT NULL DEFAULT 0,
  observed_at         DATETIME(6) NOT NULL,
  received_at         DATETIME(6) NOT NULL,
  payload_sha256      CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  record_count        INT UNSIGNED NOT NULL,
  accepted_count      INT UNSIGNED NOT NULL DEFAULT 0,
  duplicate_count     INT UNSIGNED NOT NULL DEFAULT 0,
  status              ENUM('PROCESSING', 'COMPLETED', 'FAILED') CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PROCESSING',
  completed_at        DATETIME(6) NULL,
  PRIMARY KEY (tenant_id, batch_id),
  KEY ix_ingest_batch_agent (tenant_id, agent_id, received_at),
  CONSTRAINT fk_ingest_batch_agent FOREIGN KEY (tenant_id, agent_id) REFERENCES agent (tenant_id, agent_id),
  CONSTRAINT ck_ingest_batch_counts CHECK (accepted_count + duplicate_count <= record_count)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 生レコード（保持は既定 30 日。規約回答後に確定: STAGE2_HANDOFF §11 #10）。record_hash が冪等キー（同じ内容は 1 行）。
CREATE TABLE ingest_raw (
  tenant_id    BIGINT NOT NULL,
  record_hash  CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  batch_id     CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  seq          INT UNSIGNED NOT NULL,
  dataspec     VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  record_id    VARCHAR(4) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload      MEDIUMBLOB NOT NULL,
  received_at  DATETIME(6) NOT NULL,
  parse_status ENUM('PENDING', 'PARSED', 'FAILED') CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
  parse_error  VARCHAR(200) NULL,
  PRIMARY KEY (tenant_id, record_hash),
  KEY ix_ingest_raw_batch (tenant_id, batch_id, seq),
  KEY ix_ingest_raw_received (tenant_id, received_at),
  CONSTRAINT fk_ingest_raw_batch FOREIGN KEY (tenant_id, batch_id) REFERENCES ingest_batch (tenant_id, batch_id),
  CONSTRAINT ck_ingest_raw_hash CHECK (record_hash REGEXP '^[0-9a-f]{64}$')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 取りこぼし区間（STOPPED・エージェント不達・取得失敗）。agent_key（agent_id が NULL なら空文字）で、再検出しても同じ区間を重複させない。
CREATE TABLE ingest_gap (
  id          BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id   BIGINT NOT NULL,
  agent_id    VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  agent_key   VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS (COALESCE(agent_id, _ascii'')) STORED NOT NULL,
  dataspec    VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
  reason      ENUM('STOPPED', 'AGENT_UNREACHABLE', 'FETCH_FAILURE', 'OTHER') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  gap_from    DATETIME(6) NOT NULL,
  gap_to      DATETIME(6) NULL,
  detected_at DATETIME(6) NOT NULL,
  resolved_at DATETIME(6) NULL,
  detail      VARCHAR(500) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_ingest_gap_natural (tenant_id, reason, gap_from, agent_key),
  KEY ix_ingest_gap_agent (tenant_id, agent_id),
  CONSTRAINT fk_ingest_gap_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id),
  CONSTRAINT fk_ingest_gap_agent FOREIGN KEY (tenant_id, agent_id) REFERENCES agent (tenant_id, agent_id),
  CONSTRAINT ck_ingest_gap_range CHECK (gap_to IS NULL OR gap_to >= gap_from)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 開催予定の履歴・手動補正・bot の実行
-- ---------------------------------------------------------------------------
-- 追記専用の履歴。dedup_hash が冪等キー（同じ変化を再取込しても 1 行）。race_id が NULL の行は日単位のイベント。
CREATE TABLE race_schedule_event (
  id          BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id   BIGINT NOT NULL,
  race_id     CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NULL,
  race_date   DATE NOT NULL,
  event_type  ENUM('ADDED', 'DELETED', 'CANCELLED', 'TIME_CHANGE', 'SCRATCH', 'JOCKEY_CHANGE', 'COURSE_CHANGE', 'WEATHER', 'DAY_STATUS',
                   'POSTPONED', 'REVOKED', 'MANUAL_OVERRIDE', 'MANUAL_REVOKE', 'FETCH_FAILURE', 'STALE')
                CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  old_value   JSON NULL,
  new_value   JSON NULL,
  source      ENUM('YSCH', 'RACE', '0B15', '0B14', 'MANUAL', 'SYSTEM') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  observed_at DATETIME(6) NOT NULL,
  received_at DATETIME(6) NOT NULL,
  batch_id    CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  dedup_hash  CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_race_schedule_event_dedup (tenant_id, dedup_hash),
  KEY ix_race_schedule_event_race (tenant_id, race_id, observed_at),
  KEY ix_race_schedule_event_batch (tenant_id, batch_id),
  CONSTRAINT fk_race_schedule_event_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id),
  CONSTRAINT fk_race_schedule_event_race FOREIGN KEY (tenant_id, race_id) REFERENCES race_schedule (tenant_id, race_id),
  CONSTRAINT fk_race_schedule_event_batch FOREIGN KEY (tenant_id, batch_id) REFERENCES ingest_batch (tenant_id, batch_id),
  CONSTRAINT ck_race_schedule_event_hash CHECK (dedup_hash REGEXP '^[0-9a-f]{64}$')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 手動補正。scope=DAY は race_date だけ、scope=RACE は race_schedule の行を指す。
CREATE TABLE calendar_override (
  id          BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id   BIGINT NOT NULL,
  scope       ENUM('DAY', 'RACE') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  race_date   DATE NOT NULL,
  race_id     CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NULL,
  action      ENUM('MARK_RACE_DAY', 'MARK_NO_RACE', 'CANCEL', 'SET_POST_TIME', 'POSTPONE_TO', 'ALLOW_RUN', 'DENY_RUN')
                CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  value       JSON NULL,
  reason      VARCHAR(500) NOT NULL,
  created_by  VARCHAR(254) NOT NULL,
  created_at  DATETIME(6) NOT NULL,
  valid_until DATETIME(6) NULL,
  revoked_at  DATETIME(6) NULL,
  revoked_by  VARCHAR(254) NULL,
  PRIMARY KEY (id),
  KEY ix_calendar_override_day (tenant_id, race_date, scope),
  KEY ix_calendar_override_race (tenant_id, race_id),
  CONSTRAINT fk_calendar_override_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id),
  CONSTRAINT fk_calendar_override_race FOREIGN KEY (tenant_id, race_id) REFERENCES race_schedule (tenant_id, race_id),
  CONSTRAINT ck_calendar_override_scope CHECK ((scope = 'DAY' AND race_id IS NULL) OR (scope = 'RACE' AND race_id IS NOT NULL)),
  CONSTRAINT ck_calendar_override_reason CHECK (CHAR_LENGTH(reason) >= 1),
  CONSTRAINT ck_calendar_override_revoked CHECK ((revoked_at IS NULL) = (revoked_by IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- bot の実行と排他（DB リース）。CONTRACTS §9.2 の INTERRUPTED を含む 8 状態（domain の BotRunState と一致させる）。
-- 排他: 実行中の状態（STARTING / RUNNING / DRAINING）のときだけ active_slot = 1 とし、UNIQUE (tenant_id, run_date, active_slot) が二重実行を拒否する
-- （NULL は UNIQUE で重複できる）。状態と active_slot の食い違いは CHECK が拒否する。リースの期限切れ・奪取は T3.3。
CREATE TABLE bot_run (
  tenant_id        BIGINT NOT NULL,
  run_id           CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  run_date         DATE NOT NULL,
  trigger_type     ENUM('SCHEDULER', 'MANUAL', 'RECONCILE', 'BACKFILL') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state            ENUM('STARTING', 'RUNNING', 'DRAINING', 'SUCCEEDED', 'FAILED', 'ABORTED', 'STOPPED_BY_MODE', 'INTERRUPTED')
                     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  lease_owner      VARCHAR(100) NULL,
  lease_expires_at DATETIME(6) NULL,
  heartbeat_at     DATETIME(6) NULL,
  active_slot      TINYINT NULL,
  checkpoint       JSON NULL,
  planned_start    DATETIME(6) NULL,
  planned_end      DATETIME(6) NULL,
  exit_reason      VARCHAR(200) NULL,
  started_at       DATETIME(6) NOT NULL,
  finished_at      DATETIME(6) NULL,
  version          INT NOT NULL DEFAULT 0,
  PRIMARY KEY (tenant_id, run_id),
  UNIQUE KEY uq_bot_run_active (tenant_id, run_date, active_slot),
  KEY ix_bot_run_started (tenant_id, started_at),
  CONSTRAINT fk_bot_run_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id),
  CONSTRAINT ck_bot_run_run_id CHECK (run_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
  CONSTRAINT ck_bot_run_version CHECK (version >= 0),
  CONSTRAINT ck_bot_run_slot CHECK (
    (state IN ('STARTING', 'RUNNING', 'DRAINING') AND active_slot <=> 1)
    OR (state NOT IN ('STARTING', 'RUNNING', 'DRAINING') AND active_slot IS NULL)
  ),
  CONSTRAINT ck_bot_run_finished CHECK (active_slot IS NULL OR finished_at IS NULL),
  CONSTRAINT ck_bot_run_finished_order CHECK (finished_at IS NULL OR finished_at >= started_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- ML モデルのレジストリ（ML_DESIGN §6）。モデル本体は GCS で、ここには参照と整合性の情報だけを置く。
-- model_id は domain の ModelId（[A-Za-z0-9_-]{1,64}）に合わせて VARCHAR(64)（設計案の CHAR(36) より広い: D-065）。
-- previous_model_id の自己参照 FK は (previous_model_id, tenant_id) → (model_id, tenant_id) の順にする。MySQL 9.7.4 は、子と親で同じ位置に
-- 同じ列（tenant_id）が重なる複合の自己参照 FK（(tenant_id, prev) → (tenant_id, model_id)）を**検査しない**ことを CI で確認した
-- （8.4 は検査する。単一列の自己参照・別表への複合 FK は 9.7.4 でも検査される）。D-069。
-- 有効なモデルは (tenant_id, kind) ごとに 1 つ: ACTIVE のときだけ active_flag = 1（CHECK）で、UNIQUE が 2 つ目の ACTIVE を拒否する。
-- signature_sha256 は GCS の .sig の SHA-256（署名そのものと鍵は保存しない。署名の検証は bot が行う）。
-- ---------------------------------------------------------------------------
CREATE TABLE ml_model (
  tenant_id           BIGINT NOT NULL,
  model_id            VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  kind                ENUM('calibration', 'alpha', 'legacy_compat', 'ensemble_set') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  schema_version      INT NOT NULL,
  feature_schema_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  artifact_uri        VARCHAR(500) NOT NULL,
  sha256              CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  signature_sha256    CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status              ENUM('CANDIDATE', 'ACTIVE', 'RETIRED', 'REJECTED') CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CANDIDATE',
  active_flag         TINYINT NULL,
  trained_from        DATE NULL,
  trained_to          DATE NULL,
  n_train             INT UNSIGNED NULL,
  metrics_json        JSON NULL,
  created_at          DATETIME(6) NOT NULL,
  activated_at        DATETIME(6) NULL,
  activated_by        VARCHAR(254) NULL,
  previous_model_id   VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  PRIMARY KEY (tenant_id, model_id),
  UNIQUE KEY uq_ml_model_active (tenant_id, kind, active_flag),
  UNIQUE KEY uq_ml_model_id_tenant (model_id, tenant_id),
  KEY ix_ml_model_previous (previous_model_id, tenant_id),
  CONSTRAINT fk_ml_model_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id),
  CONSTRAINT fk_ml_model_previous FOREIGN KEY (previous_model_id, tenant_id) REFERENCES ml_model (model_id, tenant_id),
  CONSTRAINT ck_ml_model_id_form CHECK (model_id REGEXP '^[A-Za-z0-9_-]{1,64}$'),
  CONSTRAINT ck_ml_model_schema_version CHECK (schema_version >= 1),
  CONSTRAINT ck_ml_model_sha256 CHECK (sha256 REGEXP '^[0-9a-f]{64}$'),
  CONSTRAINT ck_ml_model_signature_sha256 CHECK (signature_sha256 REGEXP '^[0-9a-f]{64}$'),
  CONSTRAINT ck_ml_model_active CHECK (
    (status = 'ACTIVE' AND active_flag <=> 1 AND activated_at IS NOT NULL)
    OR (status <> 'ACTIVE' AND active_flag IS NULL)
  ),
  CONSTRAINT ck_ml_model_trained CHECK (trained_from IS NULL OR trained_to IS NULL OR trained_to >= trained_from)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- V1 の race_evaluations.model_id を、同じテナントの ml_model に結び付ける（テナントをまたぐ参照を拒否）。
ALTER TABLE race_evaluations
  ADD CONSTRAINT fk_race_evaluations_model FOREIGN KEY (tenant_id, model_id) REFERENCES ml_model (tenant_id, model_id);
