# ARCHITECTURE（T1.10 全体アーキテクチャ確定・2026-10-22）

状態: **設計として確定**。ただし次の 3 つは確定していない。
- 実装で確かめる項目。検証ゲートとして残している（§10）。
- ユーザーの承認が必要な項目。案のまま残している（§11）。
- 課金リソースの作成、GCP の契約、Terraform apply。どれも承認していない（infra の I-011）。

詳細はそれぞれの文書が正本で、本書には重ねて書かない。
| 内容 | 正本 |
|---|---|
| 契約 | `CONTRACTS.md` 0.4.0 |
| 版数 | `VERSIONS.md` |
| UI | `FRONTEND_DESIGN.md` |
| ML | `ML_DESIGN.md` |
| データ・移行 | `DATA_MIGRATION_DESIGN.md` |
| 開催日 | `RACE_CALENDAR_DESIGN.md` |
| JV-Link | `JVLINK_DESIGN.md` |
| 規約 | `DATA_SOURCE_COMPLIANCE.md` |
| クラウドの構成・運用 | infra の `ARCHITECTURE.md` / `OPERATIONS_DESIGN.md` |

## 1. リポジトリと責務
| リポジトリ | 責務 | 公開 |
|---|---|---|
| puttun777/jra-bot | 参照元（**読み取り専用**） | Public |
| puttun777/jra-bot-web | アプリ（Kotlin/Spring Boot）、Windows エージェント（Python）、ML 学習 Job（Python）、移行・ゴールデンのツール（Python）、イメージ定義、CI（ビルド・テスト・イメージ・release-manifest） | Private（Public 化は D-180 で準備済み・ユーザー操作待ち。公開しても本番環境は非公開） |
| puttun777/jra-bot-infra | Terraform、deploy・migration の実行、start/stop/status、監視、ロールバック | Private |

### 1.1 jra-bot-web のディレクトリ構成（D-057）
```
settings.gradle.kts / build.gradle.kts / gradle/libs.versions.toml   # Version Catalog
domain/  persistence/  db-migration/  cloud-gcp/  bot/  ingest/  dashboard/   # Gradle (Kotlin)
agent/            # Windows JV-Link 取得エージェント（Python, Windows 専用。CI では Linux で単体テストだけ）
ml-train/         # ml-train（Kotlin の学習 Job。独立イメージ。D-257）
legacy-import/    # 旧 SQLite → MySQL の移行ツール（Kotlin の CLI。利用者の PC で実行。イメージに入らない。D-271）
tools/migrate/    # SQLite→MySQL 移行ツール（Python。デプロイしない）
tools/golden/     # 参照元から合成フィクスチャを作るスクリプト（Python。JSON だけをコミット）
docs/             # 設計・進捗・判断（正本）
.github/workflows/  # CI（T2.5）
```
Python の 3 つ（`agent/`、`ml/`、`tools/`）は依存を別々に管理する（`pyproject.toml` とロックファイル）。Gradle からは参照しない。

