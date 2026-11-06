# DATA_MIGRATION_DESIGN（T1.11 SQLite → MySQL データ移行設計）

調査日: 2026-10-22。範囲: 設計のみ。**実データの移行は行わない**。参照元のスキーマ・SQL・ロジックはコードから確認済み（`storage/schema.sql`、`db.py` の動的マイグレーション、`migration_*.sql`、各 repo、`ui/db_read*.py`）。

## 0. 前提（重要）
- **実 DB ファイル（`data/jraev.sqlite`）・学習済みモデル・データセットは GitHub にない**（参照元リポジトリは `.gitignore` で除外、`models/` には JSON 2 件のみ）。**存在すると仮定せず**、利用者が実ファイルを取得した後に実行できる手順として設計する（§12）。
- 行数・サイズ・時刻の実態（JST か UTC か）・孤立行・不正 JSON などは**実ファイルで初めて分かる**。本書の見積りは式と仮定で示し、実測で更新する（T1.20）。
- **規約**: 実データをクラウド（Cloud SQL 等）へ投入する可否は未確認（DATA_SOURCE_COMPLIANCE #2・#3、問い合わせ Q1）。したがって移行手順は **(1) ローカル MySQL（Docker）での完全リハーサル → (2) 規約回答後にクラウドへ** の 2 段階とし、(1) だけで実装検証を完結できるようにする。
- tenant（=JRA-VAN 契約者、D-023）境界を最初から入れる。

## 1. 現行スキーマの棚卸し（15 テーブル）
SQLite（`journal_mode=WAL`、`foreign_keys=ON`、`busy_timeout=10000`）。**起動時にアプリ内で `ALTER TABLE ADD COLUMN` を行う動的マイグレーション**があるため、実ファイルのスキーマは `schema.sql` と一致しているとは限らない（§12 の事前検査で差分を検出）。

| テーブル | 主キー | 外部キー | 追加列（動的） | 主な索引 | 備考 |
|---|---|---|---|---|---|
| `races` | `race_id`(12桁) | — | `track_cd, distance_m, track_type_cd, course_kubun_cd, tenko_cd, shiba_baba_cd, dirt_baba_cd` | — | `date`=YYYYMMDD、`post_time`=HH:MM（文字列）、`created_at`=CURRENT_TIMESTAMP(UTC) |
| `odds_snapshots` | `(race_id, ts, market, umaban)` | races | — | `(race_id, ts)` | `ts`=ISO（アプリ側 `datetime.now().isoformat()`、**ローカル時刻・タイムゾーンなし**）。最大の増加源（単勝） |
| `signals` | `id` AUTOINCREMENT | races | `is_paper, stake, expected_profit, skip_reason, kelly_raw, kelly_adj, bet_before_clip, clipped, mkt, mkt_adj, ratio, expected_return, suggested_bet, prob_source` | race / sent / (race,market,umaban,sent) / date / market / is_paper / ev | `sent`=通知済み |
| `results` | `(race_id, umaban)` | races | — | — | `finish`, `payout`(倍率。単勝)。**払戻桁の疑義あり（JVLINK_DESIGN §1.4）** |
| `jvlink_timestamps` | `data_type` | — | — | — | → `ingest_cursor`（ACK ベース、F22） |
| `horses` | `ketto_num` | — | — | — | JSON 文字列 5 列（`pedigree_json` 等）。`birth_date`=YYYYMMDD |
| `race_horses` | `(race_id, umaban)` | races | — | `ketto_num` | |
| `horse_past_runs` | `(ketto_num, race_id)` | — | — | `date` | |
| `model_metrics` | `id` | — | — | `(market, date_from, date_to)` | `count`(予約語に近い)、`calibration_json` |
| `payouts` | `(race_id, market, key)` | races | `is_provisional` | `(race_id, market)` | **`key` は MySQL 予約語** |
| `combo_odds_snapshots` | `(race_id, collected_at, market, combo_key)` | races | — | `(race_id, market, collected_at)` | 既定では保存しない（`enable_combo_real_odds` 既定 False、example config に無し）。**有効化すると 1 レース約 75k 行/日 → 年 2.7 億行規模**（§9） |
| `race_evaluations` | `id` | races | `settled_at, hit, payout_used, is_provisional, settle_status, best_win_hint_json` | `(race_id, snapshot_at)`, `signal_sent` | |
| `ticket_evaluations` | `id` | race_evaluations(CASCADE), races | — | race_eval / (race,snapshot) / accepted / bet_type | **`rank` は MySQL 8 で予約語** |
| `bets` | `id` | races | `selection, odds_at_bet, prob_used_for_bet, ev, expected_profit, signal_id, status, settled_at, payout, profit, notes, skip_reason` | race / ts / source / signal_id / status / settled_at | |
| `bet_legs` | `id` | bets(CASCADE) | — | bet_id / umaban | |

