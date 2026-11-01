# COMPLETION（完成条件一覧と現在の状態）

最終更新: 2026-11-01（main = f9c61af + 作業ブランチ）。**この表が『開発全体が終わったか』の判定の正本。** 他の文書（PROGRESS の履歴・PLAN のチェック項目）と食い違ったら、コード・PR・検証結果と突合してこちらを直す。
要件の出典: `REQUIREMENTS.md` §3〜§5、`MIGRATION_MATRIX.md` §1（F01〜F26）、`PLAN.md`、`UNRESOLVED.md`。

## 区分
- **A**: コードと合成データによる検証まで完成（ローカル + CI）。実機・本番では未確認の場合は『実機』列に書く。
- **B**: 本人の判断・資料・実機・本番環境の確認待ち（不足情報と必要操作を書く）。**通常の実装残件を B に移さない。**
- **C**: 未実装または未接続（実装を続ける）。

「部品の単体テストが通った」だけでは A にしない。**接続先**（本番経路で実際に呼ばれる配線）まで確認したものだけを A とする。

## 一覧
| ID | 完成条件（要件・機能表） | 実装場所 | 接続先（本番経路） | 検証 | 区分 |
|---|---|---|---|---|---|
| R1 | 開催日・発走予定に基づく制御（F03）、中止・延期・時刻変更・手動補正 | `domain/calendar`、`bot/CalendarCheckTaskHandler` | calendar-check → `JobLauncher` | 単体 + MySQL 統合 | **A**（起動の実行基盤は R2） |
| R2 | calendar-check から bot-run を**実際に起動**（Cloud Run Jobs） | `domain/bot/JobLauncher`、`:cloud-gcp`（未実装） | 本番は `UnconfiguredJobLauncher`（常に失敗 = fail closed） | — | **C**（Cloud Run Jobs の run API クライアント） |
| R3 | 取得エージェント（F01/F22/F23）: 署名・キュー・送信・ビーコン・状態機械 | `agent/`（Python） | ingest API | pytest 213 + Linux/Windows CI + Kotlin 相互運用 | **A**（Mock 取得。実機は R4） |
| R4 | JV-Link 実取得（COM）・実機 PoC（T3.0） | `agent/` の COM ワーカー | Windows 実機 | 未実施 | **B**（Windows 実機で `agent/poc` を実行。手順は `docs/USER_ACTIONS.md`〔作成予定〕） |
| R5 | JV-Data 実レコードのパーサ（F02: RA/SE/O1〜O6/HR/結果ほか） | `ingest` の `RecordParser`（合成の正規化 JSON のみ） | ingest | 合成のみ | **B**（JV-Link 仕様書 Ver.4.9 の項目位置の確認。T1.17/G-5/G-16。取得できる資料を `JVLINK_DESIGN.md` の 12 項目で特定） |
| R6 | 取込 API・冪等 upsert・バックフィル・再開・保留の再適用（F21/F22、T3.4） | `:ingest`、`domain/ingest` | MySQL | 単体 + 統合 + 実 Python エージェント | **A** |
| R7 | 結果・払戻の取込（F15 前半） | `ObservationApplier`、`ResultStore`/`PayoutStore` | MySQL（`results`/`payouts`） | 統合試験（合成レコード NRS/NHR） | **A**（実データ形式は R5） |
| R8 | 判定（EV/Kelly/確率/ワイド/三連複/買い目）（F05〜F09） | `domain/decision` | bot-run | 旧実装とのゴールデン 34 ケース全一致 | **A** |
| R9 | bot-run: 監視・評価・通知タイミングの確定・Discord 通知（F13/F14/F24） | `domain/botrun`、`bot/` | Discord Webhook | 単体 + MySQL 統合 + Fake HTTP | **A**（実 Discord は B: `USER_ACTIONS`） |
| R10 | bot-run: 発走後の結果待ち・開催日の進行（RUNNING→SETTLING→COMPLETED/INCOMPLETE）・履歴 | `BotRunOrchestrator.settleCycle`、`RaceDayAdvancerImpl` | `race_day`/`race_schedule_event` | 単体（12 件）。MySQL 統合は **C**（次の増分） | **A（単体）/ C（統合）** |
| R11 | レース単位の状態（MONITORING/OFF/RESULT_*/SETTLED）の永続化と履歴 | — | — | — | **B**（任意。履歴の粒度のために `event_type` へ `STATUS` を足す V6。`docs/V6_PROPOSAL.md` に最終 SQL・影響・検証を準備済みで承認待ち。無くても導出表示で動く） |
| R12 | reconcile（F15）: INCOMPLETE/異常終了の回復・冪等・排他 | `ReconcileService`、`ReconcileTaskHandler` | `race_day` | 単体 6 件 | **A（単体）/ C（MySQL 統合）** |
| R13 | 決済の計算（単勝）・紙上成績・代表集計（F15/F20） | `domain/performance`、`domain/settlement` | （読取り API は R15） | 単体 + 旧実装ゴールデン | **A（計算）** |
| R14 | ワイド・三連複の紙上決済（通知スナップショットの買い目 × 払戻） | — | — | — | **C** |
| R15 | 成績・シグナル・買い目・結果の読取り API（DTO・絞り込み・ページング） | `:dashboard`（`/api/v1/me` のみ） | MySQL（`app` ユーザー） | — | **C** |
| R16 | バックテスト（F20）: 保存済みデータ・未来情報の遮断・除外件数/率 | — | — | — | **C** |
| R17 | retention（F14/F16: 保持期間の削除。既定はドライラン、削除は明示設定） | `StubTaskHandler`（スタブ）→ 実装予定 | `bot --task=retention`（DELETE 権限は V5 の GRANT で付与済み） | — | **C**（実装する。実データの削除は本人の承認事項なので既定はドライラン） |
| R18 | 人の認証・認可（IAP JWT・許可リスト・ロール・deny by default） | `HumanAuthorizer`、`ApiAuthFilter` | dashboard `/api/**` | 単体 + Tomcat 統合 | **A**（実 IAP の `aud` は B: G-OPS-7） |
| R19 | ダッシュボード UI（日本語・レスポンシブ・bot 状態・次回予定・最終更新・未更新表示・シグナル/買い目/結果/成績/バックテスト画面） | `:dashboard`（UI なし） | — | — | **C** |
| R20 | 管理操作（手動補正・エージェント失効・モデル有効化）+ CSRF + 監査ログ | `AdminWriteGuardFilter`（ガードのみ） | — | — | **C** |
| R21 | ML 学習 Job・推論・モデル署名・ゲート・レジストリ（F10/F11/F12） | `ml/`、`domain/ml`、`:bot` | GCS model-store | pytest 128 + Kotlin/Python 結合 5 | **A**（実モデル・実データでの学習は B: T1.20/T1.21） |
| R22 | ML 管理 UI（有効化・メトリクス表示） | — | — | — | **C**（R19/R20 に含む） |
| R23 | DB スキーマ・Flyway V1〜V5・GRANT・複数 MySQL 版 | `:db-migration` | MySQL 8.4/9.7 | 統合 49 + CI | **A**（本番 DB への適用は B） |
| R24 | SQLite → MySQL のデータ移行ツール（F16、`DATA_MIGRATION_DESIGN`） | `tools/migrate/`（未作成） | 旧 SQLite のコピー | — | **C**（実データの投入は B。ツールは合成 SQLite で検証） |
| R25 | 運用モード（NORMAL/STOPPED）の読取りと全アプリでの遵守、ビーコン | `:cloud-gcp`、各アプリ | GCS | 単体 + 統合 | **A**（実 GCS は B: G-OPS-*） |
| R26 | CI・事前検証・イメージ・release-manifest | `.github/`、`tools/ci`、`tools/release` | GitHub Actions | CI + `tools.ci.preflight` | **A** |
| R27 | Terraform（基盤・WIF・ネットワーク・IAM・Secret・AR・Cloud Run・Jobs・Cloud SQL・Scheduler・IAP） | `jra-bot-infra`（未作成） | GCP | — | **C**（apply は B） |
| R28 | deploy / start / stop / status workflow・排他・部分失敗・安全な再実行 | `jra-bot-infra/.github/workflows`（未作成） | GCP | — | **C** |
| R29 | 監視・アラート・予算・ロールバック・バックアップ復旧・鍵更新の手順 | infra docs / `OPERATIONS.md` | — | — | **C** |
| R30 | 合成データの全体統合試験（取得側→ingest→DB→判定→Fake 通知→結果→成績） | `:bot` 統合試験 | MySQL | — | **C** |
| R31 | 旧新比較の総合（意図した差異・旧欠陥の整理）`TEST_RESULTS.md` | docs | — | ゴールデン + 差異表（E1〜E16） | **C**（文書化） |
| R32 | `OPERATIONS.md`・初期導入・移行手順・本人の最終確認チェックリスト | docs | — | — | **C** |

