# STAGE2_HANDOFF（第1段階 → 第2段階の引き継ぎ・2026-10-22）

この文書と GitHub のブランチだけで、どのセッション（スマートフォン、自宅 PC、外出先 PC から起動したクラウド実行）からでも再開できるようにする。会話の履歴には依存しない。設計の正本は各文書にあり、ここには**実装の進め方**だけを書く。
- 全体設計: `ARCHITECTURE.md`
- 契約: `CONTRACTS.md` 0.4.0
- 版数: `VERSIONS.md`

## 0. 前提の確認（第2段階を始める前に）
| 条件 | 状態（2026-10-22） | 根拠 |
|---|---|---|
| 第1段階の設計タスク T1.1〜T1.15・T1.18・T1.19・T1.22 が完了 | 本 PR がマージされれば完了 | PLAN.md |
| infra の第1段階（T1.6〜T1.8・T1.18）が main にある | 完了（infra PR #1, #2 マージ済み） | infra PLAN.md |
| ユーザー確認の T1.16・T1.17・T1.20・T1.21 | **未完了**（第2段階は**合成データだけ**で進められる。T3.x の一部はこれらに依存） | §11 |
- 第2段階の着手は、本 PR（Stage 1-D②）がマージされてから。マージの判断はユーザーが行う。

## 1. 実装順序（第2段階）
| 順 | タスク | 内容 | 依存 | 完了の根拠 |
|---|---|---|---|---|
| 1 | T2.1 | Gradle KTS マルチプロジェクトの雛形（§2 のモジュール）、Version Catalog、wrapper（9.7.1）、toolchain（JDK 25）、依存方向の検査タスク、ktlint/detekt（版は T2.1 で確認） | — | CI で `./gradlew build` が緑。G-VER-1 |
| 2 | T2.5（前半） | GitHub Actions の CI（§8）。T2.1 と同じ PR でもよい | T2.1 | PR に CI のチェックが付く |
| 3 | T2.2 | `:domain` の値オブジェクトとポート（`TenantId`・`RaceId`・`Odds`・`Probability`・`OpsMode`・`Clock`・`ModeProvider`・Repository） | T2.1 | 単体テスト（性質テストを含む） |
| 4 | T2.3 | `:db-migration`: V1/V2/`R__grants.sql`（ARCHITECTURE §5.1）と CLI `--mode` | T2.1 | G-DB-0（8.4 と 9.7 の Testcontainers）。CLI の終了コードのテスト |
| 5 | T2.4 | `:persistence`: Doma の DAO 数本（races、odds_snapshots、bot_run、app_user）、Entity⇔domain の変換、テナント SQL の静的検査、越境テスト | T2.2, T2.3 | G-VER-2・G-VER-3、越境テスト |
| 6 | T2.7（新） | `:cloud-gcp` の `ModeProvider`（GCS）を**エミュレータなしの単体テスト**で作る（HTTP をフェイクにする）。ローカル用のファイル実装 | T2.2 | CONTRACTS §5.2 の全規則をテスト |
| 7 | T2.8（新） | 各実行アプリの最小起動（`/internal/health`・`/internal/ready`、`--task` の振り分け、終了コード 0/75/2/1）。業務処理はまだ入れない | T2.4, T2.7 | CONTRACTS §4.3・§9 の契約テスト |
| 8 | T2.6 | ゴールデンテストの基盤（`tools/golden/`: 参照元を固定コミット f7dbf69 で実行し、合成入力 → JSON フィクスチャ） | T2.1 | フィクスチャの生成手順が README にあり、CI で読める |
| 9 | T2.5（後半） | イメージのビルド（push はしない）、`release-manifest.json` の生成（ドライラン）、Dependabot | T2.8 | CI の成果物に manifest が出る。**実装では digest をダミーにせず、プレビュー（`manifest-preview.json`、deployable=false）にした**（§14.7） |
- 各タスクは**小さい PR**（1 タスク = 1 PR が目安）にする。ブランチは `claude/stage2-<topic>`。

## 2. Gradle モジュール構成
`:domain` `:persistence` `:db-migration` `:cloud-gcp` `:bot` `:ingest` `:dashboard`。詳細は ARCHITECTURE §2、ディレクトリは §1.1。Python の `agent/`、`ml/`、`tools/` は Gradle の外に置く。

## 3. モジュールの依存方向
```
bot ─┐
ingest ├─▶ persistence ─▶ domain
dashboard ┘└─▶ cloud-gcp ─▶ domain
db-migration（単独）
```
- 禁止: 実行アプリどうし（bot/ingest/dashboard）の依存。`domain` から Spring・Doma・JDBC・クラウド SDK への依存。`persistence` の Entity をモジュールの外へ出すこと。
- 強制の仕方: ルートの `checkModuleDependencies` タスクを `check` に組み込む。さらに ArchUnit（版は T2.1 で確認）で、パッケージ単位の依存を検査する。

## 4. 開発環境の要件
| 項目 | 要件 | 備考 |
|---|---|---|
| JDK | Temurin 25（Gradle の toolchain で指定） | このクラウドセッション（2026-10-22）にあったのは **OpenJDK 21 だけ**。toolchain の自動取得（foojay/Adoptium の API）が**このネットワークでは遮断されている可能性**がある（VERSIONS §0）→ T2.1 で確認する |
| Gradle | wrapper 9.7.1（`gradle-wrapper.properties` で固定。`distributionSha256Sum` 付き） | システムの Gradle（8.14.3）は使わない |
| Docker | Testcontainers 用 | このクラウドセッションでは **Docker CLI はあるがデーモンが無い**（`/var/run/docker.sock` が無い） → DB の統合テストは GitHub Actions で実行する |
| Python | 3.12 以上（`tools/`、`ml/`、`agent/` の単体テスト） | セッションには 3.13 があった。版は T2.6 で固定する |
| ネットワーク | Maven Central、Gradle plugin portal、services.gradle.org | 一部の公式ドキュメントのドメインには到達できない（VERSIONS §0） |
- **セッションごとに使えるツールは違う可能性がある**。各作業の最初に `java -version`・`./gradlew --version`・`docker info` を実行し、結果を PROGRESS に記録する。**実行できなかったテストは「未実施」と書く**。成功したかのように書かない（D-056）。