## 2. Gradle モジュール（Gradle Kotlin DSL）
| モジュール | 責務 | 依存してよいもの | 禁止 |
|---|---|---|---|
| `:domain` | 業務モデル、値オブジェクト（`TenantId`・`RaceId`・`Odds`・`Probability`・`OpsMode` など）、計算・判定（EV/Kelly/確率パイプライン/買い目/急落フィルタ/開催判定）、**ML 推論基盤 `jp.jrabot.domain.ml`（T3.6-A: 厳密 JSON・正準化、`FeatureSchema`・`FeatureBuilder`（旧互換と point-in-time）、ロジスティック回帰、`ProbabilityPipeline`（legacy/v2）、モデル JSON の署名・sha256 検証、レジストリの読取りと run 固定、データセット書出し。JSON は Jackson ではなく自前の厳密パーサ。ML_DESIGN §11）**、JV-Data パーサ（固定長 cp932）、成績集計、**ポート**（Repository・`ModeProvider`・`JobLauncher`・`Clock`・`ModelStore`・`DatasetStore`・`Notifier`・`HeartbeatMirror`） | Kotlin 標準ライブラリ、kotlinx の小さなもの（日時・直列化）だけ | Spring、Doma、JDBC、クラウド SDK、HTTP クライアント |
| `:persistence` | Doma の DAO・SQL・Entity、Repository の実装、Entity⇔domain の変換、トランザクション | `:domain`、Doma、Spring JDBC/TX | Web、クラウド SDK。**Entity をモジュール外へ公開しない**（`internal`） |
| `:db-migration` | Flyway の SQL（`V*`・`R__grants.sql`）と CLI（`--mode`） | Flyway、Connector/J（Boot の BOM に依存しない） | 他のモジュールすべて |
| `:cloud-gcp`（D-045） | ポートの GCP 実装: GCS の `ModeProvider`・`ModelStore`・`DatasetStore`・`HeartbeatMirror`、Cloud Run Jobs の `JobLauncher`、Google の JWT/ID トークン検証部品 | `:domain`、Google Cloud のクライアントライブラリ | Spring Web、Doma、アプリのモジュール |
| `:bot` | Job（`--task=bot-run\|calendar-check\|reconcile\|retention\|export-dataset`）、Discord 通知（`jp.jrabot.bot.discord`: URL 検証・JDK HttpClient・`DiscordNotifier`。本文・分割・調整役は `:domain` の `notify`。T3.7）、オーケストレーション | `:domain`、`:persistence`、`:cloud-gcp` | `:dashboard`、`:ingest` |
| `:ingest` | エージェント API（認証・冪等・封筒）、パーサの呼び出し、upsert、heartbeat の写し | 同上 | `:bot`、`:dashboard` |
| `:dashboard` | 画面（静的 HTML/CSS + strict TypeScript の ES モジュール。`src/main/ts` を Gradle の compileFrontend が `static/js` へ変換: D-243・D-263）、`/api/v1`（DTO）、認証・認可、管理操作 | 同上 | `:bot`、`:ingest` |
| `:golden-runner`（T3.1、D-106） | **検証専用**: `tools/golden` のフィクスチャを読み、`:domain` の計算（`jp.jrabot.domain.decision`）を実行して結果 JSON を出力する（Python の比較器 `kotlin_compare` が旧実装の期待値と比べる）。JSON の読み書きはここだけ（`:domain` は Jackson に依存しない） | `:domain`、Jackson | それ以外のモジュールすべて。**どのモジュールもこれに依存しない**。アプリのイメージに入れない（Dockerfile は settings の include のため COPY するだけ） |

- **実行アプリ（bot/ingest/dashboard）同士は依存しない**。ルート build の検査タスクで、`project(":bot")` などへの依存があれば失敗させ、CI で強制する（T2.1）。
- 共通の処理は責務名のモジュール（`:domain`、`:persistence`、`:cloud-gcp`）に置く。`common` や `util` という名前のモジュールは作らない。マイクロサービスも増やさない。
- ローカル・テスト用のポート実装は、各モジュールの `testFixtures` に置く。ファイルのモード、固定 `Clock`、メモリの `ModelStore` など。`APP_ENV=local` では、アプリの起動クラスがファイル実装を組み立てる。
- `:domain` の `jp.jrabot.domain.decision`（T3.1）は、旧 Python と同じ式・同じ分岐順の**素の `Double`** で計算する（値オブジェクト `Odds`/`Probability`/`Money` は旧実装の境界挙動を再現できないので使わない。変換は呼び出し側）。設定は運用の既定値を持たない。D-104〜D-111。T3.2 でワイド/三連複を追加: Plackett–Luce（`PlackettLuce`）・実オッズの注入（`ComboOdds`。DB に依存せず、取得と鮮度は T3.4 の責務）・流し/BOX・`ticket_allocation`・`StrategyEntry.tickets`。D-112〜D-121。
- fat jar（Spring Boot の実行可能 jar）を作るのは `:bot`・`:ingest`・`:dashboard`・`:db-migration` だけ。

### 2.1 レイヤ（各実行アプリの中）
```
Controller（HTML / /api/v1） ─┐
Job エントリ（--task）        ├→ アプリケーションサービス（ユースケース, @Transactional の境界）→ :domain（純粋なロジック）
エージェント API              ─┘                        └→ ポート → :persistence / :cloud-gcp
```
- 表示（テンプレート・DTO）とビジネスロジックを分ける。テンプレートと Controller は Repository を直接呼ばない。HTML と `/api/v1` は同じサービスを呼ぶ。こうしておけば、将来 SPA（React など）へ移るときは表示層を差し替えるだけで済む（FRONTEND_DESIGN の見直しゲート）。
- DTO はアプリ（dashboard/ingest）ごとに定義する。`:domain` のモデルをそのまま JSON にしない。

