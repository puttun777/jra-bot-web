# jra-bot-web

JRA Bot（参照元 `puttun777/jra-bot`）を Kotlin / Spring Boot の Web アプリへ段階的にリプレースするリポジトリ（Private）。
設計・進捗・判断は `docs/` が正本。最初に `CLAUDE.md` → `docs/PROGRESS.md` → `docs/PLAN.md` を読む。再開手順は `docs/STAGE2_HANDOFF.md` §12。

## モジュール
`:domain` `:persistence` `:db-migration` `:cloud-gcp` `:bot` `:ingest` `:dashboard`（依存方向は `docs/ARCHITECTURE.md` §2）。
Gradle の規約プラグインは `build-logic/`（依存規則 `checkModuleDependencies`、ktlint、Kotlin/Spring Boot 共通設定）。版数は `gradle/libs.versions.toml`（根拠は `docs/VERSIONS.md`）。

## 開発手順
前提: JDK **25**（Temurin）。Gradle は**必ず Wrapper**（`./gradlew`）を使う。配布物の SHA-256 は `gradle/wrapper/gradle-wrapper.properties` で検証される。

```bash
./gradlew build                  # コンパイル + 単体テスト + ktlint + 依存方向チェック（CI と同じ）
./gradlew checkModuleDependencies # モジュール依存方向だけ
./gradlew ktlintCheck            # コードスタイル（自動整形: ./gradlew ktlintFormat）
./gradlew :bot:bootRun           # 各アプリの起動確認（:ingest / :dashboard も同様）
bash .github/scripts/check-repo-hygiene.sh   # 秘密・実データの混入チェック
```

- ローカルに JDK 25 が無いとき（例: クラウドセッションで JDK 21 のみ）: `./gradlew -PjdkVersion=21 build` でビルドの**スモーク**だけ行える。**これは JDK 25 での成功とは扱わない**。JDK 25 での合否は GitHub Actions（`ci.yml`）の結果が正。
- 非 UTF-8 ロケール（`LANG` 未設定）ではテストレポートの生成に失敗することがある。その場合は `LC_ALL=C.UTF-8 ./gradlew build`。
- `repo.maven.apache.org` が 429（レート制限）を返す環境では、時間を空けて再実行する（Gradle は成功分をキャッシュする）。

## CI
`.github/workflows/ci.yml`（main 向け PR と main への push）。Temurin 25 / Gradle Wrapper / 権限は `contents: read` のみ / Secrets 不使用。
DB 統合テスト（`db-integration` ジョブ）: `:db-migration:integrationTest` が Testcontainers で使い捨ての **MySQL 8.4 と 9.7** を起動し、Flyway V1/V2/`R__grants.sql` と CLI を検証する（Docker が無ければ失敗し、スキップしない）。手元で実行するには Docker が必要: `MYSQL_IMAGE=mysql:8.4 ./gradlew :db-migration:integrationTest`。通常の `./gradlew build` は Docker 不要の単体テストだけ。

## db-migration（Flyway）
`java -jar`（または `./gradlew :db-migration:run --args=--mode=info`）に `DB_URL` `DB_USER` `DB_PASSWORD`（`migrate` ユーザー）を環境変数で渡す。`--mode=info|validate|migrate` は必須。終了コードは 0 / 1 / 2 / 10（validate: 未適用あり）/ 75（DB 不達）。契約は `docs/CONTRACTS.md` §4.2。アプリの起動時には実行しない。

## Git に含めないもの
実データ（JV-Data・SQLite・DB ダンプ）、学習済みモデル（`*.pkl` 等）、秘密情報、JV-Link SDK の資料、ビルド生成物（`.gitignore` と `repo-hygiene` ジョブで防ぐ）。テストデータは合成データだけを使う。