## 5. クラウドの開発セッションで実行できるテスト（Docker なしでも可）
- `:domain` の単体テスト（JUnit 6 + 性質テスト）。ゴールデンテスト（JSON フィクスチャとの比較）。
- `:cloud-gcp` の単体テスト（HTTP をフェイクにする。実際の GCS には接続しない）。
- `:dashboard` の MockMvc とテンプレートのレンダリング、認可マトリクス（DB はフェイクの Repository）。
- `:db-migration` の SQL の静的検査（テナント条件・予約語・8.4/9.7 共通構文の lint）。
- Python: `tools/`・`ml/`・`agent/` の単体テスト（COM はフェイク）。
- ただし JDK 25 が入手できない場合は、Kotlin・Java のテストを**このセッションでは実行できない**。そのときは CI の結果を正とする。

## 6. Docker/MySQL を使うテスト（GitHub Actions で必ず実行する）
- `:db-migration`: `mysql:8.4` と `mysql:9.7` に V1/V2/R__grants を適用する（G-DB-0）。`validate` の終了コード（0/10/1）。
- `:persistence`: DAO、UPSERT、複合 FK、`@Version`、UTC の往復、越境テスト（テナント 2 つ）。
- `:ingest`・`:bot` の統合テスト（冪等、リース、二重起動の防止）。第3段階以降。
- テストには JUnit のタグ `@Tag("docker")` を付ける。Docker が無い環境では**スキップした件数を明示**し、CI では**スキップを失敗として扱う**（`-PrequireDocker=true`）。

## 7. クラウド環境では実行できない検証（ユーザー側・実リソース）
| ID | 内容 | 段階 | 実施者 |
|---|---|---|---|
| G-AGENT-1〜6 | Windows + JV-Link の PoC（JVLINK_DESIGN §11） | 第3段階の冒頭 | ユーザー（Claude はスクリプトを用意し、集計値を記録） |
| T1.17 | JV-Link の公式仕様書（SDK 同梱の PDF）の確認。**PDF は Git に入れない** | 第3段階の前 | ユーザー（結論だけを docs に） |
| T1.20 / G-ML-1 / G-ML-2 | 実 SQLite の集計、実データのリプレイ（ローカル MySQL。**クラウドに投入しない**） | 第3段階 | ユーザーの PC（Claude は SQL とスクリプトを用意） |
| G-COST-1 / G-DB-1 | 東京の単価、db-g1-small を選べるか、`max_connections`（作成画面を見るだけ。作成しない） | 第5段階の前 | ユーザー |
| G-OPS-1〜9, G-NET-1, G-DB-5/6/7（実環境の部分） | 実リソースでの検証 | 第5段階（**承認後**） | ユーザーの承認 → infra のセッション |

## 8. GitHub Actions による CI（T2.5。クラウドセッションの環境に依存しない再現性の正）
| ワークフロー | 契機 | ジョブ |
|---|---|---|
| `ci.yml` | PR、main への push | ① `build`: setup-java（Temurin 25）+ setup-gradle → `./gradlew check`（単体・lint・依存方向）。② `db`: ubuntu ランナーの Docker で Testcontainers（MySQL 8.4/9.7 のマトリクス）。③ `python`: `tools/`・`ml/`・`agent/` の単体テスト。④ `secrets-scan`: 秘密・実データっぽいファイルの検査（拡張子・サイズ・パターン） |
| `images.yml`（第2段階の後半。**実装済み**） | `ci.yml` からの呼び出し（Dockerfile・リリース・ビルド定義の変更時、フル検証時）、手動 | イメージのビルド・スモーク・`manifest-preview.json` の生成と検証（**push も認証もしない。push は第5段階でユーザーが WIF を承認するまで行わない**）。CI の実行範囲の決め方は `CI_AND_IMAGES.md` |
- Actions は**コミット SHA でピン留め**する（VERSIONS §1）。`permissions: contents: read` を既定にする。PR からフォークのシークレットは使わない（そもそもシークレットを置かない）。
- **CI の結果を完了判定の正とする**。クラウドセッションで実行した結果は補助として PROGRESS に記録する。

## 9. ソースコードの管理方式
- ブランチ: `claude/<stage>-<topic>` → main 向けの PR。**main へ直接 push しない。マージはユーザーが行う**。マージ済みのブランチを再利用しない。
- コミット: 意味のある単位（実装 + テスト）。作業の区切りごとに push する（セッションが切れても残るように）。
- Git に入れないもの: 秘密、実データ（JV-Data、DB ダンプ、実 SQLite）、モデル（`*.pkl`、実モデルの JSON）、ビルド生成物、JV-Link の仕様書 PDF。`.gitignore` と CI の検査で防ぐ。
- 依存の更新: Dependabot（D-012）。RC/M/Beta を除外する。Spring Boot 4.2 は VERSIONS §7 に従う。
- 参照元 `jra-bot` は読み取りだけ。コードは複製せず、再実装とゴールデンテストで検証する（D-004）。

