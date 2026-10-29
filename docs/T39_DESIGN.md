# T3.9（Stage 3-I）詳細設計: 結果・払戻の取込、決済、reconcile、成績集計、バックテスト

状態: **設計のみ（本体は未着手）**。2026-10-29 作成。前提: T3.8 と Flyway V5 は実装済み（CI 未検証）。T3.9 の着手は、T3.8 のレビュー・CI・マージの後にユーザーが指示する。
本書の **V6（DB 変更）は提案**であり、承認前に Flyway に入れない。旧 Python との数値は変えない（払戻の式・丸め・的中判定は旧実装どおり）。未承認の改善案（v2・adaptive_relaxation・閾値の変更）は有効化しない。
合成データだけで進められる範囲を明示し、実データ形式（JV-Data の HR・SE ほか）のパーサは T1.17 の確認後（G-5/G-16）とする。

## 0. 先に決めておくこと（ユーザー判断が要る点）
| ID | 論点 | 推奨 |
|---|---|---|
| Q1 | V6（決済・集計・バックテスト用の一意性と表）の承認 | §9 の最小案（既存の列を使い、UNIQUE を足す。新表は `backtest_run` / `backtest_item` の 2 つ） |
| Q2 | 決済の代表評価の選び方 | 旧は『発走前の最新 1 件』。新方式は **各 `race_timing_notice` が指す評価結果を、タイミングごとに決済**し、旧互換の代表（発走前の最新）を別に印付けする。数値は旧の代表と一致させる |
| Q3 | 紙の賭け（`bets`・`bet_legs`）の作成 | 旧は Signal から紙の賭けを作っていた可能性（MIGRATION_MATRIX §12）。T3.8 は Signal のみ。**T1.20 の旧データ確認後に決める**（それまでは `bets` を作らず、`signals` と `ticket_evaluations` から決済する） |
| Q4 | 天候・馬場の履歴（G-28） | バックテストでは履歴が無い行を除外し、件数を報告する（既定）。履歴表の追加は別の承認 |
| Q5 | `trigger_type=RECONCILE` の通知 | 通知しない（既存どおり） |

## 1. 現状の接続点（作り直さない既存部品）
| 領域 | 既存クラス・表 | T3.9 での使い方 |
|---|---|---|
| 取込 | `BatchIngestor`（claim → 解析 → 適用 → 保留の再適用）、`ObservationApplier`、`Observations`（`WinOddsObservation` ほか）、`ParseStatus.PENDING`、`REASON_RACE_NOT_FOUND` | `ResultObservation`・`PayoutObservation` を追加。依存（レース・出走馬）待ちは同じ保留の仕組みで再適用 |
| 状態 | `RaceScheduleTransitions`（OFF→RESULT_PENDING→RESULT_PARTIAL→SETTLED→FINALIZED）、`RaceDayTransitions`（RUNNING→SETTLING→COMPLETED/INCOMPLETE）、`RaceScheduleRepository`、`RaceDayRepository`、`ScheduleEventRepository`（履歴） | 状態の更新は必ず遷移表を通す。履歴は同一トランザクションで追記 |
| bot-run | `BotRunOrchestrator`・`RaceMonitorPlanner`・`RaceStage`（OFF の先が無い）・`CompletionAssessment`・`RunCheckpoint` v1 | 段階に `RESULT_PENDING`/`RESULT_PARTIAL`/`SETTLED` を追加（checkpoint v2。v1 を読める） |
| 通知タイミング | `race_timing_notice`（評価結果 ID・観測時刻・スナップショット）、`race_evaluations`（新方式は UNIQUE） | 決済の入力。『どの評価をどの結果で決済したか』を `evaluation_id` で辿る |
| 表（V1） | `results`(PK tenant,race,umaban)、`payouts`(PK tenant,race,market,combo_key)、`bets`/`bet_legs`、`ticket_evaluations`、`model_metrics`、`race_evaluations.settled_at/hit/payout_used/is_provisional/settle_status` | 既存の列に書く。足りないのは一意性（V6） |
| 検証 | `tools/golden`（旧 Python 実行器、`catalog.py` の KOTLIN_PORT、`kotlin_compare`）、`:golden-runner` | 決済・集計の関数を GD11 として追加（旧 `settle_*` を実行して期待値を生成） |
| GRANT | bot は `race_schedule`/`race_day`/`race_evaluations`/`bets`/`bet_legs`/`ticket_evaluations`/`model_metrics` に SELECT/INSERT/UPDATE、`results`/`payouts` は **SELECT のみ**、ingest は `results`/`payouts` に SELECT/INSERT/UPDATE | 結果・払戻の書込みは ingest（取込）。決済は bot。V6 で GRANT を足さずに済む設計にする |

