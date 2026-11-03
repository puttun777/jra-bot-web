# CI の運用と Docker イメージ（T2.5 後半）

CI の実行範囲の決め方、分岐保護の設定、イメージのビルド・スモーク、release-manifest の生成、Dependabot、無料枠の見積り（Private の GitHub Free は月 2,000 分。Public 化後は標準ランナーが無料・無制限の見込みで、下の見積りは Private の場合。公開後の実測は未確認）。判断は DECISIONS D-093〜D-100、契約は CONTRACTS §10（0.4.5）。実測値は PROGRESS の Stage 2-H。

## 1. CI の構成（`.github/workflows/ci.yml`）
| ジョブ | 内容 | いつ実行するか |
|---|---|---|
| `changes and repo hygiene` | 変更ファイルの分類（`tools/src/ci/classify.ts`）+ 秘密/実データの混入検査 + 整合検査（`consistency`） | **常に** |
| `build (Temurin 25)` | `./gradlew build`（コンパイル・単体・ktlint・依存方向）、JDK 25 の確認、domain/golden-runner/cloud-gcp/アプリ/persistence の単体テスト件数の検証、**Kotlin と旧 Python のゴールデン比較**（`:golden-runner:run --args="check …"`。T3.1、D-107。D-258 で Kotlin だけに） | Kotlin・Gradle・ビルド定義・CI・**`tools/golden`（`.md` 以外）・`golden-runner`** の変更 |
| `db integration (MySQL x)` | Testcontainers の MySQL。スイート: `migration`（:db-migration）/ `persistence`（:persistence）/ `apps`（:bot :ingest :dashboard） | 下表のとおり範囲が変わる |
| `release tools and ci scripts (TypeScript)` | `tools/`（TypeScript。D-254）の Biome（lint・format）・tsc（strict）・単体試験（node:test。件数下限 150・skip/todo/失敗 0 を JUnit XML で検査）。release-manifest・Dockerfile 静的検査・判定・ゲート・ワークフロー規則・JUnit 件数検査・preflight（Docker 不要） | リリース関連・Dockerfile・Dependabot 設定・マイグレーション・CI・`tools/` の変更 |
| （削除: `golden tests (Python 3.12)`） | 2026-11-03（D-258）。フィクスチャの検証と Kotlin との比較は `build` ジョブの `golden-runner check`（Kotlin だけ）。旧実装からの再生成の一致は CI から外した（LANGUAGE_MIGRATION §5） | - |
| （削除: `ml tools (Python 3.12)`・`ml-train (Python 3.13)`） | 2026-11-02（D-257）。ML 学習は Kotlin の `:ml-train` になり、単体試験は `build` ジョブ（件数を verify-junit で検査）、export-dataset → ml-train（jar を別プロセス）→ ModelLoader の結合試験は `db integration` の `apps` スイート（MySQL 8.4/9.7） | - |
| `agent dotnet (linux)` | Windows 取得エージェント（`agent/cs`。C#/.NET 10。D-261）: `dotnet restore --locked-mode`・Release ビルド（警告はエラー）・xunit（試験の実行ファイルを `-result-junit` で直接起動）。件数下限 290・skip は Windows 専用の 4 件まで・必須クラスを verify-junit で検査 | `agent/`（`.md` 以外）の変更、フル検証 |
| `agent windows` | 同じソリューションを `windows-2025` で。DPAPI・ジョブオブジェクト・COM 登録の確認・ジョブ付きワーカーのハング回復を含む全件（skip は Linux 専用の 3 件まで） | Windows 固有のファイル（`JraAgent.Windows`・ワーカー・ロック・秘密・Transport・版の固定・`scripts/`）の変更、フル検証 |
| （削除: `agent python (3.12/3.13)`） | 2026-11-03（D-261）。Python のエージェントは C# へ。Kotlin との結合試験（`AgentInteropIntegrationTest`）は `db integration` の `apps` スイートで C# のドライバ（`jraagent-interop`）を起動する | - |
| `images / images (build + smoke, no push)` | 5 イメージ（JVM 4 + ml-train）のビルド、スモーク、ドライラン manifest（`images.yml`） | Dockerfile・リリース・ビルド定義・マイグレーション・アプリの build 定義/リソース・CI の変更、フル検証 |
| `connectorj26 / connector/j 26.x check (MySQL x)` | Connector/J 26.x の互換性確認（**非ゲート**、`compat-connectorj26.yml`） | Connector/J・DB 関連の依存設定の変更、手動、月次 |
| `ci gate` | 最終集約。**常に実行**。実行が必要なジョブが success でなければ失敗 | 常に |