## 10. 各段階の完了条件
| 段階 | 完了条件 |
|---|---|
| 2 基盤 | §1 の順序 1〜9 が main にマージされる。CI が緑（G-VER-1〜5、G-DB-0 の結果を VERSIONS に記録）。MySQL 9.7 の採否を判断する（D-046 の更新）。PROGRESS にテストの結果がある |
| 3 bot | T3.1〜T3.9。ゴールデン GD01〜GD10・G1〜G7 が合格。カレンダーのシナリオ C01〜C28。エージェント PoC（G-AGENT）の合格と D-018 の確定。E 項目の R/F が実装済みで、C は既定 off |
| 4 dashboard | T4.1〜T4.5。8 画面と Odds/Config。認可マトリクス・越境テスト・CSRF/CSP。Playwright でスマホ幅を確認 |
| 5 infra | infra の T5.1〜T5.6（**apply はユーザー承認後**）。G-OPS-1〜9、G-COST-1、G-DB-1/5/6/7、G-NET-1 |
| 6 統合 | 旧新比較（TEST_RESULTS.md）、セキュリティレビュー、OPERATIONS.md、CONTRACTS 1.0.0 で凍結、v2 の採否はユーザーが判断 |

## 11. 未解決事項（勝手に確定しない）
| # | 事項 | 担当 | 影響する段階 |
|---|---|---|---|
| 1 | JRA-VAN への問い合わせ Q1〜Q8。**2 人目の利用方法、クラウド保存、データの再利用、一般公開はどれも未確認**。個人契約のデータを 2 人で共有できるとは仮定しない。2 人目が自分で契約しているかどうか | ユーザー（T1.16） | 実データの投入（3・5・6）。テナント設計は前倒しで対応済み |
| 2 | JV-Link 公式仕様書での確認 | ユーザー（T1.17） | 3 |
| 3 | 実 SQLite、`prob_source` の分布、実 `config.yaml`、`*.pkl` | ユーザー（T1.20） | 3（ゴールデンは合成で先行できる） |
| 4 | フロント案 A の承認、`legacy`/`v2`、E 項目の C 分類（E6/E11/E12/E14/H10）、H2 の扱い | ユーザー（T1.21） | 3・4・6 |
| 5 | Spring Boot 4.2.0 の GA（未 GA） | 監視（VERSIONS §7） | 2〜5 の切れ目 |
| 6 | G-DB-0（MySQL 9.7 の採否）、G-VER-1〜5 | 第2段階 | 2 |
| 7 | 東京の単価、共有コアを選べるか、`max_connections` | ユーザー（G-COST-1/G-DB-1） | 5 |
| 8 | GCP プロジェクト、請求、WIF の承認、web → Artifact Registry の push 権限 | ユーザー | 5 |
| 9 | infra の文書の同期（PROGRESS「infra への引継ぎ」I-H1〜I-H7） | infra のセッション | 5 の前 |
| 10 | 生レコード（`ingest_raw`）の保持期間（既定案 30 日） | 規約の回答の後 | 3 |

## 12. 次のセッションでの再開方法
1. `CLAUDE.md` → `docs/PROGRESS.md`（現在の段階・次の作業・ブランチ）→ `docs/PLAN.md`（該当タスクと依存）を読む。
2. GitHub で main の最新と、open な PR を確認する（web と infra の両方）。マージ済みのブランチなら main から作り直す。
3. 前の段階の完了条件（§10）を満たしているか、PLAN のチェックと PROGRESS の根拠で確かめる。満たしていなければ、その未完了タスクを先に片付ける（飛ばさない）。
4. ツールの確認（§4）を行い、PROGRESS に記録する。
5. 今回のタスクだけを、小さい単位で「実装 → テスト → commit → push」する。区切りごとに PROGRESS を更新する（中断に備える）。
6. 終了時: テストの結果（未実施を含む）、PLAN のチェック（完了の根拠を併記）、DECISIONS、契約を変えたなら CONTRACTS と infra への引継ぎを記録する → push → PR を作る（マージしない）。

## 13. 検証ゲート一覧（実現済みとして扱わない）
| ID | 内容 | 段階 | 文書 |
|---|---|---|---|
| G-VER-1〜5 | 版の組合せ（Kotlin/Gradle/JDK/Boot、Doma kapt、時刻・JSON・UPSERT、Thymeleaf+Security+htmx、Actions の Docker） | 2（G-VER-4 は 4 の冒頭） | VERSIONS §6 |
| G-DB-0 / 0b | MySQL 8.4/9.7 × Flyway × Connector/J（9.7.0 / 26.x）× Doma | 2 | VERSIONS §6・§9。**T2.3: 8.4・9.7.4 × Flyway 13.11.1 × Connector/J 9.7.0 は CI で合格（D-069 の対処後）。26.x と Doma は T2.4** |
| G-DB-4 | 負荷の目安（オッズの書込・集計クエリ） | 2（ローカル MySQL） | infra PLAN |
| G-DB-5 | IAM DB 認証を JDBC で使えるか | 2（調査）/ 5（実環境） | CONTRACTS §8.1 |
| G-DB-6 | Cloud SQL のユーザーの既定権限と、`R__grants.sql` による REVOKE/GRANT | 2（MySQL で）/ 5（Cloud SQL で） | CONTRACTS §8.2。**MySQL コンテナ（8.4/9.7）では T2.3 で合格。Cloud SQL は未検証** |
| G-DB-7 | プライベート IP での TLS 検証（`VERIFY_CA`）か Java Connector か | 5 | CONTRACTS §8.1 |
| G-DB-1 / G-COST-1 | 東京で db-g1-small を選べるか、`max_connections`、正式な単価 | 5 の前（ユーザー） | infra CLOUD_COSTS §10 |
| G-OPS-1〜9 | ingress=internal、停止と起動、メンテナンス、cancel のシグナル、Jobs の上限 14h、CAS、IAP のプログラム通過・`aud` の形式、組織なし IAP、apply 前ガード | 5 | infra OPERATIONS_DESIGN §12 |
| G-NET-1 | Direct VPC egress + PSA | 5 | infra PLAN |
| G-AGENT-1〜6 | Windows エージェントの PoC | 3 の冒頭（ユーザー） | JVLINK_DESIGN §11 |
| G-ML-1〜3 | H1 の実証、実データのリプレイ、v2 のバックテスト | 3（ユーザーの PC、クラウドには入れない） | ML_DESIGN §10 |
| G-ENV-1 | クラウドセッションで JDK 25 を取得できるか（toolchain の自動取得） | 2 の冒頭 | §4 |
| G-AUTH-1 / G-APP-1 / G-JOB-1 | 内部 API の実 Google 鍵での署名検証、Cloud Run 上の health/ready、Jobs の引数と終了コード | 5 | §14.5（T2.8 で新設。**未実施**） |

