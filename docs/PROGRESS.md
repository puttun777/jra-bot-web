# PROGRESS（jra-bot-web）

最終更新: 2026-10-23（第2段階 / Stage 2-H 追補: マージ後の実測と golden 再生成のフレーク修正）

## Stage 2-H 追補（マージ後の実測と golden 再生成の修正。claude/stage2-images-ci を main から作り直し）
### 経緯
- T2.5 後半は PR #12 として 2026-10-23 09:21 に main へマージ済み。本追補は、**マージ後に判明した不具合の修正**と実測の記録だけ（Stage 3 には進んでいない）。
- **不具合**: マージ後の main の CI（run 39931233623、head 97a063d）と、Dependabot の numpy PR #13（run 39931387962）で `golden tests (Python 3.12)` が失敗 → `ci gate` も失敗。原因は `tools/golden` の再生成テストがバイト完全一致を要求していたこと。`G1-MODEL-001` の `0.05767062618090029` が CI のランナーで `…027` に再生成された（2 ULP、CPU・libm の違いによる浮動小数の揺れ）。PR #11・#12 の CI と手元では同一バイトだったため、PR 時には見えなかった。T2.5 後半の変更とは無関係（T2.6 で入った検査の誤検知）。
- **修正（D-101）**: 再生成の判定を「`expected_output` だけ、そのフィクスチャ自身の `comparison_rules` の許容誤差内。他（入力・出典・メタデータ・ルール）・型・キー・順序・件数・manifest（sha256 以外）は完全一致」に変更。完全一致のケース（ハッシュ・既定値）は完全一致のまま。新しい許容値は作らない。`verify`（コミット済みの sha256）と、同一環境内で 2 回実行して完全一致を要求する検査は不変。
- **第 2 の不具合（本 PR の最初の CI で発覚。T2.5 後半から存在）**: PR #15 の `ci gate` が『欠落=[db_suites]』で失敗（golden ジョブ自体は 62 件・`generate --check`・比較器スモークとも成功。run 39937331660）。DB ジョブが無い変更では `db_suites` が空文字で、GitHub は空文字のジョブ出力を落とすため。**docs だけの PR も赤になる不具合**で、PR #12 は全ジョブが走る変更だったため見えなかった。修正は D-102（gate は `db_suites` を DB ジョブが必要なときだけ必須にする。テストは空文字の出力を落とした needs で検証）。この修正で `tools/ci` を触るため、**本 PR は CI 設定の変更として全ジョブ（connector/j 以外、約 25 分）が走る**。
### テスト結果
- ci・release の Python 単体テスト **107 件成功**（旧 104 + gate 3）。修正前に、GitHub と同じ（空文字の出力を落とす）needs で既存・追加テスト 6 件が赤になることを確認してから gate を修正。
- golden の Python 単体テスト **62 件成功**（旧 51 件 + 許容の境界 11 件: 2 ULP の揺れは許容／ケースの許容超え・型変更・入力/出典/ルール/メタデータの変更・欠落/余分・manifest の sha256 以外・例外メッセージの変更・完全一致ケースは拒否）。旧実装（固定コミット f7dbf69、numpy 2.5.3）での再生成と `generate --check` も成功（ローカル、Python 3.13）。CI の実結果は下の「最新 CI」。
- golden の揺れの修正は **CI で確認済み**: PR #15 の最初の run（39937331660）の golden ジョブで 62 件・`generate --check` 成功。ただし揺れ自体は手元で再現できておらず、この run のランナーで揺れが出たかは分からない（修正の単体テストは 2 ULP の揺れを模擬）。
- **gate の修正（D-102）の実 CI での確認は未了**: docs だけ・golden だけの PR で `ci gate` が成功することは、マージ後の最初の該当 PR（例: Dependabot の numpy PR #13 の rebase）で確認する。それまでは単体テストのみ。
### マージ後の実測（これまで『未検証』だったもの）
| 項目 | 結果 |
|---|---|
| main への push（run 39931233623） | CI 設定の変更を含むため全 10 ジョブが実行（課金 約 35 分: hygiene 1 / release-tools 1 / build 3 / golden 1 / db 8.4 7 / db 9.7 7 / images 6 / connector/j 8.4 4・9.7 4 / gate 1）。golden の失敗で `ci gate` が失敗（**必須テスト失敗でゲートが赤になることを実 CI で確認**）。docs だけの main push は未観測 |
| Dependabot Gradle PR #14（run 39931421037） | `gradle-wrapper 9.7.1 → 9.8.2`（`gradle-minor-patch` グループ）。build + db 8.4/9.7 + images が実行、connector/j と Python は skipped、`ci gate` 成功（課金 約 25 分）。**マージしない**（Gradle の版はユーザー判断。VERSIONS のゲートに沿って確認してから） |
| Dependabot pip PR #13（run 39931387962） | `numpy 2.5.3 → 2.5.4`（`python-minor-patch`）。hygiene + golden のみ実行（約 3 分）。golden は上記の不具合で失敗。**本修正を取り込んでから Dependabot に rebase させて確認する**（numpy を上げると『固定 numpy』の前提（D-090）が変わるため、マージ前にフィクスチャの再生成差分を人が見る） |
| Dependabot の動作 | 変更ファイルの分類どおりにジョブが選択され、skipped は gate で『未実施』として扱われ成功に見えない。github-actions・docker の PR は未観測 |
### 未解決・維持
従前どおり（上の Stage 2-H の一覧）。追加: branch protection の required checks を `ci gate` だけにする作業は、ユーザー作業として残っている（未確認）。
### 最新 CI
（本追補の push 後の run を PR で確認し、結果を追記する）
### ブランチ
`claude/stage2-images-ci`（PR #12 のマージ後に main から作り直し。追補の PR は main 向け、マージはユーザー）。

