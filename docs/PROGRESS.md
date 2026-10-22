# PROGRESS（jra-bot-web）

最終更新: 2026-10-22（第2段階 / Stage 2-A: T2.1・T2.5 前半・G-ENV-1・G-VER-1）

## Stage 2-A（claude/stage2-gradle-skeleton, PR #5）
### 結果
- **T2.1 完了**、**T2.5 は前半のみ完了**（DB 統合・イメージ・Dependabot・manifest は未着手）。**G-VER-1 合格**（Kotlin 2.4.21 × Gradle 9.7.1 × Boot 4.1.2 × JDK 25）。**G-ENV-1**: クラウドセッションでは JDK 25 を取得できなかった（Adoptium・foojay は接続拒否、GitHub のリリース資産は 403、apt に無い）。GitHub Actions では取得できる。
- CI（run 39826288899, head d52be01）: build (Temurin 25) 成功 / repo hygiene 成功。
### 実装したファイル
`settings.gradle.kts` `build.gradle.kts` `gradle.properties` `gradle/libs.versions.toml` `gradlew*` `gradle/wrapper/*`（sha256 付き）`.gitignore` `.editorconfig` `README.md`、`build-logic/`（`DependencyRules` `ModuleDependencyRulesPlugin` `KotlinModulePlugin` `SpringBootAppPlugin` `KtlintPlugin` とテスト）、7 モジュールの `build.gradle.kts` と最小のソース/テスト、`.github/workflows/ci.yml`、`.github/scripts/check-repo-hygiene.sh`。
### 確認した版
VERSIONS §8・§8.1。Gradle 9.7.1（Kotlin 組込 2.4.0）、Kotlin 2.4.21、Boot 4.1.2（`spring-boot-starter-webmvc`）、ktlint-cli 1.8.0、Temurin 25.0.4.1（CI）、OpenJDK 21.0.12（ローカルのスモークのみ）。Actions の SHA: checkout v7.0.1 / setup-java v6.1.0 / setup-gradle v6.4.0。
### 実行したコマンドとテスト
| コマンド | 環境 | 結果 |
|---|---|---|
| `gradle -PjdkVersion=21 build`（配布物 9.7.1 を直接実行、Maven ミラー用の init script は Git に入れていない） | ローカル JDK 21 | 成功（スモーク。JDK 25 の結果ではない） |
| 意図的に `:bot → :dashboard` を追加して `checkModuleDependencies` | ローカル | 失敗して違反を報告（確認後に元に戻した） |
| `.github/scripts/check-repo-hygiene.sh`（`*.pkl` と秘密鍵ヘッダを仕込んで） | ローカル | 検知して失敗、除去後は成功 |
| `./gradlew build` / `checkModuleDependencies ktlintCheck` | CI（JDK 25） | 成功 |
| Docker / Testcontainers | — | **未実施**（DB テストはまだ無い） |
### 互換性・環境の未確認事項
- 公式 Gradle 配布元（downloads.gradle.org）はこのセッションから遮断。Wrapper のチェックサムは GitHub 上のリリースから算出し、CI が公式 URL で検証して一致した。
- `repo.maven.apache.org` の 429（レート制限）。CI では発生しなかった。
- 非 UTF-8 ロケールではテストレポート生成が失敗する（`LC_ALL=C.UTF-8` で回避。README に記載）。
- Boot 4.2.0 は未 GA のまま。MySQL 8.4/9.7（G-DB-0）、Doma（G-VER-2）、時刻/JSON/UPSERT（G-VER-3）、Thymeleaf+Security+htmx（G-VER-4）、Testcontainers（G-VER-5）は後続タスクで検証（未実施）。
- 最小アプリは `@SpringBootTest` でコンテキスト起動のみ確認。health/ready・`--task` は T2.8。
### 次のタスク
**T2.2**（`:domain` の値オブジェクトとポート）。ブランチは `claude/stage2-domain-vo`。PR #5 のマージ後に main から作る。
### Git
ブランチ `claude/stage2-gradle-skeleton`、PR #5（main 向け、マージはユーザー）。

---
# 以前の記録（Stage 1-D②）