### 1.1 SQLite 固有の挙動・SQL
| 項目 | 実態 | MySQL での扱い |
|---|---|---|
| `INSERT OR REPLACE` | 11 箇所（races, results, payouts, horses, race_horses, horse_past_runs, jvlink_timestamps）。**行を削除→挿入**（子行・トリガ・列の既存値が失われる） | `INSERT … ON DUPLICATE KEY UPDATE`（列を明示的に更新）。`REPLACE` は使わない |
| `INSERT OR IGNORE` | 2 箇所 | `INSERT IGNORE` ではなく `ON DUPLICATE KEY UPDATE col=col` かユニーク違反の明示ハンドリング |
| `strftime` 85 / `date()` 36 / `datetime()` 10 / `substr()` 7 / `COALESCE` 5 | 日付範囲・日別集計・文字列切り出し（`race_id[:8]` が日付） | DATE 型で直接比較・`DATE()`/`DATE_FORMAT`。集計は UI から**サービス層の SQL へ移す**（F20）。`substr(race_id,1,8)` は不要（列を持つ） |
| 型親和性 | 型が緩い（TEXT に数値、REAL に整数等） | 厳格な型へ変換。**変換不能な行は隔離（quarantine）** |
| `AUTOINCREMENT` | signals, bets, bet_legs, race_evaluations, ticket_evaluations, model_metrics | `BIGINT AUTO_INCREMENT`（§3.2 のとおり旧 ID は `legacy_id` に保持） |
| `PRAGMA foreign_keys=ON` | FK 有効。ただし過去に FK 違反回避のため `fetch_historical_data.py` が最小の `races` 行を先に upsert する運用（L1177〜） | FK を維持。**孤立行は移行時に隔離 or スタブ作成**（§5.4） |
| 削除/保持 | アプリに `DELETE`/`VACUUM`/保持期間のロジックは**無い**（無制限に増加） | 保持期間を新設（§9） |
| 同時アクセス | WAL + `busy_timeout` で ダッシュボードとデーモンが同一ファイルを共有 | MySQL（接続プール）。ロック再試行は不要 |

### 1.2 時刻・日付の実態（正規化が必要）
| 列 | 形式 | 由来 | MySQL 変換 |
|---|---|---|---|
| `races.date` | `YYYYMMDD`（文字列） | JV-Data（JST） | `DATE` |
| `races.post_time` | `HH:MM`（`HH` は 24 以上や分 60 以上の特殊表現を `parse_post_time` が繰上げ） | JV-Data HHMM | `TIME`（範囲外/`00:00` は NULL + `post_time_unknown`） |
| `*.ts / snapshot_at / collected_at / settled_at / created_at(アプリ設定)` | ISO 文字列 `YYYY-MM-DDTHH:MM:SS.ffffff`（**naive・Windows ローカル時刻 = 通常 JST**） | `datetime.now().isoformat()` | `DATETIME(6)` を **UTC で保存**（JST −9h）。**変換前に実ファイルで検証**（`races.date + post_time` と `odds_snapshots.ts` の関係が JST と整合するか） |
| `*.created_at` の DEFAULT | `YYYY-MM-DD HH:MM:SS`（`CURRENT_TIMESTAMP`=**UTC**、スペース区切り） | SQLite | UTC として扱う |
→ **同一テーブル内で 2 つの時刻基準（アプリ設定=JST の `T` 区切り、DEFAULT=UTC のスペース区切り）が混在**。形式（`T` の有無）で判別して変換する。判別不能・範囲外は隔離。
| `horses.birth_date` / `horse_past_runs.date` | `YYYYMMDD` | JV-Data | `DATE`（不正は NULL） |
| `model_metrics.date_from/date_to` | `YYYYMMDD` or `YYYY-MM-DD` が混在し得る | スクリプト | `DATE`（両形式を許容） |

