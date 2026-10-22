# ARCHITECTURE（暫定案・第1段階で確定）

## 1. リポジトリ責務
| リポジトリ | 責務 | 公開 |
|---|---|---|
| puttun777/jra-bot | 参照元（読み取り専用） | Public |
| puttun777/jra-bot-web | アプリ（Kotlin/Spring Boot）、Windows 取得エージェント、ML 学習/推論プロセス、コンテナイメージ定義、CI（ビルド/テスト/イメージ） | Private |
| puttun777/jra-bot-infra | Terraform、デプロイ/マイグレーション実行 workflow、起動/停止/状態確認、監視、ロールバック | Private |

## 2. Gradle モジュール（Gradle Kotlin DSL）
```
:domain        業務モデル・値オブジェクト・計算/判定ロジック・Repository interface
               (Doma/Spring/クラウドSDK 非依存。純 Kotlin)
:persistence   Doma DAO/SQL/DB Entity、Repository 実装、Entity⇔domain 変換
:db-migration  Flyway スキーマ管理。独立実行可能（デプロイ時に一度だけ実行する Job 用）
:bot           開催日制御(calendar-check)、オッズ監視、分析、推論呼び出し、Discord 通知、決済、reconcile（データ取込は :ingest へ移管）
:ingest        (T1.2 追加案 D-020) エージェントからの生レコード受信・認証・冪等・JV-Data パース・DB upsert。dashboard とは別デプロイ(IAP の外)
:dashboard     Web UI、API、認証・認可、成績/履歴表示
```
依存方向: `bot → domain, persistence` / `ingest → domain, persistence` / `dashboard → domain, persistence` / `persistence → domain` / `db-migration → (なし。SQL のみ)`。**bot / dashboard / ingest は互いに依存禁止**（Gradle の依存検査で CI 強制）。
common モジュールは作らない。共有が必要なら責務名のモジュールを新設し DECISIONS に理由を残す。共通ライブラリは通常の JAR（Spring Boot の fat jar 化は bot/dashboard のみ）。

## 3. 実行単位（デプロイ単位）
| 単位 | 種別 | 起動契機 | 備考 |
|---|---|---|---|
| dashboard | 常時 Web（スケールゼロ可） | HTTP | ステートレス。STOPPED で新規受付停止 |
| bot-scheduler-check | 軽量 Job | 日次/定時（Scheduler） | 開催予定確認のみ。開催なしなら即終了。bot 本処理を起動するか判断 |
| bot-run | Job | 開催日の必要時間帯のみ | 結果反映完了後に終了。多重起動防止・再開 |
| db-migrate | Job | デプロイ時 1 回 | Flyway。アプリ起動時には実行しない |
| ml-train / ml-infer | Python 別プロセス（維持時） | 学習=手動/定期、推論=bot から | 学習と推論を分離。モデルは安全な形式（pickle 不使用）で保存 |
| ingest | 常時 Web（スケールゼロ可・IAP の外） | エージェントの HTTPS | 認証(トークン+HMAC)、冪等、DB 停止/STOPPED 時は 503。dashboard と別サービス |
| jvlink-agent | Windows 常駐（ユーザー PC・Python 暫定） | サーバの plan に従う | JV-Link COM(64bit 版が使えれば 64bit) から**生レコード**取得 → ingest へ送信。STOPPED/不明では取得も送信もしない。詳細 `JVLINK_DESIGN.md`、契約 `CONTRACTS.md` §7 |

## 4. データ
- 参照元 SQLite 13+2 テーブルを MySQL（第一候補）へ。Flyway は V1 ベースラインを参照元 `schema.sql` から再設計（型/インデックス/一意制約、冪等 upsert キー）。
- 時刻は DB では UTC もしくは明示 TZ 付きで保存、表示/開催日判定は Asia/Tokyo（D-006 は第1段階で確定）。
- 開催カレンダー/発走時刻は専用テーブル（変更履歴・手動補正・取得失敗状態を保持）。bot は「実開催日・発走予定」だけを根拠に動作。
- 冪等性: 取込は自然キー（race_id, 時刻, データ種別）で upsert。bot 実行は run_id + ロック（DB 行ロック or リース）で多重起動防止、進捗チェックポイントで再開。

## 5. セキュリティ構成（第一候補）
- 利用者: Cloud Run 直接 IAP + 許可リスト（最大2人）。アプリ側でも IAP の JWT 検証と許可リスト再検証（二重防御）。初期未設定は全拒否。
- 管理者操作: 管理者ロール限定。機械連携（エージェント/CI/Scheduler）は個別のサービスアカウントまたは署名付きトークンで分離。
- CI→クラウド: GitHub OIDC + Workload Identity Federation。長期鍵は保存しない。
- 秘密: Secret Manager。Discord Webhook 等はコード/設定に置かない。