## 14. `:domain` のポート一覧（T2.2 で実装済み。パッケージ `jp.jrabot.domain.*`）
Reader/Writer は分かれており、`XxxRepository` は両方を継承する。テナントを扱う操作の第一引数は `TenantId` か `TenantScoped`（`TenantBoundaryContractTest` が検査）。T2.4 以降の実装は同じ規則の越境テスト（テナント 2 つ）で検証する。
| ポート | 責務 | 入力 → 出力 | エラー | テナント | 冪等性 | 実装先 |
|---|---|---|---|---|---|---|
| `Clock` / `SystemClock` | 現在時刻（UTC の `Instant`、マイクロ秒）。`zone` の既定は Asia/Tokyo、`today()` は JST の暦日 | → `Instant` | なし | なし | 副作用なし | 本体 `SystemClock`。テスト用の固定時計は各テストに置く（2 モジュール目で必要になったら `testFixtures` へ） |
| `ModeProvider` | 運用モード（CONTRACTS §5.2）。キャッシュ・タイムアウト・`seq` 検査は実装 | → `OpsMode` | **投げない**。失敗は `UNKNOWN` | なし（全体で 1 つ） | 読取りのみ | `:cloud-gcp`（GCS）+ ローカル（ファイル）= T2.7 |
| `JobLauncher` | Job の実行を 1 つ作る | `JobLaunchRequest(tenantId, job, params)` → `LaunchResult`（Launched / Failed(transient)） | 失敗は結果で返す | `--tenant-id` を必ず渡す | **冪等でない**。二重実行はリース（D-026） | `:cloud-gcp`（Cloud Run Jobs）= T2.7 以降 |
| `ModelReader`/`ModelWriter`（`ModelStore`） | モデル JSON + `.sig`（不変） | `(TenantId, ModelId)` → `ModelArtifact?` / 書込み → `CREATED`/`UNCHANGED`/`CONFLICT` | I/O は `StorageException`（Job は exit 75） | パスは `TenantId`+`ModelId` だけから組み立てる | 同一内容なら `UNCHANGED` | `:cloud-gcp`（GCS）、メモリ/ファイル実装 = T3.6 周辺 |
| `DatasetReader`/`DatasetWriter`（`DatasetStore`） | gzip CSV（不変）。内容は解釈しない | `(TenantId, DatasetId, InputStream)` → `Created(descriptor{sha256,size})`/`AlreadyExists` | `StorageException` | 同上 | 既存なら上書きしない | 同上 |
| `Notifier` | 通知 1 件の配送（分割・429・Webhook は実装） | `Notification` → `DELIVERED`/`NOT_SENT`/`UNKNOWN` | 結果で返す。`UNKNOWN` は自動で再送しない（CONTRACTS §9.2） | SIGNAL はテナント必須 | 冪等でない。二重通知は `SignalWriter.claim` が防ぐ | `:bot`（Discord）= T3.x |
| `HeartbeatMirror` | エージェント最終接続を GCS に写す（時刻・ID・版だけ） | `(TenantId, AgentHeartbeat)` → `WRITTEN`/`SKIPPED`/`FAILED` | **投げない** | パスは `TenantId` だけから | 条件（5 分/版変更）を満たさなければ `SKIPPED` | `:cloud-gcp` = T3.4/T3.5 |
| `RaceReader`/`RaceWriter` | レース | `find`/`listByDate`、`upsert` → `UpsertOutcome` | — | 必須 | 自然キーの upsert | `:persistence` = T2.4 |
| `RaceScheduleReader`/`Writer` | 開催予定 | `find`/`listByDate`、`insertIfAbsent`、`update`（楽観ロック `UpdateResult`） | — | 必須 | `insertIfAbsent` は冪等 | `:persistence` = T2.4/T3.3（`race_schedule_event` の追記は T3.3 で拡張） |
| `OddsSnapshotReader`/`Writer` | オッズ（レース単位。全件取得なし） | `latest`/`history`、`upsert` | — | 必須 | 自然キーの upsert | `:persistence` = T2.4 |
| `SignalReader`/`Writer` | Signal と通知の claim | `insertIfAbsent`、`claim`→`markSent`/`markSendUnknown` | — | 必須 | claim は原子的。状態は逆戻りしない | `:persistence` = T2.4/T3.x |
| `BotRunReader`/`Writer` | 実行とリース | `findActive`、`tryStart`（Started/AlreadyActive）、`update`（楽観ロック） | — | 必須 | 二重起動は DB の UNIQUE が最終防壁 | `:persistence` = T2.4/T3.3 |
| `AppUserRepository`（読取りのみ） | 利用者 | `findByEmail`（唯一テナントを取らない 1 件の完全一致）、`listByTenant` | — | 例外は `findByEmail` のみ | 読取りのみ | `:persistence` = T2.4 |