### 1.1 分類（ファイル → 実行するジョブ）
`tools/src/ci/classify.ts` の `RULES` が正本（上から最初に当たった規則）。要点:
| 変更したもの | build | DB 統合 | （golden: 削除） | release-tools | images | connector/j 26.x |
|---|---|---|---|---|---|---|
| docs / `*.md` のみ | - | - | - | - | - | - |
| `bot` `ingest` `dashboard` `cloud-gcp`（ソース・テスト） | ○ | 8.4 × apps | - | - | - | - |
| `domain` | ○ | 8.4 × persistence + apps（Doma の変換・Enum 契約に影響するため） | - | - | - | - |
| `persistence` | ○ | **8.4 + 9.7 × 全スイート** | - | - | - | - |
| `db-migration`（ソース） | ○ | 8.4 + 9.7 × 全スイート | - | - | ○（CLI が焼き込まれる） | - |
| Flyway の `V*.sql` | ○ | 8.4 + 9.7 × 全スイート | - | ○（`flyway.latest_version`） | ○ | - |
| `gradle/`・`build-logic/`・`*.gradle.kts`（root）・`gradle.properties`・`.editorconfig`（Spring Boot/Doma/Flyway の版・設定が DB 連携に影響） | ○ | 8.4 + 9.7 × 全スイート | - | - | ○ | `libs.versions.toml` のみ ○ |
| `persistence/build.gradle.kts` `db-migration/build.gradle.kts` | ○ | 8.4 + 9.7 × 全 | - | - | ○ | ○ |
| `bot|ingest|dashboard/build.gradle.kts`（requiredSchemaVersion） | ○ | 8.4 × apps | - | ○ | ○ | - |
| `Dockerfile` `.dockerignore` | - | - | - | ○ | ○ | - |
| `tools/src/release/`・`tools/test/release/` `docs/schemas/` `docs/release/` `docs/CONTRACTS.md` | - | - | - | ○ | `tools/*/release` のみ ○ | - |
| `tools/golden/fixtures/`・`catalog.json` | ○（検証と Kotlin との比較が build ジョブの中にあるため。DB 統合は不要） | - | - | - | - | - |
| `ml-train/`（Kotlin。D-257） | ○ | ○（`apps` スイート。:bot の結合試験が ml-train の jar を起動するため） | - | `build.gradle.kts` のみ ○ | `src/main` と `build.gradle.kts` は ○ | - |
| `golden-runner`（Kotlin ランナー） | ○ | -（`:domain` に依存するだけで、persistence/アプリは使わない） | - | - | -（イメージの jar には入らない。Dockerfile は COPY のみ） | - |
| `.github/dependabot.yml` | - | - | - | ○ | - | - |
| `.github/workflows/`・`.github/scripts/`・`.github/actions/`・`tools/src/{ci,lib}/`・`tools/test/{ci,lib}/`・`tools/tsconfig.json`・`package.json`・`package-lock.json`・`biome.json` | ○ | 8.4 + 9.7 × 全 | ○ | ○ | ○ | 本ワークフローのみ ○ |
| 分類できないファイル・差分が空・取得失敗・想定外のイベント | **全部（fail closed）** | 全 | ○ | ○ | ○ | - |
| `workflow_dispatch`・月次 `schedule`（フル検証） | ○ | 全 | ○ | ○ | ○ | ○ |
- 差分の取り方: PR は merge コミット（HEAD）とその第 1 親の差分、push は `before..HEAD`。いずれも `git diff --no-renames -z`（ファイル名の空白・日本語に対応）。