## 2. 全体の流れと責務の分離
```
[Windows エージェント] → ingest: Result/PayoutObservation → results / payouts（is_provisional）
bot-run（発走後）: OFF → RESULT_PENDING（発走 +30 分から 5 分毎に results/payouts を確認）
   → RESULT_PARTIAL（一部の券種だけ確定） → SETTLED（決済を書く） → FINALIZED（確定成績: 月曜以降）
   → 全レース SETTLED で race_day: RUNNING → SETTLING → COMPLETED（settle_deadline までに未了なら INCOMPLETE）
reconcile（--task=reconcile）: INCOMPLETE の回復・確定成績との照合・暫定払戻の置換
成績集計: 決済済みの評価から model_metrics / 日次・券種別の集計（読取り専用の計算）
バックテスト: 保存済みの観測（odds_snapshots・results）から、過去の時点で判定を再計算して比較（通知しない）
```
- **取込（ingest）は結果・払戻の事実だけを書く。決済（的中判定・払戻額の計算）は bot が `results`/`payouts` を読んで行う。** ingest が決済を知らない（責務分離。権限もこの分担）。
- 決済は**何度実行しても同じ結果**（冪等）。結果が暫定（`is_provisional`）から確定に変わったら、置き換えて `settle_status` を更新する（履歴は残す）。

## 3. T3.9-a 結果・払戻の取込
### 3.1 追加するクラス
| クラス（パッケージ） | 内容 |
|---|---|
| `ResultObservation`（`ingest`） | `raceId`・`entries: List<ResultEntry(umaban, finish?, winPayout?)>`・`status`（暫定/確定）。着順 0・取消・除外・失格を区別する値（旧の finish の扱いは MIGRATION_MATRIX） |
| `PayoutObservation` | `raceId`・`market`・`rows: List<PayoutRow(comboKey, payout)>`・`isProvisional` |
| `ResultStore`・`PayoutStore`（ports） | `upsert`（自然キーで冪等。**確定を暫定で上書きしない**）、`listByRace`。実装は `:persistence`（`ResultDao`・`PayoutDao`。ingest ユーザー） |
| `ObservationApplier` の拡張 | `applyResult`/`applyPayout`: レースが無ければ `Deferred(RACE_NOT_FOUND)`（G2 の再適用が拾う）。出走馬が無い馬番は `Quarantined("UMABAN_NOT_ENTERED")`。払戻が負・非有限・0 は `Quarantined`。確定済みの行への暫定は `NoChange` |
| 合成パーサ | 試験用の正規化レコード（`NRS`/`NHR` 等。既存の `NOW`/`NRA` と同じ流儀）。実データ形式は G-5/G-16 |
### 3.2 一意性・冪等性
- `results (tenant, race, umaban)`・`payouts (tenant, race, market, combo_key)` は既に PK。`INSERT … ON DUPLICATE KEY UPDATE` は **ingest が UPDATE を持つので可**（bot には使わない）。
- 暫定→確定の更新は `WHERE is_provisional = 1 OR 値が同じ` のときだけ（確定を暫定で戻さない）。確定の値が前と違えば `ScheduleEvent` 相当の履歴に残す案は §9（`result_revision` は不要か検討）。
- 同じレースの結果が複数バッチに分かれて届いても、揃ったかの判定は bot 側（§4）。
### 3.3 テスト
①正常（全着順 + 単勝・ワイド・三連複の払戻）②暫定→確定（上書き・逆順は無視）③同着（1 着が複数。払戻が複数行）④取消・除外・失格（finish なし）⑤払戻の不正値（負・NaN・0）の隔離 ⑥レース未登録は保留 → レース到着後に再適用 ⑦同じバッチの再送・内容違いの再利用 ⑧テナント境界 ⑨最小権限（ingest は書ける、bot は書けない）⑩順序の逆転（払戻が結果より先）。