### 14.1 T2.3 / T2.4 への引き継ぎ（DDL と domain の整合）
- `bot_run.state` の ENUM に **`INTERRUPTED` を追加**する（CONTRACTS §9.2。RACE_CALENDAR_DESIGN の DDL 案には無い）。
- `signals` に通知の状態（`UNSENT`/`CLAIMED`/`SENT`/`SEND_UNKNOWN`）を持たせる（`sent` の TINYINT を置き換える）。自然キーは `(tenant_id, race_id, market, umaban, observed_at)`（RACE_CALENDAR §6.3 の `cycle_id`・`combo` との関係は T2.3/T3.3 で確定）。`id` は `AUTO_INCREMENT`。
- `bot_run`・`race_schedule` に楽観ロック用の `version INT NOT NULL DEFAULT 0` を持たせる（domain の `version`）。
- `app_user` は `(tenant_id, email, role VIEWER|ADMIN, enabled)`。`email` は小文字に正規化して保存し、`findByEmail` が一意に引けること（D-062）。
- `race_id CHAR(12) ascii_bin`、`venue_code` は 01〜10、`race_no` は 1〜99、`umaban` は 1〜99（D-060）。`odds` は DOUBLE で 0 より大きい値のみ。
- 時刻は UTC `DATETIME(6)` ⇔ `Instant`（マイクロ秒に切り捨て済み）。変換は `:persistence` の中だけ。
- 実装は T2.4 で `XxxRepository` を実装し、**ポートの契約（冪等性・楽観ロック・テナント必須）を越境テストで検証**する。


### 14.2 T2.3 の結果と T2.4 への引き継ぎ（実装済みの DDL）
正本は `db-migration/src/main/resources/db/migration/`（`V1__baseline.sql` `V2__ops_calendar_ingest_ml.sql` `R__grants.sql`）。判断は DECISIONS D-063〜D-068。
- **§14.1 の反映**: `bot_run.state` に `INTERRUPTED`（8 状態＝domain の `BotRunState`）、`signals.notify_state`（4 値）、`bot_run`/`race_schedule`/`race_day` に `version INT NOT NULL DEFAULT 0`、`app_user(role VIEWER|ADMIN, enabled)`、`race_id CHAR(12) ascii_bin`（日付・場・R との一致を CHECK）、場コードは `code_venue` の FK（01〜10）、`race_no`/`umaban` は CHECK 1〜99、`odds > 0`（DOUBLE は NaN/Infinity を保存できない）、時刻は `DATETIME(6)`（UTC）。
- **T2.4 が守ること**
  - 全 SQL の `WHERE`/`INSERT` に `tenant_id`。子表の FK は `(tenant_id, 親キー)` なので、DAO が別テナントの親 ID を渡しても DB が拒否する（それでも越境テストを書く）。
  - `AppUserRepository.findByEmail`: `email` は小文字に正規化して保存・検索（`EmailAddress` が正規化済み）。DB は `utf8mb4_0900_bin`＝完全一致、**全体で一意**（D-063）。大文字を含む値の INSERT は CHECK で失敗する。
  - `SignalWriter.insertIfAbsent`: 自然キー `(tenant_id, race_id, market, umaban, observed_at)` の UNIQUE。`INSERT … ON DUPLICATE KEY UPDATE id = id`（既存の行を変えない）か、重複エラーの明示処理。`claim` は `UPDATE … SET notify_state='CLAIMED' WHERE tenant_id=? AND id=? AND notify_state='UNSENT'` の影響行数で判定（`notify_updated_at` は Clock の値）。`SENT`/`SEND_UNKNOWN` への遷移は `CLAIMED` からだけ。
  - `BotRunWriter.tryStart`: `INSERT`（`state` が実行中なら `active_slot=1`、それ以外は `NULL`。食い違いは CHECK が拒否）。`UNIQUE(tenant_id, run_date, active_slot)` の重複（1062）を `AlreadyActive` に変換し、`findActive` で `runId` を返す。`update` は `WHERE version = ?` + `version = version + 1`。`INTERRUPTED` など実行中でない状態にするときは **`active_slot = NULL` を同時に設定**（リース解放）。
  - `RaceScheduleWriter.update` も同じ楽観ロック。`race_schedule` は `races` への FK を持たない。
  - 時刻: `Instant` → UTC の `LocalDateTime`（マイクロ秒に切り捨て。DB は 7 桁目以降を丸める）。接続は `time_zone='+00:00'`（`FlywayEngine` と同じ `SET time_zone`）。
  - `odds_snapshots` の PK は `(tenant_id, race_id, market, observed_at, umaban)`（D-064）。`market` は ENUM（`Market.code`）。
  - ENUM と domain の列挙の一致（`BotRunState`・`NotificationState`・`AppUserRole`・`RaceScheduleStatus`・`Market`）は `:persistence` の契約テストで照合する（`:db-migration` は `:domain` に依存できない）。
  - 権限: dashboard=`app`、ingest=`ingest`、bot=`bot` の各接続ユーザーの範囲を超える SQL（例: bot が `races` を書く）は `R__grants.sql` で拒否される。DAO を作るときに必要な権限が足りなければ、**強い権限を足さず** `R__grants.sql` を最小限で変更し、`GrantsIntegrationTest` を更新する。
- **未確認のまま**: G-DB-6（Cloud SQL で migrate が GRANT/REVOKE できるか、新規ユーザーの既定権限）、G-DB-5/7、G-DB-1、G-DB-4（負荷）。CI の MySQL コンテナでの成功を Cloud SQL の検証とは扱わない。
- **Docker**: クラウドセッションでは Docker デーモンを起動できなかった（`dockerd` の起動はサンドボックスの判定で許可されなかったため試行を止めた）。DB 統合テストは GitHub Actions（`db-integration`）が正。

