# COMPLETION（完成条件一覧と現在の状態）

最終更新: 2026-11-03（言語移行 L1〜L7 の状態を更新。web main = 432d05a、infra main = c6613ed）。旧: 2026-11-02（言語移行 L1〜L9 を追加）。旧: 2026-11-01。**この表が『開発全体が終わったか』の判定の正本。** 他の文書（PROGRESS の履歴・PLAN のチェック項目）と食い違ったら、コード・PR・検証結果と突合してこちらを直す。
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
| R2 | calendar-check から bot-run を**実際に起動**（Cloud Run Jobs） | `:cloud-gcp` `CloudRunJobLauncher`、`bot/RunJobsSettings` | 本番の calendar-check は `RUN_JOBS_*` 必須（無ければ exit 2）。無いローカルは未設定の fail closed | 偽サーバで宛先・認証・引数の上書き・失敗の分類を試験。**実 Cloud Run は未検証** | **A（web 側）/ B（実 GCP での確認）**。infra: calendar-check Job に `RUN_JOBS_*` を渡し、`sa-bot` に bot-run Job の `jobsExecutorWithOverrides` を付与（infra PR） |
| R3 | 取得エージェント（F01/F22/F23）: 署名・キュー・送信・ビーコン・状態機械 | `agent/cs`（C#/.NET 10。D-261。旧 Python 版は削除） | ingest API | xunit 296（Windows 専用 4 を含む）+ Linux/Windows CI + Kotlin 相互運用 4 | **A**（Mock 取得。実機は R4） |
| R4 | JV-Link 実取得（COM）・実機 PoC（T3.0） | `agent/` の COM ワーカー | Windows 実機 | 未実施 | **B**（Windows 実機で `agent/poc` を実行。手順は `docs/USER_ACTIONS.md`〔作成予定〕） |
| R5 | JV-Data 実レコードのパーサ（F02: RA/SE/O1〜O6/HR/結果ほか） | `ingest` の `RecordParser`（合成の正規化 JSON のみ） | ingest | 合成のみ | **B**（JV-Link 仕様書 Ver.4.9 の項目位置の確認。T1.17/G-5/G-16。取得できる資料を `JVLINK_DESIGN.md` の 12 項目で特定） |
| R6 | 取込 API・冪等 upsert・バックフィル・再開・保留の再適用（F21/F22、T3.4） | `:ingest`、`domain/ingest` | MySQL | 単体 + 統合 + 実 C# エージェント（`jraagent-interop`） | **A** |
| R7 | 結果・払戻の取込（F15 前半） | `ObservationApplier`、`ResultStore`/`PayoutStore` | MySQL（`results`/`payouts`） | 統合試験（合成レコード NRS/NHR） | **A**（実データ形式は R5） |
| R8 | 判定（EV/Kelly/確率/ワイド/三連複/買い目）（F05〜F09） | `domain/decision` | bot-run | 旧実装とのゴールデン 34 ケース全一致 | **A** |
| R9 | bot-run: 監視・評価・通知タイミングの確定・Discord 通知（F13/F14/F24） | `domain/botrun`、`bot/` | Discord Webhook | 単体 + MySQL 統合 + Fake HTTP | **A**（実 Discord は B: `USER_ACTIONS`） |
| R10 | bot-run: 発走後の結果待ち・開催日の進行（RUNNING→SETTLING→COMPLETED/INCOMPLETE）・履歴 | `BotRunOrchestrator.settleCycle`、`RaceDayAdvancerImpl` | `race_day`/`race_schedule_event` | 単体 + **MySQL 通し**（`BotRunOrchestratorIntegrationTest`） | **A** |
| R11 | レース単位の状態（MONITORING/OFF/RESULT_*/SETTLED）の永続化と履歴 | — | — | — | **B**（任意。履歴の粒度のために `event_type` へ `STATUS` を足す V6。`docs/V6_PROPOSAL.md` に最終 SQL・影響・検証を準備済みで承認待ち。無くても導出表示で動く） |
| R12 | reconcile（F15）: INCOMPLETE/異常終了の回復・冪等・排他 | `ReconcileService`、`ReconcileTaskHandler` | `race_day` | 単体 + **MySQL 通し**（`ReconcileIntegrationTest`） | **A** |
| R13 | 決済の計算（単勝）・紙上成績・代表集計（F15/F20） | `domain/performance`、`domain/settlement` | （読取り API は R15） | 単体 + 旧実装ゴールデン | **A（計算）** |
| R14 | ワイド・三連複の紙上決済（通知スナップショットの買い目 × 確定払戻）と賭式別の代表集計 | `domain/performance/ComboPaperSettlement`（純粋関数。DB に書かない: D-234） | 読取り API（R15）の `/api/v1/performance` の `combos`（`PerformanceReadService` が呼ぶ。画面の成績タブに賭式別に表示） | 単体 8（同着・速報・二重計上・発走後の除外・キー読取り）+ 画面の契約の試験（D-263） | **A**（2026-11-06 に接続済みを確認して訂正）。旧のワイド判定との差異は E17 |
| R15 | 成績・シグナル・買い目・結果の読取り API（DTO・絞り込み・ページング） | `:dashboard` `ReadApi`（`/api/v1/status|runs|races|signals|performance`。GET のみ・テナントは本人の行から・入力検査 400・no-store） | `app` ユーザー（SELECT のみ）→ MySQL | 単体 6（テナント分離・ページング・400・未更新判定）+ **MySQL 通し 4（`ReadAdminApiMySqlIntegrationTest`: 読取り・他テナントの races/signals が見えない・管理者の補正と監査記録の両方が DB に残る・閲覧者 403 で補正も監査も増えない。**監査失敗時のロールバック（同一トランザクション）は単体の偽トランザクション試験〈AdminApiTest〉でのみ確認し、実 MySQL では未検証。performance/status/管理 API 読取りのテナント分離と、他テナント側にデータを入れた逆方向も未試験**。ローカル MySQL 8.4 で成功・CI の dashboard 統合 verify に組込み）** | **A（API + MySQL 通し）**。買い目の個別一覧・馬単位の詳細は未実装 |
| R16 | バックテスト（F20）: 保存済みデータ・未来情報の遮断・除外件数/率 | `domain/backtest/BacktestRunner`（純粋な計算。DB に書かない: D-234） | `bot --task=backtest`（D-265。bot-run と同じ RaceEvaluator・設定・ML。DB に書かず通知しない。結果は 1 行の JSON をログへ）。infra は Cloud Run Job `backtest`（I-038） | 単体 5（未来のオッズを渡さない・理由別の除外件数と率・二重計上なし）+ 引数・ハンドラ・Spring の配線の試験 + イメージのスモーク（STOPPED で DB に接続しない） | **A（Job として実行）**。画面での表示・結果の保存は V6 の承認が必要（B: T39_DESIGN §8・§9）。実データでの検証は B（COMP-4） |
| R17 | retention（F14/F16: 保持期間の削除。**既定はドライラン**、削除は `RETENTION_EXECUTE=true` の明示） | `domain/retention`、`persistence/RetentionDao`、`bot/RetentionTaskHandler` | `bot --task=retention`（DELETE 権限は V5 で付与済み） | 単体 + MySQL 通し試験（ドライラン・削除・テナント分離・冪等） | **A**（実データの削除の実行は本人の承認事項。単勝オッズの間引きは未実装 = 別の判断） |
| R18 | 人の認証・認可（IAP JWT・許可リスト・ロール・deny by default） | `HumanAuthorizer`、`ApiAuthFilter` | dashboard `/api/**` | 単体 + Tomcat 統合 | **A**（実 IAP の `aud` は B: G-OPS-7） |
| R19 | ダッシュボード UI（日本語・レスポンシブ・bot 状態・次回予定・最終更新・未更新表示・シグナル/成績/運用画面） | `dashboard/src/main/resources/static`（HTML/CSS）+ `dashboard/src/main/ts`（strict TypeScript。D-243・D-263） | `/`（IAP の内側） | 静的検査（インライン script・外部 CDN・innerHTML なし）+ TS 単体 51 件 + 契約の見本と実 API の形の比較（MySQL 統合）+ 配信と no-cache の確認（Tomcat）。イメージでの配信の確認は CI の images ジョブ（ローカルは環境の制約で未実施）。**ブラウザでの目視確認は未実施（B）** | **A（実装）/ B（実機の目視）**。バックテスト画面・馬詳細・グラフは未実装（C） |
| R20 | 管理操作 + CSRF + 監査ログ | `AdminApi`（手動補正の作成・取消・一覧）、`CsrfGuardFilter`、`AuditLogWriter`（`audit_log` 追記専用）、`ApiAuthFilter`（`/api/v1/admin` は読取りも ADMIN）、`AdminWriteGuardFilter`（NORMAL 以外は 409） | `/api/v1/admin/**`（`app` ユーザー: calendar_override・audit_log の INSERT、`agent` の status・revoked_* の UPDATE） | 単体（作成/取消/400/404/409/監査の記録と失敗/CSRF の拒否）+ MySQL（監査の追記・UPDATE/DELETE 不可）は CI | **A（手動補正・エージェントの失効: D-270）**。エージェントの発行（登録コード）は CONTRACTS §3.3.2 の設計の承認待ち（B）。ML モデルの有効化は R22（B） |
| R21 | ML 学習 Job・推論・モデル署名・ゲート・レジストリ（F10/F11/F12） | `:ml-train`（Kotlin。D-257）、`domain/ml`、`:bot` | GCS model-store | ml-train 単体 47 以上 + `MlTrainInteropIntegrationTest`（bot ⇄ ml-train の jar） | **A**（実モデル・実データでの学習は B: T1.20/T1.21） |
| R22 | ML 管理 UI（有効化・メトリクス表示） | `MlModelApi`（`GET /api/v1/admin/ml-models`）・`ModelCatalogReader`・画面の運用タブ（D-268） | `app` ユーザーの `ml_model` の SELECT → MySQL | dashboard 単体 + persistence の MySQL 統合 + 画面の契約の見本（TS・Kotlin の DTO の厳密比較） | **A（表示）/ B（有効化）**: 有効化は品質ゲートの閾値が未承認のため本人の判断（API・画面に操作は置かない） |
| R23 | DB スキーマ・Flyway V1〜V5・GRANT・複数 MySQL 版 | `:db-migration` | MySQL 8.4/9.7 | 統合 49 + CI | **A**（本番 DB への適用は B） |
| R24 | SQLite → MySQL のデータ移行ツール（F16、`DATA_MIGRATION_DESIGN`） | `:legacy-import`（Kotlin。D-271・D-273）。**preflight・extract・transform・load 済み**（読取り専用の事前検査とレポート、主キー順のチャンク NDJSON.gz と manifest、読み直しの検証、新スキーマの値への変換と隔離、利用者の PC の MySQL への再開できる読込みと件数の照合） | 旧 SQLite のコピー（利用者の PC） | 合成 SQLite の単体 38・MySQL 8.4/9.7 の統合 3（WAL・パス・読取り専用・時刻の形式・外部キー・チャンク・値の型・決定性・改ざんの検出・値の変換・隔離・恒等式） | **A（preflight・extract・transform・load）/ C（チェックサムの検証・本番への promote・並行稼働中の差分）**。実データでの実行は B（本人の PC。クラウドは COMP-4） |
| R25 | 運用モード（NORMAL/STOPPED）の読取りと全アプリでの遵守、ビーコン | `:cloud-gcp`、各アプリ | GCS | 単体 + 統合 | **A**（実 GCS は B: G-OPS-*） |
| R26 | CI・事前検証・イメージ・release-manifest | `.github/`、`tools/src`（TypeScript。D-254） | GitHub Actions | CI + `npm run preflight` | **A** |
| R27 | Terraform（基盤・WIF・ネットワーク・IAM・Secret・AR・Cloud Run・Jobs・Cloud SQL・Scheduler・IAP） | `jra-bot-infra/terraform`（PR #4・#6） | GCP | `fmt/validate/test`（mock 17）。**実 plan は未実施** | **A（コード）/ B（apply・実 plan）** |
| R28 | deploy / start / stop / status workflow・排他・部分失敗・安全な再実行 | `jra-bot-infra/.github/workflows` + `ops/`（PR #4） | GCP | ops 単体 77（偽 GCP）。**GitHub 上の実行は未実施** | **A（コード）/ B（実行）** |
| R29 | 監視・アラート・予算・ロールバック・バックアップ復旧・鍵更新の手順 | infra `docs/OPERATIONS.md`、`terraform/monitoring.tf` | — | 文書 + mock | **A（文書・コード）/ B（予算は本人の設定）** |
| R30 | 合成データの全体統合試験（取得側→ingest→DB→判定→Fake通知→結果→成績） | `BotRunOrchestratorIntegrationTest` の通し + ingest 統合 + agent 相互運用 | MySQL | `TEST_RESULTS.md` §2 | **A（段ごと）/ C（HTTP から bot までを 1 本にした試験は未作成。モジュール間依存の禁止による）** |
| R31 | 旧新比較の総合（意図した差異・旧欠陥の整理）`TEST_RESULTS.md` | `docs/TEST_RESULTS.md`、MIGRATION_MATRIX E1〜E17 | — | ゴールデン + 差異表 | **A** |
| R32 | `OPERATIONS.md`・初期導入・移行手順・本人の最終確認チェックリスト | web `OPERATIONS.md`・`USER_ACTIONS.md`、infra `OPERATIONS.md` | — | — | **A（文書）**。移行手順は R24 の実装後に具体化 |

