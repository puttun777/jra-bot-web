# VERSIONS（T1.4 技術バージョン調査・選定）

確認日: **2026-10-22**（JST）。Alpha/Beta/RC/Snapshot/Milestone は採用しない。
**ステータス: 暫定選定（2026-10-22 の Stage 1-D② で MySQL を 8.4 に変更し、§6・§7 を追加）。** 版数の「最終確定」は T2.1 の疎通ビルド（Gradle + Kotlin + Boot + Doma + Flyway + Testcontainers が実際に通ること）で行い、その結果で本表を更新する。

## 0. 調査方法と制約（正直な記録）
- このクラウド環境から **docs.spring.io / doma.readthedocs.io / docs.gradle.org / dev.mysql.com / documentation.red-gate.com / api.adoptium.net / checkpoint-api.hashicorp.com** へは接続できなかった（DNS/プロキシで遮断）。
- そのため一次情報として次を使った: **Maven Central の `maven-metadata.xml`**（公式配布元）、各プロジェクトの **GitHub ソース/リリースページ**（README・docs ソース・CI 設定）、`services.gradle.org`（Gradle 公式 API）、`releases.hashicorp.com`、kotlinlang.org、npm registry。
- ベンダー公式ドキュメントに到達できず **検証が弱い項目は「要確認」と明記**した（§4）。推測で確定していない。