### 1.2 最終ゲート（`ci gate`）と分岐保護
- **分岐保護の required checks は `ci gate` だけにする（ユーザー作業）**。旧名のジョブ（`build (Temurin 25)`、`repo hygiene (secrets / real data)`、`db integration (MySQL 8.4)`、`db integration (MySQL 9.7)`、`golden tests (Python 3.12)`）を required に残すと、ジョブが走らない PR で名前が出ず Pending のまま残る（`repo hygiene` は `changes and repo hygiene` に統合）。`connector/j 26.x check` は元から非 required のまま。
- ワークフロー全体の `paths` フィルタは使っていない。`ci gate` が常に実行されるので、docs だけの PR も最終チェックが正常に終わる。
- 判定規則（`tools/src/ci/gate.ts`）: 必要なジョブ = success でなければ失敗（skipped・cancelled・failure すべて失敗）／不要なジョブ = skipped か success、実行して失敗していれば失敗／`changes` が失敗していれば失敗（skipped を信用できない）／出力が欠けていれば失敗／docs 以外なのに必要なジョブが 0 件でも失敗。Job summary に『未実施（変更内容から不要と判定。テストされていない）』と出し、skipped を成功として表示しない。
- 注意: GitHub は `if` で skipped になったジョブを required check として成功扱いにする。だから個々のジョブを required にせず、ゲートが『本当に必要だったか』を判定する。

### 1.3 14 ケースで実行されるジョブ
| # | ケース | 実行されるジョブ（`changes and repo hygiene` と `ci gate` は常に） | 検証 |
|---|---|---|---|
| 1 | docs だけ | なし | 単体テスト（classify・gate）+ 実 CI（下記の実測） |
| 2 | Kotlin domain だけ | build、DB 統合（8.4 × persistence + apps） | classify の単体テスト |
| 3 | persistence だけ | build、DB 統合（8.4 + 9.7 × 全） | 同上 |
| 4 | Flyway だけ | build、DB 統合（8.4 + 9.7 × 全）、release-tools、images | 同上 |
| 5 | CI 設定だけ | build、DB 統合（8.4 + 9.7 × 全）、golden、release-tools、images（+ compat ワークフロー自体の変更なら connector/j） | 単体テスト + 本 PR の実 CI |
| 6 | Dockerfile だけ | release-tools、images | 単体テスト |
| 7 | ゴールデンフィクスチャだけ | build（`golden-runner check`） | 同上 |
| 8 | 複数領域 | 各領域の和集合（DB は広い方に合わせる） | 同上 |
| 9 | 変更判定失敗 | **全部**（fail closed。判定ジョブ自体が落ちれば gate が失敗） | 一時 git 以外のディレクトリで差分取得を失敗させて確認 |
| 10 | 必須テスト失敗 | gate が失敗 → PR は赤 | gate の単体テスト + **実 CI で確認**（マージ後の main の run 39931233623 と Dependabot の numpy PR の run 39931387962 で golden が失敗 → `ci gate` が失敗。D-101 の事象。故意の失敗ではない） |
| 11 | 非必須の互換性テスト（connector/j 26.x）失敗 | gate は成功のまま（表に失敗を表示） | gate の単体テスト。ジョブ内も `continue-on-error` |
| 12 | Dependabot の PR | 変更ファイルで決まる（gradle の版 → build + DB 全 + images + connector/j、Dockerfile → images、actions → 全、pip → golden） | classify の単体テスト。**実 PR で確認**（2026-10-23）: gradle-wrapper PR #14（run 39931421037）は build + db 8.4/9.7 + images が走り connector/j・Python は skipped、`ci gate` 成功（約 25 分）。numpy PR #13（run 39931387962）は hygiene + golden のみ（約 3 分）。グループ化（`gradle-minor-patch`・`python-minor-patch`）も確認。未確認: github-actions・docker の PR |
| 13 | main へのマージ後 | push の差分（`before..HEAD`）で同じ判定。月次のフル検証もある（D-099） | classify の単体テスト + 実 git での差分取得テスト。**実 main push を確認**（run 39931233623、PR #12 のマージ）: CI 設定の変更を含むため全 10 ジョブが走った（課金約 35 分: 1+1+3+1+7+6+7+4+4+1）。docs だけの main push は未観測 |
| 14 | `workflow_dispatch` でフル検証 | 全ジョブ + connector/j 26.x | classify の単体テスト。**実行は未実施**（ユーザーが Actions から手動実行） |