## 4. T3.9-b bot-run の結果待ちと状態遷移
### 4.1 変更点
- `RaceStage` に `RESULT_PENDING`・`RESULT_PARTIAL`・`SETTLED`・`FINALIZED` を追加し、`RunCheckpoint.VERSION = 2`（v1 を読んで `OFF` は `RESULT_PENDING` 候補に写す。未知の版は null = 従来どおり安全側）。
- `RaceMonitorPlanner.plan`: `OFF` の次を『発走 + `resultAfter`（30 分）以降』に `RESULT_PENDING`。`allDone` の意味を『全レースが SETTLED 以降または中止』に変え、**`CompletionAssessment` は評価（発走前）と決済（発走後）を分けて判定**する。
- `BotRunOrchestrator.cycle`: 結果待ちのレースに `ResultSettler.settle(race)` を呼ぶ（発走 + 30 分から 5 分毎。`tickFor` に結果確認の間隔を追加）。`settleDeadline` を過ぎても未決済があれば `race_day` を INCOMPLETE、run は FAILED `INCOMPLETE_AT_DEADLINE`（既存の語彙）。
- `race_schedule.status` の更新（現状 T3.8 は更新していない）: CARD→MONITORING（監視開始時）→OFF（発走）→RESULT_PENDING→…。**`RaceScheduleTransitions.canTransition` を通さない更新は書かない**。`race_day` は RUNNING（最初の監視）→SETTLING（全レース OFF）→COMPLETED（全レース SETTLED/FINALIZED/CANCELLED）または INCOMPLETE。履歴（`race_schedule_event`）は同一トランザクション。
- **再起動ループの停止（G-25 の解消）**: `race_day=COMPLETED` で calendar-check は `ALREADY_COMPLETED` になり bot-run を起動しない（既存の CalendarCheckPolicy が働く）。FAILED（評価機会の喪失）の日は D-221 の `EVALUATION_LOSS_ALREADY_RECORDED` で止まる。
### 4.2 クラス
`ResultSettler`（`botrun`）、`SettlementClock`（結果待ちの間隔・締切。`BotRunConfig` に `resultAfter`・`resultPoll` を追加。**既定値なし**＝環境変数 `BOT_RUN_RESULT_AFTER_MINUTES`/`BOT_RUN_RESULT_POLL_SECONDS`、契約 0.4.x で追加）、`RaceStatusUpdater`（遷移表 + 履歴の追記を 1 つにする。`ScheduleUpdates` と同じ流儀）。
### 4.3 テスト
状態遷移の全辺（許可・禁止）、結果が一部だけ届く（RESULT_PARTIAL）、締切までに届かない（INCOMPLETE）、再起動後の復元（checkpoint v1/v2）、並行する 2 つの run（リース）、SIGTERM、STOPPED/UNKNOWN、BACKFILL（状態を進めるが通知しない）、テナント境界。

## 5. T3.9-c 決済（settle）
### 5.1 計算（旧 Python と一致させる）
- 単勝: 的中 = `finish == 1`（同着は複数馬が的中）。払戻 = `stake × odds_at_bet`（元本込み。`results.payout` が無くても成立）、`profit = payout − stake`（旧 `settle_open_win_bets_for_race`）。**丸めない**（旧は丸めていない。丸めを入れる場合は旧の挙動を確認してから）。
- ワイド・三連複: 当たり組み合わせは `payouts` の `combo_key`。`hit = (combo_key == 当たりの key)`。払戻は `payouts.payout`（100 円あたりの値の扱いは旧 `get_payout`/`get_winning_combo_keys` に合わせる）。暫定払戻は `is_provisional=1` で `payout_used` に入れ、確定後に置換（旧 `is_prov`）。
- `settle_status`: `settled`／`no_ticket`（組み合わせが無い・着順不足）。`hit=NULL` と `0` を区別する。
- 代表評価: 旧は『発走前の最新 1 件』（`COALESCE(snapshot_at, created_at) DESC`）。新方式は各 `race_timing_notice` の評価を個別に決済し、**旧互換の代表**（発走前の最新）を成績集計の既定にする（Q2）。
### 5.2 クラス
`SettlementCalculator`（純粋関数。`:domain`。golden 対象）、`ResultSettler`（読取り → 計算 → 書込みの調整。1 レース 1 トランザクション）、`SettlementStore`（ports）: `RaceEvaluationDao.markSettled`（`WHERE settled_at IS NULL OR 暫定`）、`TicketEvaluationDao`、`BetDao`/`BetLegDao`（Q3 次第）。
### 5.3 一意性・冪等性（V6 の主目的）
- `race_evaluations`: 決済は `UPDATE … WHERE id = ? AND (settled_at IS NULL OR is_provisional = 1)` で冪等。
- `ticket_evaluations`: 新方式の行だけ UNIQUE `(tenant_id, race_evaluation_id, bet_type, combo_key, umaban, uq_native)`（V5 と同じ生成列方式。旧データの重複は触れない）。
- `bets`: 新方式の行だけ UNIQUE `(tenant_id, signal_id, uq_native)`（Q3 で `bets` を作る場合）。
### 5.4 テスト（golden + 性質）
旧 `settle_*` を呼ぶ新しい golden ケース（GD11: 単勝の的中/外れ/同着/未確定、ワイド・三連複の的中/外れ/暫定→確定、no_ticket）、`SettlementCalculator` と旧の全ケース一致、冪等（2 回目は変更なし）、確定による暫定の置換、クラッシュ（決済の途中で落ちて再実行しても二重計上しない）、並行（2 プロセス）、テナント境界、最小権限（bot が `results` を書けない）。