## 1. 選定サマリ
| 項目 | 採用(暫定) | 最新安定版 | 差異の理由 | 確認元（URL） |
|---|---|---|---|---|
| JDK | **Temurin 25 (LTS)**、現行 25.0.5+8（2026-10-22） | JDK 27 は存在するが非 LTS | LTS 優先。Spring Boot は 17〜27、Gradle/Kotlin は 25 で問題ない想定（要 T2.1）。次の LTS は 29（2027-09 予定、要確認） | https://github.com/adoptium/temurin25-binaries/releases ; https://github.com/spring-projects/spring-boot/blob/4.1.x/documentation/spring-boot-docs/src/docs/antora/modules/ROOT/pages/system-requirements.adoc |
| Kotlin | **2.4.21** | 2.4.21（2.5.0-Beta2 は不採用） | Boot 4.1.2 の BOM は 2.3.21 を管理。**意図的に上書き**（Doma 3.14.1 の CI が Kotlin 2.4.21 + kapt で通っているため）。T2.1 で疎通確認 | https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml ; https://github.com/domaframework/doma/blob/master/gradle/libs.versions.toml |
| Gradle | **9.7.1** | 9.8.2（2026-10-19） | Kotlin Gradle Plugin 2.4.20–2.4.21 の公式互換表が **Gradle 7.6.3〜9.7.0**。9.8.x は表の範囲外のため、範囲内の最新パッチ 9.7.1 を採用。Boot 4.1 は Gradle 8.14+ / 9.x 対応 | https://services.gradle.org/versions/all ; https://kotlinlang.org/docs/gradle-configure-project.html |
| Spring Boot | **4.1.2** | 4.1.2（4.2.0 は **RC1 = 2026-10-22 公開、GA 前**のため不採用） | 移行計画は §7（T1.12） | https://repo1.maven.org/maven2/org/springframework/boot/spring-boot/maven-metadata.xml |
| Spring Framework / Security / Thymeleaf | Boot BOM 管理: 7.0.10 / 7.1.2 / 3.1.5.RELEASE | — | Boot BOM に従う | https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.2/spring-boot-dependencies-4.1.2.pom |
| Doma (core/kotlin/processor) | **3.14.1** | 3.14.1（2026-10-04） | — | https://repo1.maven.org/maven2/org/seasar/doma/doma-core/maven-metadata.xml ; https://github.com/domaframework/doma/releases |
| doma-spring-boot-starter | **3.0.0** | 3.0.0 | 要件: Java 17+, Doma 3.x+, **Spring Boot 4.x+**。README 上の既定 Doma は 3.11.1 → 3.14.1 へ上書き（T2.4 で検証） | https://github.com/domaframework/doma-spring-boot ; https://repo1.maven.org/maven2/org/seasar/doma/boot/doma-spring-boot-starter/maven-metadata.xml |
| Doma アノテーション処理 | **kapt**（`kapt("org.seasar.doma:doma-processor")`）。§2 参照 | — | KSP は Doma 公式未対応 | §2 |
| Doma Gradle plugin | `org.domaframework.doma.compile` 4.0.3（README 記載） | 要確認 | プラグインポータルに到達できず README の値。T2.4 で確認 | https://github.com/domaframework/doma/blob/master/README.md |
| Flyway | **13.11.1**（`flyway-core` + `flyway-mysql`） | 13.11.1（2026-10-22） | Boot BOM は 12.4.0 だが、`:db-migration` は **Boot BOM に依存しない独立モジュール**（Flyway API を直接使用）なので最新を採用。クラスファイルは Java 17 ターゲット（jar を実測） | https://repo1.maven.org/maven2/org/flywaydb/flyway-core/maven-metadata.xml ; https://github.com/flyway/flyway/releases |
| MySQL サーバ | **8.4 LTS**（暫定の基準。D-046、infra I-013）/ 9.7 LTS は第2段階 G-DB-0 の後で再判断 | 9.7.0 LTS（GA 2026-04-21） | Flyway が検証済みと公表している版に 8.4 も 9.7 も無い（infra の調査）。9.7 は Cloud SQL での GA から日が浅く（2026-08-13）、**エディションを指定しないと Enterprise Plus が既定**になり費用に直結する。そのため 8.4 LTS を基準にし、SQL は 8.4/9.7 の共通機能に限る（旧 D-015 を置換） | https://blogs.oracle.com/mysql/mysql-9-7-0-lts-is-now-available-expanded-community-capabilities-and-dynamic-data-masking-for-enterprise ; https://docs.cloud.google.com/sql/docs/mysql/choose-edition |
| MySQL JDBC (mysql-connector-j) | **9.7.0**（Boot BOM 管理） | 26.10.0（2026-10-20） | 新しい採番 26.x の意味・サーバ対応表を一次情報で確認できなかったため、Boot が検証済みの 9.7.0 を採用。Testcontainers で 26.x も試験し、通れば引き上げ可（T2.4） | https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/maven-metadata.xml |
| Testcontainers | **2.0.5**（`testcontainers` + `testcontainers-mysql`） | 2.0.5 | Boot BOM と一致。2.x からモジュール名が `testcontainers-*` | https://repo1.maven.org/maven2/org/testcontainers/testcontainers-mysql/maven-metadata.xml |
| JUnit Jupiter | **6.0.3**（Boot BOM） | 6.1.3 | Boot 4.1.2 が検証済み。6.1.x へは T2.1 で通れば引き上げ | https://repo1.maven.org/maven2/org/junit/jupiter/junit-jupiter/maven-metadata.xml |
| MockK | **1.14.11** | 1.14.11 | — | https://repo1.maven.org/maven2/io/mockk/mockk/maven-metadata.xml |
| KSP（Kotlin Symbol Processing） | 使用しない | 2.3.12（symbol-processing-api） | Doma が非対応（§2）。他に必要なライブラリが出れば再検討 | https://repo1.maven.org/maven2/com/google/devtools/ksp/symbol-processing-api/maven-metadata.xml |
| Docker Engine | CI ランナー既定（GitHub-hosted の Docker）。参考: 最新 29.9.2 | 29.9.2（2026-10-20） | ランナー側で決まるため固定しない。ベースイメージは `eclipse-temurin:25-jre` を **digest 固定**（T2.5） | https://github.com/moby/moby/releases |
| Terraform | **1.17.0**（`required_version` で固定） | 1.17.0 | — | https://releases.hashicorp.com/terraform/ |
| Terraform Google provider | **~> 8.8**（lock file で固定） | 8.8.0 | メジャー 8 の破壊的変更は T5.1 で確認 | https://releases.hashicorp.com/terraform-provider-google/ |
| GitHub Actions | `actions/checkout` **v7.0.1**、`actions/setup-java` **v6.1.0**、`google-github-actions/auth` **v3.0.0**（Node 24）、`gradle/actions`(setup-gradle) **v6.4.0** | 同左 | **コミット SHA でピン留め**し Dependabot で更新（T2.5）。`upload-artifact` / `download-artifact` / `docker/*` / `setup-terraform` / `deploy-cloudrun` は未確認 → T2.5/T5.x | https://github.com/actions/checkout/releases ; https://github.com/actions/setup-java/releases ; https://github.com/google-github-actions/auth/releases ; https://github.com/gradle/actions/releases |
| 依存更新ボット | **Dependabot**（暫定） | — | GitHub 標準で追加インストール不要。Gradle Version Catalog / Docker / Terraform / Actions 対応。Boot BOM のグルーピング等が不足なら Renovate（要 App 導入＝ユーザー作業）へ | D-012 |