## Stage 2-H（claude/stage2-images-ci, PR #12）
### 結果
- **T2.5 後半 実装完了**（Docker イメージ・release-manifest ドライラン・Dependabot・CI 最適化）。Stage 3 には進んでいない。**ml-train イメージと本番 release-manifest は未完成**（偽装していない）。
- **CI（GitHub Actions）**: run 39927310473（head 3ba8cb6、PR #12。CI 設定の変更のため全ジョブ + Connector/J 26.x）で **10 ジョブすべて成功** — changes and repo hygiene / build (Temurin 25) / release tools and ci scripts (Python) / golden tests (Python 3.12) / db integration (MySQL 8.4) / db integration (MySQL 9.7) / images (build + smoke, no push) / connector/j 26.x check (8.4, 9.7) / ci gate。最新コミット（docs の追記）の結果は下の「最新 CI」。
### 作成したもの
- **Dockerfile**（ルート 1 つ、4 ターゲット: `dashboard` `ingest` `bot` `db-migration`）・`.dockerignore`（既定拒否）。D-093。
- **ビルドしたイメージ**（CI の `images` と、クラウドセッションのローカルで）: `jra-bot/{dashboard,ingest,bot,db-migration}:<git sha>`（ローカルのみ。push なし・認証なし）。サイズ（ローカル・非圧縮）: bot 586MB / ingest 601MB / dashboard 601MB / db-migration 440MB。CI の所要: ビルド 189 秒（4 イメージ、Gradle は 1 回）+ スモーク 133 秒。
- **スモーク**（`tools/release/image_smoke.py`、D-096）: ローカル（クラウドセッション）で **138 検査すべて成功**、CI の `images` ジョブも成功（失敗 0 でなければジョブが落ちる）。内容は `CI_AND_IMAGES.md` §4.2: JDK 25 で起動、non-root（10001）、ENTRYPOINT、必須設定不足で起動失敗、bot 不正 task = 2、UNKNOWN = 75、STOPPED = 0 で DB 接続ゼロ、スタブが本番で成功にならない（prod は 0 にならない）、db-migration の CLI（2/75/10/0/1、実 MySQL 8.4 で migrate→validate→info）、パスワード非混入、SIGTERM で正常終了。スモーク自身が不備を検出できることも確認（ローカルで `db-migration` の `/app/lib` が `--chmod=0444` でディレクトリ走査不可になっていた不具合を検出→`a=rX` に修正）。
- **release-manifest**: スキーマ `docs/schemas/release-manifest.v1.json`（本番）・`release-manifest-preview.v1.json`（ドライラン）、`docs/release/rollback-verified.json`（検証記録、現在は空）、`tools/release/{manifest,cli,jsonschema_lite,repo_facts}.py`。D-094・D-095、CONTRACTS 0.4.5。**検証結果**: 単体テスト（正常・必須欠落・不正 SHA・不正 digest・不正スキーマ版・未対応 contract_version・スキーマ不一致・不正/未検証ロールバック・プレビューの本番利用拒否・再現性）。CI の `images` で `manifest-preview.json` を生成→プレビューとして適合→本番としては**拒否**→2 回生成して同一バイト、を確認。**状態**: `deployable:false`、ブロッカー `IMAGES_NOT_PUSHED` `ROLLBACK_RANGE_UNVERIFIED` `FEATURE_SCHEMA_HASH_UNDEFINED` `IMAGE_NOT_BUILT`（ml-train）。本番 manifest は生成していない（push 後の digest・ml-train・feature_schema_hash が無い）。
- **Dependabot**: `.github/dependabot.yml`（gradle / github-actions / docker / pip=`tools/golden`、週次、minor+patch グループ、自動マージなし、Boot minor/major・Connector/J major・eclipse-temurin major・numpy major は ignore）。D-100。**実 PR は未検証**。
- **CI 最適化**: `tools/ci/classify.py`（変更判定。fail closed）・`tools/ci/gate.py`（`ci gate`）、`ci.yml` の再構成、`images.yml`、`compat-connectorj26.yml`。D-097〜D-099。**分岐保護の required checks を `ci gate` だけにする**（ユーザー作業。`CI_AND_IMAGES.md` §1.2）。
### テスト結果
| 区分 | 結果 |
|---|---|
| Python（tools/ci + tools/release） | **104 件成功**（スキップ 0）。ローカル（Python 3.13）と CI の `release tools and ci scripts` ジョブ |
| Python ゴールデン | CI の `golden tests (Python 3.12)` 成功（既存、回帰なし） |
| Kotlin（build/ktlint/依存方向/単体） | CI の `build (Temurin 25)` 成功（bootJar 名の固定・アーカイブ再現性の変更を含む）。ローカルでは JDK 25 が無く、Docker 内の JDK 25 でビルドのみ確認 |
| DB 統合（MySQL 8.4 / 9.7） | CI 成功（全スイート）。Connector/J 26.x も 8.4・9.7 で成功（非ゲート） |
| 未実施 | 実 Dependabot PR、`workflow_dispatch` のフル検証、main への push 後の実行、必須テストの故意の失敗による ci gate の赤（単体テストでのみ確認）、docs だけの実 CI（本 PR は CI 設定の変更なので全ジョブが走る。docs のみの実測は PR マージ後） |
### CI の実行時間（詳細は `CI_AND_IMAGES.md` §3）
- 最適化前（run 39921807006）: 7 ジョブ・**20 分/回**（docs だけでも）。
- 最適化後: 本 PR（最悪ケース）10 ジョブ・**33 分**（connector/j なしなら 25）。docs だけ **2 分**（推定: 6 秒 + 6 秒の各ジョブを 1 分に切り上げ）、アプリのみ 7〜9 分、persistence/Flyway 15〜24 分、Docker だけ 9 分（推定）。月 2,000 分に対し、想定の使い方で約 580 分（推定。旧方式の同条件は約 1,260 分）。**課金（ジョブ時間の合計）と壁時間（約 6 分）は別**。
### セッションの環境
`java -version`: OpenJDK 21（JDK 25 なし）。`./gradlew` はローカルで未実行（Docker 内で JDK 25 のビルドを実行）。`docker info`: Docker 29.9.1（`dockerd` を起動して使用。Docker Hub / Maven Central の 429 が一時的に発生）。Python 3.13.16。actionlint 1.7.12 でワークフローを検証（エラー 0）。
### 判断
D-093〜D-100。契約: CONTRACTS 0.4.5（§1・§10 の補足。意味は変えていない）。infra への引継ぎ: I-H9（下の表）。
### Stage 2 全体の完了判定（技術面）
- T2.1〜T2.8 はすべて実装済み・PR 済み・CI 緑（T2.1 PR #5、T2.2 #6、T2.3 #7、T2.4 #8、T2.7/T2.8 #9・#10、T2.6 #11、T2.5 後半 本 PR #12）。依存の互換性（Doma 3.14.1 / Kotlin 2.4.21 / Boot 4.1.2 / Flyway 13.11.1 / Connector/J 9.7.0、MySQL 8.4・9.7）は CI で確認済み。4 イメージのビルドとスモーク成功。release-manifest は**ドライラン（プレビュー）のみ**。
- **Stage 2 の技術的な完了とは分けて残るもの**: 実 GCP での検証ゲート（G-OPS-1〜9、G-AUTH-1、G-APP-1、G-JOB-1、G-DB-1/5/6/7）、MySQL 9.7 の本番採否（D-069）、Connector/J 26.x の採否、デッドロック 1213 の再試行、bot-run の checkpoint・SIGTERM、`mltrain` のスキーマ版確認方法、ml-train イメージ（Stage 3）、本番 release-manifest（第5段階）、T1.16・T1.17・T1.20・T1.21、infra I-H1〜I-H9 と T5.0。MySQL 8.4 は暫定の本番基準のまま。**本 PR のマージ（ユーザー判断）をもって Stage 2 の実装は完了**。
### Stage 3 への引き継ぎ
- 進められる: T3.1（EV/Kelly 等の Kotlin 移植 + `tools/golden` の比較。入口は `tools/golden/catalog.py` の partial/planned）、T3.2、T3.3、T3.4、T3.7、T3.6 の Kotlin 側（実データ不要の範囲）。ブロック: T3.5・T3.6 の実データ部分・旧新比較の総合（T1.16/T1.17/T1.20/T1.21）、T3.0（実機）、第5段階（T5.0・ユーザーの GCP 承認）。
- イメージ関連の宿題: ml-train の Dockerfile ターゲットと `IMAGE_NAMES`/スモーク/`images.yml` の更新、`feature_schema_hash` の定義、ロールバック検証（`rollback-verified.json`）、bot のスタブ差し替え時にスモークの『prod は 0 にならない』を見直す。詳細は `STAGE2_HANDOFF.md` §14.7。
### 未解決・維持（完了扱いにしていない）
T1.16 / T1.17 / T1.20 / T1.21、infra I-H1〜I-H9、T5.0、MySQL 9.7 の本番採否（D-069）、Connector/J 26.x の採否、Cloud SQL の実環境検証、実 GCP での ModeProvider 検証、G-AUTH-1、G-APP-1（**追加: `/internal/health` は無トークンで 401 のため Cloud Run の HTTP プローブにそのまま使えない。プローブ方式は infra で決める**）、G-JOB-1、デッドロック 1213 の再試行、bot-run の checkpoint・SIGTERM、`mltrain` のスキーマ版確認方法、Dependabot の実運用確認、branch protection の更新。Docker のスモークの成功は Bot の業務機能の完成を意味しない。
### 次の作業
本 PR のレビュー・マージ（ユーザー）→ branch protection を `ci gate` だけに更新 → Stage 3（T3.1 から）。
### 最新 CI
（このコミットの結果は PR #12 のチェックを参照。結果を確認次第ここを更新）
### ブランチ
`claude/stage2-images-ci`（PR #12、main 向け。マージはユーザー）。