### 14.3 T2.4 の結果と T2.7 / T2.8 / T3.x への引き継ぎ（実装済みの `:persistence`）
- **実装**: 6 Repository（Race / OddsSnapshot / BotRun / AppUser / RaceSchedule / Signal）を domain のポートとして実装。Entity・DAO・SQL は `internal`。判断は DECISIONS D-070〜D-075。
- **T2.8（アプリ起動）が行うこと**: ① `DbSettings.fromEnv(env, defaultPoolMax)`（§8.3 の既定: dashboard 3 / ingest 3 / bot-run 4 / 他 1 / export 2）→ `DataSourceFactory.create(settings)` を Bean にする。② ドメインの `Clock`（`SystemClock`）を Bean にする。③ `@Import(PersistenceConfiguration::class)`。doma-spring-boot-starter は Boot の自動構成で効く（`doma.dialect` の設定は不要。`PersistenceConfiguration` が `dialect` を定義する）。④ DB ユーザーは実行単位ごと（dashboard=`app`、ingest=`ingest`、bot=`bot`）。**`app_user` を読めるのは `app` だけ**（bot / ingest は `findByEmail` を使えない）。⑤ `useAffectedRows` などの接続の固定値は `DataSourceFactory` が持つ。独自に DataSource を作るなら CONTRACTS §8.1 0.4.2 を守る。
- **スタブアプリの除外（T2.8 で外す）**: `:persistence` は starter-jdbc / doma-spring-boot-starter を実行時に持ち込むため、DataSource が無い最小アプリ（`BotApplication` `IngestApplication` `DashboardApplication`）の起動テストが `DataSourceAutoConfiguration` で失敗した（PR #8 の初回 CI で発覚）。3 つの `@SpringBootApplication(excludeName = …)` で `DataSourceAutoConfiguration` と `DomaAutoConfiguration` を除外している。**T2.8 で `DataSourceFactory` の DataSource を Bean にしたら、この除外を外す**（DataSource Bean があれば Boot の自動構成は引き下がる）。
- **呼び出し側の約束**: `BotRunWriter.tryStart` / `SignalWriter.insertIfAbsent` は、**長い REPEATABLE READ のトランザクションの中で呼ばない**（重複判定後に勝者の行を同じ接続で読むため。見えなければ `IllegalStateException`）。複数の Repository 呼び出しを 1 つのトランザクションにするときは、アプリケーションサービスの `@Transactional`（`PersistenceConfiguration` の `PlatformTransactionManager` に参加する）で囲む。
- **T3.3 が足すもの**: `race_schedule_event` の追記（`RaceScheduleRepositoryImpl` を拡張）、期限切れリースの奪取、`bot_run` の状態遷移の検証、デッドロック（1213）の再試行が必要ならここ。
- **未実装・未確認**: Connector/J 26.x の採否、MySQL 9.7 の採否、Cloud SQL（IAM 認証・TLS・`wait_timeout`・`max_connections`）、`odds_snapshots` の大量書込み性能（G-DB-4）、`combo_odds_snapshots` / `results` / `payouts` / `bets` などの Repository（必要になる Stage 3 で、同じ型（Entity + SQL + 越境テスト）で追加する）。
- **テストの足し方**: 新しい DAO の SQL は `tenant_id` を束縛で必須にする（`SqlTemplateTenantGuardTest`）。表を増やしたら `TenantSqlGuard.TENANT_TABLES` に足す。統合テストのクラスを増やしたら `ci.yml` の期待クラスの一覧に足す。

### 14.4 T2.7 の結果と T2.8 への引き継ぎ（実装済みの `:cloud-gcp` の ModeProvider）
- **実装**: `jp.jrabot.cloudgcp.ops` — `CachingModeProvider`（`ModeProvider` 実装。15 秒キャッシュ・5 秒の再試行間隔・seq 検査・単一飛行）、`OpsStateParser`（検証）、`OpsStateSource`（読取り口）の実装 `GcsOpsStateSource`（google-cloud-storage、読取りのみ）と `FileOpsStateSource`（ローカル）、`OpsModeProviders`（生成）。判断は DECISIONS D-076〜D-080。
- **T2.8 が行うこと**: ① `OpsModeProviders.fromEnvironment(System.getenv(), SystemClock)` を `ModeProvider` の Bean にする（`OPS_MODE_SOURCE`・`APP_ENV` を渡す）。設定不足で `OpsModeConfigurationException` が出たら**起動を失敗**させる（Job は終了コードの割当を CONTRACTS §9 に合わせる）。② Job（calendar-check / reconcile / retention / export-dataset / ml-train）は **DB に接続する前に** `current()` を呼び、`allowsWork` でなければ終了する（確定した非 NORMAL は exit 0 `MODE_<mode>`、UNKNOWN は exit 75: CONTRACTS §5.3）。③ bot-run は各サイクルの開始時に再確認し、UNKNOWN の連続回数（3 回で DRAINING）は呼び出し側で数える（`ModeProvider` は状態を持たない）。④ ingest は NORMAL 以外で DB に触れず 503（STARTING/STOPPING/STOPPED は `X-Ops-Mode`、UNKNOWN はヘッダなし）。⑤ ready の判定に `current()` を使うときは、取得が最大 3 秒かかり得ること（失敗直後 5 秒は即 UNKNOWN）に注意する。
- **呼び出し側の約束**: `current()` は例外を投げない（読取り・検証の失敗は UNKNOWN）が、実装の不具合は例外で伝播する。ブロックする（取得中は他の呼び出しも最大 3 秒待つ）ので、リクエストスレッドの多い箇所ではキャッシュ有効期間内の呼び出しが大半になるよう、1 リクエストで何度も呼ばず結果を使い回す。
- **ローカル実行**: `OPS_MODE_SOURCE=/path/to/ops-state.json APP_ENV=local`（合成データだけ。サンプルは `cloud-gcp/src/test` の `opsStateJson()`）。`APP_ENV` が `local` 以外でファイルを指定すると起動失敗。
- **未検証（実 GCP が要る）**: ADC（Cloud Run のランタイム SA）での認証、実バケットの IAM（読取りのみで足りること）、リージョン間のレイテンシ、SDK のリトライ設定が 3 秒の上限内で働くこと、メタデータサーバ不達時の挙動。単体テストの成功を実 GCP のアクセス成功とは扱わない。T2.8 の後 / T5 で infra と一緒に確認する（第5段階の実環境検証。既存の G-OPS-1〜9 に「ModeProvider が実 GCS から ADC で読めること」は含まれていないので、infra の T5.0 で項目を足す）。
- **未実装（範囲外）**: `ModelStore`・`DatasetStore`・`HeartbeatMirror`・`JobLauncher`・Google の ID トークン検証（`:cloud-gcp` の残り。必要になる段階で追加）、ヘルス/ready、bot-run の連続 UNKNOWN の数え上げ。