## 2. Doma と Kotlin（KSP の可否）
調査結果:
1. Doma 3.14.1 の README は Kotlin について **`doma-kotlin` + `kapt("org.seasar.doma:doma-processor:3.14.1")`** を公式手順としている（KSP の記載なし）。https://github.com/domaframework/doma/blob/master/README.md
2. Doma リポジトリのモジュールは `doma-core, doma-kotlin, doma-mock, doma-processor, doma-slf4j, doma-template` のみで **KSP 用モジュール/アーティファクトは存在しない**。Maven Central にも `doma-kotlin-ksp` は 404。https://github.com/domaframework/doma/blob/master/settings.gradle.kts
3. Doma 自身の Kotlin 統合テストは `kotlin-jvm` + `kotlin-kapt` プラグイン（Kotlin 2.4.21）。つまり **kapt + Kotlin 2.4.21 は Doma 側で継続的にテストされている**。https://github.com/domaframework/doma/blob/master/integration-test-kotlin/build.gradle.kts
4. docs `kotlin-support.md`: Kotlin 1.4.0 以降をサポート。`KQueryDsl`（Kotlin 専用 Criteria API）を DAO インターフェースより推奨、とある。https://github.com/domaframework/doma/blob/master/docs/kotlin-support.md
5. Kotlin 側: kapt のページに非推奨/廃止の明示はなく、KSP への移行ガイドがある（性能面の理由）。https://kotlinlang.org/docs/kapt.html , https://kotlinlang.org/docs/ksp-kapt-migration.html

**結論（D-009）**: Doma の公式サポート方式は **kapt**。KSP は採用しない（推測で対応を仮定しない）。リスクは kapt の将来廃止。緩和策:
- Doma/kapt を **`:persistence` に完全隔離**（domain に Doma 型を出さない既定の設計どおり）。
- 代替（公式サポート）: `:persistence` の DAO/Entity を **Java で記述し `annotationProcessor` を使う**（Doma の第一級サポート。Kotlin domain との変換層は既存設計のまま）。kapt が壊れた場合の退避路として T2.4 で小さな PoC を残す。
- `KQueryDsl` の採用可否は T2.4 で DAO(SQL テンプレート)との比較で判断（SQL 資産の可読性・Entity 非公開方針との相性）。

## 3. 互換性の確認結果
| 組合せ | 根拠 | 状態 |
|---|---|---|
| Boot 4.1.x × Java 17〜27 | Boot 4.1.x 公式 system-requirements | 確認済み |
| Boot 4.1.x × Gradle 8.14+/9.x | 同上 | 確認済み（採用 9.7.1） |
| Kotlin 2.4.21 × Gradle 9.7.x | Kotlin 公式互換表（Gradle 7.6.3–9.7.0） | 確認済み（9.7.1 は表の最大 9.7.0 の次パッチ。T2.1 で実走確認） |
| Kotlin 2.4.21 × Doma 3.14.1 (kapt) | Doma CI | 確認済み（Doma 側のテスト） |
| Kotlin 2.4.21 × Boot 4.1.2 | Boot BOM は Kotlin 2.3.21 | **未確認（要 T2.1）**。不具合時は 2.3.21 へ戻す（フォールバック） |
| Kotlin 2.4.x × JDK 25 | Kotlin 公式に最大 JDK の記載なし。Doma CI は最新 JDK で実行 | **未確認（要 T2.1）**。不具合時は JDK 21 LTS へ |
| doma-spring-boot-starter 3.0.0 × Boot 4.x | starter README: Boot 4.x+ | 確認済み（Doma 3.14.1 上書きは T2.4） |
| Flyway 13.11.1 × Java 17+ | jar 実測 class major=61 | 確認済み |
| Flyway 13.11.1 × MySQL 8.4/9.7 | 公式ドキュメントへ到達できず | **未確認**。Testcontainers（mysql:8.4, mysql:9.7）で T2.3 に検証 |
| Connector/J 9.7.0 × MySQL 9.7 | Boot BOM が 9.7.0 を管理 | 確認済み（BOM）。サーバ互換表は未確認 |
| Cloud SQL × MySQL 9.7/8.4 | Cloud SQL docs（検索結果経由） | 確認済み（エディション注意） |

