# 包括バグ監査（2026-10-27）の修正と、DB 変更を伴う恒久設計（承認待ち）

対象: `claude/stage3-bot-orchestration`（T3.8 を安全にマージできる品質にする）。**T3.9 には進んでいない。CI は未実行**（T3.8 を CI 検証済みとして扱わない）。
判断の記録は DECISIONS D-208〜D-217、未解決は UNRESOLVED（C-35〜C-40、D-17〜D-19、F-9、G-28〜G-30）。

## 0. 状態の一覧

| ID | 内容 | 状態 | 備考 |
|---|---|---|---|
| C1/C2 | 予定を信頼できず評価を逃したのに SUCCEEDED | **修正済み** | §1 |
| E | checkpoint 欠損・破損で同じタイミングを新オッズで再通知 | **暫定対策を実装**。恒久設計は §3（承認待ち） | 既存の部品だけ・DB 変更なし |
| B | 保存済み Signal と通知内容の不一致 | **暫定対策を実装**（不一致なら抑止）。恒久設計は §3 | 通知内容のスナップショットは DB 変更が必要 |
| D | `race_evaluations` の重複 | **修正済み（運用の設定で保証）**。UNIQUE は §3（承認待ち） | RR 固定 + デッドロック再試行 |
| C | エージェントが待機後に再確認せず送信 | **修正済み** | |
| F | Discord 429 待機後に STOPPED/UNKNOWN を見ない | **修正済み** | |
| A | `signal_sent` の意味 | **整理済み（意味は変えない）** | §5.1 |
| G1 | 実オッズが必要な買い目 | **現状が安全。保存設定は変えない**（試験を追加） | §5.2 |
| G2 | 保留（PENDING）レコードの再処理 | **修正済み（DB 変更なし）** | §4.1 |
| G3 | 天候・馬場など races 行の時点整合 | **修正済み（フラグ + 学習対象外）**。履歴の復元は**未解決** | §4.2 |
| G5 | Flyway と GRANT | **問題なし**（検査あり）。V5 を作るときの手順を §3 に | §5.3 |
| G6 | 更新 zip の展開 | **修正済み** | §5.4 |
| G7 | リクエストサイズ | **修正不要**（既に安全） | §5.5 |
| G8 | 最大稼働と Job タイムアウト | **契約に余白を明記**（実際の設定は T5.0） | §5.6 |
| G9 | 古い Dockerfile コメント | **修正済み** | |
| G10 | エラー通知の抑制 | **修正済み**（件数を数える） | §5.7 |
| I1 | web と infra の契約の相違 | **一覧化**（infra の変更は T5.0） | §6 |

## 1. C1/C2: 全レース発走後の終了の分類

**問題（再現済み）**: 実行中に開催日の確認が古くなる（`stale_after` 超過）と、`RaceMonitorPlanner` はそのレースを `UNTRUSTED` にして評価も通知もしないが（これは正しい）、発走時刻が過ぎると全レース `OFF` → `SUCCEEDED / ALL_RACES_OFF` で終わっていた。評価 0 件・通知 0 件・exit 0 で、運用者に何も伝わらない。

**修正**: `CompletionAssessment`（純粋関数）が、全レースが OFF または中止になった時点で、直前のサイクルの段階と発火済みのタイミングからレースごとに分類する。

| 分類 | 条件 | 扱い |
|---|---|---|
| EVALUATED | 設定した全タイミングを発火済み（**通知対象が 0 件でも**） | 正常 |
| CANCELLED | 中止 | 正常 |
| NOT_OBSERVED | この run が一度も監視していない（最初に見たときは既に発走後。遅い起動） | 正常（run の異常ではない） |
| LOST_CALENDAR | 直前の段階が `UNTRUSTED` / `UNKNOWN_TIME` のまま発走を過ぎ、必要なタイミングを逃した | **FAILED `EVALUATION_OPPORTUNITY_LOST`、exit 1、運用者へ警告** |
| LOST_DATA | 監視はしていたが、オッズ無し・古いなどで一部/全部のタイミングを発火できなかった | SUCCEEDED **`ALL_RACES_OFF_WITH_GAPS`**、exit 0、警告 |
| QUARANTINED | checkpoint を信頼できず通知を止めた（§2） | SUCCEEDED `ALL_RACES_OFF_WITH_GAPS`、警告 |