## 2. 目標スキーマの方針（MySQL 8.4 / 9.7 共通機能のみ）
| 項目 | 方針 |
|---|---|
| 文字セット | `utf8mb4`。コード列（`race_id, ketto_num, venue_code, market` 等）は `CHARACTER SET ascii COLLATE ascii_bin`（厳密一致）、名称（`bamei, name`）は `utf8mb4_0900_ai_ci` を既定とし**キーに使わない**（かな全角/半角・大文字小文字の同一視を避ける） |
| 時刻 | `DATETIME(6)` を UTC で保存。`TIMESTAMP` は 2038 年問題と暗黙の TZ 変換のため不使用。アプリは `Clock`（Asia/Tokyo）で変換 |
| 数値 | 確率・オッズ・EV・投資点数は `DOUBLE`（Python float64 と一致させゴールデンテストを可能にする）。金額（実円）は扱っていない（「点」単位）。将来実円を扱う場合のみ `DECIMAL` |
| 真偽 | `TINYINT(1)`（0/1） |
| JSON | `JSON` 型（検証済みの行のみ）。**MySQL は JSON を正規化（キー順・空白）するため、移行時チェックサムは「パース→正規化→比較」** |
| 予約語の改名 | `payouts.key`→`combo_key`、`ticket_evaluations.rank`→`rank_no`、`model_metrics.count`→`sample_count`、`date`→`race_date` / `run_date` |
| ID | レース識別は **`race_id CHAR(12)`**（YYYYMMDD+場2+R2）を全テーブル共通の自然キーとして維持（旧データと互換、1 日・1 場に 1 開催のため一意）。`kai`（回）/`nichime`（日目）は `races` の NULL 可列（旧データは不明。YSCH/RACE 再取得で後から補完）。`rt_key CHAR(16)` は導出値/列。**RACE_CALENDAR_DESIGN の `race_key CHAR(16)` は `race_id CHAR(12)` + `kai/nichime` に修正（D-040）** |
| tenant | 全テナント表に `tenant_id BIGINT NOT NULL` を**主キー/ユニークキーの先頭**に含める（§4） |
| FK | 維持（`ON DELETE` は原則 RESTRICT。`ticket_evaluations`/`bet_legs` のみ親子の CASCADE）。**FK は `(tenant_id, 親キー)` の複合**にしてテナント越境の参照を DB で禁止 |
| 索引 | 旧索引を踏襲し `tenant_id` を先頭に（例: `(tenant_id, race_id, ts)`）。実クエリ（`db_read` の WHERE/GROUP BY）から再設計し EXPLAIN で確認（T2.4） |
| 監査列 | 全表 `created_at DATETIME(6)`（UTC）、更新がある表は `updated_at`。ソース由来の識別は `legacy_id`（§3.2）|

### 2.1 代表テーブルの DDL 案（抜粋）
```sql
CREATE TABLE tenant (
  tenant_id BIGINT PRIMARY KEY, name VARCHAR(100) NOT NULL, created_at DATETIME(6) NOT NULL
);
CREATE TABLE races (
  tenant_id BIGINT NOT NULL,
  race_id   CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  race_date DATE NOT NULL, venue_code CHAR(2) CHARACTER SET ascii NOT NULL, race_no TINYINT NOT NULL,
  kai TINYINT NULL, nichime TINYINT NULL,                  -- 旧データは NULL（後で補完）
  post_time TIME NULL, post_time_unknown TINYINT(1) NOT NULL DEFAULT 0,
  name VARCHAR(100) NULL,
  distance_m SMALLINT NULL, track_type_cd CHAR(2) NULL, course_kubun_cd CHAR(2) NULL,
  tenko_cd CHAR(1) NULL, shiba_baba_cd CHAR(1) NULL, dirt_baba_cd CHAR(1) NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id, race_id), KEY ix_date (tenant_id, race_date, venue_code, race_no)
);
CREATE TABLE odds_snapshots (
  tenant_id BIGINT NOT NULL, race_id CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  observed_at DATETIME(6) NOT NULL, market VARCHAR(16) CHARACTER SET ascii NOT NULL, umaban TINYINT NOT NULL,
  odds DOUBLE NOT NULL, popularity SMALLINT NULL,
  PRIMARY KEY (tenant_id, race_id, observed_at, market, umaban),
  CONSTRAINT fk_odds_race FOREIGN KEY (tenant_id, race_id) REFERENCES races (tenant_id, race_id)
);
CREATE TABLE signals (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL, legacy_id BIGINT NULL,        -- 旧 id（移行行のみ）
  race_id CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, observed_at DATETIME(6) NOT NULL,
  market VARCHAR(16) CHARACTER SET ascii NOT NULL, umaban TINYINT NOT NULL,
  odds DOUBLE NOT NULL, prob DOUBLE NOT NULL, ev DOUBLE NOT NULL,
  is_paper TINYINT(1) NOT NULL DEFAULT 1, stake DOUBLE NOT NULL DEFAULT 1.0,
  /* …Kelly/市場確率/prob_source 等は旧列を踏襲… */ sent TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uq_legacy (tenant_id, legacy_id), KEY ix_race (tenant_id, race_id, market, umaban, sent),
  CONSTRAINT fk_sig_race FOREIGN KEY (tenant_id, race_id) REFERENCES races (tenant_id, race_id)
);
CREATE TABLE payouts (
  tenant_id BIGINT NOT NULL, race_id CHAR(12) NOT NULL, market VARCHAR(16) NOT NULL, combo_key VARCHAR(16) NOT NULL,
  payout DOUBLE NOT NULL, is_provisional TINYINT(1) NOT NULL DEFAULT 0, created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id, race_id, market, combo_key)
);
```
他テーブルも同様（`horses` は `PRIMARY KEY (tenant_id, ketto_num)`、JSON 列は `JSON`、`race_evaluations` の `best_candidate_json` 等も `JSON`）。**JRA-VAN 由来の「マスタ」（horses 等）も契約者のデータなのでテナント表**（共有しない）。