## 4. 要確認・リスク
1. **Spring Boot 4.1 の OSS サポート期限**: 出典によって 2027-06〜07 と食い違う（公式の support ページへは未到達）。4.0.x は 2026-12-31 に終了。移行計画は §7。
2. Kotlin 2.4.21 を Boot 4.1.2 上で使う組合せ（上書き）は T2.1 のスモークで検証。
3. 公式ドキュメント未到達項目: Flyway×MySQL 対応表、Connector/J 26.x の位置づけ、Temurin 25 の公式サポート期間（第三者表記では 2031-09）、JDK 29 LTS 時期。
4. MySQL は 8.4 LTS を暫定の基準とする（D-046）。9.7 LTS は G-DB-0 で両方を試し、通れば第5段階の前に再判断する。Flyway V1 は両方で動く SQL に限る。
5. `mysql-connector-j` 26.x へ上げる場合は T2.4 のテストを通すこと。
6. 日付: GitHub のリリースページは年を表示しないものがある。年は 2026（今日が 2026-10-22）と前提し、Maven メタデータの lastUpdated で裏付けた項目を優先。

## 5. フロントエンド候補（T1.5 で案 A を採用推奨。ユーザー承認待ち: D-032 / T1.21）
| 案 | 構成 | 利点 | 欠点 | 主要ライブラリ（npm/BOM 確認値） |
|---|---|---|---|---|
| A | Spring MVC + **Thymeleaf + htmx** + 軽量 CSS + グラフは ECharts/Chart.js | 単一デプロイ、Node ビルド不要、IAP/認可を Spring に集約、CI が単純、スケールゼロで軽い | 高度な対話 UI は工数大 | Thymeleaf 3.1.5.RELEASE（Boot BOM）、htmx 2.0.11、Apache ECharts 6.1.0 または Chart.js 4.5.1、Pico CSS 2.1.1（任意） |
| B | **React 19 + Vite + TypeScript + Tailwind** の SPA（Spring は API のみ） | 表現力・拡張性、将来の一般公開向き | Node ビルド/依存管理/脆弱性対応が増える、CI・Docker 工程増 | React 19.3.1、Vite 8.3.5、TypeScript 7.0.2、Tailwind CSS 4.3.3、ECharts 6.1.0 |
初期所見: 現行 Streamlit 画面群（Overview/Race/Horse/Performance/Signals/Ops）は表・フィルタ・グラフ中心で、2 人利用・コスト優先の要件には **案 A** が合う見込み。ただし画面精査（T1.5）前なので確定しない。最終的に Node を使う場合は Node LTS 版を別途確認する。

## 6. 第2段階で必要な互換性の検証（T2.1/T2.3/T2.4 の完了条件）
| ID | 組合せ | 方法 | 合格条件 | 不合格時 |
|---|---|---|---|---|
| G-VER-1 | Kotlin 2.4.21 × Gradle 9.7.1 × JDK 25 × Boot 4.1.2 | T2.1 の雛形で `./gradlew build`（GitHub Actions とクラウドセッションの両方） | 全モジュールのコンパイル・テストが通る、警告が許容範囲 | Kotlin 2.3.21（BOM 値）→ それでも駄目なら JDK 21 |
| G-VER-2 | Doma 3.14.1（kapt）× Kotlin 2.4.21 × doma-spring-boot-starter 3.0.0 | `:persistence` の DAO 1 本 + Testcontainers | DAO が生成され、CRUD・UPSERT・複合キー・`@Version` が動く | Java で DAO を書く退避路（D-009） |
| G-DB-0 | MySQL **8.4** と **9.7** × Flyway 13.11.1 × Connector/J 9.7.0 | Testcontainers（`mysql:8.4`、`mysql:9.7`）で V1/V2/R__grants を適用し、統合テスト | 両方で全マイグレーションと DAO テストが通る | 9.7 は不採用のまま（8.4 で継続） |
| G-DB-0b | Connector/J 26.x | 同上で版だけ差し替え | 同上 | 9.7.0 のまま |
| G-VER-3 | Doma の `Instant`/`LocalDateTime`（UTC）・JSON 列・`INSERT … ON DUPLICATE KEY UPDATE` | `:persistence` の統合テスト | 変換が往復で一致する（UTC の固定） | `@Domain` の変換器、または SQL テンプレート |
| G-VER-4 | Thymeleaf 3.1.5 × Spring Security 7.1 × htmx 2.0.x | `:dashboard` のスパイク（MockMvc + Playwright） | CSRF・CSP の nonce・部分更新が動く | 第4段階の冒頭で再設計 |
| G-VER-5 | Testcontainers 2.0.5 × GitHub Actions の Docker | CI ジョブ | ubuntu ランナーで MySQL が起動する | — |
- クラウドセッションで Java、Gradle、Docker が使えない場合は、**結果を「未実施」と記録**し、GitHub Actions の結果を正とする（D-056）。