- 評価 0 件という事実だけでは異常にしない（開催なし・中止・全レースが通知対象なしは従来どおり正常）。
- 既存の `BotRunState`（STARTING/RUNNING/DRAINING/SUCCEEDED/FAILED/ABORTED/STOPPED_BY_MODE/INTERRUPTED）と終了コード（0/1/75）を変えない。新しい理由名を足しただけ（CONTRACTS 0.4.14）。
- FAILED を exit 1（75 ではない）にしたのは、発走後に再実行しても評価できない（再試行しても直らない）ため。
- 試験: `BotRunIntegrityTest`（stale で評価を逃す／一部だけ逃す／評価 0 件は正常／中止は正常／オッズが来ない→警告つき成功）。

**ユーザー判断が残る点**: LOST_DATA を SUCCEEDED（警告）にするか FAILED にするか。現状は『監視はしたがデータが来なかった』を警告つき成功とした。FAILED にしたければ `completion()` の 1 行（D-209）。

## 2. E / B: 暫定対策（DB 変更なし。実装済み）

### E: checkpoint 欠損・破損
- **初回起動の正常な空**と**異常**を区別する。前の run が無い／checkpoint が null の run だけ → 正常（通知の前に必ず checkpoint を保存するので、null の run は何も通知していない）。
- 異常 = DB に『通知を試みた Signal』（UNSENT 以外）があるのに、復元した checkpoint にそのレースの発火記録が無い、または最新の checkpoint が壊れている。そのレースは **この run では通知を止める**（`quarantined`）。他のレースは続ける。`CHECKPOINT_UNRELIABLE` を運用者へ通知し、終了時は `ALL_RACES_OFF_WITH_GAPS`。
- 限界（恒久設計が必要な理由）: どのタイミングを通知済みか分からないので、そのレースの**以後のタイミングも**止める（取りこぼしを選ぶ: CONTRACTS §9.2）。checkpoint が『古いが読める』場合（保存後に DB が巻き戻った等）は検出できない。

### B: 保存済み Signal と通知の不一致
- 既存の行（同じ自然キー）と今回の計算結果が一致しなければ、**claim も通知もしない**（最新の計算結果で勝手に通知しない）。DB の行は書き換えない。`SIGNAL_MISMATCH` を運用者へ通知。
- 限界: 一致を比べられるのは `signals` 表の列（オッズ・確率・EV・stake・probSource）だけ。通知にはワイド・三連複の買い目（`BetStrategyBundle`）、理由文、カバレッジなどが含まれ、これらは DB に無い。**完全な一致の保証には通知内容の保存が要る**（§3）。

## 3. 恒久設計（**承認前は Flyway V5 を追加しない・DB を変更しない**）

### 3.1 問題の整理（評価結果の一意性と通知タイミングの一意性を分ける）
- `race_evaluations` の自然キー `(tenant_id, race_id, snapshot_at)` は **オッズの観測時刻**。新しいオッズなら `snapshot_at` が変わるので、これを UNIQUE にしても『同じ評価タイミングの二重通知』は防げない。
- 通知の単位は **評価タイミング**（発走の何分前か。例 30 分前・5 分前）。そこで次の 2 つの一意性を別の表で持つ。

| 一意性 | キー | 守るもの |
|---|---|---|
| 評価結果 | `race_evaluations (tenant_id, race_id, snapshot_at)`（新方式の行だけ） | 同じオッズの観測を再評価しても行が増えない（冪等） |
| 通知タイミング | `race_timing_notice (tenant_id, race_id, timing_centi)` PK | 同じレース・同じタイミングを 2 回通知しない（checkpoint に依存しない） |

### 3.2 追加するもの（案。`db-migration/.../V5__audit_idempotency.sql`）