### 2.2 Doma との整合
| 項目 | 方針 |
|---|---|
| エンティティ | 1 テーブル 1 エンティティ（`:persistence` 内部。API に出さない）。複合主キーは複数の `@Id`。`@Table(name=…)`、命名は `NamingType.SNAKE_LOWER_CASE` |
| 時刻 | `LocalDateTime`（UTC 列）/`LocalDate`/`LocalTime`。`Instant` の扱いは Doma の対応型を T2.4 で確認（**未確認**）し、必要なら `@Domain` で変換。ドメイン層は `Instant`/`ZonedDateTime` を使い変換は persistence 内 |
| JSON | `String` で受けて `:persistence` で型付けオブジェクトへ変換（Doma は JSON 型を直接扱わない想定。**要確認**） |
| SQL | DAO ごとに SQL ファイル（`META-INF/…/*.sql`）。**すべての SQL に `tenant_id` 条件を必須**（CI の静的検査: テナント表への SELECT/UPDATE/DELETE で `tenant_id` を含まないクエリを失敗させる）。DAO メソッドの第一引数は `TenantId` |
| UPSERT | `INSERT … ON DUPLICATE KEY UPDATE` を SQL テンプレートで記述（Doma の `@Insert` で `sqlFile=true` を使用する想定。**要検証**） |
| 楽観ロック | 更新が競合し得る表（`race_schedule`, `bot_run` 等）は `@Version` |
| 自動生成 | `AUTO_INCREMENT` は `@GeneratedValue(strategy = IDENTITY)` |
| スキーマの正 | **Flyway の SQL が正**。エンティティは手書き（Doma のコード生成はスキーマから作らない）。スキーマとエンティティの不一致は Testcontainers の統合テストで検出 |

## 3. Flyway 設計
| 項目 | 方針 |
|---|---|
| 配置 | `:db-migration` の `src/main/resources/db/migration/V{n}__{desc}.sql`（バージョン付き、不変）。ビュー/ストアドは Repeatable（`R__`） |
| ベースライン | `V1__baseline.sql`: 旧 `schema.sql` + 動的列 + `bets` を**MySQL 向けに再設計**した最終形（§2）。`V2__ingest_calendar.sql`: カレンダー/ingest/`bot_run`/`ml_model`/`app_user`。以降は追加のみ |
| 実行 | **デプロイごとに 1 回の Job**（アプリ起動時 migrate 禁止: CONTRACTS §4）。`validateOnMigrate=true`、`outOfOrder=false`、`baselineOnMigrate=false`（空 DB 前提）。DDL 専用ユーザーとアプリ用 DML ユーザーを分離 |
| 互換 | MySQL 8.4/9.7 で動く SQL のみ（バージョン固有構文を避ける）。Testcontainers で 8.4 と 9.7 の両方に適用（VERSIONS §3） |
| テナント初期化 | `V1` で `tenant` を作るが**行は入れない**。初期テナントは管理手順（移行ツール/管理 API）で作成 |
| 範囲の確定（T1.10, D-058） | V1/V2/`R__grants.sql` の表の割当は `ARCHITECTURE.md` §5.1。DB ユーザー（migrate/app/ingest/bot/mltrain）と権限は `CONTRACTS.md` §8.2。CLI は `--mode=info\|validate\|migrate`（CONTRACTS §4.2） |

### 3.1 後方互換な変更（運用開始後）
- **expand/contract**: ① 追加（NULL 可列/新表/新索引を先にデプロイ）→ ② アプリが新旧を読み書き → ③ バックフィル（バッチ）→ ④ 旧を参照停止 → ⑤ 別リリースで削除。**リネーム・型変更・NOT NULL 追加を 1 リリースで行わない**。
- 追加列は NULL 可または DEFAULT 付き。`ALGORITHM=INSTANT`/`INPLACE` を優先（8.x のオンライン DDL）、巨大表（`odds_snapshots`）への索引追加は実行時間とストレージを事前見積り。
- 旧アプリ版が新スキーマで動くこと（ロールバック可能性）をリリースゲートにする（旧版イメージに対する新スキーマ統合テスト）。
- スキーマ版とアプリ版の対応を `release-manifest.json` に記録（CONTRACTS §1）。

## 4. テナント設計
### 4.1 方式比較
| 方式 | 内容 | 長所 | 短所 |
|---|---|---|---|
| **A 共有スキーマ + `tenant_id` 列（採用案）** | 全テナント表の PK/FK/索引の先頭に `tenant_id` | 運用が単純（Flyway 1 回、接続プール 1 つ）、拡張性高 | 越境バグをアプリ/テストで防ぐ必要（MySQL に RLS なし） |
| B スキーマ分離（テナントごとの MySQL スキーマ） | 同一インスタンス内で分離 | 越境が構造的に困難、テナント単位のバックアップ/削除が容易 | Flyway をテナント数分実行、DataSource ルーティングが必要、数百以上は不向き |
| C DB インスタンス分離 | 完全分離 | 最も安全 | 固定費 ×N（費用面で非現実的） |
**採用: A**（D-039）。ただし**テナントは契約者単位（初期は 1〜2）**であり、規約面で「他テナントのデータを見せない」ことが最重要なので、**防御を多重化**: ① 複合 FK（親子で `tenant_id` 一致を DB が強制）、② DAO が `TenantId` を必須引数、③ SQL に `tenant_id` 条件が無いクエリを CI で拒否、④ 認可層が IAP の ID → `app_user.tenant_id` を解決し以降は不変のコンテキスト、⑤ **越境テスト**（テナント 2 つで全 API・全 DAO を叩き、他方のデータが返らないことを自動検証）。要件が強まれば B へ移行（`tenant_id` を持つので移行は機械的）。

