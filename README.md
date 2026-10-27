# jra-bot-web

JRA Bot（参照元 `puttun777/jra-bot`）を Kotlin / Spring Boot の Web アプリへ段階的にリプレースするリポジトリ。
**ソースコードの公開状態と、サービスの公開は別**。サービス（本番 GCP・データベース・Discord 通知・ダッシュボード）は常に本人専用・非公開で、リポジトリが Public になっても変わらない。
**利用者は本人 1 名・私的・非営利・非公開**（`docs/REQUIREMENTS.md` §0。2026-10-23 に最大 2 人から変更。`tenant_id` の構造と認証認可は維持し、利用者の登録・招待・共有は作らない）。
設計・進捗・判断は `docs/` が正本。未解決事項の管理表は `docs/UNRESOLVED.md`。最初に `CLAUDE.md` → `docs/PROGRESS.md` → `docs/PLAN.md` を読む。再開手順は `docs/STAGE2_HANDOFF.md` §12。

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
`.github/workflows/ci.yml`（main 向け PR、main への push、手動、月次）。Temurin 25 / Gradle Wrapper / 権限は `contents: read` のみ / Secrets 不使用 / 外部アクションは SHA 固定。
**変更内容で実行するジョブを決める**（`tools/ci/classify.py`。docs だけなら `changes and repo hygiene` と `ci gate` だけ。判定に失敗したら全部実行）。**分岐保護の required check は `ci gate` だけ**にする。運用・14 ケースの表・キャッシュ・無料枠の見積りは `docs/CI_AND_IMAGES.md`。
DB 統合テスト（`db-integration`）: `:db-migration:integrationTest`（Flyway V1/V2/`R__grants.sql` と CLI）、`:persistence:integrationTest`（Doma の Repository 実装・スキーマ版の検証）、`:bot:integrationTest` `:ingest:integrationTest` `:dashboard:integrationTest`（ready・Job の終了コード・非 NORMAL で DB に接続しないこと）が、Testcontainers で使い捨ての MySQL を起動して検証する（Docker が無ければ失敗し、スキップしない）。範囲は変更内容で決まる（DB・Doma・Flyway・依存の変更は **8.4 と 9.7**、アプリだけの変更は 8.4）。手元: `MYSQL_IMAGE=mysql:8.4 ./gradlew :db-migration:integrationTest :persistence:integrationTest :bot:integrationTest :ingest:integrationTest :dashboard:integrationTest`。Connector/J 26.x の確認（`-PconnectorJVersion=26.10.0`。採否は未決: D-070）は `compat-connectorj26.yml`（手動・月次・関連依存の変更時）。通常の `./gradlew build` は Docker 不要の単体テストだけ。

## Docker イメージ（T2.5 後半）
`Dockerfile` 1 つから 4 ターゲット: `dashboard` `ingest` `bot`（単一イメージで `--task=` を切り替え）`db-migration`。JDK 25 の JRE、non-root（uid 10001）、ENTRYPOINT は `java` の exec 形式（SIGTERM と終了コードがそのまま届く）、`HEALTHCHECK` なし。**push しない・クラウドに認証しない**（ローカルビルドだけ）。
```bash
SHA=$(git rev-parse HEAD)
for t in db-migration bot ingest dashboard; do docker build --target $t --build-arg GIT_SHA=$SHA -t jra-bot/$t:$SHA .; done
python3 -m tools.release.image_smoke --git-sha $SHA --report-dir out   # 起動スモーク（Docker と mysql:8.4 が必要。合成データのみ）
python3 -m tools.release.cli preview --git-sha $SHA --built-at "$(date -u -d @$(git show -s --format=%ct HEAD) +%FT%TZ)" --local-images out/images-local.json --out out/manifest-preview.json
```
Discord 通知（T3.7）は `:domain` の `notify` と `:bot` の `discord` に実装済みだが、**合成データと Fake サーバでの検証のみ**（実 Discord・実データへは送らない。COMP-4 まで）。bot の業務処理はスタブで、イメージが動くことは Bot の機能が完成した証明ではない。手順・設計の詳細は `docs/CI_AND_IMAGES.md`、判断は D-093〜D-096。

## release-manifest（ドライランまで）
`docs/schemas/release-manifest.v1.json`（本番）と `release-manifest-preview.v1.json`（ドライラン）。`tools/release` が生成・検証する（`python3 -m tools.release.cli validate --kind preview|production FILE`）。プレビュー（`manifest-preview.json`）は `deployable:false` で、レジストリ digest が無く、**本番用として拒否される**。本番の `release-manifest.json` はレジストリへの push 後の実 digest・ml-train・検証済みのロールバック範囲が揃うまで生成できない（第5段階）。単体テスト: `python3 -m tools.ci.run_tests --min-tests 100`。