## Stage 2-G（claude/stage2-golden-tests）
### 結果
- **T2.6 実装完了**（ゴールデンテスト基盤）。T2.5 後半・Stage 3 には進んでいない。Kotlin 業務ロジックは未実装。
- **旧コードを実際に実行して生成できたケース（10 ケース / 447 呼び出し、固定コミット `f7dbf69fd8940121782870de902e8c449e6ca149`）**: GD01-EV-001, GD01-KELLY-002（E5: odds=1.0 の ZeroDivisionError を実測）, GD02-PROB-001, GD02-NORM-002, G2-HASH-001, G1-MODEL-001, GD03-FILTER-001, GD05-COMBO-001, GD09-EVAL-001（evaluate_odds 全体）, GD10-DEFAULTS-001。
- **登録のみ（planned）**: G4, G5, G6, G7。**一部のみ生成（partial）**: GD02〜GD10, G2, G3（範囲と残作業は `tools/golden/catalog.py`）。implemented は GD01, G1 のみ。
- **検証できた範囲**: 旧実装を固定コミットで 2 回実行して一致 → `--check` でコミット済みフィクスチャとバイト一致（Python 3.12.3 と 3.13、numpy 2.5.3）。manifest の sha256 で手編集を検知。比較器は正常/型/キー/順序/許容誤差/NaN/例外/未提供ケースを単体テストで確認。**未検証**: Kotlin 出力との比較（Kotlin 未実装）、実データ・実 config・実モデルでの挙動（T1.20）、時刻依存経路。
- **テスト**: Python 単体テスト 51 件（旧実装あり: 51 実行/0 スキップ、なし: 4 スキップ）。ローカル成功。CI 結果は下記。
- **CI（GitHub Actions）**: run 39921807006（head 6f24519、PR #11）で **7 ジョブすべて成功** — build (Temurin 25) / repo hygiene / db integration (MySQL 8.4) / db integration (MySQL 9.7) / connector/j 26.x check (MySQL 8.4) / connector/j 26.x check (MySQL 9.7) / **golden tests (Python 3.12)**（新規。verify・単体テスト 51 件（スキップ 0）・旧実装からの再生成の完全一致・比較器スモークを含む）。
### セッションの環境
`java -version`/Gradle/Docker は今回の作業では未使用（Kotlin は変更していない。既存 6 ジョブの回帰は GitHub Actions で確認）。Python 3.13.16（ローカル）/ 3.12.3（互換確認）、numpy 2.5.3。参照元は `git clone` で取得でき、固定コミットを checkout 可能だった（公開リポジトリ）。
### 判断
D-089〜D-092（参照元の固定、フィクスチャ原則、実装方針、カタログ）。契約（CONTRACTS）の変更なし。
### 未解決・維持（完了扱いにしていない）
T1.16 / T1.17 / T1.20 / T1.21、infra I-H1〜I-H8、T5.0、実 GCP 検証、MySQL 9.7 本番採否、Connector/J 26.x 採否、デッドロック 1213 再試行、bot の checkpoint・SIGTERM、本番認証・Cloud Run の実環境検証。合成ゴールデンの成功は実データでの正確性・収益性の証明ではない。
### 次の作業
**T2.5 後半**（Docker イメージ、Dependabot、release-manifest）。その後 Stage 3（T3.1 は T2.6 の `catalog.py` の partial/planned を埋めながら Kotlin 移植と比較）。
### ブランチ
`claude/stage2-golden-tests`（PR は main 向け、マージはユーザー）。

## Stage 2-F（claude/stage2-app-bootstrap）
### 結果
- **T2.8 実装完了**（3 アプリの起動設定・内部ヘルスチェック・Job 実行契約）。T2.6 には進んでいない。**業務処理は未実装のスタブ**。スタブの起動成功を、本番で Bot が正常動作することの証明として扱わない。**実 GCP・実 Cloud SQL には一度も接続していない。**
- **PR**: #10（main 向け、マージはユーザーが判断）。
- **CI（GitHub Actions、Temurin 25）**: run 39910437001（head 054d9e7。T2.8 の全コードを含む）で **build (Temurin 25) / repo hygiene / db integration (MySQL 8.4) / db integration (MySQL 9.7) の必須 4 つと、非ゲートの connector/j 26.x check（8.4・9.7）の計 6 ジョブがすべて成功**。各ジョブ内の件数検証ステップ（cloud-gcp 91 以上、persistence 単体 23・bot 54・ingest 28・dashboard 30 以上、persistence 統合 98・bot 8・ingest 9・dashboard 9 以上、スキップ・失敗 0、期待クラスの欠落なし）も成功。ジョブログの本文はこの環境から取得できなかったため、件数の実測値はステップの成功（閾値判定）で確認した。その後の docs だけのコミットで CI が再実行される（結果は PR のチェックを参照）。
- ローカル（JDK 21 スモーク + Docker）: 全体の `./gradlew build`、`ktlintCheck`、`checkModuleDependencies` 成功。**MySQL 8.4.13 と 9.7.4 の両方**で `:persistence` 98 / `:bot` 8 / `:ingest` 9 / `:dashboard` 9 の統合テストが成功（スキップ 0・失敗 0）。`:db-migration:integrationTest`（44）は 8.4 で成功（9.7 は CI）。JDK 25 の結果は CI のもの。
### セッションの環境（CLAUDE.md の記録義務）
`java -version` = OpenJDK 21.0.12（JDK 25 なし。`-PjdkVersion=21` のスモーク）、Gradle 9.7.1 動作、Docker 29.9.1: **`dockerd` を起動でき（root）**、`mysql:8.4`・`mysql:9.7` を取得できた（ローカルの統合テストを実行できた）。Maven Central の 429 は待って再試行して解消（ミラーは使っていない）。`LC_ALL=C.UTF-8` で実行。
### 実装したもの
| 区分 | 内容 |
|---|---|
| `:domain` | `AppEnv`（local/prod）、`ModeSnapshot`、`InspectableModeProvider`（`ModeProvider` + `snapshot()`） |
| `:cloud-gcp` | `CachingModeProvider.snapshot()`（キャッシュ内の値だけ。取り直さない）、`auth/GoogleSignedTokenVerifier`（IAP JWT=ES256 / Google ID トークン=RS256。署名・audience・issuer・exp・iat・email・email_verified・ops SA の完全一致）、`auth/RequestPaths`（パス正規化）、testFixtures（偽 JWKS・署名済みトークン）、`google-auth-library-oauth2-http` 1.54.0 を固定 |
| `:persistence` | `DataSourceFactory.createLazy`（最初の接続までプールを起動しない）、`schema/SchemaVersion`・`SchemaVersionVerifier`・`AppBuildInfo`、testFixtures（`MySqlSupport`・`TcpProxy`。`:persistence` の既存テストも共用に移動） |
| `:ingest` | `IngestApplication`（除外を解除）・`IngestSettings`・`IngestConfiguration`・`/internal/health|ready|ops/mode`・`InternalAuthFilter`（Google ID トークン）・`AgentApiOpsGuardFilter`（`/v1/agent` 配下を 503）・`WebFilters` |
| `:dashboard` | 同上の構成（`IAP_AUDIENCE`・IAP JWT・DB ユーザー `app`）+ `AdminWriteGuardFilter`（書込みを 409） |
| `:bot` | `BotApplication`（`main` → `BotLauncher`）・`BotArgsParser`・`BotSettings`・`JobGate`・`BotJobExecutor`・`BotConfiguration`・`StubTaskHandler`・`CycleGate`（未接続）・`JobExit` |
| ビルド・CI | `SpringBootAppPlugin`: `generateAppInfo`（`requiredSchemaVersion` と `releaseId` を jar に焼き込み）・`integrationTest` タスク。`ci.yml`: 件数検証スクリプト `.github/scripts/verify_test_results.py`、`build` に cloud-gcp(91)・persistence 単体(23)・bot(54)・ingest(28)・dashboard(30) の検証、`db-integration`(8.4/9.7) に persistence(98) と bot(8)・ingest(9)・dashboard(9) の統合テストと検証 |
### Bean・アプリ起動構成
STAGE2_HANDOFF §14.5 の表。要点: DataSource は遅延（起動時に DB へ接続しない）、`ModeProvider` は `OpsModeProviders.fromEnvironment(env, SystemClock)`、`@Import(PersistenceConfiguration)` で Repository 6 つと Doma の `Config`、Flyway なし、`DataSourceAutoConfiguration`/`DomaAutoConfiguration` の除外は解除。bot は **モード確認の後（NORMAL のときだけ）に Spring を起動**する。
### スキーマ版検証方法
`flyway_schema_history` の `success=1` の最大版 ≥ `REQUIRED_SCHEMA_VERSION`（ビルドで jar に焼き込み。現在 2。環境変数では上書き不可）。新しい版は許容。履歴の表なし=不足（マイグレーションは実行しない）。**接続不能（一時障害）= ready `DB_UNAVAILABLE` / Job 75**、スキーマ不足 = ready `SCHEMA_INSUFFICIENT` / Job 1、認証失敗 = `DB_AUTH_FAILED` / 1、DB・権限の誤り・想定外 = `DB_ERROR` / 1（D-084）。
### 内部 API の認証状況
- **実装済み（署名検証付き）**: ingest=Google ID トークン、dashboard=IAP JWT。署名・アルゴリズム固定・issuer・audience・exp/iat・email（ID トークンは email_verified）・ops SA の完全一致。無認証=401、不正=401、dashboard で ops 以外=403。パス迂回（`;` `//` `.` `%xx` 大文字）でも素通りできない。設定が足りなければ起動失敗。認証のバイパスは存在しない。
- **未検証（ゲート G-AUTH-1）**: 実 Google の公開鍵・実トークン、鍵取得のタイムアウト、直接 IAP の `aud` の形式（G-OPS-7）。人の認可は Stage 4、エージェント認証は Stage 3。
### Job 終了コード（実装・テスト済み）
0=正常/非 NORMAL の確定状態（`MODE_<mode>`）、75=UNKNOWN・DB 不達、2=引数・設定不正（`--task` 不明・必須欠落・`OPS_MODE_SOURCE` 不正・本番での他の DB ユーザーなど）、1=スキーマ不足・認証失敗・DB/権限の誤り・想定外の例外・**本番でのスタブ（`TASK_NOT_IMPLEMENTED`）**。10 は流用しない。
### テスト件数
| 区分 | 件数 | 実行場所 |
|---|---|---|
| bot 単体 | 54（引数 9・Launcher 12・Executor 9・実プロセス 9・Spring 配線 5・JobGate 4・CycleGate 6） | `./gradlew build` |
| ingest 単体 | 28（内部 API・認証 13、エージェントガード 7、起動設定 8） | 同上 |
| dashboard 単体 | 30（内部 API・認証 14、書込みガード 8、起動設定 8） | 同上 |
| cloud-gcp | 91（既存 56 + 検証器 23・パス 5・スナップショット 7） | 同上 |
| persistence 単体 | 23（既存 15 + 版比較 2・分類 6） | 同上 |
| bot / ingest / dashboard 統合 | 8 / 9 / 9 | `integrationTest`（MySQL 8.4・9.7） |
| persistence 統合 | 98（既存 86 + スキーマ検証 12） | 同上 |
- 変異テスト（手動、全て検出された）: パス判定を素朴な `startsWith` に変更／ガードの常時通過／`X-Ops-Mode` の常時付与／UNKNOWN を続行扱い／ops 主体・issuer・email_verified の検査削除／DataSource を即時接続に変更／bot の実行を空に／スタブの本番成功／認証失敗を接続不能に分類／dashboard のメソッド判定の変更。
- 依頼の優先テスト 24 項目との対応: 1〜3 `*InternalApiTest`/`BotSpringWiringTest`、4 `*StartupConfigTest`、5 `*StartupConfigTest`/`BotLauncherTest`、6 health のテスト、7〜11 `*ReadyIntegrationTest`、12 `ops/mode` のテスト、13〜15 認証のテスト、16〜21 `BotLauncherTest`/`BotProcessTest`/`BotJobIntegrationTest`、22 `AgentApiOpsGuardTest`、23 `AdminWriteGuardTest`、24 `checkModuleDependencies`。
### 判断（DECISIONS D-081〜D-088）と契約
D-081 構成と配置 / D-082 内部 API の認証 / D-083 DB 接続の起動方針・最小権限 / D-084 スキーマ版 / D-085 bot の Job 契約 / D-086 モードの業務ガード / D-087 ops/mode と ready / D-088 APP_ENV 必須。契約: CONTRACTS 0.4.4（infra への影響は §13）。
### 未実装・未検証（完了扱いにしない）
- 各 `--task` の業務処理（スタブ。本番は exit 1）。bot-run のループ・checkpoint 保存・リース解放・SIGTERM（`CycleGate` は未接続）。`/v1/agent/*` の本体、`/v1/agent/mode`。画面・`/api/v1`・人の認証/認可・管理操作。
- 実 GCP での ModeProvider（ADC・IAM・3 秒）、実 Google の鍵での内部 API 認証（G-AUTH-1）、Cloud Run 上の health/ready（G-APP-1）、Jobs の引数・終了コードの運用（G-JOB-1）、Cloud SQL。
### 未解決・注意（引き継ぎ）
- T1.16 / T1.17 / T1.20 / T1.21、infra I-H1〜I-H7（+ 下の I-H8）、T5.0（infra 契約同期）、MySQL 9.7 の採否、Connector/J 26.x の採否、Cloud SQL の実環境検証、デッドロック 1213 の再試行設計、T3.4 の HMAC とトークンハッシュの整合、mltrain のスキーマ版確認方法（`mltrain` は履歴を読めない: D-065）、bot-run の checkpoint・SIGTERM 検証は**未完了のまま**。
- `/internal/ready` の DB 確認は呼ぶたびに 1 クエリ（キャッシュなし）。DB 停止中は最大 10 秒（`connectionTimeout`）待つことがある。infra の status/start が頻繁に呼ぶ場合は見直す。
- google-auth の鍵取得 HTTP のタイムアウトはライブラリの既定（未設定）。実環境で確認する（G-AUTH-1）。
### 次のタスク（T2.6 への引き継ぎ）
**T2.6**（ゴールデンテスト基盤: `tools/golden/` で参照元を固定コミット f7dbf69 で実行し、合成入力 → JSON フィクスチャ。T2.8 とは独立）。その後 T2.5 後半（イメージ・manifest・Dependabot。`jrabot-app.properties`・bootJar を使う）。ブランチは main から作り直す（本 PR のマージ後）。
### Git
ブランチ `claude/stage2-app-bootstrap`（main 向け PR、マージはユーザー）。