## 6. 参照元からの主な設計変更
| 項目 | 参照元 | 新 |
|---|---|---|
| DB | SQLite + アプリ内動的 migrate | MySQL + Flyway（独立 Job） |
| 常駐ループ | `run_daemon.py` の無限ポーリング | 開催日の Job（必要時間帯のみ）。内部ループは bot-run 内の有限スケジューラ |
| 設定 | YAML | 型付き設定（Spring `@ConfigurationProperties`）+ DB の可変パラメータは管理者操作 |
| モデル保存 | pickle | 非 pickle（JSON/NPZ 等、第1段階で決定） |
| UI | Streamlit（ローカル、PID/ログ参照） | Web（第1段階で選定）、ログ参照は監視基盤へ |

## 7. T1.2 / T1.3 / T1.15 による設計追加（2026-10-22）
### 7.1 取込経路
```
JV-Link(COM) → agent(薄い: 生レコード+キュー+ACK) →(HTTPS, トークン+HMAC)→ ingest → parser(:domain) → DB(race_schedule, odds, results…)
                                  ↑ plan / mode / ビーコン                      ↑ bot / dashboard は DB を読むだけ
```
- パースはサーバ（Kotlin）に集約。実データはリポジトリに置かず、合成レコードでゴールデンテスト（DATA_SOURCE_COMPLIANCE §5）。
- 開催カレンダーは DB が正（`race_day` / `race_schedule` / `race_schedule_event` / `calendar_override` / `bot_run`）。DDL 案は `RACE_CALENDAR_DESIGN.md` §4。
### 7.2 テナント境界（D-023）
全データテーブルの主キーに `tenant_id`（=JRA-VAN 契約者）を含め、認可は常にテナント境界で行う。2 人利用では契約者ごとに論理分離（DATA_SOURCE_COMPLIANCE 案 A）。
### 7.3 bot の実行
Scheduler(固定間隔) → `app --task=calendar-check`(軽量) → 条件成立で `JobLauncher` → `bot-run`(有限ジョブ)。二重実行は DB UNIQUE+リース、状態は DB から再導出。クラウド依存は `JobLauncher/ModeProvider/Clock` のポートに隔離(T1.7 未確定)。詳細 `RACE_CALENDAR_DESIGN.md` §6。
### 7.4 STOPPED 時のエージェント
モードビーコン(署名付き静的 JSON)で STOPPED と通信障害を区別。NORMAL が確認できない限り取得・送信しない。詳細 `CONTRACTS.md` §7。

## 8. T1.5 / T1.9 / T1.11 による設計追加（2026-10-22）
### 8.1 フロントエンド（`FRONTEND_DESIGN.md`）
`dashboard` = Spring Boot + **Thymeleaf SSR + htmx + ECharts**（Node ビルドなし、1 コンテナ、ステートレス）。`/api/**`（DTO）を常に提供し将来 SPA 化の余地を残す。全ページ共通の状態バー（運用モード・bot・最終更新・次回実行予定・取得失敗）。認可は IAP の JWT をアプリ側でも検証し `app_user` でテナント/ロールを解決、**未登録は 403**。旧 Streamlit の 8 画面（Overview / Race Detail / Horse Detail / Performance / Signals / Ops-Health / Model / Evaluations）と旧 `ui/` の Odds・Config を全て移行（Ops は PID/ログ中心からクラウド向けに再設計）。
### 8.2 ML（`ML_DESIGN.md`）
**学習=Python（独立 Job `ml-train`、常駐しない）、推論=Kotlin（bot 内、`ProbabilityPipeline`）**。特徴量生成は Kotlin の `FeatureBuilder` に一本化し、学習用データセットは Kotlin がエクスポート（時点整合でリークを構造的に排除）。モデルは **JSON（スキーマ版・sha256・Ed25519 署名）**、pickle は持ち込まない。`ml_model` レジストリ（テナント単位、run 開始時に固定、ensemble はセットで原子的切替、フォールバックは明示）。旧 ML の疑義（H1〜H10）により、互換性は「推論の算術」で保証し、学習は修正版（v2）で再設計。
```
bot(JVM) ── ModelStore ──▶ ml_model(ACTIVE) ─▶ model JSON  ◀── ml-train(Python Job) ◀── export-dataset(Kotlin) ◀── DB
```
### 8.3 データ（`DATA_MIGRATION_DESIGN.md`）
MySQL は **共有スキーマ + `tenant_id` 先頭 + 複合 FK**（方式 A）。レース識別は `race_id CHAR(12)`（+ `kai/nichime` NULL 可）。時刻は UTC `DATETIME(6)`。SQLite からの移行は Python ツール（`tools/migrate/`）で preflight→extract→transform→staging→verify→promote、チャンク再開・隔離・ロールバック。実ファイル取得後に、まずローカル MySQL でリハーサル（クラウドは規約回答後）。
### 8.4 実行単位の更新
| 単位 | 追加/変更 |
|---|---|
| `bot` | `--task=export-dataset`（学習用データセット出力、管理者/Scheduler 手動）を追加 |
| `ml-train`（新） | Python Job（独立イメージ）。NORMAL のみ実行。結果は `ml_model` に CANDIDATE 登録 |
| `tools/migrate`（新） | 移行専用ツール（デプロイ対象外、利用者 PC / CI で実行） |