## 6. T3.9-d reconcile（`--task=reconcile`）
- スタブ（`StubTaskHandler`）を `ReconcileTaskHandler` に置換（`BotConfiguration.botTaskHandler`）。引数: `--date`・`--tenant-id`。`trigger_type=RECONCILE` は通知しない。
- 仕事: ①`race_day=INCOMPLETE` と、`INCOMPLETE_AT_DEADLINE` で終えた run の日の補完（結果が後から届いていれば決済）②暫定払戻の確定への置換（月曜 14:00 以降の確定成績との照合）③YSCH から消えた開催日・`STALE` の扱い（G-6）④決済済みなのに `race_schedule.status` が遅れているレースの是正。
- クラス: `ReconcilePlanner`（純粋関数: 何をするか決める）、`ReconcileService`（実行）、`CalendarCheckReason.NEEDS_RECONCILE` との接続。
- 一時障害（DB）は exit 75、回復不能（対象なし以外の不整合）は exit 1、不要は exit 0。再実行しても結果が同じ。
- テスト: RACE_CALENDAR_DESIGN §9 の C27、INCOMPLETE の回復、確定値が暫定と違う場合の更新と履歴、結果が永遠に来ない日（上限で諦める）、並行する bot-run との排他（`bot_run` のリース）。

## 7. T3.9-e 成績集計
- 入力: 決済済みの `race_evaluations`（代表）+ `ticket_evaluations`。出力: 日次・券種別・モデル別の回収率・的中率・LogLoss・Brier・校正（`model_metrics` の既存列）。
- クラス: `PerformanceAggregator`（純粋関数）、`ModelMetricsWriter`（`model_metrics` へ。自然キー `(tenant, market, date_from, date_to, model_id?)`。V6 で新方式の行だけ UNIQUE。**`model_id` 列が無いので、必要なら列追加を V6 に含める（Q1）**）。
- 数値は旧 `model_metrics` の算出と一致させる（旧の式を golden 化）。サンプル 0 件は NULL（0 で埋めない）。
- 集計は読取りの計算と書込みを分け、再実行で同じ行を上書きしない（同じキーは変更なし）。テスト: 合成データでの再現性、欠損、同着、暫定の除外/含む（既定は確定のみ）、テナント境界。

## 8. T3.9-f バックテスト（旧新比較）
- 目的: 過去の観測から、各評価タイミングで判定を再計算し、旧 Python の結果と比べる。**通知しない**（`NotificationOrigin.BACKFILL`）。本番の `race_timing_notice` を消費しない（既に実装済み）。
- 入力: `odds_snapshots`（`observed_at <= asOf`）、`results`、`horse_master_history`（時点整合）、`races`（G-28: `updated_at > asOf` の行は天候・馬場を使わず除外。**除外件数を必ず報告**）。
- クラス: `BacktestRunner`（`:domain`。`RaceEvaluator` を再利用。`Clock` は注入、asOf はタイミングごとに `post − minutes`）、`BacktestReport`（件数・除外理由・指標）、`--task=backtest`（新しい Job 引数。契約の追加）、`tools/backtest_compare.py`（旧 Python との比較。ユーザーの PC で実データ）。
- 保存（Q1）: `backtest_run`(id, tenant, 範囲, 設定の hash, モデル ID, 作成時刻)、`backtest_item`(run_id, race_id, timing_centi, 評価結果の要約, 決済)。V6。**実データの結果は『未検証』と明記**（合成データでの再現性のみ）。
- 禁止: v2・adaptive_relaxation・閾値の変更の有効化（承認が先）。
- テスト: 決定的（同じ入力で同じ出力）、point-in-time（asOf 後のオッズ・結果を見ない: 結果を asOf より後に挿入しても出力が変わらない）、除外件数の報告、旧 Python との差分（合成のフィクスチャで比較）、BACKFILL が通知しない、テナント境界。