## persistence（Doma）
`:persistence` が domain の Repository ポート（Race / OddsSnapshot / BotRun / AppUser / RaceSchedule / Signal）を実装する。Doma（kapt）の DAO・Entity・SQL テンプレートは `internal` で、公開するのは `DbSettings`・`DataSourceFactory`・`PersistenceConfiguration` だけ。SQL は全て `tenant_id` を必須にし（`SqlTemplateTenantGuardTest` が静的に検査）、時刻は UTC の `DATETIME(6)`。起動設定はアプリ側（T2.8）で組み立てる。判断は `docs/DECISIONS.md` D-070〜D-075。

## 開催日制御（T3.3）
開催の有無は **DB の予定と取得の事実だけ**で判断する（曜日・祝日は使わない）。`:domain` の `jp.jrabot.domain.calendar`（有効予定の導出 `EffectiveCalendar`、起動判断 `CalendarCheckPolicy`、手動補正、状態遷移、履歴）、`:persistence`（`race_day`・`race_schedule_event`・`calendar_override`・`bot_run` のリース。更新と履歴は同一トランザクション）、`:bot` の `--task=calendar-check`。結果・設定（既定値なし: `CALENDAR_*`）・C01〜C28 の対応は `docs/RACE_CALENDAR_DESIGN.md` §11、判断は `docs/DECISIONS.md` D-122〜D-129。**本番の起動基盤（Cloud Run Jobs）と取得の事実は未実装**で、calendar-check が起動を要求しても本番では exit 1（`LAUNCHER_NOT_CONFIGURED`）、行の無い日は exit 75（不明）になる。bot-run の業務ループは T3.8。

## ML の Kotlin 推論基盤（T3.6-A）
`:domain` の `jp.jrabot.domain.ml.*`: 特徴量（`FeatureSchema` 40/35/5 次元・血統ハッシュ・旧互換 `LegacyFeatureBuilder` と point-in-time の `PointInTimeFeatureBuilder`）、ロジスティック回帰推論、`ProbabilityPipeline`（`legacy` = 本番の初期値 / `v2` = **未承認・無効**）、モデル JSON の検証（正準 JSON・sha256・Ed25519 署名・feature_schema_hash・次元。**署名の検証前のモデルは推論に使えない**）、モデルレジストリの読取りと bot-run 開始時の固定（`ModelRunPin`）、学習用データセットの出力契約（`DatasetExporter`）。昇格（ACTIVE 化）の操作は無い（Stage 4）。学習（Python）・`ml-train` イメージ・bot-run への統合は T3.6-B / T3.8。
Python の参照実装と合成成果物: `tools/ml`（`python3 -m tools.ml.run_tests --min-tests 20 --max-skipped 0` ほか。cryptography が要る: `pip install -r tools/ml/requirements.txt`）。ゴールデン（旧 Python との比較）は `tools/golden/README.md`。設計・契約・学習側への引き継ぎは `docs/ML_DESIGN.md` §11、判断は D-152〜D-164。**実データ・実 pkl・本番の署名鍵は使っていない（合成のみ）**。

## cloud-gcp（ModeProvider）
`:cloud-gcp` が domain の `ModeProvider` を実装する（CONTRACTS §5.2）。GCS の `ops-state.json`（`OPS_MODE_SOURCE=gs://…`、ADC、**読取りのみ**）を 3 秒以内に読み、成功を 15 秒キャッシュ、失敗後 5 秒は再取得せず、`seq` の巻き戻りは UNKNOWN。読めない・検証できないときは必ず UNKNOWN（NORMAL にしない）。ローカル開発・テストでは `OPS_MODE_SOURCE=/path/to/ops-state.json` と **`APP_ENV=local`**（明示したときだけ）でファイル実装を使える（GCP・認証情報不要、合成データのみ）。`APP_ENV` が `local` 以外でファイルを指定すると起動失敗（本番で黙ってフォールバックしない）。生成は `OpsModeProviders.fromEnvironment(env, clock)`。判断は D-076〜D-080、T2.8 への引き継ぎは `docs/STAGE2_HANDOFF.md` §14.4。単体テストは GCP に接続しない（`./gradlew :cloud-gcp:test`）。