## 7. Spring Boot 4.2.x への移行計画（T1.12・D-053）
### 7.1 現状（2026-10-22 の確認）
| 項目 | 結果 | 根拠 |
|---|---|---|
| 4.2.0 の状態 | **GA 前**。M1（2026-08-20）、M2（2026-09-24）、**RC1（2026-10-22）**が Maven Central にある | https://repo1.maven.org/maven2/org/springframework/boot/spring-boot/ （ディレクトリの日付） |
| GA 予定日 | **未確認**（公式の予定ページへ到達できず、検索でも見つからない）。過去の GA は 4.0.0 が 2025-11-20、4.1.0 が 2026-06-10（どちらも RC1 から約 1〜2 か月後）。この実績から**推測すると 2026-11〜12** だが、確定情報としては扱わない | 同上、https://spring.io/blog/2026/08/20/spring-boot-4-2-0-M1-available-now/ |
| 4.2 の BOM（Kotlin・Flyway 等の管理版） | **未確認**（RC1 の BOM はダウンロードに失敗）。GA 後に確認する | — |
### 7.2 方針
1. **第2段階は 4.1.2（GA 済み）で始める**。RC は採用しない（規約: Alpha/Beta/RC 不可）。
2. 移行の着手条件: 4.2.0 が GA になる → **4.2.1 以降のパッチ**が出る（初期の不具合を避ける）→ 依存ライブラリが対応を表明している（doma-spring-boot-starter、Thymeleaf の組合せ、Testcontainers）。
3. 移行の期限: 4.1.x の OSS サポートが終わる**3 か月前**まで（終了日は公式の support ページで確認し、本表を更新する。現時点では 2027 年前半と推定され、未確認）。
4. 時期: 第2〜5段階の**段階の切れ目**で行う（実装の途中で混ぜない）。1 つの PR で Boot だけを上げ、他の依存は同時に上げない（BOM が管理するものは BOM に従う）。
### 7.3 手順（その時点のセッションで実施）
1. 4.2 のリリースノートと移行ガイドを確認する（非推奨の削除、設定プロパティの改名、`spring-boot-properties-migrator` で検出）。
2. BOM の差分（Kotlin、Spring Framework/Security、Thymeleaf、Flyway は `:db-migration` が独立なので対象外、Connector/J、Testcontainers、JUnit）を VERSIONS §1 に記録する。
3. Version Catalog の `spring-boot` を上げ、`./gradlew build` と全テスト（単体、Testcontainers、認可マトリクス、ゴールデン、Playwright）を GitHub Actions で通す。
4. Kotlin を BOM から意図的に上書きしているもの（D-013）を見直す。
5. ステージング相当の確認: Cloud Run の動作は第5段階以降。それより前は CI だけで判断する。
6. ロールバック: 移行 PR を revert すれば元に戻る（スキーマの変更を含めない）。
### 7.4 検知
Dependabot（D-012）が 4.2.x の PR を出したら、本計画に従って処理する。RC/M の PR は無視する（`ignore` 設定で除外: T2.5）。