## 3. 実行単位（デプロイ単位）
| 単位 | 種別（GCP） | イメージ | 起動契機 | 備考 |
|---|---|---|---|---|
| dashboard | Cloud Run サービス（直接 IAP、min=0/max=2） | `dashboard` | HTTP | ステートレス。STOPPED では到達できない（CONTRACTS §5.3） |
| ingest | Cloud Run サービス（IAP の外、min=0/max=2） | `ingest` | エージェントの HTTPS | トークン+HMAC。NORMAL 以外は 503 |
| calendar-check | Cloud Run Job | `bot` | Scheduler（固定間隔） | 開催判定だけを行う。条件を満たしたら `JobLauncher` で bot-run を起動 |
| bot-run | Cloud Run Job（1 vCPU/2 GiB、timeout 14h、retries 0） | `bot` | calendar-check / 管理者 | リース、checkpoint、結果反映まで終えたら終了 |
| reconcile / retention | Cloud Run Job | `bot` | Scheduler | 冪等。retention はドライランと管理者承認が前提 |
| export-dataset | Cloud Run Job | `bot` | 管理者 / 手動 | 学習用のデータセットを GCS へ出力 |
| db-migration | Cloud Run Job | `db-migration` | deploy / start | `info`・`validate`・`migrate`（CONTRACTS §4） |
| ml-train | Cloud Run Job（2 vCPU/4 GiB） | `ml-train` | 管理者 / Scheduler（最初は paused） | GCS のデータセットだけで学習し、CANDIDATE を登録 |
| jvlink-agent | 利用者の Windows PC | 配布 zip | 常駐（タスクスケジューラ） | 生レコードを取得して ingest へ送る（JVLINK_DESIGN） |
| tools/migrate | 利用者の PC / CI | — | 手動 | デプロイしない |

## 4. データの流れ
```
JV-Link(COM) → agent ──HTTPS(トークン+HMAC)──▶ ingest ─parse(:domain)─▶ MySQL ◀── bot-run（評価・通知・決済）
     ▲ plan/mode          │ heartbeat の写し → GCS ops-state/agent/      ▲  └▶ Discord
     └─ ビーコン(GCS 公開・署名) ◀── infra start/stop ──▶ GCS ops-state.json（正本）─▶ ModeProvider（全アプリ）
dashboard ─読取（DTO）─▶ MySQL        export-dataset ─▶ GCS datasets ─▶ ml-train ─▶ GCS models + ml_model(CANDIDATE)
```

## 5. データ・DB（詳細は DATA_MIGRATION_DESIGN、契約は CONTRACTS §8）
- Cloud SQL for MySQL **8.4 LTS（暫定。9.7 は G-DB-0 の後で判断: D-046）**、Enterprise、db-g1-small、プライベート IP のみ。Flyway の SQL がスキーマの正本で、範囲は §5.1。
- **テナント**: 共有スキーマで、`tenant_id` を全テナント表の PK/UK/FK の先頭に置く（D-039）。共有の表は `tenant`、`app_user`、`code_venue` だけ。DAO は `TenantId` を必須引数にする。SQL の静的検査と越境テストを CI で行う。
- **レース識別**: `race_id CHAR(12)`（YYYYMMDD + 場 2 + R2）。`kai`/`nichime` は NULL 可、`rt_key16` は導出（D-040）。
- **時刻**: UTC の `DATETIME(6)` で保存し、表示と開催日の判定は Asia/Tokyo。`Clock` を注入し、`now()` を直接呼ばない（D-006・D-041）。
- **冪等性**: 取込は自然キーの upsert と `batchId`/`record_hash`。bot はサイクルキーと通知の claim。移行は `legacy_id`。
- **保持と費用**: combo オッズは既定で保存しない。保持ジョブはドライランと承認が前提（D-043、DATA_MIGRATION_DESIGN §9）。

### 5.1 Flyway V1/V2 の範囲（D-058。第2段階 T2.3 で実装）
| 版 | 内容 |
|---|---|
| `V1__baseline.sql` | 共有表: `tenant`、`app_user`、`code_venue`（データは定数だけ）。旧 SQLite の 15 表を MySQL 向けに再設計したもの: `races`、`race_horses`、`horses`、`horse_past_runs`、`odds_snapshots`、`combo_odds_snapshots`、`results`、`payouts`、`signals`、`bets`、`bet_legs`、`race_evaluations`、`ticket_evaluations`、`model_metrics`、`ingest_cursor`。移行の補助: `import_quarantine`、監査: `audit_log` |
| `V2__ops_calendar_ingest_ml.sql` | 開催: `race_day`、`race_schedule`、`race_schedule_event`、`calendar_override`、`bot_run`。取込: `agent`、`agent_registration_code`、`agent_heartbeat`、`agent_nonce`、`ingest_batch`、`ingest_raw`（生レコード。保持期間は規約回答後）、`ingest_gap`。ML: `ml_model`。通知の claim は `signals` の UNIQUE と送信状態列で行う（別の表は作らない） |
| `R__grants.sql` | 用途別ユーザーの REVOKE/GRANT（CONTRACTS §8.2。G-DB-6 で成立するか確認） |
| V3 以降 | 追加だけ（expand/contract）。V1/V2 は運用を始めた後は変更しない（運用前の第2〜4段階は、PR の中でなら作り直してよい。最初の本番 migrate で凍結する） |
- **実装済み（T2.3、DECISIONS D-063〜D-069）**: 正本は `db-migration/src/main/resources/db/migration/`。設計案からの差分は D-064/D-065（odds の PK 順、`app_user` のメールは全体 UNIQUE、`signals.notify_state`、`bot_run` の 8 状態、`ml_model.signature_sha256` など）。
- 共通の規約: `utf8mb4`、コード列は `ascii_bin`、`DATETIME(6)` は UTC、`DOUBLE`（確率・オッズ）、複合 FK は `(tenant_id, …)`、予約語は改名する（`combo_key`、`rank_no`、`sample_count`、`race_date`）。V1 では `tenant` に行を入れない。