## 2. キャッシュ
- **Gradle**: `gradle/actions/setup-gradle` の既定。キャッシュを書くのは default ブランチ（main / schedule / dispatch）のジョブだけ。PR は読取りのみで、main 用のキャッシュを汚染できない。キャッシュが無くても動く。キャッシュには依存とビルドの中間物だけが入り、秘密は入らない（Secrets を使っていない）。
- **Docker**: Dockerfile の Gradle 取得層（Wrapper の配布物）は層キャッシュ、依存のダウンロードは BuildKit のキャッシュマウント（イメージの層に残らない）。4 ターゲットは同じ `build` ステージを共有するので Gradle は 1 回だけ。ジョブをまたいだ Docker レイヤーキャッシュ（`actions/cache` 等）は導入していない（外部アクションの追加と汚染対策が必要で、images の実行頻度が低いため）。
- **npm**: `tools/` の依存は `package-lock.json` で推移的依存まで固定。`npm ci --ignore-scripts`（install スクリプトを実行しない）。依存が少ない（直接 6 件）ので npm のキャッシュは使わない（`.github/actions/setup-tools`）。
- **Python**: CI で Python を実行するジョブは無い（golden: D-258、ml: D-257、agent: D-261 で廃止）。.NET は NuGet のキャッシュを使わない（restore は数十秒）。
- GCP などクラウドのリソースは一切使わない。

## 3. 無料枠の見積り
実測は PROGRESS の Stage 2-H（run ID 付き）。課金は**ジョブごとに分単位で切り上げた実行時間の合計**（ubuntu は 1 倍）で、並列実行の壁時間ではない。最適化前の実測は run 39921807006（1 回 20 分相当）。

### 3.1 最適化前（実測）
run 39921807006（T2.6、head 6f24519。全ジョブが毎回実行）。壁時間 → 課金（分単位切り上げ）: build 64 秒 → 2、golden 19 秒 → 1、repo hygiene 6 秒 → 1、db 8.4 264 秒 → 5、db 9.7 298 秒 → 5、connector/j 8.4 133 秒 → 3、connector/j 9.7 145 秒 → 3。**合計 7 ジョブ・20 分/回**（Gradle キャッシュが温まっていた状態）。docs だけの変更でも 20 分。PR の更新ごと + main へのマージごとに実行されていた。

### 3.2 最適化後（実測 + 推定）
**実測**: run 39927310473（本 PR の head 3ba8cb6。CI 設定の変更なので**全ジョブ + Connector/J 26.x**が走る最悪ケース。Gradle キャッシュは PR 用で温まっていない）。全ジョブ成功。壁時間 → 課金: changes and repo hygiene 6 秒 → 1、release tools 7 秒 → 1、golden 21 秒 → 1、build 183 秒 → 4、db 8.4 263 秒 → 5、db 9.7 335 秒 → 6、connector/j 8.4 212 秒 → 4、connector/j 9.7 209 秒 → 4、images 358 秒 → 6（ビルド 189 秒 + MySQL 取得 23 秒 + スモーク 133 秒）、ci gate 6 秒 → 1。**合計 10 ジョブ・33 分**（connector/j を除くと 25 分）。
**推定**（上の実測ジョブ時間と、最適化前の各ステップ時間（スイート別: db-migration 66 秒 / persistence 93 秒 / apps 71 秒）からの積み上げ。シナリオごとの実測は未実施。Gradle の build は温まっていれば 1〜2 分、冷えていれば 3〜4 分）:
| ケース | 実行ジョブ | 課金の目安（分/回） | 最適化前 |
|---|---|---|---|
| docs だけ | changes + gate | **2**（6 秒 + 6 秒を各 1 分に切り上げ） | 20 |
| golden だけ | changes + golden + gate | 3 | 20 |
| bot/ingest/dashboard のみ | changes + build + db(8.4 × apps) + gate | 7〜9 | 20 |
| domain のみ | changes + build + db(8.4 × persistence + apps) + gate | 9〜11 | 20 |
| persistence / Flyway | changes + build + db(8.4 + 9.7 × 全) + gate（Flyway は + release-tools + images） | 15〜17（Flyway は 22〜24） | 20 |
| Dockerfile だけ | changes + release-tools + images + gate | 9 | 20 |
| gradle の版（Dependabot） | changes + build + db(8.4 + 9.7) + images + connector/j(8.4 + 9.7) + gate | 28〜32 | 20 |
| CI 設定 | 上のとおり（connector/j なし） | 25（実測） | 20 |
| フル検証（手動・月次） | 全ジョブ + connector/j | 33（実測） | 20 |
- 月間 2,000 分に対する目安（**推定**）: 仮に月に PR の push 40 回（docs 10 / アプリ 12 / DB 6 / Docker・リリース 4 / CI 3 / その他）、main へのマージ 15 回（平均 8 分）、Dependabot 8 件（平均 12 分）、月次フル 1 回とすると、20 + 108 + 96 + 36 + 75 + 120 + 96 + 33 ≈ **580 分**。最適化前の同じ回数（63 回 × 20 分）は約 1,260 分。docs 中心の月はさらに少ない。Stage 3 で domain の変更が増えると domain ケース（9〜11 分）が支配的になる。月 300 分程度の現在の使用量（旧方式）と比べても余裕がある見込みだが、実際の使用量は Settings → Billing で確認する（ユーザー）。
- **増える側**: 最適化の追加ジョブ（changes・gate・release-tools）は各 1 分以上（分単位切り上げ）。docs だけなら 20 → 2 分だが、全ジョブが走る CI 設定の PR は 20 → 25 分（images が加わるため）。PR で `images` が走る条件を Dockerfile・リリース・ビルド定義に限定して相殺している。
- 並列実行の壁時間（最長ジョブ ≒ 6 分）と課金（ジョブ時間の合計）は別: 本 PR は壁時間 約 6 分・課金 33 分。
- さらに削る余地（未実施）: main への push でツリーが PR と同一なら省略（D-099）、images の Docker レイヤーキャッシュ、db 統合のスイート並列化（課金は減らない）。

