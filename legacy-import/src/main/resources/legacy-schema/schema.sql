-- JRA EV Bot データベーススキーマ

CREATE TABLE IF NOT EXISTS races (
  race_id TEXT PRIMARY KEY,
  date TEXT NOT NULL,
  track TEXT NOT NULL,
  track_cd TEXT,                   -- 場コード（01..10）
  race_no INTEGER NOT NULL,
  post_time TEXT NOT NULL,
  name TEXT,
  distance_m INTEGER,              -- 距離（メートル）
  track_type_cd TEXT,              -- トラックコード（例: 11=芝, 12=ダ, 13=障害）
  course_kubun_cd TEXT,            -- コース区分
  tenko_cd TEXT,                   -- 天候コード
  shiba_baba_cd TEXT,              -- 芝馬場状態コード
  dirt_baba_cd TEXT,               -- ダート馬場状態コード
  created_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS odds_snapshots (
  race_id TEXT NOT NULL,
  ts TEXT NOT NULL,
  market TEXT NOT NULL DEFAULT 'win',
  umaban INTEGER NOT NULL,
  odds REAL NOT NULL,
  popularity INTEGER,
  PRIMARY KEY (race_id, ts, market, umaban),
  FOREIGN KEY (race_id) REFERENCES races(race_id)
);

CREATE TABLE IF NOT EXISTS signals (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  race_id TEXT NOT NULL,
  ts TEXT NOT NULL,
  market TEXT NOT NULL DEFAULT 'win',
  umaban INTEGER NOT NULL,
  odds REAL NOT NULL,
  prob REAL NOT NULL,
  ev REAL NOT NULL,
  -- 追加: 成績集計/ダッシュボード用（後方互換のためDEFAULT付き）
  is_paper INTEGER NOT NULL DEFAULT 1,  -- 1=紙/シミュレーション、0=実投資
  stake REAL NOT NULL DEFAULT 1.0,      -- 投資点数
  expected_profit REAL,                -- 期待利益（点、任意）
  skip_reason TEXT,                    -- 見送り理由（任意）
  -- Kelly関連
  kelly_raw REAL,
  kelly_adj REAL,
  bet_before_clip REAL,
  clipped INTEGER DEFAULT 0,
  -- 市場確率/検算用
  mkt REAL,
  mkt_adj REAL,
  ratio REAL,
  expected_return REAL,
  suggested_bet REAL,
  reason TEXT,
  sent INTEGER DEFAULT 0,
  created_at TEXT DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (race_id) REFERENCES races(race_id)
);

CREATE TABLE IF NOT EXISTS results (
  race_id TEXT NOT NULL,
  umaban INTEGER NOT NULL,
  finish INTEGER,
  payout REAL,
  created_at TEXT DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (race_id, umaban),
  FOREIGN KEY (race_id) REFERENCES races(race_id)
);

-- JV-Link SDKのlastfiletimestampを保存（差分取得用）
CREATE TABLE IF NOT EXISTS jvlink_timestamps (
  data_type TEXT NOT NULL,
  lastfiletimestamp TEXT NOT NULL,
  updated_at TEXT DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (data_type)
);

-- 追加: 競走馬/出走/過去走（出走歴・脚質・血統・条件適性のための土台）
CREATE TABLE IF NOT EXISTS horses (
  ketto_num TEXT PRIMARY KEY,          -- 血統登録番号
  bamei TEXT,                          -- 馬名
  sex_cd TEXT,                         -- 性別コード
  birth_date TEXT,                     -- 生年月日(YYYYMMDD)
  tozai_cd TEXT,                       -- 東西所属
  trainer_code TEXT,                   -- 調教師コード
  owner_code TEXT,                     -- 馬主コード
  breeder_code TEXT,                   -- 生産者コード
  pedigree_json TEXT,                  -- 3代血統(配列)をJSONで保存
  style_tendency_json TEXT,            -- 脚質傾向(配列)をJSONで保存
  chaku_by_surface_json TEXT,          -- 馬場別着回数(配列)をJSONで保存
  chaku_by_condition_json TEXT,        -- 馬場状態別着回数(配列)をJSONで保存
  chaku_by_distance_json TEXT,         -- 距離別着回数(配列)をJSONで保存
  race_count INTEGER,                  -- 登録レース数
  updated_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS race_horses (
  race_id TEXT NOT NULL,
  umaban INTEGER NOT NULL,
  ketto_num TEXT,
  bamei TEXT,
  jockey_code TEXT,
  trainer_code TEXT,
  created_at TEXT DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (race_id, umaban),
  FOREIGN KEY (race_id) REFERENCES races(race_id)
);

-- SE(馬毎レース情報)をベースにした過去走履歴（最小限）
CREATE TABLE IF NOT EXISTS horse_past_runs (
  ketto_num TEXT NOT NULL,
  race_id TEXT NOT NULL,
  date TEXT,                           -- YYYYMMDD（race_id先頭8桁）
  track_cd TEXT,                       -- 場コード(JyoCD)
  race_no INTEGER,
  umaban INTEGER,
  finish INTEGER,
  odds REAL,
  popularity INTEGER,
  kyakusitu_kubun TEXT,                -- 今回レース脚質判定
  corner1 INTEGER,
  corner2 INTEGER,
  corner3 INTEGER,
  corner4 INTEGER,
  time_str TEXT,                       -- 走破タイム(文字列)
  time_diff_str TEXT,                  -- タイム差(文字列)
  created_at TEXT DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (ketto_num, race_id)
);

-- インデックス
CREATE INDEX IF NOT EXISTS idx_odds_snapshots_race_ts ON odds_snapshots(race_id, ts);
CREATE INDEX IF NOT EXISTS idx_signals_race ON signals(race_id);
CREATE INDEX IF NOT EXISTS idx_signals_sent ON signals(sent);
CREATE INDEX IF NOT EXISTS idx_signals_race_market_umaban_sent ON signals(race_id, market, umaban, sent);
CREATE INDEX IF NOT EXISTS idx_signals_date ON signals(race_id, ts);
CREATE INDEX IF NOT EXISTS idx_signals_market ON signals(market);
CREATE INDEX IF NOT EXISTS idx_signals_is_paper ON signals(is_paper);
CREATE INDEX IF NOT EXISTS idx_signals_ev ON signals(ev);
CREATE INDEX IF NOT EXISTS idx_race_horses_ketto ON race_horses(ketto_num);
CREATE INDEX IF NOT EXISTS idx_horse_past_runs_date ON horse_past_runs(date);

-- モデル評価（キャリブレーション等）
CREATE TABLE IF NOT EXISTS model_metrics (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  date_from TEXT,
  date_to TEXT,
  market TEXT NOT NULL DEFAULT 'win',
  count INTEGER NOT NULL,
  brier REAL,
  logloss REAL,
  calibration_json TEXT,
  created_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_model_metrics_market_date ON model_metrics(market, date_from, date_to);

-- 払戻テーブル（wide/sanrenpuku用。単勝はresultsテーブルを使用）
-- key: 組み合わせ表現（例: wide="2-7", sanrenpuku="2-7-9"）
CREATE TABLE IF NOT EXISTS payouts (
  race_id TEXT NOT NULL,
  market TEXT NOT NULL,
  key TEXT NOT NULL,                    -- 組み合わせ（例: "2-7", "2-7-9"）
  payout REAL NOT NULL,                 -- 100円あたりの払戻金額（または倍率）
  created_at TEXT DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (race_id, market, key),
  FOREIGN KEY (race_id) REFERENCES races(race_id)
);

CREATE INDEX IF NOT EXISTS idx_payouts_race_market ON payouts(race_id, market);

-- 組み合わせオッズスナップショット（ワイド/三連複の実オッズ保存用）
CREATE TABLE IF NOT EXISTS combo_odds_snapshots (
  race_id TEXT NOT NULL,
  collected_at TEXT NOT NULL,
  market TEXT NOT NULL,                      -- "wide" or "sanrenpuku"
  combo_key TEXT NOT NULL,                   -- 組み合わせ表現（例: "1-2", "1-2-3"、昇順正規化）
  odds REAL NOT NULL,
  popularity INTEGER,                        -- 人気順位（オプション）
  source TEXT,                               -- データソース（例: "0B33", "0B35"）
  rt_key TEXT,                               -- JV-Link RTレコードキー（重複排除用）
  created_at TEXT DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (race_id, collected_at, market, combo_key),
  FOREIGN KEY (race_id) REFERENCES races(race_id)
);

CREATE INDEX IF NOT EXISTS idx_combo_odds_snapshots_race_market_collected ON combo_odds_snapshots(race_id, market, collected_at);

-- レース評価結果（通知有無に関係なく、評価したレースの情報を保存）
CREATE TABLE IF NOT EXISTS race_evaluations (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  race_id TEXT NOT NULL,
  snapshot_at TEXT NOT NULL,                 -- 評価タイミング（ISO形式）
  model_type_used TEXT,                      -- 使用したモデルタイプ（"legacy"/"calibration"/"alpha"/"ensemble"）
  model_path_used TEXT,                      -- 使用したモデルパス
  bet_types TEXT,                            -- JSON配列（例: ["ワイド","三連複"]）
  priority_mode TEXT,                        -- "edge" or "hit_rate"
  ev_threshold REAL,                         -- EV閾値
  win_odds_max REAL,                         -- 単勝オッズ上限（hit_rate時）
  win_prob_min REAL,                         -- 単勝確率下限（hit_rate時）
  win_ratio_min REAL,                        -- 単勝ratio下限（hit_rate時）
  best_candidate_json TEXT,                  -- JSON（最良候補の情報）
  best_ev REAL,                              -- 最良EV
  rejection_summary_json TEXT,               -- JSON（見送り理由の集計）
  signal_sent INTEGER DEFAULT 0,             -- 通知したかどうか（1=通知、0=見送り）
  created_at TEXT DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (race_id) REFERENCES races(race_id)
);

CREATE INDEX IF NOT EXISTS idx_race_evaluations_race_snapshot ON race_evaluations(race_id, snapshot_at);
CREATE INDEX IF NOT EXISTS idx_race_evaluations_signal_sent ON race_evaluations(signal_sent);

-- チケット評価結果（1レースに複数の候補チケットを保存）
CREATE TABLE IF NOT EXISTS ticket_evaluations (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  race_evaluation_id INTEGER NOT NULL,       -- race_evaluations.id への外部キー
  race_id TEXT NOT NULL,
  snapshot_at TEXT NOT NULL,
  bet_type TEXT NOT NULL,                    -- "単勝"/"ワイド"/"三連複"
  combo_key TEXT,                            -- 組み合わせ（例: "1-2", "1-2-3"）、単勝の場合はumaban文字列
  umaban INTEGER,                            -- 軸馬番（単勝の場合）
  odds REAL NOT NULL,
  p_model REAL,                              -- モデル確率
  p_mkt REAL,                                -- 市場確率（1/odds）
  p_final REAL,                              -- 最終確率（クリップ後）
  ratio REAL,                                -- p_final / p_mkt
  ev REAL,                                   -- 期待値
  stake REAL,                                -- 推奨投資額
  rank INTEGER,                              -- ランク（best_onlyなら1のみ）
  reject_reason TEXT,                        -- 見送り理由（通知した場合はNULL）
  accepted INTEGER DEFAULT 0,                -- 通知対象かどうか（1=通知、0=見送り）
  created_at TEXT DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (race_evaluation_id) REFERENCES race_evaluations(id) ON DELETE CASCADE,
  FOREIGN KEY (race_id) REFERENCES races(race_id)
);

CREATE INDEX IF NOT EXISTS idx_ticket_evaluations_race_eval ON ticket_evaluations(race_evaluation_id);
CREATE INDEX IF NOT EXISTS idx_ticket_evaluations_race_snapshot ON ticket_evaluations(race_id, snapshot_at);
CREATE INDEX IF NOT EXISTS idx_ticket_evaluations_accepted ON ticket_evaluations(accepted);
CREATE INDEX IF NOT EXISTS idx_ticket_evaluations_bet_type ON ticket_evaluations(bet_type);