## 9. V6（提案。承認前は Flyway に入れない）
| 変更 | 内容 | 既存データへの影響 | GRANT |
|---|---|---|---|
| `ticket_evaluations` | 生成列 `uq_native` + UNIQUE `(tenant_id, race_evaluation_id, bet_type, combo_key, umaban, uq_native)` | 旧行（`legacy_id` あり）は対象外 | 変更なし（bot は INSERT/UPDATE 済み） |
| `bets`（Q3 次第） | 生成列 + UNIQUE `(tenant_id, signal_id, uq_native)` | 同上 | 変更なし |
| `model_metrics` | `model_id` 列（NULL 可）と新方式の行だけ UNIQUE（必要な場合） | 既存行は NULL のまま | 変更なし |
| 新表 `backtest_run`・`backtest_item` | 追記専用（UPDATE なし）。PK/FK/CHECK は V5 と同流儀 | なし | bot に SELECT/INSERT、app に SELECT |
| `race_evaluations` | 変更なし（`settled_at`/`hit`/`payout_used`/`is_provisional`/`settle_status` が既にある） | | |
適用前検査・ロールバック（アプリを旧版へ戻す。新表は DROP しない）は V5 と同じ。`requiredSchemaVersion` は 6。release-manifest の `flyway.latest_version` は自動で追従する。

## 10. 実装順序（各ステップで commit・push。ローカルテストを先に、CI は最後にまとめて）
| # | 内容 | 依存 | 完了条件 |
|---|---|---|---|
| 1 | golden の拡張（GD11: 旧 `settle_*`・払戻・的中・`get_winning_combo_keys`）と `SettlementCalculator` | なし | `generate --check`、Kotlin と旧の全一致 |
| 2 | 取込: `ResultObservation`/`PayoutObservation`、`ResultStore`/`PayoutStore`、`ObservationApplier`、保留の再適用の対象追加 | 1 は不要 | §3.3 の試験、ingest 統合 |
| 3 | V6（承認後）と DAO・SQL・GRANT・ガード試験 | Q1 | db-migration 統合（旧データのある DB に適用） |
| 4 | `RaceStatusUpdater`（遷移表 + 履歴）と `race_schedule`/`race_day` の更新 | 3 は不要 | 遷移表の全辺の試験 |
| 5 | `ResultSettler`・`SettlementStore`・bot-run の結果待ち（checkpoint v2、`BotRunConfig` 追加、契約 0.4.x） | 1・2・4 | §4.3・§5.4 の試験、MySQL 統合 |
| 6 | `reconcile` | 5 | C27・§6 の試験 |
| 7 | 成績集計 | 5 | §7 の試験 |
| 8 | バックテスト | 5・7 | §8 の試験（合成データ） |
| 9 | 文書（PLAN・PROGRESS・DECISIONS・CONTRACTS・UNRESOLVED・MIGRATION_MATRIX）、ローカル全テスト、CI は ユーザーの許可後 | | |

## 11. リスクと未解決
- **実データ形式**（HR・SE・払戻の桁。`results.payout` の倍率/円の疑義: V1 のコメント）は JV-Data 仕様書の確認（T1.17）が先。合成の正規化レコードで進め、実パーサは別タスク。
- 旧の『代表評価 1 件』と新の『タイミングごと』の違い（Q2）。数値の一致は代表で担保する。
- G-28（天候・馬場の履歴）でバックテストの対象が減る。件数を報告し、履歴表は別の承認。
- 紙の賭け（`bets`）の要否（Q3）は T1.20 の旧データ次第。
- 結果が来ない日の扱い（上限・諦め方）と、確定成績の取込時刻（月曜 14:00 以降）は E 項目（公式確認）に依存。
- いずれも T3.9 の着手前に、ユーザーの判断（Q1〜Q5）を受ける。