### 4.2 共有テーブルと分離テーブル
| 区分 | テーブル | 理由 |
|---|---|---|
| **テナント表**（JRA-VAN 由来データ・利用者の判断/成績・モデル） | races, race_horses, horses, horse_past_runs, odds_snapshots, combo_odds_snapshots, results, payouts, signals, bets, bet_legs, race_evaluations, ticket_evaluations, model_metrics, ingest_cursor（旧 `jvlink_timestamps`）, race_day, race_schedule, race_schedule_event, calendar_override, bot_run, ml_model, ingest_batch, agent, agent_heartbeat, import_quarantine | データ・派生物は契約者のもの（DATA_SOURCE_COMPLIANCE #4/#7/#9） |
| **共有表**（データを含まない） | `tenant`、`app_user`（メール・ロール・`tenant_id`）、`code_venue`（競馬場コード→名称。事実の定数）、`schema_history`（Flyway）、設定の既定値 | JRA-VAN 由来のデータを含まない。`app_user` は deny by default の根拠 |
| **注意** | `horses` 等のマスタを「共有して節約」しない | （2026-10-23: 利用者は本人 1 名。以下は構造を維持する理由として残す）2 人が同じデータを持つ形になっても、契約者ごとに自分の取得物を保持する（案 A）。重複保存は容量で数百 MB 規模で、費用より規約適合を優先 |

## 5. 移行パイプライン
### 5.1 構成
```
[利用者PC] SQLite (読み取り専用コピー) ──extract──▶ NDJSON.gz(テーブル別) + manifest ──transform/validate──▶ staging(ローカルMySQL)
                                                              │ 隔離行: quarantine/*.ndjson + 理由
                                                              ▼
                              verify(件数・チェックサム・集計) ──promote──▶ 本番スキーマ(ローカル → 規約回答後にクラウド)
```
- ツール: **（2026-11-06 に置換: D-271）Kotlin の `:legacy-import`**（利用者の PC で実行する CLI。言語方針 D-250 で Python を使わない。まず preflight を実装し、以下の段階を順に足す）。旧案: Python の独立ツール `tools/migrate/`（リポジトリに置く。SQLite は標準ライブラリ、MySQL は公式ドライバ）。理由: 実 DB が利用者の PC にあり、Python/SQLite の扱いが容易、Kotlin 本体に移行専用コードを持ち込まない。**秘密・データ・出力ファイルは Git に入れない**。
- 実行環境: 利用者の PC（Docker で MySQL）。クラウドへ載せる段階は、同じ staging→promote をクラウド DB に向けて実行（接続は Cloud SQL Auth Proxy 等。**規約回答後**）。

### 5.2 フェーズ
| # | フェーズ | 内容 | 失敗時 |
|---|---|---|---|
| P0 | **事前検査（preflight）** | ① SQLite のコピー（稼働中は `sqlite3 .backup` または停止後のファイルコピー。WAL の `-wal/-shm` を含む）を取り sha256 を記録、`PRAGMA integrity_check`、② `PRAGMA table_info` で**スキーマ指紋**を取り `schema.sql` との差分（動的列の有無）を報告、③ テーブル別の行数・PK 重複・孤立行（FK 違反: `PRAGMA foreign_key_check`）・NULL/型の分布、④ 時刻形式の分布（`T` 有無）と JST/UTC 整合のサンプル検証、⑤ JSON 列の妥当性、⑥ `race_id` 形式（12 桁数字）、⑦ 推定サイズ（§9） | 検査報告のみ。移行しない |
| P1 | extract | 読み取り専用接続（`file:…?mode=ro`）。テーブルごとに PK 順で**チャンク**（例: 10,000 行 or 日付範囲）に分け NDJSON.gz に出力、チャンクごとに行数・PK 範囲・内容ハッシュを `manifest.json` に記録 | チャンク単位で再実行 |
| P2 | transform/validate | 型・時刻・命名の変換（§1.2, §2）、**tenant_id 付与**、`legacy_id` 保持、`INSERT OR REPLACE` 由来の重複は PK で一意（SQLite の PK が保証）、変換不能行は隔離 | 隔離行は `import_quarantine`（テナント表）と NDJSON に理由付きで保存 |
| P3 | load（staging） | staging スキーマ（`import_{run}`）へバッチ INSERT（2,000〜5,000 行/トランザクション、PK 順）。`legacy_id` の UNIQUE で冪等 | チャンク単位でロールバック→再試行 |
| P4 | verify | §6 | 不一致なら promote しない |
| P5 | promote | 依存順（`tenant` → races → horses → race_horses/horse_past_runs → odds_snapshots/results/payouts → signals → race_evaluations → ticket_evaluations → bets → bet_legs → model_metrics → ingest_cursor）に `INSERT … SELECT` で staging から本番へ。ID は新規採番し、`legacy_id` と対応表（`import_id_map`）で子の参照を張り替え | トランザクション（表単位）。失敗時は §7 |
| P6 | 事後検証 | 本番側で §6 を再実行 + ビジネス検証（§6.3） | 失敗なら §7 のロールバック |