## 実行アプリ（bot / ingest / dashboard、T2.8）
3 アプリは独立して起動する（相互依存なし）。環境変数は `docs/CONTRACTS.md` §2、構成は `docs/STAGE2_HANDOFF.md` §14.5。**本番で必須の設定が足りなければ起動に失敗する**（ローカル実装へ黙ってフォールバックしない）。`APP_ENV` は `local`/`prod` が必須。
- `:ingest` / `:dashboard`: `GET /internal/health`（DB・GCS・モードに依存しない）、`/internal/ready`（DB 接続とスキーマ版 ≥ 必要な版で 200、他は 503。**モードは見ない**）、`/internal/ops/mode`（診断）。`/internal` は ops サービスアカウントの署名付きトークン（ingest=Google ID トークン、dashboard=IAP JWT）だけを通し、無認証・不正は拒否する（認証のバイパスは無い）。ingest の `/v1/agent` 配下は NORMAL 以外で 503、dashboard の書込みは NORMAL 以外で 409。**業務 API・画面・管理操作は未実装**（Stage 3・4）。
- `:bot`: `--task=bot-run|calendar-check|reconcile|retention|export-dataset --tenant-id=<n>`。モード確認は DB に触れる前（STOPPED 等=exit 0、UNKNOWN=75、引数・設定不正=2、スキーマ不足・認証/権限・例外=1、DB 不達=75）。**calendar-check（T3.3）以外の業務処理は未実装のスタブ**で、本番(`APP_ENV=prod`)では exit 1（`TASK_NOT_IMPLEMENTED`）になり、成功に見えない。
- DB は起動時に接続しない（遅延）。Flyway は組み込まない（マイグレーションは `:db-migration` だけ）。`REQUIRED_SCHEMA_VERSION` はビルドで jar に焼き込む。

## db-migration（Flyway）
`java -jar`（または `./gradlew :db-migration:run --args=--mode=info`）に `DB_URL` `DB_USER` `DB_PASSWORD`（`migrate` ユーザー）を環境変数で渡す。`--mode=info|validate|migrate` は必須。終了コードは 0 / 1 / 2 / 10（validate: 未適用あり）/ 75（DB 不達）。契約は `docs/CONTRACTS.md` §4.2。アプリの起動時には実行しない。

## ゴールデンテスト基盤（T2.6）
`tools/golden/`: 旧 JRA Bot を**固定コミット f7dbf69** で実行して得た結果を、**合成入力**の JSON フィクスチャとして保存・検証・比較する Python ツール（Gradle 外）。検証: `python3 -m tools.golden.verify`、比較: `python3 -m tools.golden.compare --actual <kotlin出力.json>`、再生成の確認: `python -m tools.golden.generate --legacy-path <jra-bot> --check`。手順・フィクスチャ形式・未実装ケースは `tools/golden/README.md`。CI は `golden tests (Python 3.12)` ジョブ。合成データでの一致は実データでの正確性を意味しない。

## 判定ロジックの Kotlin 移植（T3.1 単勝 / T3.2 ワイド・三連複）
`:domain` の `jp.jrabot.domain.decision`（純粋 Kotlin）。EV・Kelly・市場確率・急落フィルタ・単勝の判定（T3.1）に加え、ワイド/三連複の Plackett–Luce 確率（`PlackettLuce`）・推定オッズ（`ComboOddsEstimate`）・実オッズの注入（`ComboOdds`）・流し/BOX・`ticket_allocation`・`StrategyEntry.tickets`（T3.2）。`WinDecisionEvaluator.evaluate(horses, realOdds)` が旧 `evaluate_odds` と同じ順序で判定する。金額は旧実装の「点」（`Double`）。旧実装との比較は検証専用の `:golden-runner` と `tools/golden/kotlin_compare.py`（CI の `build` ジョブ）で、21 ケース/810 呼び出しのうち Kotlin が検証したのは 18 ケース/661 呼び出し（全一致。ML・特徴量ハッシュ・旧関数既定値の 3 ケース/149 呼び出しは未対応で、成功に数えない）。未承認の数値変更（E6/E12/E14/H4/H10 の改善案）は未実装で、`pipeline_version=legacy`・`fixes.*=false` のみ。本番で動作する Bot ではない（`StubTaskHandler` は未置換）。

## 権利・利用条件（Public 公開時）
- **ライセンスは付与していない（All rights reserved）**。ソースを公開していても、利用・複製・改変・再配布・派生物の作成を許諾しない。GitHub 上の閲覧と、GitHub の利用規約が認める範囲の fork だけが可能（それ以上の権利は与えない）。外部からの Pull Request・Issue は募集しない（`SECURITY.md`）。
- 旧実装 `puttun777/jra-bot`（MIT, Copyright (c) 2026 puttun777）由来の部分と第三者 OSS の表示は `NOTICE.md`。既存の MIT 等の表示は削除しない。
- JRA-VAN Data Lab. のデータ・SDK（JV-Link）・仕様書・学習済みモデルは含まない。ソースコードの公開について JRA-VAN からの許可は**得ていない**（確認事項は `docs/UNRESOLVED.md` E-7）。

## Git に含めないもの
実データ（JV-Data・SQLite・DB ダンプ）、学習済みモデル（`*.pkl` 等）、秘密情報、JV-Link SDK の資料、ビルド生成物（`.gitignore` と `repo-hygiene` ジョブで防ぐ）。テストデータは合成データだけを使う。
