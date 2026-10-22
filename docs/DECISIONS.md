# DECISIONS（判断記録）

| ID | 日付 | 判断 | 理由/備考 | 状態 |
|---|---|---|---|---|
| D-001 | 2026-10-22 | 作業ブランチ `claude/stage0-bootstrap` に push する。`main` は作成/変更しない | 実装先2リポジトリは**空**（コミット無し・main 無し）。PR には base ブランチが必要なため、初期 `main` をユーザーが用意するまで PR は作れない。「main へ直接 push 禁止」を優先 | 解消（main は Stage 0 コミットで初期化済み） |
| D-002 | 2026-10-22 | 単一リポジトリ単位でセッションを分ける（W と I は別セッション可）。本セッションは 3 リポジトリ全て clone 済みで両方へ push 可能だったため一括で実施 | 複数リポジトリ制約の確認結果は PROGRESS | 確定 |
| D-003 | 2026-10-22 | 要件の正本は W、インフラ固有は I、契約(CONTRACTS)の正本は W | 二重管理による乖離防止 | 確定 |
| D-004 | 2026-10-22 | 参照元は **Public**。内容は読み取りのみ。実装先に参照元のコードを大量複製しない（移植は再実装＋ゴールデンテストで検証） | ライセンスは MIT。JRA-VAN データ・モデルは含めない | 確定 |
| D-005 | 2026-10-22 | 運用モードの正本は DB ではなくインフラ側（DB 停止中も参照可能な場所） | STOPPED で DB が止まるため。詳細 T1.8 | 確定（T1.10: 正本=GCS `ops-state.json`, CONTRACTS §5.2） |
| D-006 | 2026-10-22 | 時刻保存方式（UTC vs JST）は第1段階で確定。表示/開催日判定は Asia/Tokyo | 参照元の扱いを精査してから | 確定（T1.10: UTC `DATETIME(6)` 保存・JST 表示, D-041） |
| D-007 | 2026-10-22 | 版数・Doma の Kotlin 処理方式（KSP 等）は第0段階で決めない | 公式情報で確認が必要（T1.4） | 解消（D-009 で kapt に決定） |
| D-008 | 2026-10-22 | ML モデルは pickle を引き継がない | 任意コード実行リスク。形式は T1.9 | 確定（D-035） |
| D-009 | 2026-10-22 | Doma の Kotlin アノテーション処理は **kapt**（公式手順）。KSP は不採用。Doma は `:persistence` に隔離し、kapt 不可時の退避路として Java 記述 DAO + `annotationProcessor` の PoC を T2.4 で残す | Doma README/リポジトリに KSP 対応なし、Doma CI は Kotlin 2.4.21 + kapt。詳細 VERSIONS §2 | 確定 |
| D-010 | 2026-10-22 | 版数は暫定選定（JDK Temurin 25 LTS / Kotlin 2.4.21 / Gradle 9.7.1 / Spring Boot 4.1.2 / Doma 3.14.1 / Flyway 13.11.1 / Connector/J 9.7.0 / Testcontainers 2.0.5 等）。**最終確定は T2.1 の疎通ビルド** | 一部公式 docs に到達できず（VERSIONS §0）。未確認組合せのフォールバック: Kotlin 2.3.21、JDK 21 | 暫定 |
| D-011 | 2026-10-22 | Gradle は最新 9.8.2 ではなく **9.7.1** | Kotlin Gradle Plugin 2.4.x の公式互換表が Gradle ≤9.7.0 | 暫定 |
| D-012 | 2026-10-22 | 依存更新ボットは Dependabot を暫定採用（Renovate は App 導入が必要＝ユーザー作業のため必要時に提案） | 追加設定不要 | 暫定 |
| D-013 | 2026-10-22 | Boot BOM 管理の依存（JUnit, mysql-connector-j, Testcontainers 等）は原則 BOM 値に従い、上書きは理由を VERSIONS に記録（Kotlin のみ意図的上書き）。`:db-migration` は Boot BOM 非依存で Flyway 最新 | 検証済み組合せ優先 | 確定 |
| D-014 | 2026-10-22 | Spring Boot 4.1.x の OSS サポートは 2027 年前半で終了見込み → 4.2.0 GA 後に 4.2.x へ移行する作業を第2〜5段階の合間に計画 | 出典間で終了日が不一致、公式 support ページは要確認 | 計画化（T1.12: VERSIONS §7, D-053） |
| D-015 | 2026-10-22 | MySQL は 9.7 LTS を暫定第一候補、8.4 LTS をフォールバック。Cloud SQL では **9.7 のエディション未指定 = Enterprise Plus 既定**のため Enterprise を明示する前提で T1.6 を評価 | 最新 LTS 優先 + 費用リスク | 置換（D-046: 8.4 LTS を暫定基準） |
| D-016 | 2026-10-22 | 設定の既定値は関数既定値でなく `config.example.yaml` を正としてゴールデンテストを作る（参照元で食い違いあり: ev_shrink_factor 0.7/0.55 等） | MIGRATION_MATRIX §4 | 確定 |
| D-018 | 2026-10-22 | Windows 取得エージェントは Python（第一段階）で**薄く**実装。64bit 版 JV-Link が使えれば 64bit、無ければ 32bit。ゲート=T3 で 64bit JV-Link + pywin32 の動作確認、不可なら C#/.NET へ。パース・業務ロジックは持たない | 参照元の COM 呼出し実績を流用、Windows 固有部分を最小化、言語を増やさない（JVLINK_DESIGN §3） | 案（G-AGENT-1〜6 の PoC 後に確定, T1.19） |
| D-019 | 2026-10-22 | エージェント→サーバは**生レコード（cp932 バイト）**転送、パースはサーバ（Kotlin）。生レコード保管は規約回答後に期間決定（既定案: 30 日） | パーサ 1 箇所・再解析可・エージェント更新頻度低（JVLINK_DESIGN §4.3） | 確定（設計）。生レコード保持期間は規約回答後 |
| D-020 | 2026-10-22 | ingest は dashboard と**別デプロイ（`:ingest` モジュール/サービス）**。直接 IAP は機械クライアントを通せないため IAP の外でトークン+HMAC 認証 | CONTRACTS §3.1。T1.10 で確定 | 確定（T1.10） |
| D-021 | 2026-10-22 | 取得計画は pull 型（サーバが plan を返す） | 開催変更・手動補正をサーバのみで反映、STOPPED で idle | 確定（設計） |
| D-022 | 2026-10-22 | チェックポイントは ACK 後のみ前進、`acked_ts` からオーバーラップ読み直し、サーバ冪等 | 参照元は JVOpen 成功時に保存し読込途中失敗で欠損し得た | 確定（設計） |
| D-023 | 2026-10-22 | 全データテーブルの主キーに `tenant_id`（=JRA-VAN 契約者）。2 人利用は契約者ごとに論理分離（DATA_SOURCE_COMPLIANCE 案 A） | 第7条: 他人は第三者。後付けは高コスト | 確定（設計）。規約回答（T1.16）で再確認 |
| D-024 | 2026-10-22 | 開催判定は DB の有効予定のみ（曜日・祝日不使用）。UNKNOWN と NO_RACE を区別 | 要件（単純な土日祝判定禁止）、RACE_CALENDAR_DESIGN §1 | 確定（設計） |
| D-025 | 2026-10-22 | 予定源=YSCH+RACE(RA)+0B14。0B14 は全置換前提でスナップショット差分。中止=データ区分9。延期/代替は自動検知を保証せず手動補正 | 検出能力の確度表（RACE_CALENDAR_DESIGN §0）。一次確認は T1.17 | 確定（設計）。一次確認は T1.17 |
| D-026 | 2026-10-22 | 二重実行防止=DB UNIQUE(active_slot)+リース、状態は DB から再導出（メモリ状態禁止） | 参照元 `result_fetched` 等の再起動問題 | 確定（設計） |
| D-027 | 2026-10-22 | calendar-check（軽量）と bot-run（本処理）を分離。Scheduler は固定間隔で起動するだけ、判断はアプリ内 | 要件、クラウド非依存 | 確定（設計） |
| D-028 | 2026-10-22 | クラウド依存は `JobLauncher/ModeProvider/Clock` ポートの背後へ隔離 | T1.7 未確定 | 確定（T1.10: GCP 実装は `:cloud-gcp`） |
| D-029 | 2026-10-22 | STOPPED 時のエージェント契約（CONTRACTS §7）: NORMAL 確認できない限り取得・送信しない。署名付きモードビーコンで STOPPED と通信障害を区別。無限リトライ禁止。stop=ビーコン最初/start=最後 | T1.15、infra 調査（desired 正本は DB 非依存） | 確定（CONTRACTS 0.4.0 §7。infra 同期済み） |
| D-030 | 2026-10-22 | 実データ（JV-Data/DB ダンプ）は Git・CI ログ・PR に含めない。ゴールデンテストは仕様書レイアウトから生成した合成レコード | DATA_SOURCE_COMPLIANCE #10 | 確定 |
| D-031 | 2026-10-22 | 一般公開は現契約では前提にしない（JRADB が必要）。技術的拡張性は維持。本番データ投入は規約回答（Q1〜Q3）後 | DATA_SOURCE_COMPLIANCE #8, §7 | 案（ユーザー承認待ち, T1.16） |
| D-032 | 2026-10-22 | フロントは **A: Spring Boot + Thymeleaf SSR + htmx + ECharts** を採用案。B（React+TS+Vite）は見直しゲート付きで保留。`/api/**`（DTO）を常に提供 | FRONTEND_DESIGN §4。読み取り中心・2 人利用・単一コンテナ・スケールゼロ・Kotlin 統一 | 案（採用推奨・ユーザー承認待ち, T1.21） |
| D-033 | 2026-10-22 | ML の互換性保証は「推論の算術」まで。旧モデルの予測品質は正解とみなさず、学習は時点整合（リーク是正）の v2 で再設計 | ML_DESIGN §2（H1〜H10: 市場フォールバックの疑い、alpha 次元ずれ、ラベルリーク、二重ブレンド等） | 確定（設計） |
| D-034 | 2026-10-22 | ML 方式 **A: 学習=Python 独立 Job / 推論=Kotlin**。特徴量は Kotlin に一本化しデータセットをエクスポート | 線形モデルで推論が単純、Python を実行経路から外す、学習は必要時のみ | 確定（設計） |
| D-035 | 2026-10-22 | モデル形式は JSON（`model_schema_version`、sha256、Ed25519 署名）。NPZ/ONNX は不採用/将来。pickle 禁止。pkl→JSON 変換は利用者ローカルの一回限りツール | ML_DESIGN §5 | 確定（設計） |
| D-036 | 2026-10-22 | モデルレジストリ `ml_model`（テナント単位）。bot-run は開始時にモデル固定、ensemble はセット単位で原子切替、**フォールバックは理由を記録しサイレント禁止**、パディング/スライスで次元を合わせない | ML_DESIGN §6 | 確定（設計）。H2 の旧挙動を再現しない点は T1.21 で承認 |
| D-037 | 2026-10-22 | 確率パイプラインは `legacy`（二重ブレンド含む挙動再現）と `v2`（単回ブレンド）を設定で切替。`v2` への切替は旧新比較と閾値再検討の後にユーザー判断 | ML_DESIGN §7.1。H4（モデル寄与 ≈ 0.0625） | 案（ユーザー判断待ち, T1.21）。本番初期値は legacy |
| D-038 | 2026-10-22 | データ移行は Python ツール `tools/migrate/`（staging→promote、チャンク再開、隔離、ロールバック）。まずローカル MySQL でリハーサル、クラウド投入は規約回答後 | DATA_MIGRATION_DESIGN §5・§12 | 確定（設計）。実ファイル未取得 |
| D-039 | 2026-10-22 | テナント方式 A（共有スキーマ + `tenant_id` 先頭 + 複合 FK + DAO 必須引数 + CI の SQL 検査 + 越境テスト）。共有表は `tenant/app_user/code_venue` のみ。JRA-VAN 由来のマスタもテナント表 | DATA_MIGRATION_DESIGN §4。規約（案 A） | 確定（設計）。規約回答で再確認 |
| D-040 | 2026-10-22 | レース識別を `race_id CHAR(12)`（+ `kai/nichime` NULL 可、`rt_key16` 導出）に統一。RACE_CALENDAR_DESIGN の `race_key CHAR(16)` を修正済み | 旧データに回・日目がない、開催は日・場で一意 | 確定（設計） |
| D-041 | 2026-10-22 | 時刻は UTC `DATETIME(6)`。移行時は形式（`T` 有無）で JST/UTC を判別、実ファイルで検証 | DATA_MIGRATION_DESIGN §1.2 | 確定（設計）。JST/UTC 判別は実ファイルで検証 |
| D-042 | 2026-10-22 | 旧 ID は `legacy_id` に保持し新 ID を採番（`UNIQUE(tenant_id, legacy_id)`） | 冪等・ロールバックの識別 | 確定（設計） |
| D-043 | 2026-10-22 | 保持ポリシー（DATA_MIGRATION_DESIGN §9.2）。combo オッズは既定で保存しない。保持ジョブはドライラン + 管理者承認（実データ削除は承認制） | 年 2.7 億行規模の回避 | 確定（設計）。保持期間の値は運用開始前に再確認 |
| D-044 | 2026-10-22 | 判定ロジックの旧挙動の欠陥候補 E1〜E15（MIGRATION_MATRIX §7.3）は、旧挙動をゴールデンに記録した上で、新仕様への変更は個別に承認を得て DECISIONS に記録 | 黙って挙動を変えない（既存機能を勝手に変えない） | 確定（方針）。個別の C 項目はユーザー承認待ち（MIGRATION_MATRIX §8, T1.21） |
| D-045 | 2026-10-22 | **T1.10 モジュール確定**: `:domain` `:persistence` `:db-migration` `:cloud-gcp`（新） `:bot` `:ingest` `:dashboard`。bot/ingest/dashboard は相互依存禁止（CI 強制）。`:cloud-gcp` はポートの GCP 実装（GCS ModeProvider/ModelStore/DatasetStore/HeartbeatMirror、Jobs JobLauncher、JWT 検証部品）を 1 箇所にまとめるため追加 | 3 アプリで同じ GCS/JWT 実装を重複させない、domain をクラウド SDK 非依存に保つ。`common` は作らない（ARCHITECTURE §2） | 確定（設計） |
| D-046 | 2026-10-22 | MySQL は **8.4 LTS を暫定基準**（D-015 を置換、infra I-013 と統一）。9.7 は G-DB-0 合格後に再判断。SQL は 8.4/9.7 共通機能に限定 | Flyway の検証済み版表に 8.4/9.7 が無い、9.7 の Cloud SQL GA が新しい、エディション既定の費用リスク | 暫定（G-DB-0） |
| D-047 | 2026-10-22 | **CONTRACTS 0.4.0**: infra 差異 D1〜D12 を統合。`phase` を廃止し `mode`（4 値）/`desired`（2 値）に統一、ビーコンに `schema_version`・`kid`、署名鍵は Secret Manager | 実装前のため互換フィールド不要 | 確定 |
| D-048 | 2026-10-22 | `ModeProvider` は GCS を 15 秒キャッシュ（成功時のみ）、失敗・不正は UNKNOWN（NORMAL 扱いしない）。**Job は UNKNOWN で exit 75**（非 NORMAL の確定値は exit 0） | 開催日に GCS 障害で黙って何もしない事態を通知で検知するため。infra OPERATIONS_DESIGN §2.4（UNKNOWN も exit 0）との差は引継ぎ I-H1 | 確定（infra 同期待ち） |
| D-049 | 2026-10-22 | bot イメージは 1 つで `--task=bot-run\|calendar-check\|reconcile\|retention\|export-dataset` を切替 | イメージ数・ビルド時間・脆弱性対応の削減。リソース設定は Job 定義側で個別 | 確定 |
| D-050 | 2026-10-22 | `db-migration --mode=info\|validate\|migrate`（必須・既定なし）。validate は 0/10（未適用あり）/1、DB 不達 75。アプリは `REQUIRED_SCHEMA_VERSION` 未満で ready=503/Job exit 1 | start 時に migrate せず検査だけ行うため（D6） | 確定 |
| D-051 | 2026-10-22 | DB は用途別ユーザー（migrate/app/ingest/bot/mltrain）、GRANT は web の `R__grants.sql`、ユーザー作成は infra。プール上限は最悪同時で合計 24（CONTRACTS §8.3）。TLS 必須（`VERIFY_CA` 第一案） | 最小権限、接続上限の明示（D8）。G-DB-1/5/6/7 で検証 | 確定（設計）/ 検証待ち |
| D-052 | 2026-10-22 | 内部 API 認証: dashboard=直接 IAP JWT（ES256, iss=`https://cloud.google.com/iap`, aud=`IAP_AUDIENCE`）で ops SA を `ROLE_OPS`（読み取りのみ）、ingest=Google ID トークン（iss=accounts.google.com, aud=サービス URL, email=ops SA）。エージェント API とは方式を混ぜない | D3。aud の形式は G-OPS-7 | 確定（設計） |
| D-053 | 2026-10-22 | **T1.12**: 第2段階は Boot 4.1.2 で開始。4.2.x へは GA かつ 4.2.1 以降・依存の対応確認後、4.1 OSS 終了の 3 か月前までに、段階の切れ目で単独 PR で移行（VERSIONS §7） | 4.2.0 は RC1（2026-10-22）で GA 前。RC は採用しない | 確定（計画） |
| D-054 | 2026-10-22 | **T1.19**: エージェント PoC は第3段階冒頭にユーザーの Windows PC で実施（G-AGENT-1〜6）。Claude はスクリプトと手順を用意し、集計値のみを記録。実データ・キー・ログ全文は扱わない。PoC の送信先はローカルのモックのみ | クラウドで実行不可、規約未確認（JVLINK_DESIGN §11） | 確定（計画） |
| D-055 | 2026-10-22 | **T1.22**: E1〜E15 を R（記録修正・承認不要）/ F（旧挙動を固定）/ C（数値変更案・既定 off・承認要）に分類。本番初期値は `pipeline_version=legacy`、`fixes.*=false` | ユーザー未承認の数値挙動変更を本番仕様にしない（MIGRATION_MATRIX §8） | 確定（方針）/ C 項目は承認待ち |
| D-056 | 2026-10-22 | 完了判定の正は GitHub Actions の CI 結果。クラウドセッションでの実行結果は補助。Java/Gradle/Docker が使えなければ「未実施」と記録し成功と書かない。Docker 依存テストは CI でスキップを失敗扱い | セッションごとに実行環境が異なる（2026-10-22 のセッション: JDK 21 のみ、Docker デーモンなし） | 確定 |
| D-057 | 2026-10-22 | web リポジトリに Python の `agent/`・`ml/`・`tools/migrate`・`tools/golden` を同居（Gradle 外、各自 pyproject + ロック） | 新規リポジトリを作らない、契約（スキーマ）を同じ PR で変更できる | 確定 |
| D-058 | 2026-10-22 | Flyway の範囲: V1=共有表 + 旧 15 表の再設計 + quarantine/audit_log、V2=開催/取込/ML、`R__grants.sql`。V1/V2 は最初の本番 migrate で凍結（それまでは PR 内で作り直し可） | ARCHITECTURE §5.1 | 確定（設計） |
| D-059 | 2026-10-22 | Performance の 3 系統（evaluations/bets/signals）は統合せず、定義を明示した 3 基準として維持。各基準は旧 `calculate_*` と一致。既定表示は evaluations | 旧機能を黙って変えない、集計定義の揺れを名前で区別 | 確定（設計） |