---

## Stage 2-E（claude/stage2-gcp-mode-provider）
### 結果
- **T2.7 実装完了**（`:cloud-gcp` の `ModeProvider`: GCS 実装 + ローカルファイル実装、単体テスト、CI の件数検証）。T2.8 には進んでいない。**実 GCP には一度も接続していない**（単体テストの成功を実 GCP のアクセス成功とは扱わない）。
- **PR**: #9（main 向け、マージはユーザーが判断）。
- **CI（GitHub Actions、Temurin 25）**: run 39902427400（head 04f709f。T2.7 の全コードを含む）で **build (Temurin 25) / repo hygiene / db integration (MySQL 8.4) / db integration (MySQL 9.7) の必須 4 つと、非ゲートの connector/j 26.x check（8.4・9.7）の計 6 ジョブがすべて成功**。build ジョブの「cloud-gcp の単体テストの実行件数の検証」ステップも成功（0 件・スキップ・失敗なし。件数の閾値は 55 以上）。既存の DB 統合テスト・domain のテストは変更しておらず、同じジョブで成功した。その後の docs だけのコミットで CI が再実行される（結果は PR のチェックを参照）。
- ローカル（JDK 21 スモーク）の全体 `./gradlew build` も成功: domain 214 / persistence 15 / db-migration 57 / cloud-gcp 56 / アプリ各 2、スキップ・失敗 0（Maven Central の 429 を待って再試行した後）。JDK 25 の結果は CI のもの。
### セッションの環境（CLAUDE.md の記録義務）
`java -version` = OpenJDK 21.0.12（JDK 25 なし。`-PjdkVersion=21` のスモーク）、Gradle 9.7.1 動作、Docker 29.9.1 CLI あり（今回の範囲では未使用。DB 統合テストは変更していない）。Maven Central が IP 単位で 429 を返し、ビルドは待って再試行した（GCS ミラーを init script で置こうとしたが、パッケージレジストリの迂回として拒否されたので**削除し使っていない**）。ローカルのロケールが POSIX だと日本語のテスト名でレポート生成が失敗するため `LC_ALL=C.UTF-8` で実行した（CI は影響なし）。ローカルの結果は JDK 21 のスモークで、JDK 25 の結果ではない。
### 実装したクラスと責務（`cloud-gcp/src/main/kotlin/jp/jrabot/cloudgcp/ops/`）
| クラス | 責務 |
|---|---|
| `CachingModeProvider` | `ModeProvider` の実装。成功の 15 秒キャッシュ、失敗後 5 秒は再取得しない、seq の単調性検査、単一飛行（1 つの `ReentrantLock`）、ログ（変化時のみ INFO / 理由が変わったとき WARN）、読取り・検証の失敗を UNKNOWN に変換 |
| `OpsStateParser` / `OpsState` / `OpsFailureReason` | JSON の構文解析とスキーマ検証（Jackson 3、重複キー・末尾トークン拒否）。GCS 版・ローカル版で共通。失敗理由は固定語彙（入力の値を含めない） |
| `OpsStateSource`（`fun interface`）/ `OpsStateReadException` | 生のバイト列を読む口。ここがテストで差し替わる（フェイク） |
| `GcsOpsStateSource` / `GcsLocation` / `GcsStorageFactory` | `gs://` の解析と、google-cloud-storage による**読取りのみ**の取得。ADC、接続・読取り 3 秒、リトライ全体 2.4 秒、呼び出し側の上限 3 秒を専用スレッドで強制。404/401/403/タイムアウト/その他を分類 |
| `FileOpsStateSource` | ローカルファイル（GCP・DB・認証情報不要）。ファイル不在・権限・大きさの分類 |
| `OpsModeProviders` / `OpsModeConfigurationException` | `fromEnvironment(env, clock)`。`gs://` は GCS 実装、ファイルは `APP_ENV=local` のときだけ、それ以外・未設定・不正は起動失敗 |
### 使用したライブラリ・キャッシュ・seq（詳細は DECISIONS D-076〜D-080、VERSIONS §11）
- GCP ライブラリ: `com.google.cloud:google-cloud-storage:2.75.0`（`Storage.readAllBytes` だけを使用）。ほか Jackson 3・slf4j-api（Boot の BOM）。`:domain` には何も追加していない。
- キャッシュ: 成功値を `fetchedAt + 15s` 未満の間だけ使う（ちょうど 15 秒で再取得）。失敗は保存せず、失敗時刻から 5 秒は取得せず UNKNOWN。時計が戻ったらキャッシュを使わない。取得は単一飛行。
- seq: 受理した最新の状態をキャッシュと独立に保持（プロセス存続中）。`seq` が小さい → UNKNOWN（`SEQ_ROLLBACK`）、同じ `seq` で `mode` / `desired.mode` が違う → UNKNOWN（`SEQ_CONFLICT`。契約への追加の厳格化で D-079 に根拠を記録）、同じ `seq` で内容が同じ → 正常。
### 追加した単体テスト（`:cloud-gcp:test` 56 件 = 既存のマーカー 1 + 新規 55。実 GCP・エミュレータなし）
| クラス | 件数 | 内容（依頼の番号） |
|---|---|---|
| `OpsStateParserTest` | 12 | 4 モード(1-4)・追加フィールド(7)・任意項目・schema_version(10)・JSON 不正(11)・必須不足(12)・mode 不正(13)・desired 不正(14)・型不正・入力値をログに出さない |
| `CachingModeProviderTest` | 20 | seq 増加(5)・同じ seq(6)・15 秒境界(8,9)・巻き戻り(15)・巻き戻り検知がキャッシュ切れ/失敗をまたぐ・読取り失敗が全部 UNKNOWN(16-19 の分類)・期限切れ後の障害(20)・5 秒の再試行抑制(21)・復旧(22)・UNKNOWN で allowsWork=false(25)・時計の逆行・ログ(変化時のみ INFO、WARN の重複抑制と再掲、JSON を出さない、スタックなし)・不具合の伝播 |
| `CachingModeProviderConcurrencyTest` | 3 | 32 スレッドの同時呼び出しで取得 1 回・全員同じ結果、失敗中も 1 回、期限切れの同時更新と seq 巻き戻りの整合(26) |
| `GcsOpsStateSourceTest` | 10 | **実際の SDK** を 127.0.0.1 のフェイク HTTP サーバに向け、200 / 404(18) / 403・401(17) / 500 と接続拒否(19) / 遅延(16) / クライアント生成失敗 / 大きすぎる応答 / **GET しか送らないこと** / メッセージに内容を含めない / 結合 / `GcsLocation` |
| `LocalFileModeProviderTest` | 10 | ファイル判定(NORMAL 以外)・不在(23)・不正 JSON(24)・ディレクトリ/大きすぎる・キャッシュと seq 検査が GCS 版と同じ・`APP_ENV=local` のみ許可・本番/未設定で起動失敗・`OPS_MODE_SOURCE` の不正・`gs://` は生成時に通信しない・設定値をメッセージに出さない |
- 変異テスト（手動）: seq 巻き戻りの検査・再試行間隔・失敗時のキャッシュ破棄をそれぞれ壊すとテストが失敗することを確認し、元に戻した。
### ローカルの結果（JDK 21 スモーク。JDK 25 ではない）
`:cloud-gcp:test` 56 件・スキップ 0・失敗 0、`ktlintCheck`、`checkModuleDependencies` 成功。全体の `./gradlew build` は初回 Maven Central の 429 で失敗したが、待って再試行し成功した（上記）。
### 実 GCP で未検証の項目（完了扱いにしない）
ADC（Cloud Run のランタイム SA）での認証、実バケットの IAM（`storage.objects.get` だけで足りること）、レイテンシ、SDK のリトライと 3 秒の上限が実環境で意図どおり働くこと、メタデータサーバ不達時の挙動。既存の検証ゲート G-OPS-1〜9 に該当項目が無いので、infra の T5.0 で追加する。
### T2.8 への引き継ぎ
STAGE2_HANDOFF §14.4（`OpsModeProviders.fromEnvironment` を Bean にする、起動失敗の扱い、Job は DB 接続前に判定、ingest の 503、bot-run の連続 UNKNOWN の数え上げは呼び出し側）。T2.4 の除外（`DataSourceAutoConfiguration`）は **そのまま**（T2.8 で外す）。
### 未解決・注意（引き継ぎ。完了扱いにしない）
- T1.16 / T1.17 / T1.20 / T1.21、I-H1〜I-H7、T5.0（infra 契約同期。**今回の追加: 巻き戻りの検知はプロセス存続中有効なので、正本を意図して巻き戻す復元手順にアプリの再起動を含める**）、MySQL 9.7 の採否、Connector/J 26.x の採否、Cloud SQL の実環境検証、デッドロック（1213）の再試行設計、T3.4 の HMAC とトークンハッシュの整合は**未完了のまま**。
- T2.5 の残り（イメージ・Dependabot・release-manifest）、T2.6（ゴールデン）、T2.8 は未着手。`:cloud-gcp` の `ModelStore` などの他のポート実装も未着手。
- `desired.set_at` / `operation.*` は契約が「使う」としつつ検証対象に挙げていないため任意にした（D-077）。infra が将来これらを必須にしたくなったら契約で合わせる。
### Git
ブランチ `claude/stage2-gcp-mode-provider`（main 向け PR、マージはユーザー）。コミット: 本体+テスト+CI の件数検証 → 本 docs。