## 8. T2.1 での実測結果（2026-10-22, Stage 2-A）
実際の依存解決とビルドで確認した事実。CI の結果は PROGRESS に記録する。
| 項目 | 結果 | 備考 |
|---|---|---|
| Gradle 9.7.1 | ビルド成功（配布物を `github.com/gradle/gradle-distributions` から取得し、`gradle wrapper` で Wrapper を生成） | `distributionSha256Sum=acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a`。**この値は公式配布元（downloads.gradle.org）が遮断されていたため GitHub 上の同一リリースから計算した**。公式 URL に対する Wrapper の検証は GitHub Actions が実行する（不一致なら CI が失敗する） |
| Kotlin 2.4.21 × Gradle 9.7.1 × Spring Boot 4.1.2 | **JDK 21 でビルド・テスト成功**（G-VER-1 のうち JDK 21 部分） | Boot 4.1.2 の BOM（Kotlin 2.3.21）を Kotlin 2.4.21 で上書きして問題なし。`build-logic` も Kotlin 2.4.21 でコンパイル（Gradle 同梱の Kotlin は 2.4.0）。警告をエラーにする設定（`allWarningsAsErrors`）でも通った |
| JDK 25 | **このセッションでは未確認**（JDK 25 が無く、取得経路が遮断: `api.adoptium.net`・`download.foojay.io` は接続拒否、GitHub のリリース資産は 403、apt に無い）。JDK 21 の成功を 25 の成功とは扱わない | G-ENV-1: クラウドセッションでの取得は**不可（2026-10-22 時点）**。JDK 25 の合否は GitHub Actions（`actions/setup-java` の Temurin 25）で確認する |
| `spring-boot-starter-webmvc` | Boot 4.1.2 に存在し解決できた（`dashboard`/`ingest`） | Boot 4 の starter 名（旧 `-web` ではない） |
| `ktlint-cli` 1.8.0（`all` jar を JavaExec） | Kotlin 2.4.21 のソースを検査できた | Gradle プラグインは互換性が未確認のため使わない |
| Maven Central の 429 | `repo.maven.apache.org` が IP 単位でレート制限（このセッション）。ローカルでは GCS ミラー（`maven-central.storage-download.googleapis.com`）を init script で先頭に置き、不足分は待って再試行 | ミラーは新しい版（Boot 4.1.2 の一部）が未同期。リポジトリには**ミラー設定を入れない**（CI は Maven Central） |
| 追加した依存 | `spring-boot-starter` / `-webmvc`（アプリ）、`spring-boot-starter-test`（アプリのテスト）、`kotlin-test-junit5`、`junit-jupiter`、`junit-platform-launcher`（版は Boot BOM）、ktlint-cli、`org.jetbrains.kotlin.plugin.spring` | 版の上書きは Kotlin のみ（既存の D-013） |
| GitHub Actions のピン留め（コミット SHA） | `actions/checkout` v7.0.1 = `3d3c42e5…`、`actions/setup-java` v6.1.0 = `b843ebf9…`、`gradle/actions` v6.4.0 = `3f5f9ada…`（注釈付きタグの指す commit） | `git ls-remote` で取得。`upload-artifact` 等は未使用 |

### 8.1 CI（GitHub Actions）での確認（PR #5, run 39826288899, 2026-10-22）
| 項目 | 結果 |
|---|---|
| JDK 25 | **成功**。Temurin 25.0.4.1+1-LTS で `./gradlew build`（Launcher JVM 25.0.4.1）。`JdkRuntimeTest`（`EXPECTED_JDK=25`）が合格し、テストの JVM が 25 であることを確認（G-ENV-1: GitHub Actions では取得可能） |
| Gradle Wrapper | 公式 URL（services.gradle.org）から 9.7.1 を取得し、`distributionSha256Sum` の検証を通過（§8 の「GitHub 上のリリースから計算した値」が公式配布物と一致したことになる） |
| Kotlin 2.4.21 × Gradle 9.7.1 × Boot 4.1.2 × JDK 25 | ビルド・テスト成功（**G-VER-1 合格**）。`allWarningsAsErrors` 有効 |
| 単体テスト | 合格 11 件・失敗 0（アプリ 3×2、domain 2、persistence/cloud-gcp/db-migration 各 1。build-logic の 13 件は別ビルドで実行され、ログには個別に出ないが `build` は成功） |
| `checkModuleDependencies` / `ktlintCheck` | 成功（7 モジュール） |
| repo-hygiene | 成功 |
| Docker / Testcontainers | **未実施**（対象のテストがまだ無い） |

