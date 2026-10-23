-- V3__ingest_auth_fetch_backfill.sql  (T3.4 / D-130〜D-139)
-- V1・V2 は書き換えない（追加の ALTER と新しい表だけ）。
--   1. agent: 署名鍵（暗号化）の列と agent_id の全体一意（HTTP の X-Agent-Id からテナントを引くため。D-131）
--   2. ingest_batch: 取得セッション（fetch）との対応と失敗理由
--   3. ingest_fetch: 取得セッション（JVOpen 1 回分）の進捗と結果 = 「取得の事実」の正本（D-134）
--   4. ingest_backfill: バックフィル要求の状態（D-138）
--   5. combo_odds_snapshots: ワイドの上限オッズの列（ワイドは範囲で公表される。odds は下限）

-- 署名鍵。サーバはトークンそのものを保存しない。署名に使う鍵 K = HMAC-SHA256(token, "jra-bot/agent-sign/v1") を、サーバ側の鍵（KEK。
-- Secret Manager 由来で DB には置かない）で AES-256-GCM 暗号化して持つ（nonce 12 + 暗号文 32 + タグ 16 = 60 バイト）。
-- NULL の行は認証できない（fail closed）。kid は KEK の識別子（ローテーション用）。
ALTER TABLE agent
  ADD COLUMN sign_key_enc VARBINARY(96) NULL AFTER token_hash,
  ADD COLUMN sign_key_kid VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER sign_key_enc,
  ADD UNIQUE KEY uq_agent_agent_id (agent_id),
  ADD CONSTRAINT ck_agent_sign_key CHECK ((sign_key_enc IS NULL) = (sign_key_kid IS NULL));