---
## Stage 2-D（claude/stage2-persistence-doma, PR #8）
### 結果
- **T2.4 実装完了**（`:persistence` の Doma DAO・Entity・SQL・Repository、統合テスト、CI）。T2.7 以降には進んでいない。
- **CI（GitHub Actions、Temurin 25）**: run 39898663936（head 5730d70、T2.4 の全コード）で **build (Temurin 25) / repo hygiene / db integration (MySQL 8.4) / db integration (MySQL 9.7) の必須 4 つと、非ゲートの connector/j 26.x check（8.4・9.7）がすべて成功**。件数（ジョブ内の検証ステップの出力）: `:persistence:integrationTest` **86 件（11 クラス）× 8.4・9.7 の両方、スキップ 0・失敗 0**、`:db-migration` 統合 44 件 × 両方（従来どおり）、Connector/J 26.10.0 の確認ジョブは 8.4・9.7 とも 86 件合格。Docker 不要の `:persistence:test` は 15 件。その後の docs だけのコミットで CI が再実行される（結果は PR のチェックを参照）。
- 途中の失敗（隠さず記録）: ① 初回 CI（head 931f12a、run 39897665565）で `build` が失敗 — `:dashboard:test` が `DataSourceAutoConfiguration`（Failed to determine a suitable driver class）で落ちた。`:persistence` が starter-jdbc / doma-spring-boot-starter を実行時に持ち込み、DataSource の無い最小アプリ（bot / ingest / dashboard）の起動テストに自動構成が効いたため。3 アプリで当該の自動構成を名前指定で除外して解決（T2.8 で外す。STAGE2_HANDOFF §14.3）。この時 DB 統合テストは緑だった。② ローカルでの試行錯誤（`NamingType` の import、Spring の `registerBean` のオーバーロード、SQL ガードの正規表現、権限の読み違い 4 件）は CI に出る前に修正済み。
### セッションの環境（CLAUDE.md の記録義務）
`java -version` = OpenJDK 21.0.12（JDK 25 なし。`-PjdkVersion=21` のスモーク）、Gradle 9.7.1 動作。**今回は `dockerd` を起動でき（root）、Docker 29.9.1 で MySQL 8.4（8.4.13）の統合テストをローカルで実行できた**（前回は許可されなかった）。`mysql:9.7` は Docker Hub の 429 でローカルに取得できず、9.7 は CI だけ。Maven Central の 429 は GCS ミラーの init script（`~/.gradle/init.d`、Git 外）で回避。ローカルの結果は JDK 21 のスモークで、JDK 25 の結果ではない（JDK 25 は CI）。
### 実装したもの（`persistence/`）
| 区分 | 内容 |
|---|---|
| Repository 実装（`internal`） | `RaceRepositoryImpl` `OddsSnapshotRepositoryImpl` `BotRunRepositoryImpl` `AppUserRepositoryImpl` `RaceScheduleRepositoryImpl` `SignalRepositoryImpl`（ポートの全メソッド） |
| Entity + DAO（`internal`、Doma kapt、SQL ファイル方式） | `RaceDao` `OddsSnapshotDao` `BotRunDao` `SignalDao` `RaceScheduleDao` `AppUserDao`（SQL 25 本は `src/main/resources/META-INF/jp/jrabot/persistence/dao/`） |
| 変換・方言・接続 | `mapping/Mapping.kt`（Instant⇔UTC、ENUM）、`doma/UtcJdbcMappingVisitor.kt`（JVM のタイムゾーン非依存）、`jdbc/DbSettings.kt` `jdbc/DataSourceFactory.kt`（HikariCP、UTC、`useAffectedRows`）、`PersistenceConfiguration.kt`（Spring 配線。公開 API はこの 3 つ） |
| 単体テスト（Docker 不要、15 件） | `SqlTemplateTenantGuardTest`（6。SQL から `tenant_id` 条件を外すと失敗。実ファイルの変異で検証）`DbSettingsTest` `MappingTest` `PersistenceModuleTest` |
| 統合テスト（Testcontainers、86 件） | `WiringIntegrationTest`(5) `RaceRepository…`(9) `OddsSnapshotRepository…`(8) `BotRunRepository…`(13) `SignalRepository…`(13) `RaceScheduleRepository…`(8) `AppUserRepository…`(6) `TenantIsolation…`(7) `TimeConversion…`(4) `EnumContract…`(6) `Permissions…`(7)。スキーマは本物の db-migration CLI（installDist）で適用 |
| ビルド・CI | `persistence/build.gradle.kts`（kapt、`integrationTest`、`-PconnectorJVersion`）、`gradle/libs.versions.toml`（Doma 3.14.1、doma-spring-boot 3.0.0）、`.github/workflows/ci.yml`（`db-integration` に :persistence を追加、期待 11 クラス・件数・スキップ・失敗の検証、非ゲートの `persistence-connectorj26`） |
### テナント分離の検証結果
`TenantIsolationIntegrationTest`（テナント 2 件）: 他テナントのレース・オッズ・予定・BotRun・Signal・利用者は find/list で見えず、`update`/`claim`/`markSent`/`markSendUnknown` は NotFound/false で元の行は不変。同じ自然キーを T2 が書いても T1 の行は変わらない。他テナントの親（レース）を使ったオッズ・Signal の保存は複合 FK（1452）で拒否（DAO 経由と生 SQL の両方）。存在しないテナントは空/ null（全件へフォールバックしない）、`TenantId.of(0)` は拒否。**SQL 静的検査**: 全 SQL が `tenant_id` を束縛で必須（例外は `findByEmail` の 1 本のみ、許可リストに固定）。OR・定数のテナント・`SET tenant_id`・WHERE 無し・未知の表は違反。実ファイルから条件を外すと CI が失敗することを実際に確認した。
### 楽観ロック・冪等・排他の検証結果
楽観ロック（`bot_run`・`race_schedule`）: 一致する version で成功し +1、古い version は Conflict、他テナント/不在は NotFound。**2 接続の同時更新で成功は常に 1 つ**（10 回/8 回の反復）。冪等: `races`/`odds` の upsert は CREATED→UNCHANGED（`updated_at` 不変）→UPDATED、オッズの再送は行を増やさず、途中で失敗したスナップショットは 1 行も残らない。bot の二重起動: 2〜6 接続の同時 `tryStart` で勝者は常に 1 つ（15 ラウンド）、負けは勝者の runId を返す。主キー重複は AlreadyActive にならず 1062 のまま投げる。INTERRUPTED で `active_slot` が NULL になり同日に再開始できる。通知: `claim` は 8 接続の同時でも勝者 1 つ、不正な遷移（逆戻り・別の終端・UNSENT からの終端）は拒否、再実行は冪等、`insertIfAbsent` の再送は通知の状態を戻さない。
### MySQL 8.4 / 9.7 のテスト結果
上記 CI の件数のとおり、両方で 86 件合格（`db integration`）。9.7 のマイナー版はログに残していない。D-069（9.7.4 の自己参照 FK）の問題は `ml_model` の DDL で対処済みで、今回の DAO には影響しなかった。
### Doma と Connector/J の互換性
G-VER-2（Doma 3.14.1 kapt × Kotlin 2.4.21 × Boot 4.1.2 × starter 3.0.0）・G-VER-3（UTC・JSON・UPSERT・楽観ロック・複合キー）は**合格**、Java DAO への退避は不要だった（PoC なし）。Doma 標準の `Timestamp` 経由は JVM のタイムゾーンで値がずれるため使わず、`setObject`/`getObject` の方言を使う（D-073）。G-DB-0b: Connector/J 26.10.0 は 8.4・9.7 で 86 件合格（非ゲート。**採否は未決**、9.7.0 を既定のまま維持）。詳細は VERSIONS §10。
### 判断（DECISIONS D-070〜D-075）
D-070 技術構成 / D-071 楽観ロックと更新の意味 / D-072 冪等性・重複の扱い（`useAffectedRows=true` が前提）/ D-073 時刻・型変換 / D-074 権限（`R__grants.sql` は変更なし）/ D-075 テスト構成と SQL 静的検査。契約: CONTRACTS 0.4.2（§8.1 に JDBC の固定値）。
### 新たに見つかった仕様差異・注意（完了扱いにしない）
- **`app_user` を読めるのは `app` だけ**、`ingest` は `bot_run`・`signals` も読めない（R__grants.sql どおりで不足ではない）。bot / ingest のテナントは起動引数（`--tenant-id`）で渡す設計のまま。
- `tryStart` / `insertIfAbsent` は長い REPEATABLE READ のトランザクション内で呼ばない前提（STAGE2_HANDOFF §14.3）。デッドロック（1213）の自動再試行は未実装（並行テストでは発生せず）。
- `post_time_unknown` は `postTime == null` で 1（domain の「不明なら null」に合わせた解釈）。`race_schedule.kai/nichime` は domain に無く NULL のまま。
- `bot_run.checkpoint` は JSON 列なので、不透明な文字列でも**妥当な JSON** でなければ保存できない（domain の KDoc の想定より厳しい）。T3.3 で型付けするときに確認する。
### 未解決・注意（引き継ぎ。完了扱いにしない）
- T1.16 / T1.17 / T1.20 / T1.21、I-H1〜I-H7、T5.0（infra 契約同期）、Cloud SQL の実環境検証（G-DB-1/5/6/7）、G-DB-4（負荷）、MySQL 9.7 の採否（D-069 の原因確認が未了）、Connector/J 26.x の採否、D-064・D-066 の未決定事項、T3.4 の HMAC とトークンハッシュの整合、mltrain のスキーマ版確認方法は**未完了のまま**。
- T2.5 の残り（イメージ・Dependabot・release-manifest）、T2.6（ゴールデン）、T2.7、T2.8 は未着手。
### 次のタスク（T2.7 への引き継ぎ）
**T2.7**（`:cloud-gcp` の `ModeProvider`: GCS 実装 + ローカル実装。CONTRACTS §5.2。`:persistence` には依存しない。HTTP をフェイクにした単体テストで足りる）。**T2.8** は T2.7 の後で、`DataSourceFactory` + `PersistenceConfiguration` を組み込み、3 アプリの `excludeName` を外す（STAGE2_HANDOFF §14.3）。ブランチは main から作り直す（PR #8 のマージ後）。
### Git
ブランチ `claude/stage2-persistence-doma`、PR #8（main 向け、マージはユーザー）。コミット: 931f12a（T2.4 本体+テスト+CI）→ 2fd2b79（docs）→ 5730d70（アプリの自動構成の除外）→ 本 docs。