### 5.3 再開（中断耐性）
- `import_run`（run_id, 開始時刻, 入力ファイル sha256, 状態）と `import_chunk`（run_id, table, chunk_no, key_from, key_to, status[PENDING/LOADED/VERIFIED/PROMOTED/FAILED], rows, hash, attempts, error）を staging 側に保持。再実行は **VERIFIED/PROMOTED 済みチャンクをスキップ**、FAILED/LOADED は再処理。チャンクの load は「範囲削除→挿入」を 1 トランザクションで行い**冪等**。
- ツール自体が途中で落ちても、同じ入力ファイル（sha256 一致）で同じコマンドを再実行すれば続きから進む。入力が変わった（sha256 不一致）場合は新しい run として扱い、旧 run は破棄を明示操作で。

### 5.4 重複・孤立・不正データの扱い
| ケース | 既定の扱い | 補足 |
|---|---|---|
| 同一 PK の重複（複数ソース DB の統合時） | **新しい方（`updated_at`/`created_at`）を採用**。衝突はレポート | 単一 SQLite なら発生しない |
| 子行の親が無い（FK 違反。例: `odds_snapshots` の `race_id` が `races` に無い） | **隔離（既定）**。`--stub-parents` 指定時のみ最小の `races` 行（`is_stub=1`）を作成 | 過去の `fetch_historical_data` が最小 races を作っていた運用との整合 |
| 不正 JSON | 隔離し、その列だけ NULL にして他列を移行する選択肢（`--lenient-json`）を用意（既定は行ごと隔離） | |
| 時刻判別不能 | 隔離（行ごと）。ユーザーが解釈ルールを指定して再実行 | |
| 数値の外れ値（odds ≤ 0 等） | そのまま移行（ビジネス意味は保持）+ 警告レポート | 変換で値を変えない |
| `race_id` 形式不正 | 隔離 | |

## 6. 検証（移行前後）
### 6.1 件数・チェックサム
| 検査 | 内容 |
|---|---|
| 件数 | テーブル別・**日別**（`race_date`）・テナント別に SQLite と MySQL を比較。`移行行 + 隔離行 = 元の行数` を恒等式として確認 |
| チェックサム | 行を**正規化文字列**（型変換後の値を固定フォーマットで連結、JSON は正規化）にし、`MD5(行)` を**順序非依存に合算**（例: 各行 MD5 の先頭 64bit を 128bit 加算）した値をテーブル×チャンクで比較 |
| 抜き取り | 各テーブル最大 1,000 行を乱数（シード記録）で選び**列ごとに突合**（浮動小数は同値、時刻は JST⇄UTC 変換後） |
| 参照整合 | MySQL 側で FK 検査（ロード後に `FOREIGN_KEY_CHECKS=1` で検証）、孤立 0 |
| 一意性 | 主キー/UNIQUE 違反 0 |

### 6.2 取りこぼし防止
- extract 時の行数と manifest、load 後の行数、promote 後の行数の 3 点が一致。差分は run レポートに列挙し、**未解決の差分がある run は完了にしない**。

### 6.3 ビジネス検証（旧新比較）
- 旧 `calculate_performance_*`（signals/bets/evaluations 3 系統）と新サービスの集計を**同一期間・同条件**で比較（件数、総投資、払戻、損益、的中率、回収率）。丸めは表示層のみ。差異は許容 0（浮動小数は 1e-9）。
- 払戻の桁疑義（JVLINK_DESIGN §1.4）がある場合は、**移行では値を変えず**、疑義の検証結果に基づく補正は別の変換バージョン（`transform_version`）として管理。

## 7. 失敗時の復旧・ロールバック・バックアップ
| 項目 | 設計 |
|---|---|
| バックアップ（移行前） | ① 元 SQLite を読み取り専用で**保管**（sha256 記録、ローカルの暗号化ストレージ。クラウドへは上げない、規約回答まで）。② 移行先 MySQL の論理バックアップ（`mysqldump`）を promote 前後に取得。③ クラウドでは promote 前にオンデマンドバックアップ（課金は保管量のみ: CLOUD_COSTS）。**元 SQLite は移行完了後も一定期間（推奨 6 か月以上）保持し削除しない** |
| 失敗時（P0〜P4） | 本番は未変更。staging を破棄して再実行（冪等） |
| 失敗時（P5 promote 中） | 表単位のトランザクション。完了済みの表は `import_run` に記録。**初回移行はテナントが空であることを前提**とし、ロールバック = 依存の逆順に `DELETE … WHERE tenant_id=? [AND legacy_id IS NOT NULL]`、または promote 前バックアップからの復元。移行行は `legacy_id` で識別できるため、移行後にアプリが追加した行（`legacy_id IS NULL`）を巻き込まない |
| カットオーバー前の巻き戻し | 旧システム（SQLite + Python デーモン）は**カットオーバーまで正本のまま**。新システムの結果は参照のみ（通知しない）。問題があれば新側を破棄して旧のまま継続 |
| 部分失敗の再開 | §5.3 |
| 手順の検証 | ローカル MySQL で全フェーズ + ロールバックを**リハーサル**し、所要時間・件数・差異を記録（T3 の受入条件） |

