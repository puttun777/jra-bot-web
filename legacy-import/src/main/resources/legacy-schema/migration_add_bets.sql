-- バックテスト/成績集計機能用テーブル追加

-- bets テーブル（実際に買った/ペーパートレードの記録）
CREATE TABLE IF NOT EXISTS bets (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  race_id TEXT NOT NULL,
  ts TEXT NOT NULL,
  market TEXT NOT NULL DEFAULT 'win',
  stake REAL NOT NULL,
  source TEXT NOT NULL DEFAULT 'paper',  -- 'paper' | 'manual'
  note TEXT,
  created_at TEXT DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (race_id) REFERENCES races(race_id)
);

-- bet_legs テーブル（複数買いの拡張用。単勝は1レッグ）
CREATE TABLE IF NOT EXISTS bet_legs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  bet_id INTEGER NOT NULL,
  umaban INTEGER NOT NULL,
  odds_at_bet REAL NOT NULL,
  prob_at_bet REAL,
  ev_at_bet REAL,
  created_at TEXT DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (bet_id) REFERENCES bets(id) ON DELETE CASCADE
);

-- インデックス
CREATE INDEX IF NOT EXISTS idx_bets_race_id ON bets(race_id);
CREATE INDEX IF NOT EXISTS idx_bets_ts ON bets(ts);
CREATE INDEX IF NOT EXISTS idx_bets_source ON bets(source);
CREATE INDEX IF NOT EXISTS idx_bet_legs_bet_id ON bet_legs(bet_id);
CREATE INDEX IF NOT EXISTS idx_bet_legs_umaban ON bet_legs(umaban);