## 4. イメージの手順
### 4.1 ビルド（ローカルのみ・push しない）
`docs/DEVELOPER_GUIDE.md` の「Docker イメージ」のコマンド。ビルド引数は `GIT_SHA`（40 桁のコミット SHA。jar の `releaseId` と OCI ラベルに入る）。レジストリのタグは使わない（デプロイは digest 指定: CONTRACTS §1）。クラウドセッションの注意: Docker Hub・Maven Central が 429 を返すことがある（時間を空けて再実行。依存はキャッシュマウントに溜まる）。企業プロキシ配下ではビルドに CA の注入が必要（リポジトリの Dockerfile は変更しない）。
### 4.2 スモークが確認すること（`tools/src/release/image-smoke.ts`、D-096。2026-11-02 に Python から移植: D-254）
| 区分 | 内容 |
|---|---|
| 静的（5 イメージ） | ENTRYPOINT が設計どおりの exec 形式で CMD なし／USER=10001:10001／OCI ラベル revision・version=Git SHA／ENV に秘密らしい名前がない・履歴に秘密の代入がない・TZ=Asia/Tokyo・HEALTHCHECK なし／JDK 25／実効 uid 10001／javac・gradle・mvn・git・curl・wget・gcc・make・unzip がない／`/app` に書けない／ファイルシステムに秘密・実データらしい名前（.env・鍵・sqlite・pkl・PDF 等）がない／`/app` の構成（jar だけ、db-migration は lib/ だけ）が想定どおり・書込み不可 |
| jar | 焼き込みの `requiredSchemaVersion` が `build.gradle.kts` と一致、`releaseId` = Git SHA／アプリに Flyway が入っていない／MySQL ドライバがある／jar 内に秘密・実データ名がない／自前リソースに秘密らしい代入がない。db-migration は同梱マイグレーションがリポジトリの `V*/R*.sql` と一致 |
| bot（DB なし、`--network none`） | 引数なし・不正 `--task`・設定不足・`--tenant-id` 欠落 = 2、ModeProvider UNKNOWN = 75、prod でローカルファイルのモード = 2、prod で GCS 不達 = 75（0 にならない）、prod で bot 以外の DB ユーザー = 2 |
| db-migration（DB なし） | 引数なし/不正 `--mode`/環境変数不足 = 2（stdout 空）、DB 不達 = 75 + JSON 1 行 |
| ingest / dashboard（DB なし） | prod で設定不足・環境なし・prod のローカルファイルモード = 起動失敗（非 0・ハングしない） |
| CI 専用 MySQL 8.4（合成ユーザー・ランダムパスワード） | db-migration: 空 DB の info（current=null）→ validate=10 → migrate=0 → validate=0 → info（current=latest）／誤パスワード=1。bot: STOPPED は 5 タスクとも 0 で **DB 接続ゼロ**（サーバの Connections 差分 = 確認用の 1 本）、NORMAL（local のみ）は DB に接続しスタブが `TASK_NOT_IMPLEMENTED`/`STUB_LOCAL_OK` と記録して 0、認証失敗 = 1、スキーマ版不足（履歴の表なし）= 1 でテーブルを作らない。ingest/dashboard: 起動して `/internal/health`・`/internal/ready` が無トークンで 401、`docker stop` が 10 秒以内に 0/143（137・OOM でない） |
| 秘密 | テスト用パスワードがどのコンテナの標準出力/標準エラー/ログにも出ない |
- **限界（未検証・誤って成功扱いしない）**: ① 本番(`APP_ENV=prod`)で NORMAL のとき bot のスタブが exit 1 になる経路はイメージでは再現できない（本番は GCS からしかモードを読めない。`BotJobIntegrationTest` が根拠）。イメージでは『prod は 0 にならない』ことだけ確認。② 実 GCP の ADC・実 IAP/ID トークンでの `/internal` 認可は未検証（G-AUTH-1、G-APP-1）。③ `/internal/health` は無トークンだと 401 なので、Cloud Run の通常の HTTP プローブは通らない（TCP プローブか認証付きプローブが必要。infra の T5.2 で決める: I-H9）。④ bot-run のループ・SIGTERM の checkpoint は未実装・未検証（G-JOB-1）。⑤ Bot のスタブが動いたことは業務機能の完成を意味しない。⑥ イメージサイズは bot 586MB / ingest・dashboard 601MB / db-migration 440MB（ローカル測定、非圧縮。ベースの Ubuntu + JRE で約 430MB）。サイズ削減（jlink・Alpine）は動作保証を優先して行っていない。