## 本人の判断・操作が必要なもの（B のまとめ。包括委任の対象外）
| 項目 | 不足している情報・必要な操作 | 準備状況 |
|---|---|---|
| Windows/JV-Link 実機（R4） | 実機で `agent/poc` を実行し結果（成否・件数のみ）を返す | 手順書は `docs/USER_ACTIONS.md`（作成予定） |
| JV-Data 仕様（R5） | JV-Link 仕様書（Ver.4.9）の該当ページ、`JVLINK_DESIGN.md` の 12 項目の回答 | 項目は特定済み |
| 旧 SQLite・設定・モデルの確認（T1.20/T1.21） | `SELECT` 2 本の集計結果（行数のみ）、legacy/v2 の判断 | SQL は `UNRESOLVED.md` D |
| V6（R11 のみ・任意） | ENUM 追加 1 件の承認（GRANT 変更なし） | `docs/V6_PROPOSAL.md`（作成済み） |
| GCP（R27）・OAuth・Secret・予算 | 請求アカウント、OAuth クライアント、Secret の投入、apply の承認 | plan までコード化。手順は infra docs |
| 実 Discord | Webhook を Secret に入れて送信確認 | 手順は `USER_ACTIONS` |
| COMP-4 | JRA-VAN への回答（実データのクラウド投入可否） | 問い合わせ文面は `DATA_SOURCE_COMPLIANCE.md` |
| Public 化 | ユーザーのみが操作 | — |