## 現在の段階（Stage 1-D② 時点の記録。現在は第2段階に入っている）
**第1段階は設計タスクが完了**。
- 完了: T1.1〜T1.15、T1.18、T1.19（計画）、T1.22。
- 未完了（ユーザー・実環境。**完了扱いにしない**）: T1.16（JRA-VAN への問い合わせ）、T1.17（公式仕様書）、T1.20（実 SQLite など）、T1.21（判断）。
- 次は**第2段階（基盤）**。実装順と完了条件は `STAGE2_HANDOFF.md`。

## 今回の完了（claude/stage1-finalize-web）
| タスク | 成果 | 完了の根拠 |
|---|---|---|
| T1.10 全体アーキテクチャ確定 | `ARCHITECTURE.md` を全面改訂。モジュールに `:cloud-gcp` を追加（D-045）、依存規則、レイヤ、実行単位（GCP）、データの流れ、Flyway V1/V2 の範囲（D-058）、セキュリティの主体表、検証ゲート、ユーザー承認待ちの一覧 | ARCHITECTURE §2〜§11 |
| CONTRACTS 0.4.0 | infra の差異 D1〜D12 をすべて解消（§12 の対応表）。`phase` を廃止、ModeProvider（§5.2・§5.3）、heartbeat の写し（§5.4）、db-migration の CLI（§4）、DB 接続・用途別ユーザー・プール合計 24（§8）、Job の終了コード・SIGTERM（§9）、release-manifest（§10）、内部 API の認証（§11） | CONTRACTS §12 |
| T1.12 Boot 4.2 移行計画 | 4.2.0 は **RC1（2026-10-22）で GA 前**。第2段階は 4.1.2 で始め、GA 後の 4.2.1 以降に段階の切れ目で移行する（VERSIONS §7、D-053） | Maven Central のディレクトリの日付 |
| T1.19 エージェント PoC 計画 | G-AGENT-1〜6、ユーザーの PC での手順、集計値だけを扱う方針（JVLINK_DESIGN §11、D-054）。**実機検証は未実施**（T3.0） | JVLINK_DESIGN §11 |
| T1.22 E 項目の新仕様 | E1〜E15 を R/F/C に分類。C（E6・E11・E12・E14、H10、H2）は既定 off で承認待ち。本番の初期値は `legacy`（MIGRATION_MATRIX §8、D-055） | MIGRATION_MATRIX §8 |
| 技術・DB・ML・UI の整合 | VERSIONS（MySQL 8.4 暫定、§6 互換性ゲート G-VER-1〜5・G-DB-0）。ML_DESIGN §10（D12、v2 は自動で切り替えない）。FRONTEND_DESIGN（D11、Performance の 3 基準 D-059）。RACE_CALENDAR_DESIGN §6.5（GCP 実装）。DATA_MIGRATION_DESIGN §3。DECISIONS（既存の状態を更新、D-045〜D-059） | 各文書 |
| Stage 2 への引き継ぎ | 新規 `STAGE2_HANDOFF.md`（実装順、モジュール、依存、環境、テストの区分、CI、管理方式、段階ごとの完了条件、未解決事項、再開方法、ゲート一覧） | — |

## テスト結果
- 該当なし（設計のみ。コードは書いていない）。参照元 `jra-bot` と infra は**読み取りだけ**で、変更していない。
- **このクラウドセッションのツール（2026-10-22 の記録）**: OpenJDK 21.0.12（JDK 25 は無い）、システムの Gradle 8.14.3、Python 3.13.16、Docker CLI はあるが**デーモンが無い**。→ 第2段階では JDK 25 の取得（G-ENV-1）を最初に確認する。Testcontainers は GitHub Actions で実行する（D-056）。
- 実 SQLite・実モデル・実 config は**提供されていない**ため、それらを使うテストは**未実施**（T1.20）。

## 判断（今回）
D-045〜D-059（DECISIONS）。主なもの:
- `:cloud-gcp` の追加。
- MySQL 8.4 暫定。
- UNKNOWN で exit 75。
- bot は 1 イメージで `--task`。
- 用途別 DB ユーザーと `R__grants`。
- Boot 4.2 の計画。
- E 項目の R/F/C 分類。
- CI の結果を正とする。