## 5. release-manifest の生成手順
1. （CI の `images`）`image-smoke` が `images-local.json`（ローカルの image ID）を出す。2. `node build/tools/src/main.js release preview …` が `manifest-preview.json` を生成（`built_at` はコミット時刻。同じ入力から同じバイト列）。3. `validate --kind preview` で適合を、`validate --kind production` で**拒否される**ことを確認（CI で自動）。成果物は Actions のアーティファクト `images-dry-run`（14 日）。
- 本番 `release-manifest.json`（`node build/tools/src/main.js release production …`）は、**以下がすべて揃うまで生成できない**（生成コマンドは検証に通らなければ何も書かない）: ① レジストリへ push した後の実 digest 5 件（push は第5段階でユーザーが承認）、② ml-train イメージ（Stage 3）、③ `feature_schema_hash`（Stage 3。ML_DESIGN §4）、④ 検証済みのロールバック範囲（初回は release 自身）、⑤ ml-train の Python 版。ローカルの image ID を digest として扱わない。
- 検証項目: `tools/test/release/manifest.test.ts`（正常、必須欠落、未知フィールド、不正 SHA、タグのみ/短い/大文字/他レジストリ/他名前/仮値の digest、不正スキーマ版、未対応 contract_version、CONTRACTS との不一致、焼き込み値との不一致、flyway 不一致、アプリ間の食い違い、不正・未検証のロールバック範囲、プレビューの本番利用拒否、プレビューへの digest 混入、ブロッカー隠し、生成の再現性、CLI の終了コード）。

## 6. Dependabot（`.github/dependabot.yml`、D-100）
gradle / github-actions（`/` と `/.github/actions/*`）/ npm（`/`。typescript・@types/node のメジャーは提案しない: D-254）/ docker（`Dockerfile`）/ nuget（`/agent/cs`。Microsoft.*・System.* のメジャーは TargetFramework と一緒に手動: D-261）。週次（月・火・金・水・木）、minor/patch は 1 グループ、PR 上限 3/2/2/2/2、自動マージなし。Spring Boot の minor/major、Connector/J の major、eclipse-temurin の major は提案しない。pip（`tools/golden`・`ml`）は Python の廃止（D-257・D-258）で削除。設定とディレクトリ構成の整合は `tools/test/ci/workflows.test.ts` が検査。**未検証**: Dependabot の実 PR（有効化後の最初の PR で、ignore・グループ化・build-logic の検出を確認）。Dependabot version updates / security updates の有効化はリポジトリ設定（ユーザー作業）。