### 14.5 T2.8 の結果と T2.6 / Stage 3・4 への引き継ぎ（実装済みの 3 アプリの起動）
- **実装**: 3 アプリが独立して起動する。判断は DECISIONS D-081〜D-088、契約の補足は CONTRACTS 0.4.4。構成は次のとおり。
  | アプリ | 起動の仕組み | 内部 API / 入口 | 認証 | モードガード |
  |---|---|---|---|---|
  | `:ingest` | Spring Boot（Tomcat）。DataSource は遅延、`ModeProvider` は `OpsModeProviders.fromEnvironment` | `/internal/health` `/internal/ready` `/internal/ops/mode`（GET のみ） | Google ID トークン（RS256、aud=`INTERNAL_ID_TOKEN_AUDIENCE`、email=`OPS_SA_EMAIL`）。満たさなければ 401 | `/v1/agent` 配下（`/v1/agent/mode` を除く）: NORMAL 以外は 503 + `X-Ops-Mode`（UNKNOWN はヘッダなし）。ハンドラに到達しない |
  | `:dashboard` | 同上 | 同上 | IAP JWT（`X-Goog-IAP-JWT-Assertion`、ES256、aud=`IAP_AUDIENCE`、email=`OPS_SA_EMAIL`）。無効=401、ops 以外=403 | 全パスの書込みメソッド: NORMAL 以外は 409 `OPS_MODE_NOT_NORMAL` |
  | `:bot` | `main` → `BotLauncher`（引数 → 設定 → モード → NORMAL のみ Spring 起動 → スキーマ版 → タスク）。Web サーバなし | `--task=bot-run\|calendar-check\|reconcile\|retention\|export-dataset` `--tenant-id=<n>` | — | Job の開始時に `JobGate`（DB 接続の前） |
- **Bean 構成**（各アプリの `XxxConfiguration`）: `Clock`=`SystemClock`、`XxxSettings`（環境変数の検証）、`AppBuildInfo`（jar 焼き込み）、`DataSource`（`createLazy`）、`ModeProvider`（`InspectableModeProvider`）、`SchemaVersionVerifier`、(web) `InternalCallerVerifier`、`PersistenceConfiguration` を `@Import`（Repository 6 つと Doma の `Config`）。Flyway は無い。
- **環境変数**: 全アプリ `APP_ENV`（必須 local/prod）・`DB_URL`・`DB_USER`・`DB_PASSWORD`・`DB_POOL_MAX`（任意）・`OPS_MODE_SOURCE`・`RELEASE_ID`（任意）。dashboard は `IAP_AUDIENCE`・`OPS_SA_EMAIL`、ingest は `INTERNAL_ID_TOKEN_AUDIENCE`・`OPS_SA_EMAIL`。**本番で足りなければ起動失敗**（ローカル実装へフォールバックしない）。
- **ローカルで起動する手順**: 合成の `ops-state.json` を置き（`cloud-gcp/src/test` の `opsStateJson()` の形）、`APP_ENV=local OPS_MODE_SOURCE=/path/ops-state.json DB_URL=jdbc:mysql://127.0.0.1:3306/jrabot DB_USER=… DB_PASSWORD=…` を渡して `./gradlew :ingest:bootRun`。`/internal` は署名付きトークンが必要なので、手元から叩くには本番と同じ検証を通るトークンが要る（認証のバイパスは無い。テストは偽の JWKS を使う）。bot は `./gradlew :bot:bootRun --args="--task=calendar-check --tenant-id=1"`（`STOPPED` なら DB に触れず exit 0）。
- **テスト区分**: Docker 不要（`./gradlew build` の `test`）— bot 54 / ingest 28 / dashboard 30 / persistence 23 / cloud-gcp 91 / domain 214 / db-migration 57。Docker 必要（`integrationTest`、MySQL 8.4 と 9.7）— bot 8 / ingest 9 / dashboard 9 / persistence 98 / db-migration 44。CI の検証スクリプトは `.github/scripts/verify_test_results.py`（0 件・スキップ・失敗・期待クラスの欠落を失敗にする）。
- **T2.5 後半（Dockerfile・manifest）が使うもの**: bootJar の `jrabot-app.properties`（`-PreleaseId=<git sha>` で `releaseId`）。bot のイメージの ENTRYPOINT は `java -jar` で `--task=…` を args に渡す。
- **Stage 3 が足すもの**: 各 `--task` の業務処理（`StubTaskHandler` を差し替える。本番でスタブが exit 1 になる仕組みを外すのは実装が揃ってから）、bot-run のループ（`CycleGate` を接続）・checkpoint・リース解放・SIGTERM（CONTRACTS §9.2。**未実装・未検証**）、`/v1/agent/*` の本体（認証フィルタは `AgentApiOpsGuardFilter.ORDER`=20 より後）、`/v1/agent/mode`。
- **Stage 4 が足すもの**: 人の認証/認可（IAP JWT → `ALLOWED_USERS` + `app_user` + ロール）、管理操作（`AdminWriteGuardFilter.ORDER`=50 より前に認可を置く）、CSRF、画面。
- **新しい検証ゲート（未実施。完了扱いにしない）**
  | ID | 内容 | 段階 |
  |---|---|---|
  | G-AUTH-1 | 実 Google の公開鍵（IAP の JWK、Google の証明書）で、実際の IAP JWT／ID トークン（ops SA）が検証できること。鍵の取得の失敗時の挙動（現在は拒否＝401）、取得のタイムアウト（ライブラリの既定。上限は未設定）、直接 IAP の `aud` の形式（G-OPS-7 と同時） | 5 |
  | G-APP-1 | Cloud Run 上で `/internal/health` が DB 停止中も 200、`/internal/ready` が STARTING 中に DB を確認できること（start の S8）。Cloud SQL の IAM/TLS の下での `flyway_schema_history` の読取り（`app`/`ingest`/`bot` の SELECT）と `SchemaVersionVerifier` の分類（Connector/J の例外が実環境でも想定どおりか） | 5 |
  | G-JOB-1 | Cloud Run Jobs で `--task` の引数が渡ること、終了コード 0/75/2/1 が Scheduler/通知で意図どおり扱われること | 5 |