---

## Stage 2-C（claude/stage2-db-migration, PR #7）
### 結果
- **T2.3 実装完了**（`:db-migration`: Flyway V1/V2/`R__grants.sql`、CLI `--mode=info|validate|migrate`、単体・統合テスト、CI）。T2.4 以降には進んでいない。
- **CI（GitHub Actions、Temurin 25）の結果**: run 39882827525（head 13ccf21、T2.3 の全コード）で **build (Temurin 25) / repo hygiene / db integration (MySQL 8.4) / db integration (MySQL 9.7) の 4 つがすべて成功**。統合テストは 44 件（実行 44・スキップ 0・失敗 0）を両バージョンで確認（ジョブ内の検証ステップが「0 件・スキップ・失敗」を失敗にする）。その後の docs だけのコミットで CI が再実行される（結果は PR のチェックを参照）。
- 途中の失敗（隠さず記録）: 初回の統合テストは 8.4 で 2 件、9.7 で 3 件失敗した。共通の 2 件はテストの期待値の誤りと CLI の不具合（変更された Repeatable が `pending` に 2 回出る → `distinct()`、履歴の無い非空スキーマの validate は 10 が正）。9.7 だけの 1 件は **MySQL 9.7.4 が複合の自己参照 FK を検査しない**という実際の差（D-069、DDL の列順を変更して両方で検査されるようにした）。
### セッションの環境（CLAUDE.md の記録義務）
`java -version` = OpenJDK 21.0.12（JDK 25 なし。`-PjdkVersion=21` のスモークのみ）、Gradle 9.7.1 は動作、Docker は CLI のみでデーモン無し。`dockerd` を起動しようとしたがサンドボックスの判定で許可されなかったため、**クラウドセッションでは DB を起動していない**。したがって DB を使うテストの結果はすべて GitHub Actions のもの。Maven Central の 429 は前回と同じく GCS ミラーを `~/.gradle/init.d`（Git 外）に置いて回避。
### 実装したもの
| 区分 | ファイル（`db-migration/`） |
|---|---|
| SQL | `src/main/resources/db/migration/V1__baseline.sql`（20 表）・`V2__ops_calendar_ingest_ml.sql`（13 表）・`R__grants.sql`（4 ユーザー: リセット → 付与） |
| CLI | `Cli.kt` `CliArgs.kt` `DbConfig.kt`（`Redactor`）`ErrorClassifier.kt` `ExitCodes.kt` `Json.kt` `MigrationEngine.kt` `FlywayEngine.kt` `DbMigrationMain.kt` |
| 単体テスト（Docker 不要、57 件） | `CliArgsTest` `CliTest`（フェイクエンジンで全モードの終了コード・JSON・秘密の非出力）`DbConfigTest` `JsonTest` `ErrorClassifierTest` `ProcessExitCodeTest`（実プロセスで 2 / 75）`MigrationScriptsTest`（SQL の静的検査: 表の一覧、tenant_id 先頭、複合 FK、権限の範囲） |
| 統合テスト（Testcontainers、44 件） | `MigrationApplyIntegrationTest` `SchemaConstraintsIntegrationTest` `GrantsIntegrationTest` `CliIntegrationTest` `MigrationFailureIntegrationTest`（+ `MySqlSupport` `CliHarness`） |
| ビルド・CI | `build.gradle.kts`（`integrationTest` タスク）、`gradle/libs.versions.toml`（Flyway 13.11.1）、`build-logic`（Flyway は `:db-migration` だけ、`:db-migration` は Spring/Doma に依存しない）、`.github/workflows/ci.yml`（`db-integration` ジョブ 8.4/9.7。既存ジョブは変更なし）、`.editorconfig`（統合テストの SQL 文字列の行長） |
### テスト結果の一覧（どこで実行したか）
| テスト | 実行場所 | 結果 |
|---|---|---|
| `:db-migration:test` 57 件、`:build-logic:test`、`:domain:test`、`ktlintCheck`、`checkModuleDependencies` | クラウドセッション（JDK 21 のスモーク） | 成功。JDK 25 の結果ではない |
| 同上 + `./gradlew build`（全モジュール） | GitHub Actions（JDK 25） | **成功**（run 39882827525） |
| 統合テスト 44 件 × MySQL 8.4 | GitHub Actions | **成功**（スキップ 0） |
| 統合テスト 44 件 × MySQL 9.7（9.7.4） | GitHub Actions | **成功**（D-069 の対処後。初回は 3 件失敗） |
| 統合テストのクラウドセッションでの実行 | — | **未実施**（Docker デーモンを起動できない） |
統合テストの確認項目（依頼の 1〜18）: 空 DB への V1/V2/R__ 適用、表・列・型・索引、複合 FK による越境拒否（主要な子表のすべて）、UNIQUE（app_user のメール全体一意、signals 自然キー、bot_run の二重実行、ml_model の ACTIVE、取込の冪等キー）、bot_run の 8 状態（INTERRUPTED 含む）と version の楽観ロック、signals の通知 4 状態と claim の原子性、マイクロ秒の往復、validate 0 / 10 / 1、DB 不達 75（実プロセス）、引数エラー 2、migrate 再実行の冪等、権限（許可・拒否・DDL 拒否・余分な権限の除去）、失敗版の記録と repair しないこと、GRANT 先ユーザー不在、migrate の GRANT 権限不足。
### 判断（DECISIONS D-063〜D-069）
D-063 app_user のメールは全体で UNIQUE / D-064 キー設計（odds の PK 順、signals の自然キーに cycle_id 列を設けない、通知 4 状態）/ D-065 設計案との差分と権限の範囲 / D-066 未決定のまま残したもの / D-067 技術構成 / D-068 CLI の出力と 75 の範囲 / D-069 MySQL 9.7.4 の自己参照 FK。
### 未解決・注意（引き継ぎ。完了扱いにしない）
- **MySQL 9.7 の採否**: 実行できたが、D-069 の挙動の原因（MySQL の不具合か仕様か）を確認していない。8.4 を暫定基準のまま維持（D-046）。Connector/J 26.x（G-DB-0b）・Doma（G-VER-2/3）は T2.4。
- **Cloud SQL は未検証**: G-DB-6（migrate が GRANT/REVOKE できるか、新規ユーザーの既定権限）、G-DB-5、G-DB-7、G-DB-1、G-COST-1、G-DB-4（負荷）。MySQL コンテナの成功を Cloud SQL の検証とは扱わない。
- T1.16 / T1.17 / T1.20 / T1.21 は**未完了のまま**。馬番・レース番号の上限 99（D-060）、`bets.status` の値、`race_evaluations` の一意キー（D-066）は保留。
- T2.5 の残り（イメージ・Dependabot・release-manifest）、T2.7、T2.8、T5.0（infra 契約同期）、infra I-H1〜I-H7 は未着手。
### infra への引継ぎ（追加。infra のセッションで反映）
1. DB ユーザー `app` `ingest` `bot` `mltrain` は infra が**先に**作る（無いユーザーへの GRANT で migrate は失敗する）。`migrate` には `GRANT ALL PRIVILEGES ON <schema>.* TO migrate WITH GRANT OPTION` まで付ける（`R__grants.sql` は migrate の権限に触れない）。Cloud SQL で可能かは G-DB-6。
2. `db-migration` の終了コード: **75 は接続系だけ**（認証失敗・DB 不在は 1）。`validate` の 10 には変更された `R__grants.sql` も含まれる。標準出力は JSON 1 行（CONTRACTS §4.2 の補足、0.4.1）。
3. start の S4 で `validate` が 10 のとき、履歴の無い非空スキーマでも 10 になる（migrate が 1 で拒否する）。
4. 観察（T3.4 で確定すべき）: CONTRACTS §3.3 は「トークンはハッシュだけ保存」と「HMAC-SHA256(token, …) で署名」を両方書いており、サーバがトークンを持たないと HMAC を検証できない。HMAC の鍵をどう導出するか（例: トークンから導いた別のハッシュ）を決める必要がある。DB 側は `agent.token_hash CHAR(64)` で足りる。
5. `mltrain` は `flyway_schema_history` を読めないので、ml-train の `REQUIRED_SCHEMA_VERSION` 検査（§4.3）をどうするか要決定（T3.x）。
### 次のタスク
**T2.4**（`:persistence` の Doma DAO。引き継ぎは STAGE2_HANDOFF §14.2）。T2.7（`ModeProvider`）は並行して着手できる。ブランチは main から作り直す（PR #7 のマージ後）。
### Git
ブランチ `claude/stage2-db-migration`、PR #7（main 向け、マージはユーザー）。