## 6. セキュリティ
| 主体 | 経路 | 認証 | 認可 |
|---|---|---|---|
| 利用者（**本人 1 名**。2026-10-23 に最大 2 人から変更: REQUIREMENTS §0） | ブラウザ → dashboard | 直接 IAP + **アプリ側の JWT 検証**（二重） | `ALLOWED_USERS` ∩ `app_user`。テナントとロールを解決し、未登録は 403（deny by default） |
| 管理者 | 同上 | 同上 | `ROLE_ADMIN`（`ADMIN_USERS` にも含まれること）+ CSRF + mode=NORMAL + 監査ログ |
| infra（ops SA） | GitHub Actions → `/internal/*` | IAP JWT（dashboard）/ Google ID トークン（ingest） | `ROLE_OPS` は読み取り専用で、テナントのデータには触れない |
| エージェント | Windows → ingest | トークン（ハッシュで保管）+ HMAC + nonce | 自テナントの `/v1/agent/*` だけ |
| Job・サービス | Cloud Run → GCP | 実行単位ごとのランタイム SA | 最小権限（CONTRACTS §11.3）、DB は用途別ユーザー（§8.2） |
| CI | GitHub Actions → GCP | WIF だけ（長期キーなし） | ワークフローとブランチで条件を付ける |
- 秘密は Secret Manager だけに置く。実データ（JV-Data、DB ダンプ）、モデル、秘密は Git、CI のログ、PR に含めない（D-030）。
- テスト・開発では**合成データ**を使う。**規約の回答が来るまで、実データをクラウドへ投入しない**（D-031）。

## 7. 運用モード（契約は CONTRACTS §5、手順は infra OPERATIONS_DESIGN）
`mode` は NORMAL / STARTING / STOPPING / STOPPED の 4 つ。`desired` は NORMAL / STOPPED。正本は GCS。アプリは `ModeProvider` を通して読み、15 秒キャッシュする。UNKNOWN は NORMAL として扱わない。STOPPED では DB を停止し、dashboard・ingest は ingress=internal、Scheduler は PAUSED。エージェントはビーコンで判別する。

## 8. フロントエンド・ML・開催日（要約）
- **UI**: Thymeleaf SSR + htmx + ECharts。日本語、モバイルファースト。旧 Streamlit の 8 画面と旧 `ui/` の Odds・Config を移す。`/api/v1` の DTO を用意し、Entity は公開しない（FRONTEND_DESIGN）。Performance の 3 系統は、統合せずに**定義を明示した 3 つの基準**として残す（D-059）。
- **ML**: 学習は Python の独立 Job、推論は Kotlin。特徴量は Kotlin の `FeatureBuilder` を正準とし、モデルは JSON（版・sha256・Ed25519）で pickle は禁止。確率パイプラインは `legacy`（旧挙動の再現・比較用）と `v2`（目標）。**v2 が精度で勝るとはまだ証明されていない。本番への自動切替はしない**（D-037・ML_DESIGN）。
- **開催日**: 判定は DB の有効予定だけで行う（曜日・祝日は使わない）。calendar-check と bot-run を分け、多重起動は UNIQUE とリースで防ぐ（RACE_CALENDAR_DESIGN）。
- **判定ロジックの欠陥候補 E1〜E15**: `MIGRATION_MATRIX.md` §8（T1.22）。承認されていない数値挙動の変更は、本番の仕様にしない。