## 8. 既存アプリとの並行稼働・カットオーバー
- **JV-Link は 1 利用キー・1 端末**（JVLINK_DESIGN §2）で、旧デーモンと新エージェントが同時に取得できるかは**未確認**（COM の同時オープン・JV-LinkAgent の共有）。したがって並行稼働は次の段階制とする。
| 段階 | 旧（Windows+SQLite） | 新（クラウド or ローカル MySQL） | 通知 |
|---|---|---|---|
| S0 | 稼働（正本） | 履歴を移行（一括）。**夜間差分**（`created_at/ts` の透かし以降、冪等）を同期 | 旧のみ |
| S1 | 稼働（正本） | 新が差分データでダッシュボード/計算を**シャドー実行**（取得はしない）。旧新比較（§6.3、EV/判定の一致率） | 旧のみ |
| S2 | **停止（開催のない日に切替）** | 新エージェントが取得を担当、bot はシャドー（通知しない）で 1〜2 開催日 | なし or 旧のみ手動 |
| S3 | 停止・SQLite は読み取り専用で保管 | 新が正本。通知切替 | 新のみ |
- **カットオーバー基準**: ゴールデン全通過、移行検証 100%、シャドー期間の判定一致（許容差を事前合意）、STOPPED/NORMAL の運用手順リハーサル完了、ユーザー承認。
- **規約**: S0 以降でクラウドに実データを置くのは Q1 回答後。それまでは**ローカル MySQL でリハーサル/シャドー**を継続できる設計にしておく。

## 9. データ保持期間とストレージ費用
### 9.1 増加量の見積り（式。実測で更新）
| テーブル | 行の増え方 | 行サイズ（索引込み概算） | 年間（開催 ~100 日/年、36 R/日、頭数 16） |
|---|---|---|---|
| `odds_snapshots`（単勝） | サイクル数 × 頭数。サイクル ≈ 77/レース（far 300s: 42、mid 60s: 25、near 30s: 10）→ 約 1,200 行/レース | ~120 B | 36 × 1,200 × 100 ≈ **4.3M 行 ≈ 0.5 GB/年** |
| `combo_odds_snapshots`（ワイド 153 + 3連複 816） | 既定では**保存しない**。保存する場合 969 行 × 77 サイクル ≈ 75k 行/レース | ~130 B | **約 2.7 億行 ≈ 35 GB/年**（非現実的） |
| `signals` | 通知数（数十〜数百/日） | ~500 B | < 50 MB |
| `race_evaluations` / `ticket_evaluations` | 評価レース × 評価回 / チケット数 | 各 ~300 B | 数十〜数百 MB |
| `horse_past_runs` / `horses` / `race_horses` | 取り込み範囲に依存（JV 補てん/蓄積） | ~200 B | 数百 MB（初期取込時） |
| `results` / `payouts` / `races` | レース数 | 小 | 数 MB |
→ **初期 DB 20 GB は十分に保守的**（実際は 3 年履歴で 5〜10 GB 程度の見込み、`horses`/過去走の取込量次第）。

### 9.2 保持ポリシー（案）
| データ | 保持 | 方法 |
|---|---|---|
| races, results, payouts, signals, bets, evaluations, 成績 | 無期限 | — |
| `odds_snapshots`（単勝） | 詳細 400 日。以降は**発走 5 分前の最終スナップショット + 判定時点**のみ残し集計 | 月次の保持ジョブ（冪等、STOPPED 中は実行しない）、将来はパーティション |
| `combo_odds_snapshots` | **既定は保存しない**。有効化する場合も**判定に使った候補組のみ**・保持 14 日 | 設定 + 保持ジョブ |
| 生レコード（ingest） | 30 日（規約回答で変更）（D-019） | 保持ジョブ |
| ML データセット | 90 日（再生成可能） | 保持ジョブ |
| `bot_run`/`race_schedule_event`/エージェントのハートビート | 1 年 | 保持ジョブ |
| モデル成果物・レジストリ | 無期限（小さい） | — |
- 保持ジョブは**削除前にドライラン**（件数のみ出力）を必須とし、実データ削除は管理者の明示承認（ユーザー指示: 承認なく実データを削除しない）。
- パーティションは FK と併用できない（MySQL 制約）ため、巨大表（odds）は FK を外して `(tenant_id, race_date)` パーティションを検討（Stage 2 の実測後）。