## 9. T2.3 での実測結果（2026-10-23, Stage 2-C。G-DB-0）
GitHub Actions（`db-integration` ジョブ、ubuntu-24.04、Docker 28.0.4、Temurin 25、PR #7）で、`mysql:8.4` と `mysql:9.7` の使い捨てコンテナ（Testcontainers 2.0.5）に Flyway 13.11.1 + Connector/J 9.7.0 で V1/V2/`R__grants.sql` を適用して検証した。クラウドセッションでは Docker デーモンを起動できず実行していない。
| 組合せ | 結果 | 根拠 |
|---|---|---|
| MySQL 8.4 × Flyway 13.11.1 × Connector/J 9.7.0（JDK 25） | **合格**: 統合テスト 44 件（実行 44・スキップ 0・失敗 0） | run 39882827525（head 13ccf21）。イメージ `mysql:8.4` の digest `sha256:76f035…` |
| MySQL 9.7（9.7.4）× Flyway 13.11.1 × Connector/J 9.7.0（JDK 25） | **合格（D-069 の対処後）**: 同じ 44 件 | 同上。初回は 3 件失敗: ① `ml_model` の自己参照複合 FK が検査されない（D-069。DDL を変更して解消）、②③ は 8.4 と共通のテスト期待値の誤り（修正） |
| Flyway 13.11.1 の MySQL 対応 | 8.4・9.7.4 の両方で V1/V2/R__ の適用、validate、migrate の再実行、失敗版の記録が動作 | 同上 |
| Connector/J 26.x（G-DB-0b） | **未実施** | T2.4 で版だけ差し替えて検証する |
| Doma × MySQL（G-DB-0 の後半） | **未実施** | T2.4 |
| Cloud SQL 上の MySQL 8.4 / 9.7 | **未検証**（コンテナの成功を Cloud SQL の検証とは扱わない） | G-DB-1/5/6/7 は第5段階 |
- **MySQL の採否**: 8.4 LTS を暫定の基準のまま維持する（D-046）。9.7 は上記のとおり実行できたが、D-069 の挙動（自己参照 FK の未検査）の原因確認が済むまで採用を判断しない。
- Flyway 13.11.1 の community の jar にはテレメトリ送信の実装が無い（`NullFlywayTelemetryManager` のみ）ことを jar で確認した。
- Boot の BOM が管理する Flyway は 12.4.0 だが、`:db-migration` は 13.11.1 を直接指定（BOM は制約なので高い方が選ばれる）。
- テストで使った MySQL サーバの版: 9.7 は 9.7.4。8.4 のマイナー版はログに出していない（digest のみ記録）。