```sql
-- (1) 評価結果の一意性。旧データ(legacy_id あり)は対象外（重複を含み得る: D-066）。新方式の行(legacy_id IS NULL)だけ UNIQUE
ALTER TABLE race_evaluations
  ADD COLUMN uq_native TINYINT AS (IF(legacy_id IS NULL, 1, NULL)) VIRTUAL,
  ADD UNIQUE KEY uq_race_evaluations_native (tenant_id, race_id, snapshot_at, uq_native);

-- (2) 通知タイミングの一意性 + 通知内容のスナップショット（INSERT のみ。UPDATE・DELETE しない）
CREATE TABLE race_timing_notice (
  tenant_id        BIGINT NOT NULL,
  race_id          CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  timing_centi     INT UNSIGNED NOT NULL,            -- 発走の何分前 × 100（30 分 = 3000、5 分 = 500）。浮動小数の比較を避ける
  run_id           CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  evaluation_id    BIGINT NOT NULL,                  -- 評価結果（race_evaluations.id）
  odds_observed_at DATETIME(6) NOT NULL,             -- 評価に使った単勝オッズの観測時刻
  notice_schema    SMALLINT UNSIGNED NOT NULL,       -- notice_json の版
  notice_json      JSON NOT NULL,                    -- 通知する内容そのもの（SignalView の列・買い目・理由文・signal_id）
  notice_sha256    CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  fired_at         DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id, race_id, timing_centi),
  KEY ix_race_timing_notice_eval (tenant_id, evaluation_id),
  CONSTRAINT fk_race_timing_notice_race FOREIGN KEY (tenant_id, race_id) REFERENCES races (tenant_id, race_id),
  CONSTRAINT fk_race_timing_notice_eval FOREIGN KEY (tenant_id, evaluation_id) REFERENCES race_evaluations (tenant_id, id),
  CONSTRAINT ck_race_timing_notice_sha CHECK (notice_sha256 REGEXP '^[0-9a-f]{64}$')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
```

**検証済み（使い捨てのスクラッチ DB、MySQL 8.0.46。リポジトリのスキーマは変更していない）**: 旧データの重複（legacy_id あり 2 行 + 同じキーの新方式 1 行）がある表に (1) の ALTER が成功する／新方式の行は `INSERT … ON DUPLICATE KEY UPDATE tenant_id = tenant_id` で 1 回目 1 行・2 回目 0 行（`useAffectedRows=true` と整合）／(2) の PK が同じ (race, timing) の 2 回目を拒否する。8.4/9.7 は CI で確認する（未実施）。

### 3.3 冪等キーの定義
- 評価結果: `(tenant_id, race_id, snapshot_at)`（新方式の行）。挿入は `INSERT … ON DUPLICATE KEY UPDATE tenant_id = tenant_id`（今の `WHERE NOT EXISTS` を置き換える。デッドロックも不要になる。分離レベルの固定と再試行は残す）。
- 通知タイミング: `(tenant_id, race_id, timing_centi)`。`timing_centi = round(minutes × 100)`（設定のタイミング `30,5` → 3000, 500。強化モードでもキーは設定値）。
- Signal: 従来どおり `(tenant, race, market, umaban, observed_at)` と `notify_state`（UNSENT→CLAIMED→SENT|SEND_UNKNOWN）。

### 3.4 処理の順序（コミットの位置は今と同じ）
1. 評価（純粋）→ `race_evaluations` へ冪等に追記 → `signals` へ冪等に追記（UNSENT。既存の行と違えば抑止: §2）。
2. 通知内容を組み立て、**`race_timing_notice` へ INSERT（= 発火の確定。今の『checkpoint を保存してから通知』の位置）**。同じ (race, timing) が既にあれば発火済み = 何もしない。複数のタイミングが同時に満たされた場合（遅い起動など）は 1 トランザクションで該当するキーをすべて INSERT し、**新しく取れたキーだけ**を発火とする。
3. 通知は**保存したスナップショットから**作る（再計算しない）。Signal ごとの claim → 送信 → SENT/SEND_UNKNOWN は今のまま。
4. 再起動時: `race_timing_notice` から発火済みのタイミングを読んで checkpoint の `fired` と合わせる（DB が正。checkpoint は進行のヒントに格下げ）。§2 の暫定の隔離は『表が無い旧版 DB』への備えとして残す。

### 3.5 既存データへの影響・移行
- `race_evaluations`: 列と索引の追加だけ。**行は変更・削除しない**。旧データ（`legacy_id` あり）は UNIQUE の対象外（NULL は重複とみなされない）。移行データの重複はそのまま残る。
- 新方式の行に重複がある開発用 DB では ALTER が失敗する（ER_DUP_ENTRY。実行は 1 文で原子的）。事前確認のクエリ: `SELECT tenant_id, race_id, snapshot_at, COUNT(*) FROM race_evaluations WHERE legacy_id IS NULL GROUP BY 1,2,3 HAVING COUNT(*) > 1;`。本番はまだ T3.8 を動かしていないので 0 件のはず。あれば先頭の id を残して手作業で整理する（自動削除しない）。
- `race_timing_notice`: 空で作る。過去の通知は復元しない（**架空の履歴を作らない**）。デプロイ時に既に進行中の日は checkpoint が引き続き守る。
- `signals`: 変更なし（`notify_state` が通知状態の正）。`race_evaluations.signal_sent` の意味も変えない（§5.1）。