### 9.3 費用への影響（Cloud SQL 東京 推定: CLOUD_COSTS §2.1、Iowa×1.30）
| DB サイズ | SSD $0.221/GB月 | バックアップ（同量保持）$0.104/GB月 | 月額（税抜、円 156/USD） |
|---|---|---|---|
| 5 GB | $1.1 | $0.5 | 約 ¥250 |
| 10 GB | $2.2 | $1.0 | 約 ¥500 |
| 20 GB（仮置き） | $4.4 | $2.1 | 約 ¥1,010 |
| 50 GB | $11.1 | $5.2 | 約 ¥2,540 |
| combo 全保存（+35 GB/年） | +$7.7/月/年分 | +$3.6 | **年ごとに約 +¥1,760/月**（非推奨） |
- 2 テナント（案 A）でデータが 2 倍になる場合も、5〜10 GB × 2 で月 ¥1,000 前後。**ストレージより DB インスタンス（CLOUD_COSTS の主因）が支配的**。

## 10. 並行稼働中の差分同期（S0〜S1）
- 透かし: テーブルごとの `max(ts/created_at/id)`。差分抽出は透かし以降（**重複許容、冪等**）。`INSERT OR REPLACE` 由来の更新（`races`, `results`, `payouts`, `horses` 等）は PK 一致で上書き（新しい `updated_at` のみ）。
- 削除は旧アプリが行わないため差分に出ない。

## 11. 実装タスクへの展開
| タスク | 内容 |
|---|---|
| T2.3 | V1 ベースライン（§2）+ V2（カレンダー/ingest/ml_model/app_user）、Testcontainers で 8.4/9.7 適用 |
| T2.4 | エンティティ/DAO（`tenant_id` 必須）、越境テスト、SQL の `tenant_id` 静的検査、Doma の `Instant`/JSON/UPSERT の検証 |
| T3.x | `tools/migrate/`（preflight/extract/transform/load/verify/promote、チャンク再開、隔離、レポート）、合成 SQLite（実データなし）での自動テスト |
| T6.x | 実ファイルでのリハーサル、旧新比較、カットオーバー |

## 12. 実ファイル取得後の実行手順（Runbook 案。**今回は実行しない**）
1. 旧デーモン/ダッシュボードを停止し（または開催のない日に）、`data/jraev.sqlite`（+ `-wal`/`-shm`）を**読み取り専用でコピー**、sha256 を記録。元ファイルは保管（削除しない）。
2. ローカルに MySQL（Docker, 9.7 または 8.4）を起動し、Flyway で V1〜Vn を適用（空 DB）。
3. `tools/migrate preflight --sqlite <copy>` を実行し、レポート（スキーマ差分、行数、孤立行、時刻形式、JSON 妥当性、サイズ見積り）を確認。**ユーザーが方針（孤立行の扱い、時刻の解釈、隔離行の扱い）を承認**。
4. `extract` → `transform/validate` → `load(staging)` → `verify`（§6）。隔離行を確認。
5. `promote` → 事後検証（§6.3 の旧新比較）。
6. リハーサル記録（所要時間、件数、差異）を `docs/TEST_RESULTS.md` に追記。
7. （規約回答後）クラウド DB に向けて同手順を実行。promote 前にバックアップ。
8. シャドー/カットオーバー（§8）。
**受入基準**: 隔離行 = 説明可能、件数恒等式成立、チェックサム一致、集計差 0、ロールバック手順の成功実績。

## 13. 決定案・未確認
| ID | 内容 | 状態 |
|---|---|---|
| D-038 | （**D-271 で Kotlin の `:legacy-import` に置換**）移行は Python ツール（`tools/migrate/`）。staging→promote の段階制、チャンク再開、隔離（quarantine）。まずローカル MySQL でリハーサル、クラウドは規約回答後 | 案 |
| D-039 | テナント方式 A（共有スキーマ + `tenant_id` 先頭 + 複合 FK + DAO 必須引数 + CI の SQL 検査 + 越境テスト）。共有表は `tenant/app_user/code_venue` のみ | 案 |
| D-040 | レース識別は `race_id CHAR(12)`（+ `kai`/`nichime` NULL 可）に統一し、RACE_CALENDAR_DESIGN の `race_key CHAR(16)` を修正。`rt_key`(16桁) は導出 | 案 |
| D-041 | 時刻は UTC `DATETIME(6)`。移行時は形式（`T` 有無）で JST/UTC を判別して変換、実ファイルで JST 仮定を検証 | 案 |
| D-042 | 旧 ID は `legacy_id` に保持し新 ID を採番（UNIQUE (tenant_id, legacy_id) で冪等・ロールバック識別） | 案 |
| D-043 | 保持ポリシー（§9.2）。combo オッズは既定で保存しない。保持ジョブはドライラン + 管理者承認 | 案 |
| 未確認 | 実 DB の規模・時刻基準・孤立行、Doma の `Instant`/JSON/UPSERT 対応、JV-Link の旧新同時取得可否、MySQL 9.7 での DDL 挙動 | T1.20 / T2.4 / T3 |