### 14.6 T2.6 の結果と T2.5 後半 / Stage 3 への引き継ぎ（ゴールデンテスト基盤）
- `tools/golden/`（Python、Gradle 外）。使い方・形式・未実装は `tools/golden/README.md`、判断は D-089〜D-092。CI ジョブ `golden tests (Python 3.12)`（既存 6 ジョブは不変）。
- **生成済み 10 ケース / 447 呼び出し**（固定コミット f7dbf69）。Kotlin 側は未実装で、`compare` の自己比較は比較器の検査にすぎない。
- **Stage 3 の入口**: Kotlin ランナーは `fixtures/*.json` の `input.calls` を読み、`adapter.CALLS` と同名の関数（calc_ev, calc_kelly_fraction, estimate_prob_normalized, hash_bucket, model_predict, check_odds_drop, evaluate_odds ほか）を実行して `{"schema_version":1,"results":{case_id:{results:[…]}}}` を出力 → `compare`。E5（odds=1.0 の ZeroDivisionError）等は「旧挙動」として記録されており、新仕様（`ODDS_AT_PAR` 等）の期待値は別ケースとして追加する。
- **未実装（catalog.py の planned / partial）**: ワイド・三連複の Plackett–Luce（E8〜E11）、hit_rate モード、max_total_bet_per_race 縮小（E6）、ML 例外ラベル（E1）、ensemble（G4）、decision 側ブレンド（G5）、合成 SQLite の特徴量ベクトル（G6）、時間帯スケジュール/adaptive_relaxation（run_daemon.py 側。時刻依存）、config.example.yaml 全キーの突合（T1.20）。
- **次**: T2.5 後半（Docker イメージ・Dependabot・release-manifest）。T2.6 では未着手。

### 14.7 T2.5 後半の結果と Stage 2 の完了判定 / Stage 3 への引き継ぎ（イメージ・manifest・Dependabot・CI 最適化）
- **実装**: `Dockerfile`（D-093）、`.dockerignore`（既定拒否）、`tools/release/`（manifest の生成・検証 CLI、スモーク）、`docs/schemas/release-manifest{,-preview}.v1.json`、`docs/release/rollback-verified.json`、`tools/ci/`（変更判定・ゲート）、`.github/workflows/{ci,images,compat-connectorj26}.yml`、`.github/dependabot.yml`。運用手順・CI の 14 ケースの表・無料枠の見積りは `docs/CI_AND_IMAGES.md`。結果の実測は PROGRESS の Stage 2-H。
- **分岐保護の更新（ユーザー作業）**: required checks を **`ci gate` だけ**にする。旧名（`build (Temurin 25)`・`repo hygiene (secrets / real data)`・`db integration (MySQL 8.4)` など）を required にしたままだと、docs だけの PR で該当ジョブが走らず `Pending` のまま残る（マトリクスのジョブは skipped のとき `${{ matrix.mysql }}` が展開されない名前で出る）。`repo hygiene` は `changes and repo hygiene` に統合した。
- **Stage 2 の完了判定（技術面）**: T2.1〜T2.8 は実装済み（PR #5〜#12）で、CI は緑（PROGRESS に run ID）。依存の互換性（Doma/Kotlin/Boot/Flyway/Connector/J 9.7.0、MySQL 8.4/9.7）と 4 イメージのビルド・スモークは CI で確認。**未完了・未検証として残る（Stage 2 の技術的な完了とは分けて扱う）**: 実 GCP での ModeProvider・内部 API 認証・Cloud Run Jobs の引数と終了コード（G-OPS-*, G-AUTH-1, G-APP-1, G-JOB-1）、Cloud SQL の実環境（G-DB-1/5/6/7）、MySQL 9.7 の採否（D-069）、Connector/J 26.x の採否、デッドロック 1213 の再試行、bot-run の checkpoint・SIGTERM、`mltrain` のスキーマ版確認方法、**ml-train イメージ（Stage 3）と本番 release-manifest（第5段階）**、T1.16・T1.17・T1.20・T1.21（ユーザー）、infra I-H1〜I-H9 と T5.0。
- **Stage 3 へ進める作業**（上のブロッカーに依存しない）: T3.1（EV/Kelly/確率/判定/急落フィルタの Kotlin 移植と `tools/golden` の比較）、T3.2（ワイド/三連複）、T3.3（開催日制御）、T3.4（取込 API）、T3.7（Discord 通知）、T3.6 の Kotlin 側（FeatureBuilder 等。実データを使わない範囲）。**ブロックされる作業**: T3.5・T3.6 の実データ部分・旧新比較の総合（T1.16/T1.17/T1.20/T1.21）、T3.0（実機）、第5段階全体（T5.0・infra の追従・ユーザーの GCP 承認）、本番 manifest（ml-train + push 後の digest + 検証済みロールバック範囲 + feature_schema_hash）。
- **Stage 3 が足すもの（イメージ関連）**: bot のスタブ差し替え時に `StubTaskHandler` の本番 exit 1 を外すのは実装が揃ってから（スモークの『prod は 0 にならない』検査も合わせて見直す）。`ml/` の Dockerfile ターゲット（`ml-train`）を追加し、`IMAGE_NAMES`/`BUILDABLE_IMAGES`（`tools/release/repo_facts.py`）・スモーク・`images.yml` を更新する。`feature_schema_hash`（ML_DESIGN §4）を定義し manifest に載せる。ロールバックの『旧イメージ × 新スキーマ』統合テストを作り `docs/release/rollback-verified.json` に記録する。