### 3.6 最小権限 GRANT の変更（`R__grants.sql`。承認が必要）
| ユーザー | `race_timing_notice` | 理由 |
|---|---|---|
| bot | SELECT, INSERT | 発火の確定とスナップショットの保存。**UPDATE・DELETE なし**（不変） |
| app | SELECT | ダッシュボードの表示（任意。不要なら付けない） |
| ingest / mltrain | なし | |
`race_evaluations` の GRANT は変更なし。`GrantsMatrixTest` が表の一覧と GRANT の食い違いを検出する（新しい表を足すと、リセットと付与の両方が要る）。

### 3.7 旧 Python 互換への影響
旧 Python に対応する仕組みは無い（旧は SQLite の `result_fetched` や日付で重複を抑えていた）。判定の数値・通知の本文の書式・`race_evaluations` の既存列の意味は変えない。ゴールデン（31 ケース/988 呼び出し）は判定の数値だけを比べるので影響しない。

### 3.8 回帰テスト（承認後に追加）
`race_evaluations`: ①RR/RC 両方で同時挿入が 1 行（デッドロックなし） ②旧データの重複があっても ALTER が通り、旧行は変わらない ③新方式の行だけ UNIQUE ④`ON DUPLICATE KEY` の戻り件数。`race_timing_notice`: ⑤同じ (race, timing) の 2 回目は拒否 ⑥checkpoint を壊す/消す/古いものに戻しても、再起動後に同じタイミングを再通知しない（**新オッズでも**） ⑦通知文が保存したスナップショットと一致（ワイド・三連複の買い目と理由文を含む） ⑧テナント越境（別テナントの同じ race_id/timing は別） ⑨GRANT（bot は INSERT/SELECT のみ、UPDATE/DELETE が拒否される） ⑩複数タイミング同時発火 ⑪スナップショット保存の途中で落ちても通知されない（at-most-once） ⑫8.4/9.7（CI）。

### 3.9 ロールバック方針
V5 は追加のみ（列・索引・表）。戻すときは**前のイメージに戻す**（旧コードは新しい表・列を使わない。旧コードの `WHERE NOT EXISTS` の挿入も新しい UNIQUE と矛盾しない）。DB 側は手動で `DROP TABLE race_timing_notice; ALTER TABLE race_evaluations DROP INDEX uq_race_evaluations_native, DROP COLUMN uq_native;`（Flyway の undo は使わない）。表を落とすとスナップショットと発火の記録が失われ、以後は checkpoint と §2 の暫定対策だけで守る。

### 3.10 判断が必要な点（承認前に確定しない）
1. **V5（上の表・列・索引・GRANT）を承認するか**（UNRESOLVED D-17）。
2. **通知内容の保存形式**: ①構造化 JSON（推奨。レンダラの変更が保存済みの事実を変えない。`SignalView`/買い目の直列化を実装する）／②送信する本文のテキストそのもの（実装は小さいが、本文の書式変更と区別できない）。
3. **発火の確定後・送信前に落ちた場合**: ①取りこぼす（現行の契約 §9.2。推奨の初期値）／②保存したスナップショットで再開する（Signal の claim は 1 件ずつなので二重にならない。契約 §9.2 の方針変更）。
4. `app` への SELECT 付与（ダッシュボードで通知内容を表示するか）。

## 4. G2 / G3（フェーズ C）

