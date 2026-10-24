"""SQLite のスキーマ。

バッチの状態（送信側の見方）:
  QUEUED      キュー投入済み = 送信待ち（永続化済み。ここまで来れば、プロセスが落ちても失わない）
  SENDING     送信中（同時に 1 つだけ。クラッシュ後の起動時に QUEUED へ戻す）
  RETRY_WAIT  再試行待ち（`next_try_at` まで送らない。同じ batchId・同じ内容で再送する）
  ACKED       サーバが受理（本文は消す）
  DEAD        自動再送を止めた（不正・期限切れ・溢れ・上限超過・取得セッションの中止）。**成功ではない**
取得セッション（JVOpen 1 回分）の状態:
  READING     取得中（バッチを少しずつキューへ入れている）
  FETCHED     取得済み = EOF まで読み終え、全バッチがキューに入った（`fetched_ts`・`queued_ts` が動く）
  COMPLETED   サーバが取得の完了を確認（EOF バッチの ACK に `cursor` が付いたときだけ `acked_ts` が動く）
  ABORTED     中止（不正・認証・再試行上限など）。`acked_ts` は動かない
  INTERRUPTED 再起動で中断（JV-Link の読み位置は復元できない）。キューに入った分は送るが、完了にはしない
"""

SCHEMA_VERSION = 1

DDL = """
CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL);

CREATE TABLE IF NOT EXISTS fetch_session (
  fetch_id        TEXT PRIMARY KEY,
  cursor_key      TEXT NOT NULL,
  dataspec        TEXT NOT NULL,
  option          INTEGER,
  origin          TEXT NOT NULL CHECK (origin IN ('LIVE', 'BACKFILL')),
  backfill_id     INTEGER,
  fromtime        TEXT,
  last_file_timestamp TEXT,
  target_from     TEXT,
  target_to       TEXT,
  state           TEXT NOT NULL CHECK (state IN ('READING', 'FETCHED', 'COMPLETED', 'ABORTED', 'INTERRUPTED')),
  next_seq        INTEGER NOT NULL DEFAULT 0,
  cursor_confirmed INTEGER NOT NULL DEFAULT 0,
  reason          TEXT,
  created_at      REAL NOT NULL,
  updated_at      REAL NOT NULL,
  completed_at    REAL
);

CREATE TABLE IF NOT EXISTS batch (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  batch_id        TEXT NOT NULL UNIQUE,
  cls             TEXT NOT NULL CHECK (cls IN ('S', 'R')),
  dataspec        TEXT NOT NULL,
  fetch_id        TEXT REFERENCES fetch_session (fetch_id),
  fetch_seq       INTEGER,
  eof             INTEGER NOT NULL,
  priority        INTEGER NOT NULL,
  observed_at     REAL NOT NULL,
  expires_at      REAL,
  payload         BLOB NOT NULL,
  payload_sha256  TEXT NOT NULL,
  payload_len     INTEGER NOT NULL,
  record_count    INTEGER NOT NULL,
  status          TEXT NOT NULL CHECK (status IN ('QUEUED', 'SENDING', 'RETRY_WAIT', 'ACKED', 'DEAD')),
  attempts        INTEGER NOT NULL DEFAULT 0,
  next_try_at     REAL NOT NULL DEFAULT 0,
  last_outcome    TEXT,
  dead_reason     TEXT,
  created_at      REAL NOT NULL,
  updated_at      REAL NOT NULL,
  acked_at        REAL,
  UNIQUE (fetch_id, fetch_seq),
  CHECK ((fetch_id IS NULL) = (fetch_seq IS NULL))
);
CREATE INDEX IF NOT EXISTS batch_by_status ON batch (status, priority, id);
CREATE INDEX IF NOT EXISTS batch_by_fetch ON batch (fetch_id, fetch_seq);

CREATE TABLE IF NOT EXISTS checkpoint (
  cursor_key  TEXT PRIMARY KEY,
  fetched_ts  TEXT,
  queued_ts   TEXT,
  queued_seq  INTEGER,
  acked_ts    TEXT,
  updated_at  REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS state (k TEXT PRIMARY KEY, v TEXT NOT NULL, updated_at REAL NOT NULL);

CREATE TABLE IF NOT EXISTS alert (
  name      TEXT PRIMARY KEY,
  detail    TEXT NOT NULL,
  raised_at REAL NOT NULL
);
"""