## 7. ローカルでの確認
```bash
npm ci --ignore-scripts                     # 初回だけ（Node.js 24.22.2。依存は package-lock.json どおり）
npm run lint && npm test                    # Biome・tsc（strict）・単体試験（件数・skip を JUnit XML で検査。Docker 不要）
npm run preflight -- --list                 # push 前検証で選ばれる検査（変更分類は CI と同じ classify.ts）
EVENT_NAME=push BEFORE_SHA=<sha> node build/tools/src/main.js classify   # 変更判定の確認（git 履歴が必要）
```

## 8. T3.6-B の追加（ml-train イメージと release-manifest v2）
- **ml-train イメージ**（`--target ml-train`。D-257 で Kotlin へ）: 他の 4 つと同じ `build` ステージの `:ml-train:bootJar` と JRE の `runtime-base`（non-root 10001）、ENTRYPOINT は exec 形式の java。スモーク（`image-smoke`）は jar の焼込み（requiredSchemaVersion・releaseId）・試験資源が入っていないこと・合成データセットで学習・署名・登録（CI 専用 MySQL）・冪等・REJECTED・モード・認証失敗・改ざんを確認する。**push しない・本番 GCP に接続しない**。
- **release-manifest v2**（D-177）: `feature_schemas`（calibration / alpha / market の hash）と、その正準 JSON の sha256 である `feature_schema_hash`。スキーマは `docs/schemas/release-manifest(.preview).v2.json`。本番 manifest の生成は第 5 段階のゲートが揃うまで禁止のまま（プレビューのみ）。

## 9. Public 化した場合の運用（D-180。Visibility の変更はユーザーの最終操作）
**コード上の確認済み**（2026-10-26）: `pull_request_target`・`workflow_run`・self-hosted ランナー・`secrets.*`・`vars.*`・`id-token` は使っていない。全ワークフローは `permissions: contents: read`。`ci.yml` の `pull_request` はフォークからでも Secrets を受け取らない。Artifact は `images-dry-run`（保持 14 日）のみで、manifest のプレビューとスモークの結果。実 JRA-VAN データ・クラウド認証情報は CI に存在しない。クラウドのデプロイは Private の `jra-bot-infra` から行う（web 側の CI に GCP 認証を足さない）。ランナーは標準の `ubuntu-24.04` と `windows-2025` だけ（Public では標準ランナーは無料の見込み。**課金構成は追加しない**。公開後の実測は未確認）。
**品質ゲートは変更しない**: `ci gate`（必須）、変更内容の分類（`tools/src/ci/classify.ts`）、`repo-hygiene`、Dependabot を維持する。

**ユーザーが手動で設定する推奨項目**（Claude は設定しない。GitHub 側の状態は未確認）:
1. Settings → Actions → General: **Fork pull request workflows → 「Require approval for all outside collaborators」**（外部の実行は承認制）。**Workflow permissions: Read repository contents**（既定を読み取りのみ）。「Allow GitHub Actions to create and approve pull requests」はオフ。
2. Settings → Rules → Rulesets（または Branch protection）で `main`: **Require a pull request before merging**、**Require status checks: `ci gate`**、**Block force pushes**、**Restrict deletions**。GitHub Free でも Public リポジトリでは利用できる（公開直後に画面で確認する）。
3. Settings → Code security: **Secret scanning**・**Push protection**・Dependabot alerts / security updates・（可能なら）Private vulnerability reporting をオン。
4. Settings → General: Issues・Discussions・Wiki・Projects は不要ならオフ。Secrets and variables が空であること、2FA が有効であることを確認する。
5. インフラ（Terraform）で将来 Workload Identity Federation を作るときは、`attribute_condition` を `jra-bot-infra` の特定リポジトリ・ブランチだけに限定し、`jra-bot-web` と PR からのトークンを信頼しない。

**継続的なセキュリティスキャンの提案**（費用・頻度を考慮。今回は実装しない）: ①GitHub の Secret scanning と Push protection（Public は無料）を主とする。②gitleaks / trufflehog の全履歴スキャンは、**毎回の CI に入れない**。月次の既存フル検証に合わせた手動実行か、Claude クラウド上でのリリース前スキャンにする。③PR ごとの差分スキャン（`gitleaks --log-opts=base..head`）は数秒で終わるため、希望すれば `changes and repo hygiene` に足せる（今回は追加しない）。