## 10. T2.4 での実測結果（2026-10-23, Stage 2-D。G-VER-2 / G-VER-3 / G-DB-0 の後半 / G-DB-0b）
GitHub Actions（`db-integration` 8.4 / 9.7、ubuntu-24.04、Temurin 25、PR #8）の結果は下表（run 39898663936、head 5730d70）。クラウドセッションでは Docker デーモンを起動でき（`dockerd`）、**MySQL 8.4.13 と Connector/J 9.7.0 / 26.10.0 の組合せをローカル（JDK 21）で先に確認**した（Docker Hub の 429 で `mysql:9.7` はローカルで取得できなかったため、9.7 は CI だけ）。
| ゲート | 内容 | 結果 |
|---|---|---|
| G-VER-2 | Doma 3.14.1（kapt）× Kotlin 2.4.21 × Spring Boot 4.1.2 × doma-spring-boot-starter 3.0.0 | **合格**: kapt が DAO（`*DaoImpl`）と Entity メタクラスを生成し、`allWarningsAsErrors` のままコンパイル・実行できた（ローカル JDK 21、CI は JDK 25）。starter の `DomaAutoConfiguration`（`Config` と `TransactionAwareDataSourceProxy` 相当）で Repository を Spring のトランザクションに参加させた。starter の既定 `dialect` は自前の方言で置き換え（`@ConditionalOnMissingBean`）。Java DAO への退避は**不要だった**（PoC なし）。Doma Gradle plugin は使わず、kapt の `doma.resources.dir` で SQL ファイルの存在を検査 |
| G-VER-3 | UTC 変換・JSON 列・UPSERT・楽観ロック・複合キー | **合格**: マイクロ秒の往復と切り捨て、5 つの JVM タイムゾーン × 夏時間の欠落時刻、`ON DUPLICATE KEY UPDATE`（行別名構文）の 1/2/0 件、`bot_run`/`race_schedule` の `version`、複合 PK・複合 FK、JSON 列（`bot_run.checkpoint`）。**Doma 標準の `Timestamp` 経由は使わない**（D-073） |
| G-DB-0（後半） | MySQL 8.4 / 9.7 × Doma の DAO テスト | **合格**: `:persistence:integrationTest` 86 件（11 クラス）を MySQL 8.4・9.7 の両方で実行し、スキップ 0・失敗 0（ジョブ内の検証ステップが件数・スキップ・失敗・期待クラスの欠落を検査）。同ジョブの `:db-migration` 統合 44 件も合格。Connector/J 9.7.0、Temurin 25。9.7 のマイナー版は今回ログに残していない |
| G-DB-0b | Connector/J 26.x | ローカル（MySQL 8.4.13 × 26.10.0、JDK 21）: 統合 86 件中 85 件合格・1 件は版文字列を比べる**テスト側の誤り**（修正済み）。CI の非ゲート確認ジョブ（Connector/J 26.10.0、Temurin 25）: **MySQL 8.4・9.7 の両方で 86 件すべて合格**（スキップ 0・失敗 0。26.10.0 が実際に使われたことはテストが版を検査）。ただし Flyway のスキーマ適用は :db-migration の 9.7.0 で行っており、26.x で確認したのは :persistence の DAO・接続だけ。**採否は未決**（26.x の位置づけ・サーバ対応表を一次情報で確認できていない。9.7.0 を既定のまま維持） |
- 版の固定: `doma = 3.14.1`、`doma-spring-boot = 3.0.0`（`gradle/libs.versions.toml`）。Hikari・spring-jdbc・Connector/J は Boot の BOM（Connector/J 9.7.0）。
- Connector/J の `useAffectedRows` は既定 false で、**この設定が無いと upsert の UNCHANGED/UPDATED を区別できない**（CONTRACTS §8.1 0.4.2）。26.x でも同じ挙動で動いた。
- MySQL 9.7 の採否は保留のまま（D-069 の原因確認が未了）。今回の成功だけで本番採用を決めない。

## 11. T2.7 での追加（2026-10-23, Stage 2-E）
| 依存 | 版 | 使うモジュール | 根拠 |
|---|---|---|---|
| `com.google.cloud:google-cloud-storage` | **2.75.0** | `:cloud-gcp` だけ | Maven Central の `maven-metadata.xml` の最新安定版（lastUpdated 2026-09-30）。`com.google.cloud:libraries-bom` の 26.90.0 は別の版番号体系なので取り違えない。BOM は使わず単体で固定（推移依存は Boot の BOM の制約に従う）。Alpha/Beta/RC ではない |
| `tools.jackson.core:jackson-databind` | Boot 4.1.2 の BOM（Jackson 3） | `:cloud-gcp` | `ops-state.json` の厳密な解析（重複キー検出・末尾トークン拒否）。`:domain` には入れない（`checkModuleDependencies` が強制） |
| `org.slf4j:slf4j-api` | Boot の BOM | `:cloud-gcp` | ログ。実装（Logback）はアプリ側 |
- 確認したこと: コンパイル・単体テストが JDK 21（ローカルのスモーク）で成功。JDK 25 は CI。SDK の挙動（404/403/401/500/遅延/接続拒否の変換、読取り（GET）だけを送ること）は**ローカルのフェイク HTTP サーバ**で確認した。**実 GCP（ADC・ランタイム SA・実バケット）は未確認**。
- 注意: ADC を取得できない環境では SDK は認証なしで要求する（非公開バケットは 401/403 → UNKNOWN）。Cloud Run 上の ADC によるメタデータサーバ経由の認証、実バケットの IAM（`storage.objects.get` のみ）は infra の実環境で確認する（第5段階の実環境検証。既存の G-OPS-1〜9 に「ModeProvider が実 GCS から ADC で読めること」は含まれていないので、infra の T5.0 で項目を足す）。

