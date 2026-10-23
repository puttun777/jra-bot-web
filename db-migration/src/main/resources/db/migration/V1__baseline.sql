-- V1__baseline.sql  (T2.3 / D-058 / D-063〜D-066)
-- 共有表 3 + 旧 SQLite 15 表の再設計 + import_quarantine + audit_log。MySQL 8.4 / 9.7 共通の構文だけを使う。
--
-- 規約:
--   * 文字セットは utf8mb4。コード列（race_id・場コード・市場など）は ascii_bin（厳密一致）。名称は utf8mb4_0900_ai_ci でキーに使わない。
--   * 時刻は UTC の DATETIME(6)。確率・オッズは DOUBLE（Python float64・Kotlin Double と一致）。
--   * 全テナント表は tenant_id をキーの先頭に置く。子表は (tenant_id, 親キー) の複合 FK で、テナントをまたぐ参照を DB が拒否する。
--   * 予約語は改名（combo_key・rank_no・sample_count・race_date・run_date）。
--   * 子表を CASCADE する FK の列（tenant_id・親 ID）は CHECK に使わない（MySQL の制約 3823）。
--   * V1 では tenant・app_user に本番の行を入れない。code_venue は事実の定数（場コード 01〜10）だけ。

-- ---------------------------------------------------------------------------
-- 共有表（JRA-VAN 由来のデータを含まない）
-- ---------------------------------------------------------------------------
CREATE TABLE tenant (
  tenant_id  BIGINT       NOT NULL,
  name       VARCHAR(100) NOT NULL,
  created_at DATETIME(6)  NOT NULL,
  PRIMARY KEY (tenant_id),
  CONSTRAINT ck_tenant_id CHECK (tenant_id >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 利用者。email は小文字に正規化して保存し（アプリの EmailAddress が正規化する: D-062）、**全体で一意**にする。
-- AppUserRepository.findByEmail は tenant を取らずに 1 件を返す契約なので、同じ email が 2 つのテナントに存在してはならない（D-063）。
-- 照合順序は utf8mb4_0900_bin（大文字小文字を同一視しない完全一致）。
CREATE TABLE app_user (
  tenant_id  BIGINT       NOT NULL,
  email      VARCHAR(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  role       ENUM('VIEWER', 'ADMIN') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  enabled    TINYINT(1)   NOT NULL DEFAULT 1,
  created_at DATETIME(6)  NOT NULL,
  updated_at DATETIME(6)  NOT NULL,
  PRIMARY KEY (tenant_id, email),
  UNIQUE KEY uq_app_user_email (email),
  CONSTRAINT fk_app_user_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id),
  CONSTRAINT ck_app_user_email_form CHECK (CHAR_LENGTH(email) BETWEEN 3 AND 254 AND LOCATE('@', email) > 1),
  CONSTRAINT ck_app_user_email_lower CHECK (NOT REGEXP_LIKE(email, '[A-Z]', 'c')),
  CONSTRAINT ck_app_user_enabled CHECK (enabled IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE code_venue (
  venue_code CHAR(2) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name       VARCHAR(20) NOT NULL,
  PRIMARY KEY (venue_code),
  CONSTRAINT ck_code_venue_code CHECK (venue_code BETWEEN '01' AND '10')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO code_venue (venue_code, name) VALUES
  ('01', '札幌'), ('02', '函館'), ('03', '福島'), ('04', '新潟'), ('05', '東京'),
  ('06', '中山'), ('07', '中京'), ('08', '京都'), ('09', '阪神'), ('10', '小倉');

-- ---------------------------------------------------------------------------
-- レース（旧 races）。race_id = YYYYMMDD + 場 2 桁 + R 2 桁（D-040）。race_id の各部分と列の一致を CHECK で保証する。
-- 旧 track（場名の文字列）は code_venue から引けるので持たない。
-- ---------------------------------------------------------------------------
CREATE TABLE races (
  tenant_id          BIGINT NOT NULL,
  race_id            CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  race_date          DATE NOT NULL,
  venue_code         CHAR(2) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  race_no            TINYINT UNSIGNED NOT NULL,
  kai                TINYINT UNSIGNED NULL,
  nichime            TINYINT UNSIGNED NULL,
  post_time          TIME NULL,
  post_time_unknown  TINYINT(1) NOT NULL DEFAULT 0,
  name               VARCHAR(100) NULL,
  distance_m         SMALLINT UNSIGNED NULL,
  track_type_cd      VARCHAR(2) CHARACTER SET ascii COLLATE ascii_bin NULL,
  course_kubun_cd    VARCHAR(2) CHARACTER SET ascii COLLATE ascii_bin NULL,
  tenko_cd           VARCHAR(2) CHARACTER SET ascii COLLATE ascii_bin NULL,
  shiba_baba_cd      VARCHAR(2) CHARACTER SET ascii COLLATE ascii_bin NULL,
  dirt_baba_cd       VARCHAR(2) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at         DATETIME(6) NOT NULL,
  updated_at         DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id, race_id),
  KEY ix_races_date (tenant_id, race_date, venue_code, race_no),
  CONSTRAINT fk_races_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id),
  CONSTRAINT fk_races_venue FOREIGN KEY (venue_code) REFERENCES code_venue (venue_code),
  CONSTRAINT ck_races_race_id_form CHECK (race_id REGEXP '^[0-9]{12}$'),
  CONSTRAINT ck_races_race_no CHECK (race_no BETWEEN 1 AND 99),
  CONSTRAINT ck_races_kai CHECK (kai IS NULL OR kai BETWEEN 1 AND 99),
  CONSTRAINT ck_races_nichime CHECK (nichime IS NULL OR nichime BETWEEN 1 AND 99),
  CONSTRAINT ck_races_post_time CHECK (post_time_unknown = 0 OR post_time IS NULL),
  CONSTRAINT ck_races_distance CHECK (distance_m IS NULL OR distance_m > 0),
  CONSTRAINT ck_races_race_id_parts CHECK (
    race_id = CONCAT(YEAR(race_date) * 10000 + MONTH(race_date) * 100 + DAYOFMONTH(race_date), venue_code, LPAD(race_no, 2, '0'))
  )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 競走馬マスタ。契約者のデータなのでテナント表（共有しない: DATA_MIGRATION_DESIGN §4.2）。
CREATE TABLE horses (
  tenant_id               BIGINT NOT NULL,
  ketto_num               VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  bamei                   VARCHAR(100) NULL,
  sex_cd                  VARCHAR(2) CHARACTER SET ascii COLLATE ascii_bin NULL,
  birth_date              DATE NULL,
  tozai_cd                VARCHAR(2) CHARACTER SET ascii COLLATE ascii_bin NULL,
  trainer_code            VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
  owner_code              VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
  breeder_code            VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
  pedigree_json           JSON NULL,
  style_tendency_json     JSON NULL,
  chaku_by_surface_json   JSON NULL,
  chaku_by_condition_json JSON NULL,
  chaku_by_distance_json  JSON NULL,
  race_count              INT UNSIGNED NULL,
  created_at              DATETIME(6) NOT NULL,
  updated_at              DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id, ketto_num),
  CONSTRAINT fk_horses_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE race_horses (
  tenant_id    BIGINT NOT NULL,
  race_id      CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  umaban       TINYINT UNSIGNED NOT NULL,
  ketto_num    VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NULL,
  bamei        VARCHAR(100) NULL,
  jockey_code  VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
  trainer_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at   DATETIME(6) NOT NULL,
  updated_at   DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id, race_id, umaban),
  KEY ix_race_horses_ketto (tenant_id, ketto_num),
  CONSTRAINT fk_race_horses_race FOREIGN KEY (tenant_id, race_id) REFERENCES races (tenant_id, race_id),
  CONSTRAINT ck_race_horses_umaban CHECK (umaban BETWEEN 1 AND 99)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 過去走。過去のレースは races に無いことがあるので race_id に FK を張らない（旧スキーマも同じ）。
CREATE TABLE horse_past_runs (
  tenant_id        BIGINT NOT NULL,
  ketto_num        VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  race_id          CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  run_date         DATE NULL,
  venue_code       CHAR(2) CHARACTER SET ascii COLLATE ascii_bin NULL,
  race_no          TINYINT UNSIGNED NULL,
  umaban           TINYINT UNSIGNED NULL,
  finish           SMALLINT UNSIGNED NULL,
  odds             DOUBLE NULL,
  popularity       SMALLINT UNSIGNED NULL,
  kyakusitu_kubun  VARCHAR(2) CHARACTER SET ascii COLLATE ascii_bin NULL,
  corner1          TINYINT UNSIGNED NULL,
  corner2          TINYINT UNSIGNED NULL,
  corner3          TINYINT UNSIGNED NULL,
  corner4          TINYINT UNSIGNED NULL,
  time_str         VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
  time_diff_str    VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at       DATETIME(6) NOT NULL,
  updated_at       DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id, ketto_num, race_id),
  KEY ix_horse_past_runs_date (tenant_id, run_date),
  CONSTRAINT fk_horse_past_runs_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id),
  CONSTRAINT ck_horse_past_runs_odds CHECK (odds IS NULL OR odds > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- オッズ。最大の増加源なので、キーを実クエリ（latest / history = レース + 市場で時刻順）に合わせる。
-- 自然キーの構成要素は設計案（DATA_MIGRATION_DESIGN §2.1）と同じで、順序だけ market を observed_at の前に置く（D-064）。
-- market は ENUM（1 バイト）。値は domain の Market.code と一致させる。
-- ---------------------------------------------------------------------------
CREATE TABLE odds_snapshots (
  tenant_id   BIGINT NOT NULL,
  race_id     CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  market      ENUM('win', 'wide', 'sanrenpuku') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  observed_at DATETIME(6) NOT NULL,
  umaban      TINYINT UNSIGNED NOT NULL,
  odds        DOUBLE NOT NULL,
  popularity  SMALLINT UNSIGNED NULL,
  PRIMARY KEY (tenant_id, race_id, market, observed_at, umaban),
  CONSTRAINT fk_odds_race FOREIGN KEY (tenant_id, race_id) REFERENCES races (tenant_id, race_id),
  CONSTRAINT ck_odds_umaban CHECK (umaban BETWEEN 1 AND 99),
  CONSTRAINT ck_odds_positive CHECK (odds > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 組み合わせオッズ。既定では保存しない（D-043）。有効化した場合のみ使う。
CREATE TABLE combo_odds_snapshots (
  tenant_id    BIGINT NOT NULL,
  race_id      CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  market       ENUM('win', 'wide', 'sanrenpuku') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  collected_at DATETIME(6) NOT NULL,
  combo_key    VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  odds         DOUBLE NOT NULL,
  popularity   SMALLINT UNSIGNED NULL,
  source       VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
  rt_key       VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at   DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id, race_id, market, collected_at, combo_key),
  CONSTRAINT fk_combo_odds_race FOREIGN KEY (tenant_id, race_id) REFERENCES races (tenant_id, race_id),
  CONSTRAINT ck_combo_odds_positive CHECK (odds > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 結果・払戻
-- ---------------------------------------------------------------------------
CREATE TABLE results (
  tenant_id  BIGINT NOT NULL,
  race_id    CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  umaban     TINYINT UNSIGNED NOT NULL,
  finish     SMALLINT UNSIGNED NULL,
  payout     DOUBLE NULL, -- 単勝の倍率。桁の疑義（JVLINK_DESIGN §1.4）は T3.x で確認するので、ここでは非負だけを要求する
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id, race_id, umaban),
  CONSTRAINT fk_results_race FOREIGN KEY (tenant_id, race_id) REFERENCES races (tenant_id, race_id),
  CONSTRAINT ck_results_umaban CHECK (umaban BETWEEN 1 AND 99),
  CONSTRAINT ck_results_payout CHECK (payout IS NULL OR payout >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE payouts (
  tenant_id      BIGINT NOT NULL,
  race_id        CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  market         ENUM('win', 'wide', 'sanrenpuku') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  combo_key      VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payout         DOUBLE NOT NULL,
  is_provisional TINYINT(1) NOT NULL DEFAULT 0,
  created_at     DATETIME(6) NOT NULL,
  updated_at     DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id, race_id, market, combo_key),
  CONSTRAINT fk_payouts_race FOREIGN KEY (tenant_id, race_id) REFERENCES races (tenant_id, race_id),
  CONSTRAINT ck_payouts_payout CHECK (payout >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 判定・通知・賭け
-- ---------------------------------------------------------------------------
-- signals: 自然キーは (tenant_id, race_id, market, umaban, observed_at)（domain の Signal。D-064）。
-- observed_at は 1 サイクルが使ったオッズの取得時刻で、サイクルの識別子を兼ねる。cycle_id 列は設けない
-- （domain に cycle_id が無く、RACE_CALENDAR_DESIGN の組(combo)も signals にはまだ無い。必要になれば T3.3 で NULL 可の列を追加する）。
-- 通知は UNSENT → CLAIMED → SENT | SEND_UNKNOWN の一方向（旧 sent TINYINT を置き換え）。
CREATE TABLE signals (
  id                BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id         BIGINT NOT NULL,
  legacy_id         BIGINT NULL,
  race_id           CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  market            ENUM('win', 'wide', 'sanrenpuku') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  umaban            TINYINT UNSIGNED NOT NULL,
  observed_at       DATETIME(6) NOT NULL,
  odds              DOUBLE NOT NULL,
  prob              DOUBLE NOT NULL,
  ev                DOUBLE NOT NULL,
  is_paper          TINYINT(1) NOT NULL DEFAULT 1,
  stake             DOUBLE NOT NULL DEFAULT 1.0,
  expected_profit   DOUBLE NULL,
  skip_reason       VARCHAR(200) NULL,
  kelly_raw         DOUBLE NULL,
  kelly_adj         DOUBLE NULL,
  bet_before_clip   DOUBLE NULL,
  clipped           TINYINT(1) NOT NULL DEFAULT 0,
  mkt               DOUBLE NULL,
  mkt_adj           DOUBLE NULL,
  ratio             DOUBLE NULL,
  expected_return   DOUBLE NULL,
  suggested_bet     DOUBLE NULL,
  prob_source       VARCHAR(100) NULL,
  reason            VARCHAR(500) NULL,
  notify_state      ENUM('UNSENT', 'CLAIMED', 'SENT', 'SEND_UNKNOWN') CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNSENT',
  notify_updated_at DATETIME(6) NULL,
  created_at        DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_signals_natural (tenant_id, race_id, market, umaban, observed_at),
  UNIQUE KEY uq_signals_tenant_id (tenant_id, id),
  UNIQUE KEY uq_signals_legacy (tenant_id, legacy_id),
  KEY ix_signals_unsent (tenant_id, notify_state, id),
  CONSTRAINT fk_signals_race FOREIGN KEY (tenant_id, race_id) REFERENCES races (tenant_id, race_id),
  CONSTRAINT ck_signals_umaban CHECK (umaban BETWEEN 1 AND 99),
  CONSTRAINT ck_signals_odds CHECK (odds > 0),
  CONSTRAINT ck_signals_prob CHECK (prob BETWEEN 0 AND 1),
  CONSTRAINT ck_signals_stake CHECK (stake >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE bets (
  id                BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id         BIGINT NOT NULL,
  legacy_id         BIGINT NULL,
  race_id           CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  placed_at         DATETIME(6) NOT NULL,
  market            ENUM('win', 'wide', 'sanrenpuku') CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'win',
  stake             DOUBLE NOT NULL,
  source            ENUM('paper', 'manual') CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'paper',
  note              VARCHAR(500) NULL,
  selection         VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  odds_at_bet       DOUBLE NULL,
  prob_used_for_bet DOUBLE NULL,
  ev                DOUBLE NULL,
  expected_profit   DOUBLE NULL,
  signal_id         BIGINT NULL,
  status            VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL, -- 値の集合は旧コードの確認後に T3.x で確定する（D-066）
  settled_at        DATETIME(6) NULL,
  payout            DOUBLE NULL,
  profit            DOUBLE NULL,
  skip_reason       VARCHAR(200) NULL,
  created_at        DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_bets_tenant_id (tenant_id, id),
  UNIQUE KEY uq_bets_legacy (tenant_id, legacy_id),
  KEY ix_bets_race (tenant_id, race_id, placed_at),
  KEY ix_bets_signal (tenant_id, signal_id),
  CONSTRAINT fk_bets_race FOREIGN KEY (tenant_id, race_id) REFERENCES races (tenant_id, race_id),
  CONSTRAINT fk_bets_signal FOREIGN KEY (tenant_id, signal_id) REFERENCES signals (tenant_id, id),
  CONSTRAINT ck_bets_stake CHECK (stake >= 0),
  CONSTRAINT ck_bets_odds CHECK (odds_at_bet IS NULL OR odds_at_bet > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE bet_legs (
  id          BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id   BIGINT NOT NULL,
  legacy_id   BIGINT NULL,
  bet_id      BIGINT NOT NULL,
  umaban      TINYINT UNSIGNED NOT NULL,
  odds_at_bet DOUBLE NOT NULL,
  prob_at_bet DOUBLE NULL,
  ev_at_bet   DOUBLE NULL,
  created_at  DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_bet_legs_legacy (tenant_id, legacy_id),
  KEY ix_bet_legs_bet (tenant_id, bet_id),
  CONSTRAINT fk_bet_legs_bet FOREIGN KEY (tenant_id, bet_id) REFERENCES bets (tenant_id, id) ON DELETE CASCADE,
  CONSTRAINT ck_bet_legs_umaban CHECK (umaban BETWEEN 1 AND 99),
  CONSTRAINT ck_bet_legs_odds CHECK (odds_at_bet > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 評価
-- ---------------------------------------------------------------------------
-- race_evaluations: 旧テーブルに一意制約は無く、サイクルの冪等キー（snapshot_at）を一意にするかは T3.3 で確定する
-- （移行データに重複があり得るため、ここでは根拠のない UNIQUE を足さない: D-066）。model_id → ml_model の FK は V2 で足す。
CREATE TABLE race_evaluations (
  id                     BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id              BIGINT NOT NULL,
  legacy_id              BIGINT NULL,
  race_id                CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  snapshot_at            DATETIME(6) NOT NULL,
  model_type_used        VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  model_path_used        VARCHAR(500) NULL,
  model_id               VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  fallback_reason        VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  bet_types              JSON NULL,
  priority_mode          VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ev_threshold           DOUBLE NULL,
  win_odds_max           DOUBLE NULL,
  win_prob_min           DOUBLE NULL,
  win_ratio_min          DOUBLE NULL,
  best_candidate_json    JSON NULL,
  best_ev                DOUBLE NULL,
  rejection_summary_json JSON NULL,
  signal_sent            TINYINT(1) NOT NULL DEFAULT 0,
  settled_at             DATETIME(6) NULL,
  hit                    TINYINT(1) NULL,
  payout_used            DOUBLE NULL,
  is_provisional         TINYINT(1) NOT NULL DEFAULT 0,
  settle_status          VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
  best_win_hint_json     JSON NULL,
  created_at             DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_race_evaluations_tenant_id (tenant_id, id),
  UNIQUE KEY uq_race_evaluations_legacy (tenant_id, legacy_id),
  KEY ix_race_evaluations_race (tenant_id, race_id, snapshot_at),
  CONSTRAINT fk_race_evaluations_race FOREIGN KEY (tenant_id, race_id) REFERENCES races (tenant_id, race_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE ticket_evaluations (
  id                 BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id          BIGINT NOT NULL,
  legacy_id          BIGINT NULL,
  race_evaluation_id BIGINT NOT NULL,
  race_id            CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  snapshot_at        DATETIME(6) NOT NULL,
  bet_type           VARCHAR(16) NOT NULL, -- 旧データは「単勝」「ワイド」「三連複」の日本語表記
  combo_key          VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
  umaban             TINYINT UNSIGNED NULL,
  odds               DOUBLE NOT NULL,
  p_model            DOUBLE NULL,
  p_mkt              DOUBLE NULL,
  p_final            DOUBLE NULL,
  ratio              DOUBLE NULL,
  ev                 DOUBLE NULL,
  stake              DOUBLE NULL,
  rank_no            INT NULL,
  reject_reason      VARCHAR(200) NULL,
  accepted           TINYINT(1) NOT NULL DEFAULT 0,
  created_at         DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_ticket_evaluations_legacy (tenant_id, legacy_id),
  KEY ix_ticket_evaluations_eval (tenant_id, race_evaluation_id),
  KEY ix_ticket_evaluations_race (tenant_id, race_id, snapshot_at),
  CONSTRAINT fk_ticket_evaluations_eval FOREIGN KEY (tenant_id, race_evaluation_id) REFERENCES race_evaluations (tenant_id, id) ON DELETE CASCADE,
  CONSTRAINT fk_ticket_evaluations_race FOREIGN KEY (tenant_id, race_id) REFERENCES races (tenant_id, race_id),
  CONSTRAINT ck_ticket_evaluations_odds CHECK (odds > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE model_metrics (
  id               BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id        BIGINT NOT NULL,
  legacy_id        BIGINT NULL,
  date_from        DATE NULL,
  date_to          DATE NULL,
  market           ENUM('win', 'wide', 'sanrenpuku') CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'win',
  sample_count     INT NOT NULL,
  brier            DOUBLE NULL,
  logloss          DOUBLE NULL,
  calibration_json JSON NULL,
  created_at       DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_model_metrics_legacy (tenant_id, legacy_id),
  KEY ix_model_metrics_market_date (tenant_id, market, date_from, date_to),
  CONSTRAINT fk_model_metrics_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id),
  CONSTRAINT ck_model_metrics_count CHECK (sample_count >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 取込カーソル（旧 jvlink_timestamps）。ACK を受けた分だけ進める（JVLINK_DESIGN §5.2）。
-- ---------------------------------------------------------------------------
CREATE TABLE ingest_cursor (
  tenant_id            BIGINT NOT NULL,
  data_type            VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  last_file_timestamp  VARCHAR(14) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  updated_at           DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id, data_type),
  CONSTRAINT fk_ingest_cursor_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 移行の補助と監査
-- ---------------------------------------------------------------------------
-- 変換できなかった行の隔離先（DATA_MIGRATION_DESIGN §5.4）。payload は元の行を JSON にしたもの。
CREATE TABLE import_quarantine (
  id            BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id     BIGINT NOT NULL,
  migration_run VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_table  VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_key    VARCHAR(255) NULL,
  reason_code   VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reason_detail VARCHAR(500) NULL,
  payload       JSON NULL,
  created_at    DATETIME(6) NOT NULL,
  resolved_at   DATETIME(6) NULL,
  PRIMARY KEY (id),
  KEY ix_import_quarantine_run (tenant_id, migration_run, source_table),
  CONSTRAINT fk_import_quarantine_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 管理操作の監査（who / when / what / before / after / 理由。CONTRACTS §11.1）。追記専用（UPDATE・DELETE を grant しない）。
CREATE TABLE audit_log (
  id          BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id   BIGINT NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  actor_type  ENUM('USER', 'AGENT', 'SYSTEM') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  actor       VARCHAR(254) NOT NULL,
  action      VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  target_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  target_id   VARCHAR(100) NULL,
  before_json JSON NULL,
  after_json  JSON NULL,
  reason      VARCHAR(500) NULL,
  request_id  VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  PRIMARY KEY (id),
  KEY ix_audit_log_time (tenant_id, occurred_at),
  CONSTRAINT fk_audit_log_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
