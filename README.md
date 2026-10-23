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
DB 統合テスト（`db-integration` ジョブ）: `:db-migration:integrationTest`（Flyway V1/V2/`R__grants.sql` と CLI）、`:persistence:integrationTest`（Doma の Repository 実装・スキーマ版の検証。スキーマは本物の db-migration CLI で適用）、`:bot:integrationTest` `:ingest:integrationTest` `:dashboard:integrationTest`（T2.8: ready・Job の終了コード・非 NORMAL で DB に接続しないこと）が、Testcontainers で使い捨ての **MySQL 8.4 と 9.7** を起動して検証する（Docker が無ければ失敗し、スキップしない）。手元で実行するには Docker が必要: `MYSQL_IMAGE=mysql:8.4 ./gradlew :db-migration:integrationTest :persistence:integrationTest :bot:integrationTest :ingest:integrationTest :dashboard:integrationTest`。Connector/J 26.x の確認は `-PconnectorJVersion=26.10.0`（採否は未決。D-070）。通常の `./gradlew build` は Docker 不要の単体テストだけ。

## persistence（Doma）
`:persistence` が domain の Repository ポート（Race / OddsSnapshot / BotRun / AppUser / RaceSchedule / Signal）を実装する。Doma（kapt）の DAO・Entity・SQL テンプレートは `internal` で、公開するのは `DbSettings`・`DataSourceFactory`・`PersistenceConfiguration` だけ。SQL は全て `tenant_id` を必須にし（`SqlTemplateTenantGuardTest` が静的に検査）、時刻は UTC の `DATETIME(6)`。起動設定はアプリ側（T2.8）で組み立てる。判断は `docs/DECISIONS.md` D-070〜D-075。

## cloud-gcp（ModeProvider）
`:cloud-gcp` が domain の `ModeProvider` を実装する（CONTRACTS §5.2）。GCS の `ops-state.json`（`OPS_MODE_SOURCE=gs://…`、ADC、**読取りのみ**）を 3 秒以内に読み、成功を 15 秒キャッシュ、失敗後 5 秒は再取得せず、`seq` の巻き戻りは UNKNOWN。読めない・検証できないときは必ず UNKNOWN（NORMAL にしない）。ローカル開発・テストでは `OPS_MODE_SOURCE=/path/to/ops-state.json` と **`APP_ENV=local`**（明示したときだけ）でファイル実装を使える（GCP・認証情報不要、合成データのみ）。`APP_ENV` が `local` 以外でファイルを指定すると起動失敗（本番で黙ってフォールバックしない）。生成は `OpsModeProviders.fromEnvironment(env, clock)`。判断は D-076〜D-080、T2.8 への引き継ぎは `docs/STAGE2_HANDOFF.md` §14.4。単体テストは GCP に接続しない（`./gradlew :cloud-gcp:test`）。

## 実行アプリ（bot / ingest / dashboard、T2.8）
3 アプリは独立して起動する（相互依存なし）。環境変数は `docs/CONTRACTS.md` §2、構成は `docs/STAGE2_HANDOFF.md` §14.5。**本番で必須の設定が足りなければ起動に失敗する**（ローカル実装へ黙ってフォールバックしない）。`APP_ENV` は `local`/`prod` が必須。
- `:ingest` / `:dashboard`: `GET /internal/health`（DB・GCS・モードに依存しない）、`/internal/ready`（DB 接続とスキーマ版 ≥ 必要な版で 200、他は 503。**モードは見ない**）、`/internal/ops/mode`（診断）。`/internal` は ops サービスアカウントの署名付きトークン（ingest=Google ID トークン、dashboard=IAP JWT）だけを通し、無認証・不正は拒否する（認証のバイパスは無い）。ingest の `/v1/agent` 配下は NORMAL 以外で 503、dashboard の書込みは NORMAL 以外で 409。**業務 API・画面・管理操作は未実装**（Stage 3・4）。
- `:bot`: `--task=bot-run|calendar-check|reconcile|retention|export-dataset --tenant-id=<n>`。モード確認は DB に触れる前（STOPPED 等=exit 0、UNKNOWN=75、引数・設定不正=2、スキーマ不足・認証/権限・例外=1、DB 不達=75）。**業務処理は未実装のスタブ**で、本番(`APP_ENV=prod`)では exit 1（`TASK_NOT_IMPLEMENTED`）になり、成功に見えない。
- DB は起動時に接続しない（遅延）。Flyway は組み込まない（マイグレーションは `:db-migration` だけ）。`REQUIRED_SCHEMA_VERSION` はビルドで jar に焼き込む。

## db-migration（Flyway）
`java -jar`（または `./gradlew :db-migration:run --args=--mode=info`）に `DB_URL` `DB_USER` `DB_PASSWORD`（`migrate` ユーザー）を環境変数で渡す。`--mode=info|validate|migrate` は必須。終了コードは 0 / 1 / 2 / 10（validate: 未適用あり）/ 75（DB 不達）。契約は `docs/CONTRACTS.md` §4.2。アプリの起動時には実行しない。

## ゴールデンテスト基盤（T2.6）
`tools/golden/`: 旧 JRA Bot を**固定コミット f7dbf69** で実行して得た結果を、**合成入力**の JSON フィクスチャとして保存・検証・比較する Python ツール（Gradle 外）。検証: `python3 -m tools.golden.verify`、比較: `python3 -m tools.golden.compare --actual <kotlin出力.json>`、再生成の確認: `python -m tools.golden.generate --legacy-path <jra-bot> --check`。手順・フィクスチャ形式・未実装ケースは `tools/golden/README.md`。CI は `golden tests (Python 3.12)` ジョブ。合成データでの一致は実データでの正確性を意味しない。

## Git に含めないもの
実データ（JV-Data・SQLite・DB ダンプ）、学習済みモデル（`*.pkl` 等）、秘密情報、JV-Link SDK の資料、ビルド生成物（`.gitignore` と `repo-hygiene` ジョブで防ぐ）。テストデータは合成データだけを使う。