## 言語移行（2026-11-02 要件変更。D-250。正本は `LANGUAGE_MIGRATION.md`）
完成の条件: 自分たちで保守する Python が通常開発・CI・運用の必須経路に無い。区分は上と同じ（A = コード + 合成データ検証まで、B = 本人・実機・本番待ち、C = 未実装）。
| ID | 対象 | 移行先 | 状態 | 区分 |
|---|---|---|---|---|
| L1 | CI・リリース補助（tools/ci・tools/release・JUnit 件数検査） | TypeScript（`tools/src`） | **実装・CI 接続済み**（web PR #36。D-254）。試験 164 件（2026-11-03） | **A** |
| L2 | infra の運用 CLI（ops） | TypeScript（infra `ops/src`） | **実装・CI 接続済み**（infra PR #7）。試験 138 件（L6 の preview-guard・db-users を含む）。実 GCP は未検証 | **A（コード）/ B（実 GCP）** |
| L3 | ML 学習・評価・品質ゲート・成果物・convert-pkl（ml/・tools/ml） | Kotlin（`:ml-train`） | **実装・接続・合成データで検証済み**（D-257）。Python の ml/・tools/ml は削除。実 pkl の変換確認は本人の PC（B）、実データでの学習・閾値は COMP-4・未承認のまま | **A**（合成データの範囲） |
| L4 | ゴールデン（tools/golden） | Kotlin（`golden-runner verify/check`） | **実装・CI 接続済み**（D-258）。旧 Python の verify・kotlin_compare と同じ報告（要約がバイト単位で一致）。Python の生成器・CI ジョブは削除。旧実装からの再生成の一致は CI から外した（LANGUAGE_MIGRATION §5） | **A** |
| L5 | Windows 取得エージェント（agent/） | C#/.NET 10 | **実装・CI 接続済み**（web PR #42。D-261・D-262）。xunit 307 件（Linux・Windows の CI）・Kotlin の相互運用試験 4/4。Python の agent は削除 | **A**（実機 COM は B: R4） |
| L6 | GCP 資源定義（terraform/） | Pulumi + TypeScript | **定義・mock 試験 27 件・preview guard・`ops db-users` 済**（infra PR #9。I-036・I-037）。Terraform は up/import の承認まで現行の定義 | **A（コード・mock）/ B（preview の workflow・WIF・up/import・実 GCP）** |
| L7 | フロントエンド（app.js） | TypeScript（`dashboard/src/main/ts`） | **実装・接続済み**（D-263）。TS 試験 47 件・契約の見本を Kotlin の MySQL 統合試験と共有。イメージの実ビルドと配信のスモークは CI の images ジョブで確認（ローカルは環境の制約で未実施）。Python は無い | **A**（実ブラウザの目視は B: R19） |
| L8 | 残機能（上表の C） | Kotlin/TS | R16・R20・R22・R24（preflight〜load）は実装済み（D-266〜D-275）。R30 の 1 本の通し試験は未作成 | **A（大半）/ C（R30 の通し・R24 のチェックサムと promote は COMP-4 後）** |
| L9 | リリース経路（AR 公開・本番 manifest・deploy 受渡し） | GitHub Actions + TS | **実装済み**（D-276）: `release.yml`（手動・環境の承認・ci gate の確認・ビルド・スモーク・WIF の短期トークンで push・digest・本番 manifest の生成と検証・GitHub Release の資産）と `publish`（TS。試験 7）。ワークフローの規則の試験 6。infra の WIF・SA は承認待ちの PR | **A（コード）/ B（infra の WIF・SA の承認と作成、変数・環境の設定、実公開: USER_ACTIONS A8・B4b）** |

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
