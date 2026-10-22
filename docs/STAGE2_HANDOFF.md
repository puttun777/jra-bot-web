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
| 9 | T2.5（後半） | イメージのビルド（push はしない）、`release-manifest.json` の生成（ドライラン）、Dependabot | T2.8 | CI の成果物に manifest（digest はダミー可）が出る |
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
| `images.yml`（第2段階の後半） | main への push、タグ | イメージのビルドと `release-manifest.json` の生成（**push は第5段階でユーザーが WIF を承認するまで行わない**） |
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
| G-DB-0 / 0b | MySQL 8.4/9.7 × Flyway × Connector/J（9.7.0 / 26.x）× Doma | 2 | VERSIONS §6 |
| G-DB-4 | 負荷の目安（オッズの書込・集計クエリ） | 2（ローカル MySQL） | infra PLAN |
| G-DB-5 | IAM DB 認証を JDBC で使えるか | 2（調査）/ 5（実環境） | CONTRACTS §8.1 |
| G-DB-6 | Cloud SQL のユーザーの既定権限と、`R__grants.sql` による REVOKE/GRANT | 2（MySQL で）/ 5（Cloud SQL で） | CONTRACTS §8.2 |
| G-DB-7 | プライベート IP での TLS 検証（`VERIFY_CA`）か Java Connector か | 5 | CONTRACTS §8.1 |
| G-DB-1 / G-COST-1 | 東京で db-g1-small を選べるか、`max_connections`、正式な単価 | 5 の前（ユーザー） | infra CLOUD_COSTS §10 |
| G-OPS-1〜9 | ingress=internal、停止と起動、メンテナンス、cancel のシグナル、Jobs の上限 14h、CAS、IAP のプログラム通過・`aud` の形式、組織なし IAP、apply 前ガード | 5 | infra OPERATIONS_DESIGN §12 |
| G-NET-1 | Direct VPC egress + PSA | 5 | infra PLAN |
| G-AGENT-1〜6 | Windows エージェントの PoC | 3 の冒頭（ユーザー） | JVLINK_DESIGN §11 |
| G-ML-1〜3 | H1 の実証、実データのリプレイ、v2 のバックテスト | 3（ユーザーの PC、クラウドには入れない） | ML_DESIGN §10 |
| G-ENV-1 | クラウドセッションで JDK 25 を取得できるか（toolchain の自動取得） | 2 の冒頭 | §4 |