---

## Stage 2-B（claude/stage2-domain-vo, PR #6）
### 結果
- **T2.2 実装完了**（`:domain` の値オブジェクト・業務モデル・Repository・ポート・単体テスト）。T2.5 は前半のみ完了のまま（変更なし）。
- CI（JDK 25）の結果: run 39832361545（head 3d8b0cb、T2.2 の全コード + docs）: build (Temurin 25) 成功（`./gradlew check` + 依存規則の明示実行）/ repo hygiene 成功。その後の PROGRESS 更新だけのコミットは docs のみで、同じ CI がもう一度走る（結果は PR のチェックを参照）。先行の run 39832300265（010b24a）は後続の push に置き換えられて cancelled
### セッションの環境（CLAUDE.md の記録義務）
`java -version` = OpenJDK 21.0.12（JDK 25 なし）、Gradle Wrapper 9.7.1 は動作、Docker CLI あり・デーモンなし（今回 Docker 依存のテストは無い）。`repo.maven.apache.org` / `repo1.maven.org` が 429 を返すため、ユーザー領域（`~/.gradle/init.d`、Git 外）に Google の Maven Central ミラーを先に試す init script を置き、再試行して解決した。**ローカルのフルビルド（Spring Boot の 3 アプリ）は依存取得の 429 で完了できなかったため、ローカルでは `:domain:test` `:build-logic:test` `ktlintCheck` `checkModuleDependencies` のみ実行**（アプリ 3 モジュールのビルドは CI が正）。
### 追加した型とインターフェース（`jp.jrabot.domain.*`）
- 値オブジェクト: `tenant.TenantId`（通常クラス）・`tenant.EmailAddress`・`race.RaceId`・`race.Venue`・`race.HorseNumber`・`odds.Odds`・`value.Probability`・`value.Money`・`ops.OpsMode`・`signal.SignalId`・`bot.RunId`・`ml.ModelId`/`DatasetId`・`agent.AgentId`（`TenantId` 以外は `@JvmInline value class`）。
- 業務モデル: `Tenant` `AppUser` `Race` `RaceSchedule` `OddsEntry`/`OddsSnapshot`/`Market` `Signal`/`NotificationState` `BotRun`/`BotRunState`/`BotRunTrigger` `ModelArtifact` `DatasetDescriptor` `Notification` `AgentHeartbeat` `JobLaunchRequest`。`TenantScoped` がテナントを持つモデルの印。
- Repository（Reader/Writer 分離）: Race、RaceSchedule、OddsSnapshot、Signal、BotRun、AppUser（読取りのみ）。結果型 `InsertOutcome` `UpsertOutcome` `UpdateResult`。
- ポート: `Clock`/`SystemClock` `ModeProvider` `JobLauncher` `ModelReader`/`Writer`/`Store` `DatasetReader`/`Writer`/`Store` `Notifier` `HeartbeatMirror`。**各ポートの責務・入出力・エラー・テナント・冪等性・実装先は STAGE2_HANDOFF §14**。
- 採用理由は DECISIONS D-060（値オブジェクト）・D-061（ポート）・D-062（EmailAddress）。
- 作らなかったもの（必要性が未確認）: `Horse`/`RaceHorse`（特徴量を実装する T3.6 で追加）、`TenantRepository`、`race_day`/`calendar_override`/`race_schedule_event` のモデル（T3.3）、EV/Kelly/確率/ワイド・三連複の計算（Stage 3）、Doma/Entity/SQL（T2.3/T2.4）。数値の挙動（legacy/v2、市場ブレンド、Kelly 係数、E1〜E15、閾値、丸め）は一切変更していない。
### 依存検査の強化（`build-logic`）
`:domain` に HTTP クライアント（`java.net.http`・OkHttp・Ktor・Apache）、Jackson、JPA/Hibernate、AWS/Azure/Google API・gRPC、Flyway を禁止。import だけでなく**完全修飾名での直接参照**も検出。`java-test-fixtures` が付ける自己依存は違反にしない。単体 +3、TestKit +2（`domain` が import 無しで HTTP クライアントを使うと失敗することを実ビルドで確認）。
### テスト（ローカル JDK 21 のスモーク。JDK 25 の結果ではない）
| 対象 | 結果 |
|---|---|
| `:domain:test` | 214 件成功（うち既存 2、`JdkRuntimeTest` は `EXPECTED_JDK` が無いため何もしない） |
| `:build-logic:test` | 18 件成功（既存 13 + 追加 5） |
| `ktlintCheck` / `checkModuleDependencies` | 成功 |
| ローカルのフルビルド（`:bot` `:ingest` `:dashboard`） | **未実施**（429 で依存を取得できず）。CI で確認 |
主な検証: 正常値・不正値・境界（`Odds` 1.0 / 0 / 負 / NaN / Infinity、`Probability` 0・1・範囲外・-0.0・正規化、`Money` の桁あふれ・負・合計）、`RaceId`（桁数・全角数字・閏年・場コード・レース番号・往復・16 桁導出）、等価性と hashCode、`OpsMode`（UNKNOWN は NORMAL にならない・正本に無い表記は UNKNOWN）、UTC⇔JST（15:00Z の境界・年またぎ）、固定時計、テナント境界の契約（全ポートの第一引数が `TenantId`/`TenantScoped`、例外は `findByEmail` だけ。ドメイン外の型を出さない）、`Clock.kt` 以外での時刻の直接取得の禁止、パストラバーサルになる ID の拒否。**性質テスト（乱数）は入れていない**（パラメータ化テストで境界を網羅。必要なら T3.1 で追加）。
### 未解決・注意（引き継ぎ）
- T1.16 / T1.17 / T1.20 / T1.21 は**未完了のまま**。infra の I-H1〜I-H7（下の「infra への引継ぎ」）も第5段階の前に必要。
- `RaceId` のレース番号・`HorseNumber` の上限（1〜99）、`Odds` の下限（正の有限値）は公式仕様（T1.17）の確認後に再検討（D-060）。
- RACE_CALENDAR_DESIGN の `bot_run` DDL に `INTERRUPTED` が無い（CONTRACTS §9.2 にはある）。T2.3 で DDL を合わせる（STAGE2_HANDOFF §14.1）。`cycle_id` と `observed_at` の関係（Signal の自然キー）も T2.3/T3.3 で確定。
- `DomainModule`（Stage 2-A のマーカー）は他モジュールが参照しているため残している。T2.4/T2.7 で実型を使うときに外す。
- 固定時計 `FixedClock` はテスト内。2 つ目のモジュールが必要としたら `testFixtures` に移す（ARCHITECTURE §2）。
### 次のタスク
**T2.3**（`:db-migration`: V1/V2/`R__grants.sql` と CLI `--mode`。G-DB-0。STAGE2_HANDOFF §14.1 の整合事項を反映）。ブランチは `claude/stage2-db-migration`。T2.3 は Docker（Testcontainers）が必要で、クラウドセッションでは実行できないため CI で確認する。並行して T2.7（`ModeProvider` の GCS 実装、HTTP をフェイクにした単体テスト）も着手できる。
### Git
ブランチ `claude/stage2-domain-vo`、PR #6（main 向け、マージはユーザー）。コミット: 010b24a（T2.2 本体）、3d8b0cb（docs）、PROGRESS 更新。