### 4.1 G2: 保留（PENDING）レコードの再処理
- **確認した問題**: レース未登録で保留にした生レコード（`ingest_raw.parse_status='PENDING'`、理由 `RACE_NOT_FOUND`）を再適用する仕組みが無かった。非 YSCH の取得は PENDING があっても SUCCEEDED でカーソルが進むため、エージェントは再送しない。さらに再送されても `insertIfAbsent` が重複と判定して終わり、PENDING のままだった。
- **修正（DB 変更なし。`ingest` は `ingest_raw` の UPDATE 権限を持つ）**: ①同じバッチ内は『レースを定義する観測（開催日・レース・発走時刻の変更）→ 依存する観測（オッズ）』の順に適用（生レコードの保存は元の並び） ②新しいレースが反映されたら、そのテナントの `RACE_NOT_FOUND` の保留を古い順に最大 200 件、**取り込んだバッチの種別・観測時刻のまま**再解析・再適用（1 件ずつ別のトランザクション。失敗は保留のまま次の機会）。成功は PARSED、隔離は FAILED へ ③再送された保留が適用できたら PENDING を更新。
- テナント越境: 一覧と更新は `tenant_id` 必須。別テナントのレース到着で他テナントの保留は動かない（試験あり）。
- **残る限界**: 生レコードの保持期間（30 日案）を過ぎた保留は再適用できない。結果・払戻の依存（T3.9）も同じ仕組みの理由コードを足せばよいが、今は対象外（オッズだけ）。実データ形式のパーサが無い `Unsupported` の PENDING は別（パーサ追加後の再解析: G-5）。

### 4.2 G3: 学習・推論・バックテストの時点整合
- **確認した問題**: `FeatureSource.raceConditions` は `races` の**現在の行**を返す。そのうち `tenko_cd`（天候）・`shiba_baba_cd`/`dirt_baba_cd`（馬場）は時間で変わる値で、変更の履歴が無い。過去のレースの学習・バックテストでは、結果確定後の値（asOf より後）が特徴量に入り得る（距離・コース種別・コース区分は変わらないとみなす）。発走時刻も現在値（変更されたレースでは asOf の基準がずれ得るが、asOf は発走時刻より前に決まるので漏れる向きではない）。馬マスタ・過去走・オッズは既に時点整合（`horseAsOf`・`run_date < レース日`・`ts <= asOf`）。
- **修正**: `RaceConditions.updatedAt`（`races.updated_at`）を追加。**asOf より後に更新された行は、天候・馬場を使わず空（0）にし、`RACE_CONDITIONS_NEWER_THAN_AS_OF` を付ける**（推測しない）。`export-dataset` はその行を**学習対象から外す**（空にした値で学習すると、値のある本番とずれるため）。`manifest` の `skipped.conditions_newer_than_as_of` に件数を出す（0 件なら従来とバイト列が同じ）。ライブの推論（asOf = 現在）は `updated_at <= 現在` なので変わらない。旧互換の `LegacyFeatureBuilder` と旧数値は変えていない。
- **未解決（架空の履歴を作らない）**: 天候・馬場の履歴が無いので、**過去のレースの asOf 時点の値は復元できない**。現状は WE 等のパーサ自体が無く（T3.6 以降）、新しいデータでも `updated_at` が asOf より後になるレースが多いと、学習に使える行が少なくなる。恒久対応は『天候・馬場の観測履歴表（観測時刻つき）+ パーサ』で、**DB 変更の承認が必要**（UNRESOLVED G-28）。実データでの件数は T1.20/規約確認の後に評価する。
- バックテスト（T3.9）は同じ `PointInTimeFeatureBuilder` を使う前提。T3.9 で『履歴が無い行は除外し、除外件数を報告する』ことを要件に入れる。

## 5. その他

### 5.1 A: `race_evaluations.signal_sent`
旧 `signal_sent` は『この評価の判定が notify（買い目あり）か skip か』で、旧 UI の「通知のみ（signal_sent=1）」フィルタ・`decision=notify/skip` に使われていた（Discord の配送結果ではない）。移行データも同じ意味。**意味は変えない**。`BotRunRepositories` にコメントを足した。実際の通知状態の正は `signals.notify_state`。ダッシュボードで『通知済み』を出すときは `notify_state` を使うこと。

### 5.2 G1: 実オッズが必要な買い目
`requireRealOddsForCombo` は旧互換の既定で true。組み合わせオッズが無い・古いときは推定オッズに置き換えず、見送り（『実オッズ未取得のため見送り』）にする。`INGEST_STORE_COMBO_ODDS` の既定は false のまま（**勝手に ON にしない**。D-8 はユーザー判断）。結果として、保存を ON にするまでワイド・三連複は通知されない（見送りの扱いは `notify_skips` の設定どおり）。試験: `BotRunIntegrityTest`（ワイド・三連複の Signal が作られない）。