## 9. 参照元からの主な設計変更
| 項目 | 参照元 | 新 |
|---|---|---|
| DB | SQLite + アプリ内の動的 migrate | MySQL + Flyway（独立 Job、起動時 migrate なし） |
| 常駐ループ | `run_daemon.py` の無限ポーリング | 開催日の有限 Job（calendar-check → bot-run） |
| 取得 | daemon 内の JV-Link 直呼び | Windows エージェント → ingest（生レコード、サーバ側でパース） |
| 設定 | YAML | 型付き設定（`@ConfigurationProperties`）+ 管理者操作で変える可変パラメータ |
| モデル | pickle | JSON + 署名 + レジストリ |
| UI | Streamlit（ローカル、認証なし） | dashboard（IAP + アプリ認可、テナント分離） |
| 停止 | プロセス停止 | 4 つのモードと GCS の正本、DB の停止 |

## 10. 検証ゲート（まだ実現済みとは扱わない）
一覧と実施する段階は `STAGE2_HANDOFF.md` §13 にまとめた。主なものは次のとおり。
- G-DB-0: MySQL 8.4/9.7 × Flyway 13.11.1 × Connector/J × Doma。
- G-DB-1: 東京で db-g1-small を選べるか、`max_connections`。
- G-DB-5: IAM DB 認証。
- G-DB-6: GRANT。
- G-DB-7: TLS 検証方式。
- G-COST-1: 東京の正式単価。
- G-OPS-1〜9。
- G-AGENT-1〜6: エージェントの PoC。
- G-ML-1〜3: H1 の実証、旧新比較、v2 のバックテスト。

## 11. ユーザーの承認・確認待ち（勝手に確定しない）
- T1.16（2026-10-23 に再スコープ。現行は UNRESOLVED.md の E-1〜E-3 / COMP-1〜4。以下は 2026-10-22 時点の記述）: JRA-VAN への問い合わせ Q1〜Q8。**2 人目の利用方法、クラウド保存、データの再利用、一般公開はどれも未確認**。個人契約のデータを 2 人で共有できるとは仮定しない（D-023・D-031）。
- T1.17: JV-Link の公式仕様書での確認。
- T1.20: 実 SQLite、`prob_source` の分布、実 `config.yaml`、`*.pkl`。
- T1.21: フロント案 A の承認、`legacy`/`v2`、E 項目の挙動変更。
- infra 側のユーザー確認: G-COST-1 と G-DB-1（作成画面を見るだけ）、GCP プロジェクトと請求、第5段階での apply の承認。

## T3.6-A（Stage 3-F1）で追加した ML の構成（2026-10-24）
- `:domain` の `jp.jrabot.domain.ml.*`: `json`（厳密パーサ・正準 JSON）/ `features`（スキーマ・ハッシュ・旧互換と point-in-time のビルダ・`FeatureSource` ポート）/ `model`（推論の算術・`ModelLoader`・`ModelTrust`・`VerifiedModel`）/ `pipeline`（`LegacyProbabilityPipeline`・`V2ProbabilityPipeline`・`FallbackReason`）/ `registry`（`ModelRegistryReader`・`ModelRunPin`・`ModelSession`）/ `store`（ローカル/メモリの実装）/ `dataset`（`DatasetExporter`）と `MlInference`。外部依存は JDK の標準（`java.security` の Ed25519・MD5・SHA-256）だけ。
- `:persistence`: `MlModelRegistryRepository`（`ml_model` の SELECT のみ。bot の権限で足りる）。一覧の表示用は `MlModelCatalogRepository`（`ModelCatalogReader`。dashboard の管理画面。ACTIVE を先頭に新しい順。D-268）。`FeatureSource`・`DatasetSource` の実装は T3.6-B。
- `:golden-runner`: 旧 `WinProbabilityModel` の互換モジュール `GoldenMl`（次元の切詰め/ゼロ埋め = 旧 H2 をここにだけ置く。本番の 3 アプリは `:golden-runner` に依存しない）。
- 流れ（bot-run。T3.8 で配線）: `ModelRunPin.capture`（ACTIVE を固定）→ `ModelSession`（`ModelStore` から読み、sha256・署名・スキーマ・次元を検証）→ `MlInference`（そのモデルのスキーマで特徴量を作り、検証済みモデルで確率）→ `ProbabilityPipeline`（正規化・ensemble・判定側ブレンド）→ `WinDecisionEvaluator`（`MlProbabilityInput` で確率を受け取る。EV・Kelly・買い目の計算は T3.1/T3.2 のまま）。
- ML 学習: `:ml-train`（Kotlin。`:domain`・`:persistence`・`:cloud-gcp` に依存。数値は Hipparchus）。旧 Python の `tools/ml`・`ml/` は 2026-11-02 に削除（D-257）。合成成果物（`domain/src/test/resources/ml`）は削除前の Python が生成したもので固定。