---
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
| I-H8 | (T2.8 / CONTRACTS 0.4.4) **`APP_ENV`（local/prod）を全アプリに必ず渡す**（無いと起動/Job が失敗）。`REQUIRED_SCHEMA_VERSION` は環境変数で渡さない（jar に焼き込み）。dashboard は `IAP_AUDIENCE`・`OPS_SA_EMAIL`、ingest は `INTERNAL_ID_TOKEN_AUDIENCE`・`OPS_SA_EMAIL` が無いと起動しない。本番の DB ユーザーは `app`/`ingest`/`bot` 以外だと起動しない。`/internal/ready` は理由付きの 503（`DB_UNAVAILABLE` 等）を返す。dashboard の `/internal` は ops 以外の IAP 主体に 403。bot の Job は引数エラー・設定不正が exit 2 | CONTRACTS §2・§4.3・§9.1・§11 |
| I-H9 | (T2.5後半 / CONTRACTS 0.4.5) **イメージと manifest**: ① release-manifest は本番スキーマ（`docs/schemas/release-manifest.v1.json`）で検証し、`kind`/`deployable` を持つプレビュー（`manifest-preview.json`）を拒否する。`images.*` は `…-docker.pkg.dev/<prj>/jra-bot/<name>@sha256:<64桁>` の 5 つ（ml-train を含む。現時点で ml-train は未実装＝本番 manifest は未生成）。② コンテナは uid 10001 の non-root、`ENTRYPOINT` は `java` の exec 形式で `CMD` なし。Cloud Run Jobs では `command` を上書きせず `args`（`--task=… --tenant-id=…` / `--mode=…`）だけ渡す。③ web アプリは `PORT`（既定 8080）で待受け。Dockerfile に `HEALTHCHECK` は無い → startup/liveness プローブ（`/internal/health` は無トークンだと 401 を返す点に注意: 認証付きプローブか TCP プローブか HTTP でのヘッダー付与を infra で決める。G-APP-1）。④ ルート FS は読取り専用でも動く（一時ファイルは `/tmp`）。 | CONTRACTS §1・§10 |

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