### 5.3 G5: Flyway と GRANT
`R__grants.sql` は『リセット → 付与』の Repeatable Migration で、内容を変えると次の migrate で再適用される。`GrantsMatrixTest` が V1〜V4 の全 36 表と GRANT の食い違いを検出する。bot の T3.8 に必要な権限（`race_evaluations`・`signals`・`bot_run` の SELECT/INSERT/UPDATE）は付与済みで、**今回の修正で GRANT の変更はない**。V5 を作るときは、versioned の後に repeatable が実行される順序（Flyway の既定）に依存して、新しい表の GRANT を `R__grants.sql` に足す。足し忘れは `GrantsMatrixTest` が失敗する。

### 5.4 G6: 更新 zip
署名・ハッシュ検証済みの成果物でも、展開前に宣言サイズで検査する: 件数 ≤ 5000、展開後の合計 ≤ 1 GiB、1 ファイル ≤ 512 MiB、圧縮率 ≤ 200、Windows 形式のパス（`..\`・ドライブ指定・絶対パス）、リンク、特殊ファイル、重複（大文字小文字違いを含む）を拒否。失敗時は何も展開せず残骸を残さない。

### 5.5 G7: リクエストサイズ
修正不要。`AgentAuthFilter` は `Content-Length` が上限（既定 1 MiB、`INGEST_MAX_BODY_BYTES` 1 KiB〜8 MiB）を超えたら 413、チャンク転送でも `readNBytes(max+1)` で読む量を制限してから認証する。gzip の展開にも上限（`maxInflatedBytes`）がある。試験あり（`AgentAuthIntegrationTest`）。

### 5.6 G8: 最大稼働とジョブタイムアウト
`BOT_RUN_MAX_RUNTIME_MINUTES` の上限は 840 分（14 時間）で、Job のタイムアウトと同じ値にすると、アプリの自己停止（ABORTED/DRAINING の記録）の前にプラットフォームに強制終了される。**契約に『Job の最大実行時間 ≥ 最大稼働 + 15 分以上（猶予・DRAINING・checkpoint の保存）』を明記**した（CONTRACTS 0.4.14）。実際の Job 定義は T5.0。

### 5.7 G10: エラー通知の抑制と観測
`ErrorReporter` は同じ内容を 5 分に 1 回に畳む（レースごとの違いは本文の詳細にあり、キーは件名 + 本文の先頭 120 文字）。**畳んだ件数を数え**（`suppressedTotal`）、次に送るときに本文へ『同じ内容 N 件を cooldown のため送っていません』と書く。bot-run は畳まれたとき `ERROR_REPORT_SUPPRESSED` をログに残す（件数を失わない）。障害件数の観測は構造化ログのイベント数で行う（監視の設定は T5.x）。

## 6. I1: web と infra の契約の相違（infra の変更は T5.0。今回は変更しない）
| 項目 | web（正本） | infra の記載 |
|---|---|---|
| contract_version | 0.4.14 | 0.3.0（`docs/CONTRACTS.md`） |
| UNKNOWN の終了コード | 75（再実行されるべき）。確定した非 NORMAL は 0 | OPERATIONS_DESIGN: 『NORMAL 以外・UNKNOWN なら終了（exit 0）』 |
| Job のタスク名 | `bot-run` / `calendar-check` / `reconcile` / `retention` / `export-dataset` | ARCHITECTURE: `--task=run\|calendar-check\|reconcile\|retention\|export-dataset\|backfill` |
| 利用者数 | 本人 1 名（要件変更 2026-10-23） | ARCHITECTURE: 『ブラウザ(許可2名)』『IAP 許可 2 名』 |
| bot-run の環境変数 | `BOT_RUN_*`（既定なし。未設定は exit 2）、`CALENDAR_*`、ML 有効時は `MODEL_STORE_URI`・`MODEL_VERIFY_PUBKEY` | 未記載 |
| Job のタイムアウト | ≥ 最大稼働 + 15 分（契約 0.4.14） | 未記載 |
| reconcile / retention | **未実装のスタブ（本番では exit 1 `TASK_NOT_IMPLEMENTED`）** | Scheduler で定期実行する設計 → T5.0 では有効にしない |
| GRANT | V3/V4 の追加、`app` の `race_schedule_event` INSERT（D-9 未承認）、V5（承認後） | 未同期（G-9 の I-H1〜I-H9 に含む） |