## infra への引継ぎ（infra のセッションで、infra の文書を CONTRACTS 0.4.0 に追従させる。今回は infra を変更していない）
| ID | 内容 | infra の該当箇所 |
|---|---|---|
| I-H1 | モード UNKNOWN の Job は **exit 75**（非 NORMAL の確定値は exit 0）。status と通知で 75 を異常として扱う | OPERATIONS_DESIGN §2.4 |
| I-H2 | `db-migration --mode=validate` の終了コード **10 = 未適用あり**（0=なし、1=不整合、75=DB 不達）。start の S5 は 10 を「保留版あり」と判定する | OPERATIONS_DESIGN §5 |
| I-H3 | release-manifest に `required_schema_version`、`rollback.min_app_release`、`feature_schema_hash`、`runtime` を追加。`contract_version` のメジャー版が一致しなければ deploy を拒否 | CONTRACTS §3（infra） |
| I-H4 | ビーコンに `schema_version`・`kid` を追加し、**`phase` を削除**。環境変数 `AGENT_HEARTBEAT_PREFIX`・`INTERNAL_ID_TOKEN_AUDIENCE`・`OPS_SA_EMAIL`・`DB_POOL_MAX`・`DISCORD_ERROR_WEBHOOK_URL`・`MODE_BEACON_PUBKEYS`（複数） | OPERATIONS_DESIGN §3、CONTRACTS §1（infra） |
| I-H5 | DB ユーザー名は `migrate`/`app`/`ingest`/`bot`/`mltrain`。Terraform はユーザーの作成だけを行い、GRANT は web の `R__grants.sql`（G-DB-6）。プール表の合計は retention・export-dataset を含めて 24 | ARCHITECTURE（infra）§DB、CONTRACTS D8 |
| I-H6 | calendar-check・reconcile・retention・export-dataset の Job は **bot イメージ + `--task=`**。ml-train の引数は `--tenant-id --dataset-id` | ARCHITECTURE（infra）Jobs |
| I-H7 | cancel の猶予時間について、アプリは 10 秒を前提にしている（G-OPS-4 で実測）。bot-run は NORMAL 中の cancel で exit 75 | OPERATIONS_DESIGN §6・§12 |

## 未完了・未解決（ユーザーの判断・確認）
1. **T1.16**: JRA-VAN への問い合わせ Q1〜Q8。**2 人目の利用方法、クラウド保存、データの再利用、一般公開は未確認のまま**。個人契約のデータを 2 人で共有できるとは仮定しない。2 人目が自分で契約しているかどうか。規約の回答が来るまで、実データをクラウドへ投入しない。
2. **T1.17**: JV-Link の公式仕様書の確認。
3. **T1.20**: 実 SQLite、`prob_source` の分布（H1）、実 `config.yaml`、`*.pkl`。
4. **T1.21**: フロント案 A の承認、`legacy`/`v2`、C 分類の項目（E6/E11/E12/E14/H10）と H2。
5. Spring Boot 4.2.0 の GA（未 GA。VERSIONS §7）。
6. 実リソースでの検証: G-COST-1/G-DB-1（ユーザーが作成画面で確認するだけ）、G-OPS-*、G-DB-5/6/7、G-NET-1（第5段階。**apply・課金リソースの作成はユーザーの承認後**）。
7. 公式ドキュメントに到達できず未確認のもの: 直接 IAP の `aud` の形式、Cloud Run Jobs の cancel 猶予、db-g1-small の `max_connections`。

## 次に実行する内容
- **第2段階**（本 PR のマージ後）。`STAGE2_HANDOFF.md` §1 の順序で進める。最初は T2.1 + T2.5（前半）で、ブランチは `claude/stage2-gradle-skeleton`。冒頭で G-ENV-1（JDK 25 の取得）を確認する。
- infra のセッション: I-H1〜I-H7 の追従（第5段階の前ならいつでもよい）。
- ユーザー: T1.16・T1.20・T1.21。

## Git
- ブランチ: `claude/stage1-finalize-web`（base: main fa5e59c）
- コミット: 80edaf4（CONTRACTS 0.4.0）、73a6e34（T1.10/T1.12/T1.19/T1.22）、本コミット（DECISIONS/PLAN/PROGRESS/STAGE2_HANDOFF）
- PR: push 後に作成（マージはユーザー）