-- 取得セッション（JVOpen 1 回分。複数バッチに分割される）。fetch_id は封筒の fetch.id（UUID）。
--   next_seq     : 連続して受理済みのバッチ数（= 次に受け付ける fetch.seq）。飛びや重複は受け付けない。
--   status       : IN_PROGRESS（途中）/ SUCCEEDED（EOF まで連続して読了）/ FAILED（読了できない確定した理由がある）
--   covered_from/to : 「取得成功の事実」が及ぶ日付の範囲。YSCH では [target_from, min(target_to, この取得で見た最後の開催日)]。
--                     範囲が空（NULL）の成功は事実にならない（予定なしを確認したことにしない）。
CREATE TABLE ingest_fetch (
  tenant_id           BIGINT NOT NULL,
  fetch_id            CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  agent_id            VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  dataspec            VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  option_no           SMALLINT NULL,
  fromtime            VARCHAR(14) CHARACTER SET ascii COLLATE ascii_bin NULL,
  origin              ENUM('LIVE', 'BACKFILL') CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'LIVE',
  backfill_id         BIGINT NULL,
  target_from         DATE NULL,
  target_to           DATE NULL,
  last_file_timestamp VARCHAR(14) CHARACTER SET ascii COLLATE ascii_bin NULL,
  status              ENUM('IN_PROGRESS', 'SUCCEEDED', 'FAILED') CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'IN_PROGRESS',
  fail_reason         VARCHAR(100) NULL,
  next_seq            INT UNSIGNED NOT NULL DEFAULT 0,
  record_count        BIGINT UNSIGNED NOT NULL DEFAULT 0,
  unparsed_count      INT UNSIGNED NOT NULL DEFAULT 0,
  failed_parse_count  INT UNSIGNED NOT NULL DEFAULT 0,
  max_race_date       DATE NULL,
  covered_from        DATE NULL,
  covered_to          DATE NULL,
  started_at          DATETIME(6) NOT NULL,
  last_batch_at       DATETIME(6) NOT NULL,
  completed_at        DATETIME(6) NULL,
  PRIMARY KEY (tenant_id, fetch_id),
  KEY ix_ingest_fetch_fact (tenant_id, dataspec, status, covered_from, covered_to),
  KEY ix_ingest_fetch_agent (tenant_id, agent_id, started_at),
  CONSTRAINT fk_ingest_fetch_agent FOREIGN KEY (tenant_id, agent_id) REFERENCES agent (tenant_id, agent_id),
  CONSTRAINT ck_ingest_fetch_completed CHECK ((status = 'IN_PROGRESS') = (completed_at IS NULL)),
  CONSTRAINT ck_ingest_fetch_range CHECK (target_to IS NULL OR target_from IS NULL OR target_to >= target_from),
  CONSTRAINT ck_ingest_fetch_covered CHECK (covered_to IS NULL OR (covered_from IS NOT NULL AND covered_to >= covered_from)),
  CONSTRAINT ck_ingest_fetch_covered_status CHECK (covered_from IS NULL OR status = 'SUCCEEDED'),
  CONSTRAINT ck_ingest_fetch_fail CHECK ((status = 'FAILED') = (fail_reason IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- バッチ → 取得セッション。fetch_seq は取得セッション内の連番（0 始まり）。fail_reason は FAILED の理由（本文・秘密は入れない）。
ALTER TABLE ingest_batch
  ADD COLUMN fetch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN fetch_seq INT UNSIGNED NULL,
  ADD COLUMN fail_reason VARCHAR(200) NULL,
  ADD UNIQUE KEY uq_ingest_batch_fetch_seq (tenant_id, fetch_id, fetch_seq),
  ADD CONSTRAINT fk_ingest_batch_fetch FOREIGN KEY (tenant_id, fetch_id) REFERENCES ingest_fetch (tenant_id, fetch_id),
  ADD CONSTRAINT ck_ingest_batch_fetch CHECK ((fetch_id IS NULL) = (fetch_seq IS NULL));

ALTER TABLE ingest_gap
  ADD UNIQUE KEY uq_ingest_gap_tenant_id (tenant_id, id);

-- バックフィル要求（過去期間の再取得）。JVOpen で実際に取得するのはエージェント（T3.5）。ここは要求と進捗の状態だけ。
--   request_key : 同じ要求の重複登録を防ぐ自然キー（dataspec|option|fromtime|gap_id）。完了後に同じ要求を出し直せるよう、
--                 実行中の要求だけが UNIQUE になる（active_slot = 1 のときだけ。NULL は UNIQUE で重複できる）。
CREATE TABLE ingest_backfill (
  id           BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id    BIGINT NOT NULL,
  dataspec     VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  option_no    SMALLINT NULL,
  option_key   SMALLINT GENERATED ALWAYS AS (COALESCE(option_no, -1)) STORED NOT NULL,
  fromtime     VARCHAR(14) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  target_from  DATE NULL,
  target_to    DATE NULL,
  gap_id       BIGINT NULL,
  status       ENUM('REQUESTED', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'CANCELLED') CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'REQUESTED',
  active_slot  TINYINT GENERATED ALWAYS AS (IF(status IN ('REQUESTED', 'IN_PROGRESS'), 1, NULL)) STORED NULL,
  fetch_id     CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  requested_at DATETIME(6) NOT NULL,
  requested_by VARCHAR(254) NOT NULL,
  updated_at   DATETIME(6) NOT NULL,
  completed_at DATETIME(6) NULL,
  fail_reason  VARCHAR(100) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_ingest_backfill_active (tenant_id, dataspec, option_key, fromtime, active_slot),
  KEY ix_ingest_backfill_status (tenant_id, status, requested_at),
  CONSTRAINT fk_ingest_backfill_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id),
  UNIQUE KEY uq_ingest_backfill_tenant_id (tenant_id, id),
  CONSTRAINT fk_ingest_backfill_gap FOREIGN KEY (tenant_id, gap_id) REFERENCES ingest_gap (tenant_id, id),
  CONSTRAINT ck_ingest_backfill_range CHECK (target_to IS NULL OR target_from IS NULL OR target_to >= target_from),
  CONSTRAINT ck_ingest_backfill_completed CHECK ((status IN ('COMPLETED', 'FAILED', 'CANCELLED')) = (completed_at IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- テナントをまたぐ参照を DB で拒否するため、親側にも (tenant_id, id) の UNIQUE を足し、FK は tenant_id を先頭にした複合 FK にする。
ALTER TABLE ingest_fetch
  ADD CONSTRAINT fk_ingest_fetch_backfill FOREIGN KEY (tenant_id, backfill_id) REFERENCES ingest_backfill (tenant_id, id);

-- ワイドは範囲（下限〜上限）で公表される。odds は下限（旧実装の EV 計算が使う低い方）、odds_high は上限（三連複は NULL）。
ALTER TABLE combo_odds_snapshots
  ADD COLUMN odds_high DOUBLE NULL AFTER odds,
  ADD CONSTRAINT ck_combo_odds_high CHECK (odds_high IS NULL OR odds_high >= odds);
