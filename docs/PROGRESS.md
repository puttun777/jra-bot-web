# PROGRESS（jra-bot-web）

最終更新: 2026-11-01（T3.9③・reconcile・preflight・infra PR #4。判定の正本は COMPLETION.md）
旧: 2026-10-30（保留の再適用のレビュー・公開準備。D-229。CI 未実施）
旧: 2026-10-29（追加レビュー 2: リース継続・保留の再適用・G-31 の null・T3.9 設計の訂正〈D-224〜D-228〉。同ブランチ。CI 未実施。PR なし）
旧: 2026-10-29（追加レビュー・ML 連携の実行・T3.9 の詳細設計。同ブランチ。CI 未実施）
旧: 2026-10-29（Flyway V5 の実装〈D-17/D-18/D-19 の承認反映。同ブランチ。T3.9 には進んでいない・CI 未実施〉。記録は下）
旧: 2026-10-28（包括バグ監査の修正）
旧: 2026-10-27（Stage 3-H レビュー修正: G-23/G-24 の判断反映・5 分前の強化・モード確認間隔）
旧: 2026-10-27（Stage 3-H: T3.8 bot-run オーケストレーション）
旧: 2026-10-27（Stage 3-G: T3.7 Discord 通知）
旧: 2026-10-26（Public 化の準備）／2026-10-24（Stage 3-F2・3-F1・3-E）


## 現在の状態と次の作業（2026-11-01 夜 時点。main = f9c61af + 作業ブランチ）
**判定の正本は `docs/COMPLETION.md`（R1〜R32 の A/B/C）。この節はその要約と、再開手順。開発全体は『完了』ではない。**
**マージ済み**: web PR #30・#31、infra PR #3（前日まで）、**infra PR #4（T5.1〜T5.6: Terraform・ops・workflow・Runbook。`ci gate`・terraform・ops-tests 成功。Opus レビュー 3 回の高4・中13 を修正）**。
**この PR（web・作業ブランチ）の内容**: push 前検証 `tools.ci.preflight`/`consistency`（CI と同じ分類・同じ Verify ブロック）、T3.9③（bot-run の結果待ち・開催日の進行・G-34 警告・M2 検出）、reconcile、`BOT_RUN_RESULT_*`、V6 提案（`docs/V6_PROPOSAL.md`: ENUM 追加 1 件だけ・任意）、COMPLETION.md。独立レビュー（Opus）の高 1・中 5 を D-237 で修正。
| 区分 | 内容 |
|---|---|
| **A（コード + 合成データ検証済み）** | R1・R3・R6〜R9・R13（単勝）・R18・R21・R23・R25・R26、R10/R12 の単体 + reconcile の MySQL 通し試験（`ReconcileIntegrationTest`）、R27〜R29（infra PR #4。実 GCP 未検証） |
| **C（実装を続ける）** | R2 Cloud Run Jobs の起動クライアント（現在は `UnconfiguredJobLauncher` = fail closed）、R14 ワイド/三連複の紙上決済、R15 読取り API、R16 バックテスト、R17 retention（DELETE 権限は V5 で付与済み。実装する）、R19/R20/R22 UI・管理操作・CSRF・監査ログ、R24 SQLite→MySQL 移行ツール、R30 全体統合試験、R31/R32 文書（TEST_RESULTS・OPERATIONS・USER_ACTIONS） |
| **B（本人の判断・資料・実機）** | R4 Windows 実機、R5 JV-Data 仕様、旧 SQLite/設定/モデルの確認、V6（任意）、GCP（請求・OAuth・Secret・apply）、実 Discord、COMP-4、Public 化 |
**次に実行する手順（順）**: ① R17 retention（既定ドライラン）→ R14 ワイド/三連複 → R16 バックテスト → R2 JobLauncher（`:cloud-gcp`）→ ② Stage 4: R15 読取り API → R19/R20/R22 UI・管理（CSRF・監査ログ）→ ③ R24 移行ツール → ④ R30 統合試験・R31/R32 文書・USER_ACTIONS → ⑤ COMPLETION.md の最終照合と最終報告。**各増分で `python3 -m tools.ci.preflight` → 独立レビュー（Opus: 認可・DB 制約・競合・リース）→ 修正 → push 1 回 → PR → CI → マージ**。
**この環境の注意**: JDK 25 は `/opt/jdk25dl/jdk-25*`（`JAVA_HOME` を設定。preflight は自動検出）。`LC_ALL=C.UTF-8` が必要。Docker Hub の 429（images のベース取得）と、Docker 内の Gradle 配布取得の TLS（PKIX）は環境依存で `images` はローカルでは未実施になる（CI で確認）。Maven Central の 429 は再試行。
**事前検証（push 前）の結果（head=6e8d9b0 → 69f99a7）**: diff-check・hygiene・classify-self・consistency・kotlin-build・kotlin-golden・db-it-9.7・golden-py・agent・ml-train・ml-tools は成功。db-it-8.4 は ingest の `AgentAuthIntegrationTest`（時刻ずれ 301 秒の境界の flaky）で 1 回失敗 → 310 秒に直して再実行（結果は下）。**`images` は未実施**（上記の環境要因）。**agent-windows は未実施**（Windows ランナー）。

---

## 2026-11-01 PR #30 作成・CI 修正・独立レビュー反映・T3.9 ①（claude/stage3-bot-orchestration = claude/jra-bot-replace-continue-l1lk71）
**Actions は再利用可能**（保留解除の指示）。PR #30（T3.8・V5・保留の再適用）を作成して CI を実行。
| 項目 | 結果 |
|---|---|
| CI 1 回目 | `changes and repo hygiene` 失敗（PUBLIC_READINESS.md が秘密検査のパターン文字列を含む誤検知）→ 文言を言い換え（検査は弱めていない）。後続ジョブは skipped |
| CI 2 回目 | `agent python` 失敗（ruff format 1 件・mypy 1 件。CI の固定版 ruff 0.17.2 / mypy 2.4.0 をローカルで再現して直した。pytest 213 件成功）|
| 独立レビュー（Opus・読取り専用・1 名） | 高 0・中 2・低 8。**M1 修正**（D-230: checkpoint に origin、再現試験は修正を外すと失敗）。M2・低は UNRESOLVED G-32 |
| T3.9 ① 決済の計算 | `:domain` `settlement.SettlementCalculator`（純粋関数）+ ゴールデン GD11（3 ケース/54 呼び出し）。**Kotlin と旧実装が全一致**（35 ケース中 34 対応・1042 呼び出し、不一致 0）。D-231/D-232（旧欠陥 E16: 旧の評価決済は常に例外）|
**ローカル検証**（JDK 21、合成データ。Maven Central の 429 を再試行で回避）: `:domain:test` 全件成功（`BotRunIntegrityTest` +1、`SettlementCalculatorTest` 10）、`:golden-runner:test`、ktlintCheck・checkModuleDependencies 成功、Python golden `run_tests` 93 件成功。**参照元 jra-bot は読み取りのみ**（実行で作られた `__pycache__` は削除済み・`git status` クリーン）。
**CI（124b95b）**: build / db 8.4 / db 9.7 / golden / agent python・windows / release tools / hygiene は成功。`images`（スモーク）が失敗: T3.8 で `bot-run` に運用値の環境変数が必須になった（既定なし）ためスモークの bot-run が exit 2 → スモークに合成の `BOT_RUN_*` を渡して修正（dfe391d。運用値ではない）。`ml tools`/`ml-train`/`connectorj26` は変更範囲外のため skipped（設計どおり）。
**T3.9 ②（結果・払戻の取込）**: D-233。`:domain` の観測・ポート・`ObservationApplier`、`:persistence` の `ResultDao`/`PayoutDao`（条件付き upsert）、`:ingest` の合成レコード `NRS`/`NHR`。ローカル: `:persistence` 統合 5 件 + 単体、`:ingest` 統合（AGENT_PYTHON 指定で全件）、`:domain`/`:bot` 単体、ktlint・依存検査すべて成功。
**T3.9 方針転換（D-234）**: 新 bot-run は評価に最良コンボを記録しないため、旧の評価単位の決済（GD11 の `SettlementCalculator`）は実運用の入力が無い（計算と旧実装との一致は検証済みで残す）。通知した Signal × results × payouts から**純粋関数で紙上成績を計算**（`PaperSettlement`・`PerformanceAggregator`。代表集計は (レース, 馬番) ごとの発走前の最新 1 件）。DB への決済結果の保存（V6）は不要になった。残: ワイド/三連複の買い目の決済（通知スナップショットの買い目から）、bot-run の結果待ち・race_schedule/race_day の状態更新、reconcile、バックテスト。
**PR #30 はマージ済み**（merge 73f74c8。822644d で `ci gate`・build・db 8.4/9.7・golden・images・agent すべて成功、skipped は ml tools/ml-train/connectorj26 のみで変更範囲外。委任の条件を確認: 独立レビューの高指摘なし・M1 修正を再確認・V6 なし）。**infra PR #3 もマージ済み**（T5.0 完了。文書のみ）。残るレビュー指摘: M2・低 L1〜L8・G-34（UNRESOLVED G-32/G-34）。
**未実施**: MySQL 統合のローカル再実行（変更が `:domain` のみのため CI に委ねる）。
**承認待ち/本人判断**: V6 の最終 SQL と追加 GRANT（docs/T39_DESIGN.md §9。適用対象の Flyway には入れていない）、払戻の単位以外の同着・返還・取消（JV-Data 仕様）、COMP-4。
**次の手順**: ① PR #30 の CI を確認（全ジョブ。skipped を成功扱いにしない）し、レビュー修正差分を再確認 → 条件を満たせばマージ。② T3.9 ②（結果・払戻の取込 → RaceStatusUpdater → ResultSettler と bot-run の結果待ち）。

---

## 保留の再適用のレビューと公開準備（claude/stage3-bot-orchestration・2026-10-30。PR なし・**CI 未実行**。D-229）
**費用条件**: リポジトリは Private。分岐保護 API が「Upgrade to GitHub Pro」と返すため GitHub Free と推測（billing API は 403 で、残り無料枠・予算上限は**確認できていない**）。`ci.yml` は main 宛て PR と main への push で起動するので、**PR 作成・マージは保留**。ブランチへの push は CI を起動しない（確認済み: `push` は `branches: [main]` のみ）。
| 項目 | 内容 | 試験 |
|---|---|---|
| `PendingReplayer`（`:domain` に抽出） | キーセットで全件走査、失敗行を同じ走査で選び直さない、`ReplayReport` で残件・中断を返す | `PendingReplayerTest` 8 件成功。旧 10,000 件上限を戻す変異で 25,000 件の試験が失敗することを確認 |
| 再試行の契機（新） | 完了済みバッチの再送で再開／同時バッチの保留をコミット後にレース有無を確認して再適用。残件は WARN（件数のみ）| `IngestPipelineIntegrationTest` +2。両修正を外す変異で 2 件とも失敗することを確認 |
| 性能の実測（単発） | MySQL 8.4.13・ingest_raw 100 万行で 1 ページ 約 0.5〜0.8 秒。候補索引で 約 1〜14 ミリ秒 | V6 候補（未適用・要承認）|
| 公開準備 | README を概要・図・設計判断・検証範囲・AI 利用に再構成（旧は `docs/DEVELOPER_GUIDE.md`）。公開前レビュー `docs/PUBLIC_READINESS.md`。PR 下書き `docs/pr-drafts/` | — |
**ローカル検証**（JDK 21、Docker 上の **MySQL 8.4.13**〔Testcontainers〕、合成データ。件数は各モジュールの JUnit XML から集計）: `:domain:test` **1021 件**、`:persistence:integrationTest` **199 件**、`:db-migration:integrationTest` **49 件**、`:bot:integrationTest` **38 件**（`MlTrainInteropIntegrationTest` を含む）、`:ingest:integrationTest` **57 件**（実 Python エージェント含む）。**すべて失敗 0・エラー 0・スキップ 0**。ktlint は自分の変更に整形指摘が出たため `ktlintFormat` で直し、`ktlintCheck`・`checkModuleDependencies` 成功。
**MySQL 版検査の失敗の原因**: 以前の `:persistence` の 1 件失敗（`WiringIntegrationTest`、8.4 期待の版検査）は、**MySQL 8.4.13 では再現せず（199 件成功）、8.0.46 を使っていた環境差だった**。テストの期待値は変えていない。
**未実施**: JDK 25・MySQL 9.7（このセッションから取得できなかった）、GitHub Actions 全体、Python（agent/ml/golden）・repo-hygiene の再実行（今回の変更の対象外。前回成功の記録のみ）、実 Discord・Cloud Run・Windows 実機。
**未解決**: 払戻の単位などの公式確認（`jra-van.jp` がセッションから名前解決できず**未確認**）、COMP-4、V6、E-7、ライセンスの選択。
**次の手順**: ① Billing で Actions の残り無料枠と上限停止の設定を確認（ユーザー）→ PR 作成（`docs/pr-drafts/stage3-bot-orchestration.md`）→ CI → 条件を満たせば squash merge。② 上の未確認の検証結果を追記。③ V6 索引・T3.9 は承認後。
**続行を妨げているもの**: 費用条件の未確認（CI 起動を伴う操作のみ）。

---

## 追加レビュー 2（2026-10-29。D-224〜D-228。PR なし・Actions なし。T3.8・V5・本節の変更は CI で未検証）
| 項目 | 内容 | 試験 |
|---|---|---|
| リース継続（D-225） | `LeaseKeeper.holdsNow()` + `LeaseProbe`。新しい評価・claim・送信・Discord の試行ごとに確認。heartbeat スレッドは足さない | `BotRunLeaseTest`（評価が 6 分かかる → 確定・通知なし。ゲートを消すと失敗を確認）|
| 保留の再適用（D-227） | 理由に待つレースを含め、キーセットのページングで全件走査（200 件/ページ。総数の上限なし: 10,000 件の上限は撤回）。旧形式も対象。テナント境界・観測時刻を維持 | `IngestPipelineIntegrationTest`（ページ超過・別レース・旧形式）、`RawPendingOrderIntegrationTest` |
| G-31（D-226） | `updatedAt` が null の天候・馬場も使わず除外（学習・推論で同じ規則）。履歴表は設計のみ（ML_DESIGN §15） | `FeatureBuilderTest` |
| T3.9 設計の訂正 | V6: NULL 正規化の生成列（スクラッチ MySQL で重複拒否を確認）、新表の GRANT、`bets` は V6 から外す。Q2 二重計上防止・金額概念の分離、Q4 比較案 | docs |
| ML 連携 6→5 | 以前の『6 件』は 5 テスト + クラス初期化失敗の疑似エントリ 1。テスト数は減っていない（`@Test` 5） | — |
**ローカル検証**（JDK 21・MySQL 8.0.46・合成データ）: `:domain` 全件成功、`:bot` 136 + 統合 38（ML 連携 5 を含む）、`:ingest` 35 + 統合 55（実 Python エージェント含む）、`:persistence` 33 + 統合 199 中 1 失敗（既知: MySQL 8.4 期待の版検査）、`:dashboard` 30 + 9、`:db-migration` 58 + 49、ktlintCheck・checkModuleDependencies 成功。Python（agent/ml）・golden・repo-hygiene は今回の変更の対象外のため再実行していない（前回成功）。**CI は未実施**。V6・T3.9 本体は未着手。

## 追加レビューと T3.9 の詳細設計（claude/stage3-bot-orchestration・2026-10-29。PR なし。**GitHub Actions は起動していない**。T3.8・V5 は CI で未検証）
### 追加レビューで見つけて直したもの（D-222。変更前のコードで失敗する試験を作って確認）
| 問題 | 修正 | 回帰試験 |
|---|---|---|
| bot-run が 1 サイクルの先頭でしか heartbeat を更新せず、1 レースの通知が長引く（Discord の 429 の待機は最大数分）とリース（300 秒）が切れて checkpoint を保存できない | レースの合間に `heartbeatIfDue()` | `BotRunIntegrityTest`（同時に発走する 3 レースが各 130 秒かかる。修正を外すと失敗することを確認） |
| 保留（PENDING）の再適用が古い順で、居座る古い保留が新しい保留の再適用を飢えさせる | 新しい順・上限 500 | `RawPendingOrderIntegrationTest`（順序・`settlePending` の確定済み不変・テナント境界） |
| COM ワーカーが辞書でない JSON 行（配列など）を返すと予期しない例外 | `BAD_PROTOCOL` として扱う | `test_worker.py` |
| `MlTrainInteropIntegrationTest` の種データの `races.updated_at` が as-of より後で、D-214 の仕様どおり全行が除外され `DATASET_EMPTY` | 種データの更新時刻を as-of より前に | 5 件が成功 |
### 運用上の重要な帰結（要判断・UNRESOLVED G-31）
D-214 により、**遅れて取り込んだ過去レース（バックフィル）は `races.updated_at` が取込時刻になり、天候・馬場の as-of 時点の値を確認できないため学習データから全て除外される**。実データでは学習に使える行が極端に減り得る。
### 実行したもの（ローカル。JDK 21・MySQL 8.0.46・合成データ）
| 区分 | 結果 |
|---|---|
| `:domain` / `:bot` / `:persistence` / `:ingest` / `:db-migration` / `:dashboard` / `:cloud-gcp` / `:golden-runner`（単体 + MySQL 統合） | 1010 / 174 / 231 / 88 / 107 / 39 / 102 / 20 件。失敗は `persistence` の 1 件のみ（既知: `WiringIntegrationTest` が MySQL 8.4 を期待する版検査。8.0.46 のため） |
| Kotlin/Python ML 連携（`MlTrainInteropIntegrationTest`） | **5 件成功**（これまで未実施。`ml/requirements.txt` を入れたローカル venv を `ML_TRAIN_PYTHON` に指定。実モデル・実データはリポジトリに入れていない） |
| `ml` の pytest | 128 件成功 |
| 旧 Python とのゴールデン比較 | 再生成の一致（32 フィクスチャ/994 呼び出し）、`verify`、`run_tests`（93 件）、Kotlin との比較 **31 ケース/988 呼び出し全一致**（最大絶対誤差 1.07e-14。GD10 の 6 呼び出しは未対応のまま） |
| agent の pytest / `tools.ci` / `tools.release` / ktlint / `checkModuleDependencies` / repo-hygiene | 213 件（4 件は Windows 専用でスキップ）／130／42／成功／成功／成功 |
| 判定の不変条件の性質試験（`DecisionInvariantsFuzzTest`） | 9000 件超のランダム入力で、例外・非有限値・負の金額・1 点の上限超過・レース合計の上限超過なし。同率の並びが入力順に依存するのは旧互換（呼び出し側が馬番順に固定） |
| **未実施** | **GitHub Actions 全体**（JDK 25・MySQL 8.4/9.7・Docker・required check `ci gate`）。JDK 25・MySQL 8.4/9.7 はこの環境に無く、用意しなかった。V5 の 8.4/9.7 での適用。実 Discord・Cloud Run・Windows 実機 |
### 監査の範囲（読んで確認したもの。問題が無かったものも含む）
Plackett–Luce/買い目/予算上限（性質試験）、ML ゲート（NaN は FAIL で fail closed）、GCS モデルストア（作成のみ・`.sig` 先 `.json` 後・パスは ID だけから組み立て）、FetchPolicy（非 YSCH は保留があっても SUCCEEDED → G2 の再適用で補う）、Windows の COM ワーカー（pickle なし・シェルなし・Base64 検証・タイムアウトで強制終了・再起動の上限）、dashboard（内部 API だけ。認証フィルタのパス正規化は `RequestPathsTest` で網羅）、CI ゲート（実行が必要なジョブが skipped なら失敗）。**低**: GCS の `readAllBytes` に大きさの上限がない（自前のバケットで、ModelLoader が 4 MiB で拒否するが、ダウンロード後。メタデータで事前に拒否するのが望ましい。HTTP 試験への影響があるので今回は変更せず）。
### T3.9
詳細設計: `docs/T39_DESIGN.md`（D-223、UNRESOLVED D-20）。本体は未着手。
### 次にユーザーが行うこと
1. D-20（T3.9 の設計 Q1〜Q5）と G-31（G3 の運用上の帰結）の判断。 2. Actions の無料枠が戻ったら、PR の作成と CI の実行を指示（PR・マージは指示があるまで行わない）。 3. T3.9 の着手の指示。
### ブランチ
`claude/stage3-bot-orchestration`（PR なし）。

---

## Flyway V5（claude/stage3-bot-orchestration・2026-10-29。T3.9 には進んでいない。PR なし。**CI は未実施 = T3.8・V5 は CI で未検証**）
承認（2026-10-29）: D-17（V5 を条件付きで承認）／D-18（取りこぼしを許容。自動再送しない）／D-19（評価機会の喪失は FAILED・exit 1）。設計と差分: `docs/AUDIT_FIX_DESIGN.md` §3・§7、D-218〜D-221、UNRESOLVED C-41・D-17〜D-19・G-30、CONTRACTS 0.4.15。
### 実装
- **V5**: `race_evaluations` に生成列 `uq_native` + UNIQUE（新方式の行だけ。旧データの重複は変更・削除しない。ALTER を先に置き、重複があれば何も変えずに失敗）。新表 `race_timing_notice`（PK `(tenant_id, race_id, timing_centi)`、複合 FK、`notice_schema`/`notice_json`/`notice_sha256`、CHECK、追記専用）。GRANT: bot は SELECT/INSERT、app は SELECT。`requiredSchemaVersion` は全アプリ 5。
- **通知**: 評価結果 → Signal → スナップショット組み立て → **DB に確定（claim）** → checkpoint → 確定した行を読み戻してハッシュ照合 → Discord。確定の前に送らない。0 件でも確定。複数タイミング同時到来は 1 評価・1 通知。checkpoint の欠損・破損・古い状態や新しいオッズでも同じタイミングを再通知しない。
- **D-19**: 評価機会の喪失（予定不信頼・データ不足・checkpoint 隔離）は FAILED `EVALUATION_OPPORTUNITY_LOST`（exit 1）。同じ日の再実行は `EVALUATION_LOSS_ALREADY_RECORDED`（exit 0）。`ALL_RACES_OFF_WITH_GAPS` は廃止。
- **`timing_centi`**: `TimingKey`（0.01 分刻み・0.01〜1440 分・丸めず拒否・衝突検出）。
- 実装中に見つかった問題: `ON DUPLICATE KEY UPDATE` は UPDATE 権限を要求する（追記専用の表に bot の UPDATE を付けない → 通常の INSERT + 主キー重複の扱いに変更）。
### テスト（ローカル: JDK 21・MySQL 8.0.46〔ポート 3307〕。合成データ・Fake HTTP のみ。モジュールごとの単体 + 統合の合計）
| モジュール | 結果 |
|---|---|
| `:domain` | **1006 件成功**（+15: `BotRunIntegrityTest` を V5 の意味に書き直し〔12→20〕、`NoticeSnapshotTest` 5、`TimingKeyTest` 3） |
| `:persistence` | 229 件中 1 件失敗（既知: `WiringIntegrationTest` が MySQL 8.4 を期待する版検査〔8.0.46 のため〕）。`TimingNoticeIntegrationTest` 7（RR/RC の同時確定、JSON 往復、最小権限、テナント境界）、`BotRunOrchestratorIntegrationTest` +1（checkpoint 消失 + 新オッズ） |
| `:db-migration` | **107 件成功**（`V5MigrationIntegrationTest` 5: 旧データの重複を含む DB への適用、新方式の行だけ UNIQUE、重複があれば失敗して何も変えない、PK/CHECK/FK/GRANT、再 migrate） |
| `:ingest` / `:dashboard` / `:cloud-gcp` / `:golden-runner` | 88 / 39 / 102 / 20 件成功（ingest は `AGENT_PYTHON` 指定で実 Python エージェントとの相互運用を含む）。**golden は全一致を維持** |
| `:bot` | 175 件中 6 件は `ML_TRAIN_PYTHON`（ml の依存を入れた Python）が無く**未実施**（`MlTrainInteropIntegrationTest`）。それ以外は成功 |
| `agent`（pytest） / `tools.ci` / `tools.release` / ktlint / `checkModuleDependencies` / repo-hygiene | 212 件成功 / 130 / 42 / 成功 |
| **未実施** | **GitHub Actions 全体（JDK 25・MySQL 8.4/9.7・Docker イメージ・Python の CI 構成・required check `ci gate`）**。V5 の MySQL 8.4/9.7 での適用。実 Discord、Cloud Run。`MlTrainInteropIntegrationTest` 6 件 |
### PR 作成前に残る事項
CI の実行（Actions の無料枠が戻るまで未実行。PR・マージはユーザーの指示まで行わない）／本番 DB への V5 適用は**未承認**／運用値の確定（T1.20）／`reconcile`・`retention` はスタブのまま／infra の同期は T5.0（migrate を先に・GRANT）。
### ブランチ
`claude/stage3-bot-orchestration`（PR なし）。

---

## 包括バグ監査の修正（claude/stage3-bot-orchestration・2026-10-28。T3.9 には進んでいない。CI は未実施 = T3.8 は CI で未検証）
詳細な設計と判断: `docs/AUDIT_FIX_DESIGN.md`、D-208〜D-217、UNRESOLVED C-35〜C-40・D-17〜D-19・F-9・G-28〜G-30、CONTRACTS 0.4.14。
### 修正したもの（DB スキーマ・GRANT は変更していない。**V5 は提案のみで未承認**）
| ID | 修正 | commit |
|---|---|---|
| C1/C2 | 全レース発走後の終了を分類。予定を信頼できず評価を逃したら FAILED `EVALUATION_OPPORTUNITY_LOST`（exit 1）、データ不足は SUCCEEDED `ALL_RACES_OFF_WITH_GAPS` + 警告。評価 0 件・中止・通知対象なしは正常のまま | 9a7df54 |
| E（暫定） | checkpoint 欠損・破損: 通知試行の記録があるのに発火記録を復元できないレースは通知を止める。初回の空 checkpoint は正常 | 9a7df54 |
| B（暫定） | 既存 Signal と計算結果が違えば claim も通知もしない（`SIGNAL_MISMATCH`） | 9a7df54 |
| D | 分離レベルを RR に固定 + デッドロック再試行（RR/RC 指定で同時挿入が 1 行の試験）。親行ロックは bot に races の UPDATE 権限が無いので不採用 | f3220ab |
| C | エージェントがペーシング待機後・claim 前にモードを再確認 | 9a7df54 |
| F | Discord のシグナル通知は POST の直前（429 待機後を含む）にモード確認（エラー通知は対象外） | 9a7df54 |
| G2 | レース待ちの保留レコードを新しいレースの到着後に再適用／バッチ内は依存するレースを先に適用／再送の PENDING 更新 | f3220ab |
| G3 | asOf より後に更新された races 行の天候・馬場を使わない（フラグ + 学習対象外） | f3220ab |
| G6/G9/G10 | 更新 zip の展開前検査／Dockerfile のコメント／エラー通知の畳んだ件数 | 9a7df54 |
| A/G1/G5/G7/G8 | 意味の明記（`signal_sent`）・試験の追加（実オッズ無しの買い目）／問題なし・修正不要（GRANT・サイズ制限）／契約に余白を明記（Job のタイムアウト） | 68fae75 |
### 再現した問題と条件（監査時点・コード変更前）
- C1: `stale_after` を短くして実行中に予定が古くなる → 評価 0・通知 0・`SUCCEEDED ALL_RACES_OFF`。E: checkpoint を空にして再起動 + 新しいオッズ → 同じタイミングの通知が 1 → 2 件。B: 同じ自然キーの UNSENT 行（推奨額 3.0）に対し再計算（7.0）が通知された。D: READ COMMITTED で 8 並列 × 40 回中 37 回重複（RR は 0 件でデッドロック 1213）。C: ペーシング待機中に STOPPED → 余分な POST が 1 件。
### テスト（ローカル: JDK 21・MySQL 8.0.46〔ポート 3307、`JRABOT_TEST_MYSQL_EXTERNAL`〕。手で進める時計・Fake HTTP・合成データのみ）
| 区分 | 結果 |
|---|---|
| `:domain` 単体 | **991 件成功**（+17: `BotRunIntegrityTest` 12〔C1/C2・E・B・G1〕、`FeatureBuilderTest` +3〔G3〕、`DatasetExporterTest` +1、`ErrorReporterTest` +1。既存 3 件は終了理由の変更に合わせて更新） |
| `:bot` / `:persistence` / `:ingest` / `:dashboard` / `:cloud-gcp` / `:golden-runner` / `:db-migration` 単体 | 136 / 33 / 35 / 30 / 102 / 20 / 57 件成功（+3 Discord〔F〕）。**golden は全一致を維持** |
| MySQL 統合 | `persistence` 188 件中 1 件失敗（既知: `WiringIntegrationTest` が MySQL 8.4 を期待する版検査。8.0.46 のため）。`ingest` 53 件成功（`AGENT_PYTHON` 指定で実 Python エージェントとの相互運用を含む。G2 の 3 件を追加）。`dashboard` 9・`db-migration` 44 成功。`persistence` +2（D の RR/RC 同時挿入）。`bot` 39 件中 6 件は `ML_TRAIN_PYTHON`（ml の依存を入れた Python）が無く**未実施** |
| `agent`（pytest） | **212 件成功**・4 件スキップ（Windows 専用）。+9（C の待機後の再確認 1、G6 の更新 zip 8） |
| `tools/ci`・`tools/release`・repo-hygiene・ktlint・`checkModuleDependencies` | 成功（`tools.ci.run_tests` 130 件、`tools.release` 42 件） |
| 検証した設計（コードには入れていない） | V5 案の SQL を使い捨てのスクラッチ DB で検証（旧データの重複を許したまま新方式の行だけ UNIQUE／`ON DUPLICATE KEY` の戻り件数／PK による同一タイミングの拒否）。DB は削除済み |
| **未実施** | **GitHub Actions 全体（JDK 25・MySQL 8.4/9.7・Docker イメージ・Python の CI 構成）**、実 Discord、Cloud Run、実機の Windows。`MlTrainInteropIntegrationTest` 6 件。`IngestReadyIntegrationTest` は他モジュールの統合試験と並列に走らせると接続数の検査が揺れる（単独実行では成功） |
### 旧 Python 互換への影響
判定の数値・通知の書式・ゴールデンは変えていない。旧互換の特徴量（`LegacyFeatureBuilder`）も不変（G3 は新方式の `PointInTimeFeatureBuilder` だけ）。`signal_sent` の意味は旧と同じ。旧データ（`legacy_id` あり）の重複は触れていない。
### DB 変更が必要な問題（承認待ち: UNRESOLVED D-17〜D-19）
E・B・D の恒久対策（AUDIT_FIX_DESIGN §3）。天候・馬場の履歴（G-28）。
### 保留
LOST_DATA の終了状態（D-19）、発火確定後に落ちた場合の再開方針（D-18）、`INGEST_STORE_COMBO_ODDS`（D-8。ON にしていない）。
### PR・マージ前に残る作業
CI の実行（Actions の無料枠が戻るまで未実行）／D-17〜D-19 の判断／運用値の確定（T1.20）／`reconcile`・`retention` はスタブのまま／infra の同期は T5.0。
### ブランチ
`claude/stage3-bot-orchestration`（PR なし。ユーザーのレビューを待つ）。

---

## Stage 3-H レビュー修正（同ブランチ・2026-10-27。T3.9 には進んでいない）
### 反映した判断と修正（D-204〜D-207、CONTRACTS 0.4.13、UNRESOLVED C-34・G-23・G-24）
- **G-23**: 通知は『評価タイミングごとに最大 1 回』で確定（再起動をまたいだ重複も防止。既存実装を `30,5` で検証）。指定の値は `bot/config/bot-run.sample.env`（**開発・合成データ・CI 用の暫定サンプル。本番の運用値として確定していない**。ファイルと README 相当のコメントに明記し、テストが TestSupport の値と一致を固定）。コードに本番の既定値は無い。判定閾値などの本番設定は T1.20 まで未確定。
- **G-24**: `hit_rate_min_ev_schedule` を移植（`BOT_RUN_PRIORITY_MODE=hit_rate` と `BOT_RUN_HIT_RATE_MIN_EV_SCHEDULE` を**明示したときだけ**有効。未設定は固定閾値のまま）。旧の式（`>=30` far / `>=5` mid / near。境界は上側）と一致するテスト。`adaptive_relaxation` は**実装・有効化していない**（T3.9 のバックテスト後に再判断）。
- **5 分前の強化（確認済みの不具合）**: `30,5` では旧互換の判定が最大のタイミング（30）に先に一致し、5 分前でも強化されなかった（実コードのテストで確認）。`TightenMode`（`false`=数値を変えない / `true`=修正後: 残り 5 分以下で強化 / `legacy`=旧の癖を保持）に分けた。修正後は旧実装と**意図的に違う**。
- **UNKNOWN の停止時間（確認済みの不一致）**: 初版は運用モードの確認がループ 1 周ごと（最大 heartbeat の 60 秒）で、UNKNOWN の 3 回連続は約 120 秒かかっていた（契約は約 45 秒）。ループを『モード・停止要求・heartbeat・最大稼働の確認（15 秒以下）』と『評価サイクル（nextCycleAt）』に分離。UNKNOWN は最初の観測から 30 秒で停止（45 秒以内）、STOPPED は 15 秒以内に検知。heartbeat 60 秒・リース 300 秒は不変（最大の heartbeat の間隔 75 秒以内をテスト）。
### テスト（ローカル: JDK 21・MySQL 8.0.46。手で進める時計のみで実 sleep なし。**CI は未実施**）
| 区分 | 結果 |
|---|---|
| `:domain` 単体 | **974 件成功**（+14: `BotRunScheduleTest` 10〔30/5 の評価・通知、同タイミングの重複防止・再起動、まとめ発火、hit_rate schedule、強化 3 モード、UNKNOWN 45 秒、STOPPED 15 秒、heartbeat 60 秒/リース 300 秒、BACKFILL〕、`BotRunComponentsTest` を更新） |
| `:bot` 単体 | **133 件成功**（+3: サンプル設定ファイル、hit_rate 設定、強化の値） |
| `:persistence` 単体 / `:ingest` / `:dashboard` / `:cloud-gcp` / `:golden-runner` | 33 / 35 / 30 / 102 / 20 件成功。**ゴールデン 31 ケース / 988 呼び出し全一致を維持** |
| MySQL 統合（ローカル 8.0.46）`BotRun*`（`30,5` の通し・再実行の冪等・BACKFILL・リース・テナント境界・最小権限） | 成功。`persistence` 全体は 185 件中既知の 1 件〔8.4 を期待するサーバ版検査〕を除き成功、`bot` の `MlTrainInteropIntegrationTest` 6 件は `ML_TRAIN_PYTHON` 未設定の既知の失敗 |
| ktlint・`checkModuleDependencies`・repo-hygiene | 成功 |
| **未実施** | **GitHub Actions 全体（JDK 25・MySQL 8.4/9.7・Docker イメージ・Python）**、実 Discord、Cloud Run。**T3.8 は CI で未検証。最終検証済みとして扱わない** |
### PR 作成前に残る問題
- CI（Actions 無料枠が戻るまで未実行）。PR はユーザーの許可まで作らない。
- 運用値の確定（T1.20 の実 config.yaml）。ML は市場確率のまま（昇格・v2 なし）。`adaptive_relaxation` の再判断。
- 依存取得の 429 でローカルの Gradle が稀に失敗する（再実行で通る）。
- 完了した日の bot-run 再起動（T3.9 で `race_day` を COMPLETED に）。
### ブランチ
`claude/stage3-bot-orchestration`（PR なし。ユーザーが CI を許可するまで保持）。

---

## Stage 3-H（claude/stage3-bot-orchestration）: T3.8 bot-run オーケストレーション
### 結果（**合成データ・Fake HTTP サーバ・メモリ上の偽物・ローカル MySQL 8.0.46 のみで検証。実 Discord・実 JRA-VAN データ・本番 GCP・Cloud Run には触れていない**。T3.9 には進んでいない）
- **データ利用方針は維持**: 構成（Windows エージェント → ingest → MySQL → Kotlin の特徴量・推論 → Python の独立した学習）は変更なし。期待値だけを保存する方式にしていない。実データのクラウド投入・実データ由来の通知は COMP-4 の公式回答まで行わない。JRA-VAN の規約確認を『済み』にしていない。未解決事項（P-1〜P-3・T1.16/17/20/21・T3.0・COMP-4・E-7・D-13/14・Stage 5）は変更なし。Visibility・Terraform・GCP・Secrets に触れていない。
- **実装**（D-190〜D-203、CONTRACTS 0.4.12、UNRESOLVED C-30〜C-33、MIGRATION_MATRIX §12、RACE_CALENDAR_DESIGN §13、ML_DESIGN §14）:
  - `:domain` `jp.jrabot.domain.botrun`: `BotRunOrchestrator`（開始検証 → リース → 再検証 → 復元 → ML 固定 → ループ → 終了）、`LeaseKeeper`（版の追跡・衝突の再試行・Lost の畳み込み）、`ModeGate`（UNKNOWN の連続 3 回）、`RaceMonitorPlanner`（レースの段階を毎サイクル再導出）、`RaceCycleProcessor`（鮮度 → 判定 → 冪等な記録 → checkpoint 保存 → 通知）、`RaceEvaluator`（既存部品の接続だけ）、`MlRunStarter`、`RunCheckpoint`、`OddsFreshness`、`BotRunConfig`（既定なし）、`Pacer`。テスト用の偽物は `botrun.testing`（D-203）。
  - `:persistence`: `RaceEvaluationWriter`（`INSERT … WHERE NOT EXISTS`）と `RaceEntryReader`（`race_horses.bamei`）。**マイグレーション・GRANT の変更なし**（スキーマ版は 4 のまま）。SQL テナント検査（`TenantSqlGuard`）を `INSERT … SELECT` に対応させて強化。
  - `:bot`: `BotRunSettingsParser`（`BOT_RUN_*`。必須・既定なし）、`BotRunTaskHandler`（`--trigger` → 通知の出どころ）、`SignalInstaller`（SIGTERM → `StopFlag`）、`SleepPacer`、`BotRunWiring`。`bot-run` はスタブでなくなった（`reconcile`・`retention` は引き続きスタブ）。
- **起動から終了までの流れ**: ①引数・設定の検証（不足は exit 2）②運用モード（DB に触れる前。確定した非 NORMAL は 0、UNKNOWN は 75）③スキーマ版 ④有効な開催日の導出と `CalendarCheckPolicy`（曜日・祝日を使わない。開催なし等は 0、予定が不明・古い・未確認・発走時刻なしは 75）⑤`bot_run` リース取得（DB の UNIQUE。他が有効なら 0 `ALREADY_ACTIVE`）⑥STARTING で再検証 ⑦前の run の checkpoint を復元 ⑧古い claim を `SEND_UNKNOWN` に ⑨ML をこの run で固定 ⑩RUNNING → ループ（停止要求・最大稼働・モード・heartbeat → 開催日の再導出 → 監視対象 → 各レース: オッズの鮮度 → 判定 → `race_evaluations`/`signals` を冪等に記録 → 発火済みを checkpoint に保存 → 通知）⑪全レースが OFF または中止で DRAINING → SUCCEEDED（`ALL_RACES_OFF`）。SIGTERM は INTERRUPTED、モード変更は STOPPED_BY_MODE、リース喪失は何も書かず 75。
- **旧 Python との互換**: 判定の数値（EV・Kelly・賭け額・確率の意味・丸め）は変更なし。**ゴールデン 31 ケース / 988 呼び出しが旧実装と全一致**（最大絶対誤差 1.066e-14。未対応は GD10 の 1 ケース / 6 呼び出しのまま）。旧の通知タイミング（`main/extra_snapshot_minutes`）・ポーリング間隔・5 分前の強化（opt-in）・履歴件数を設定で再現。旧と**違う**点: 古いオッズでは通知しない（是正）、発走時刻不明は通知しない（是正）、通知は『各タイミング 1 回』（新しい仕様・要確認 G-23）、`hit_rate_min_ev_schedule`/`adaptive_relaxation` は未移植（G-24）。MIGRATION_MATRIX §12 に全機能の分類。
- **リース・二重起動**: メモリのロックは使っていない（最終防壁は `bot_run` の UNIQUE と行ロック）。メモリ上の偽物で orchestrator の振る舞い（二重起動の拒否・期限切れの奪取・checkpoint の引継ぎ・古い実行の更新禁止・リース喪失で停止・強制終了からの復旧）を、**本物の MySQL（最小権限の `bot` ユーザー）で通しの 1 日・再実行の冪等・BACKFILL・有効なリースがあれば開始しない・テナント境界**を確認した（ローカル 8.0.46）。
### テスト（実行環境の確認: `java -version` = OpenJDK 21.0.12〔JDK 25 なし。`-PjdkVersion=21`〕、Gradle 9.7.1、Docker はクライアントのみ〔デーモンなし〕。ローカル: JDK 21・`LC_ALL=C.UTF-8`・MySQL 8.0.46〔apt の `mysql-server-core-8.0`、ポート 3307〕。**JDK 25・MySQL 8.4/9.7・Docker・GitHub Actions の合否ではない**。完了判定の正は GitHub Actions: D-056）
| 区分 | 結果 |
|---|---|
| `:domain` 単体 | **960 件成功**（新規 71: `BotRunLifecycleTest` 17・`BotRunLeaseTest` 13・`BotRunRestartAndModeTest` 19・`BotRunMlTest` 9・`BotRunComponentsTest` 13） |
| `:bot` 単体 | **130 件成功**（新規 20: `BotRunSettingsParserTest` 6・`BotRunTaskHandlerTest` 10〔実シグナル USR2 を含む〕・`BotRunFakeServerIntegrationTest` 4〔Fake HTTP サーバ。1 日分・429・5xx・BACKFILL〕。`BotSpringWiringTest`・`CalendarSettingsParserTest`・`TestSupport` を更新） |
| `:persistence` 単体 | 33 件成功（`SqlTemplateTenantGuardTest` を更新） |
| `:ingest` / `:dashboard` / `:cloud-gcp` / `:golden-runner` | 35 / 30 / 102 / 20 件成功。Kotlin vs 旧 Python は 31 ケース / 988 呼び出し全一致 |
| `:persistence:integrationTest`（MySQL 8.0.46） | 185 件中 184 成功。新規 9（`BotRunOrchestratorIntegrationTest` 5・`BotRunRepositoriesIntegrationTest` 4）成功。失敗 1 は既知（`WiringIntegrationTest` のサーバ版検査〔8.4 を期待〕。ローカルが 8.0） |
| `:bot:integrationTest`（MySQL 8.0.46） | 39 件中 33 成功。新規 2（`BotJobIntegrationTest` の bot-run）成功。失敗 6 は `MlTrainInteropIntegrationTest`（`ML_TRAIN_PYTHON` 未設定。環境の問題・既知） |
| ktlint・`checkModuleDependencies`・repo-hygiene・`tools/ci`（130 件） | 成功 |
| **未実施** | GitHub Actions 全体（JDK 25・MySQL 8.4/9.7・connectorj26・Docker イメージ・ml-train・Python のジョブ）、実 Discord、Cloud Run Jobs、`:ingest`/`:dashboard` の MySQL 統合（変更の影響が薄いため未実施だが、CI で確認が必要）、`ML_TRAIN_PYTHON` を使う Python 連携 |
### 変更・判断・未解決
- 判断: D-190〜D-203（DECISIONS）。契約: CONTRACTS 0.4.12（`BOT_RUN_*` を infra の Job 定義へ: T5.0）。
- **新規の未解決**（UNRESOLVED）: **G-23 運用値と通知方式のユーザー確認**（最重要）、G-24（`hit_rate_min_ev_schedule`/`adaptive_relaxation` の扱い）、G-25（T3.9 への引き継ぎ）、G-26（`horse_master_history` の追記）、G-27、F-7/F-8（検証ゲート）。解消: C-30〜C-33（G-8、G-17 ①②、G-21 ①〜⑤）。**既存の P-1〜P-3・T1.16/T1.17/T1.20/T1.21・T3.0・COMP-4・E-7・D-13/D-14・Stage 5 は変更なし（完了扱いにしていない）**。
- 旧実装の観察（変更なし・記録のみ）: `current_snapshot_minutes` が最大のタイミングで決まる癖（`[30,10,5]` では『5 分前の強化』が働かない）を `SnapshotPolicy.legacyCurrent` でそのまま再現（テストで固定）。
### 次の作業（**自動では進まない。ユーザーのレビューを挟む**）
1. **ユーザー**: T3.8 のコードと G-23（運用値・通知方式）・G-24 を確認。Actions の無料枠が戻る（または Public 化を完了して知らせる）まで PR は作らない。
2. 枠が戻ったら PR を作り（`claude/stage3-bot-orchestration` → main）、CI（JDK 25・MySQL 8.4/9.7・images）の結果を記録。失敗したら『検査を緩めず』直す。
3. **Stage 3-I（T3.9）**: PLAN.md「T3.8 後の道筋」の ①〜⑤（結果・払戻・決済・reconcile・成績集計・バックテスト）。着手はユーザーの承認後。
4. その後 第4段階（dashboard）→ **T5.0（infra 同期）**→ 第5段階（課金リソースは明示的な承認後）。
### ブランチ・PR・CI
`claude/stage3-bot-orchestration`（main の eed3c0e から）。URL: https://github.com/puttun777/jra-bot-web/tree/claude/stage3-bot-orchestration 。**PR は未作成**（Actions 無料枠が残り 150 分未満のため）。`claude/*` への push は CI を起動しない。**CI の結果はまだ無い（未検証）**。

---

## Stage 3-G（claude/stage3-discord-notification）: T3.7 Discord 通知
### 結果（**合成データ・Fake の HTTP サーバのみで検証。実 Discord・実 Webhook・実 JRA-VAN データへは触れていない**。T3.8 には進んでいない）
- **データ利用方針は維持**: アーキテクチャは変更なし（期待値だけを保存する案へは切り替えていない）。実データのクラウド投入・実データ由来の通知は COMP-4 の公式回答まで行わない（運用ゲート。コードでは強制していない）。JRA-VAN がクラウド保存・通知を許可したことにも、本人認証で規約確認が不要になったことにもしていない。未解決事項（P-1〜P-3・T1.16/17/20/21・T3.0・COMP-4・E-7・D-13/14・Stage 5）は変更なし。
- **実装**（D-181〜D-189、CONTRACTS 0.4.11 §9.2、MIGRATION_MATRIX §11）:
  - `:domain` `notify`: `ContentSplitter`（行単位・サロゲート安全・1900）、`RateLimitWait`（retry_after → Retry-After → X-RateLimit-Reset-After → 1.0）、`MentionPolicy`/`DiscordText`、`NoticeViews`/`NoticeFormatter`（旧 `send_signal`/`send_signals_grouped` の本文。単勝・ワイド・三連複・見送り・複数シグナル統合・買い目の重複除外と上位 6 点。判定結果をそのまま表示し EV/Kelly/確率を再計算しない）、`SignalNotificationDispatcher`、`ErrorReporter`/`SecretRedactor`、`ml.features.ReasonFormatter`（G-17 ⑥）と `reasonFacts`（旧互換/point-in-time）。
  - `:bot` `discord`: `DiscordWebhookUrl`（https の discord.com 系 `/api/webhooks/<id>/<token>` だけ。IP・ポート・クエリ・他ホストは拒否。ループバックのテスト用は `forLocalTest` のみ）、`JdkWebhookTransport`（リダイレクト無効・タイムアウト）、`DiscordNotifier`、`DiscordSettings`（`bot-run` のときだけ読む）、`StopFlag`、Bean 配線（`Notifier`/`SignalNotificationDispatcher`/`ErrorReporter`）。
  - `:persistence`: `SignalReader.listClaimedBefore`（古い CLAIMED の回収用 SQL）。新モジュールなし。
- **配送の安全規則**: 429 と接続前の失敗だけ再試行（最大 5 試行・Retry-After を尊重・60 秒超は再試行せず終了）。5xx・要求タイムアウト・切断・想定外の応答・一部のチャンクだけ送信済みは `UNKNOWN`（再送しない）。確実な拒否（4xx）と 1 通目の失敗は `NOT_SENT`。Signal は DELIVERED→`SENT`、それ以外→`SEND_UNKNOWN`（claim 済みは戻せないため NOT_SENT も SEND_UNKNOWN。取りこぼしを選ぶ）。**exactly-once は保証しない**。出どころ（`NotificationOrigin`、既定値なし）が LIVE 以外・運用モードが NORMAL 以外（毎回読む）・停止要求中は何も変えず通知しない。宛先未設定は claim の前に検出。claim 後に記録できなかったものは `recoverStaleClaims`（NORMAL のみ・古い claim のみ）。
- **旧 Python との差**: 旧は 5xx・タイムアウトも最大 5 回再送し、daemon はグループ全体を再送した（N3/N4）。旧 `mention`（自由文字列）は移植しない（N5）。**旧欠陥を発見**: N1（『ℹ️ 期待利益は…確率』がほぼ常に出る）、N2（`send_signals_grouped` が成功経路の末尾で `UnboundLocalError`＝送信済みなのに例外 → 再起動後の再通知）。いずれも互換モードで再現・運用モードでは是正（数値は不変）。MIGRATION_MATRIX §11。
- **ゴールデン G8**（旧 `f7dbf69`）: `_split_content`・`_parse_retry_after_seconds`・`_deduplicate_tickets`・`send_signal`・`send_signals_grouped`（本文）・`format_reason_from_features`。ケース 26→**32**、呼び出し 880→**994**。Kotlin が検証したのは **31 ケース / 988 呼び出しで全一致**（未対応は GD10 の 1 ケース / 6 呼び出しのまま。成功に数えない）。既存フィクスチャは未変更（`--add-new`）。HTTP 送信関数の差し替えと `requests` のスタブで、旧実装はネットワークに出ない。
- **公式仕様**: 429 の `retry_after`（秒の小数）・`Retry-After`・`X-RateLimit-Scope`（user/global/shared）は公式ドキュメントの検索結果で確認。discord.com へは直接接続できず（ネットワーク遮断）、2000 文字の数え方は確認できていない（UNRESOLVED G-20）。
### テスト（ローカル: JDK 21・`LC_ALL=C.UTF-8`・Gradle 429 は再試行。**JDK 25 の合否ではない**。完了判定の正は GitHub Actions: D-056）
| 区分 | 結果 |
|---|---|
| `:domain` 単体 | **889 件成功**（新規 67: `notify` の `ContentSplitterTest` 11・`RateLimitWaitTest` 5・`MentionPolicyTest` 5・`NoticeFormatterTest` 13・`HorseReasonTest` 7・`SignalNotificationDispatcherTest` 18・`ErrorReporterTest` 8） |
| `:bot` 単体 | **110 件成功**（新規 35: `DiscordNotifierFakeServerTest` 23〔2xx・400/401/403/404・429+Retry-After・500/502/503/504・タイムアウト・接続切断・接続拒否・分割途中の失敗・停止/SIGTERM・ログ漏洩〕・`DiscordWebhookUrlTest` 4〔SSRF〕・`DiscordSettingsTest` 6・`BotSpringWiringTest` +2） |
| `:persistence` 単体 / 統合 | 33 件成功 / 統合は**ローカル未実施**（Docker・MySQL なし）。`SignalRepositoryIntegrationTest` に `listClaimedBefore` の 1 件を追加（CI で確認） |
| `:golden-runner` | 20 件成功。Kotlin vs 旧 Python 31 ケース / 988 呼び出し全一致（最大絶対誤差 1.066e-14） |
| `tools/golden`（Python） | **93 件成功**（`GOLDEN_LEGACY_PATH` あり・スキップ 0）、`verify`・`generate --check` 成功 |
| `tools/ci` | 130 件成功。ktlint・`checkModuleDependencies`・repo-hygiene 成功 |
| **未実施** | 実 Discord への送信、bot-run + MySQL + Fake サーバの通知の統合試験（T3.8）、MySQL 統合テスト（CI）、Docker イメージ（CI） |
### 未解決・新規（UNRESOLVED.md が正本。解消: C-27〜C-29、G-8、G-17 ⑥）
G-20（2000 文字の数え方）・G-21（T3.8 への引き継ぎ）・G-22（実 Discord 未検証）。**既存の P-1/P-2/P-3・T1.16/T1.17/T1.20/T1.21・T3.0・COMP-4・E-7・D-13/D-14・Stage 5 は変更なし。**
### 次の作業（**自動では進まない**）
T3.8（bot-run 全体ループ。G-21 の ①〜⑥）。第 5 段階前に T5.0（`DISCORD_MENTION_USER_ID` の追加を含む infra 同期）。
### ブランチ・PR・CI
`claude/stage3-discord-notification`（main の b534ac7 から）。PR・CI の結果は PR を参照（**マージはユーザーの判断**）。

---

## Public 化の準備（claude/prepare-public-repo、2026-10-26）— Visibility は変更していない
- **スキャン**: gitleaks（`go install` でビルド）を全履歴に実行: リモート 56 参照（全ブランチ + PR ヘッド 27）、117 コミット（うち差分のあるもの 95 を走査、約 14MB）、**10 件検出・すべてテスト用ダミー**（`agent/tests` のテストベクトル `signingKeyHex`〔`TEST-VECTOR-ONLY` トークンから導出〕、テスト用のトークン文字列、`Ed25519PrivateKey.generate` のフィールド名）。実秘密 0。**trufflehog は取得・ビルドが環境の判定で拒否され未実施**。前回の自前スキャン（747 ファイル）とも一致。
- **Actions/Artifacts**: run 100/103 件のメタデータ、失敗 run 3 件（push 1・PR 2）の失敗ジョブログ（末尾）と images ジョブのログ末尾、Artifact `images-dry-run`（URL 取得のみ。**中身は未読**）を確認。秘密・実データなし。ログ全文・全 run のログ・期限切れ Artifact・PR 24 以外のコメントは未確認。
- **変更**: CLAUDE.md・README.md・REQUIREMENTS §0・ARCHITECTURE §1・CI_AND_IMAGES（§9 追加）・DATA_SOURCE_COMPLIANCE §0.1・JVLINK_DESIGN §1.4（バイト位置削除）・DECISIONS D-180・UNRESOLVED（D-16・E-7）・SECURITY.md・NOTICE.md。コード・CI は未変更。
- **未解決**: JRA-VAN のソース公開許可なし（E-7）。履歴に §1.4 のバイト位置が残る。GitHub 側の設定（Secrets・fork PR 承認・ruleset・secret scanning）は未確認。Public 化の判断はユーザー（D-16）。
- **次**: ユーザーが CI_AND_IMAGES §9 を確認し、Visibility を変更するか判断。

## Stage 3-F2（claude/stage3-ml-training）: T3.6-B ML の学習 Job・export-dataset・DB/GCS 実装
### 結果（**合成データのみで検証。ML は本番利用可能ではない**。実データでの学習・精度・品質閾値・実 pkl の変換・実 GCS は未検証。Stage 3-G / T3.7 には進んでいない）
- **実装**（判断 D-165〜D-179、詳細は ML_DESIGN §12・CONTRACTS 0.4.10・ml/README.md）:
  - Kotlin: `bot --task=export-dataset`（`DatasetPublisher`: CSV → manifest〔確定の印〕）、`:persistence` の `DbFeatureSource`/`DbDatasetSource`/`HorseMasterHistoryStoreImpl`（SQL が asOf・レース日で時間を制限）、V4 `horse_master_history`（G-17 ⑤。`HorseMasterAtTime.resolve`）、`:cloud-gcp` の `GcsModelStore`/`GcsDatasetStore`（作成のみ・`ifGenerationMatch=0`・署名先/JSON 最後）、`DatasetStore` の manifest 読書き。スキーマ版は 4。
  - Python `ml/`（`ml_train`）: データセット検証（`tools/ml` の契約を再利用）→ v2 学習（標準化 + L2 ロジスティック回帰 + Platt + 直近重み）→ 評価（valid 前半で校正・後半で評価）→ 品質ゲート（既定値なし・fail closed）→ 署名つきモデル JSON（Kotlin `ModelLoader` と同じ検証順）→ 保管 → `ml_model` へ CANDIDATE / REJECTED のみ登録（ACTIVE へ昇格しない・既存行を変更しない）。SIGTERM は確定前なら成果物を片付けて 75。
  - 旧 pkl → JSON 変換（`ml_train.legacy_pkl`。許可リスト式 Unpickler。ローカル専用。**実 pkl の変換は未実施**）。
  - ml-train イメージ（5 つ目）、release-manifest v2（`feature_schemas` + `feature_schema_hash`: G-17 ⑧）、CI `ml-train` ジョブ（`ci gate` に統合）、Dependabot pip `/ml`。
- **範囲外のまま**: T3.7（Discord）、bot-run 統合（T3.8）、実 JRA-VAN データ、本番 GCP・Terraform、モデルの ACTIVE 昇格、v2 の有効化、品質閾値の確定（D-13）、本番 release-manifest、イメージの push。
### テスト（ローカル: JDK 21・`LC_ALL=C.UTF-8`・MySQL 8.0.46〔ポート 3307〕。**JDK 25 と MySQL 8.4/9.7 の合否ではない**。完了判定の正は GitHub Actions: D-056、D-179）
| 区分 | 結果 |
|---|---|
| `ml/`（pytest・ruff・mypy strict） | **128 件成功**・ruff・mypy 成功 |
| `:domain` 単体 | 822 件成功 |
| `:cloud-gcp` 単体 | 102 件成功（`GcsStoresTest`・`GoogleObjectClientHttpTest` を追加） |
| `:persistence` 単体 / 統合 | 33 / 175 件。`MlSourceIntegrationTest`（8）成功。`WiringIntegrationTest.サーバは想定した版で動いている` は**ローカルが MySQL 8.0.46 のため失敗**（8.4 を期待する検査。F1 と同じ。CI の 8.4/9.7 で確認） |
| `:bot` 単体 / 統合 | 75 / 36 件（`MlTrainInteropIntegrationTest` = Kotlin 出力 → Python 学習 → Kotlin `ModelLoader` 検証、5 件成功） |
| `:ingest` 単体 / 統合、`:dashboard` 単体 / 統合 | 35 / 50、30 / 9 件 |
| 並列実行時の注意 | 全モジュールを並列実行すると、サーバの接続数を数える 3 件（Bot/Ingest/Dashboard の「DB に接続しない」検査）が、同じ外部 MySQL を共有する他モジュールの接続で失敗する。`--max-workers=1` で単独実行すると成功（ローカル固有。CI は Testcontainers で独立） |
| `tools/ci`・`tools/release`（Python） | 130 件成功（`python3 -m tools.ci.run_tests --min-tests 100`） |
| ktlint・`checkModuleDependencies` | 成功 |
| Docker イメージのビルド・スモーク | **ローカルでは未実施**（Docker デーモンなし）。GitHub Actions の `images` で確認 |
### 未解決（UNRESOLVED.md が正本。今回解消したのは C-20〜C-26 のみ。新規 D-13〜D-15、G-19）
T1.20（実 pkl・実 SQLite）、T1.21、品質ゲートの閾値（D-13）、署名鍵運用（D-14）、保持期間（D-15）、実 GCS での検証・`gs://` の直接読書き・履歴の追記（G-19）、E 項目（規約・仕様確認）、T3.0 Windows 実機。
### 次の作業（**自動では進まない**）
T3.7（Discord 通知。G-17 ⑥）／T3.8（bot-run 統合。G-17 ①②。履歴の追記）。第 5 段階前に T5.0（infra 同期。スキーマ版 4 の GRANT・`mltrain` ユーザー）。
### ブランチ・PR・CI
`claude/stage3-ml-training`（main の 8ab5f41 から）。PR: https://github.com/puttun777/jra-bot-web/pull/24 （**マージはユーザーの判断**。自動マージなし）。
CI（head a978147, run 40128981405）: build (Temurin 25)・db integration (MySQL 8.4 / 9.7)・golden・ml tools・**ml-train (Python 3.13)**・release tools・agent python 3.12/3.13・agent windows・**images（5 イメージのビルドとスモーク。push なし）**・changes and repo hygiene・**ci gate がすべて成功**（connectorj26 は非ゲートでスキップ）。
経緯: 初回 CI で `images` が失敗した。①ml-train イメージの venv に `--chmod=a=rX` を付けたため python が起動できなかった → 外した ②スモークの env 引数の重複 ③`ml/requirements.txt` に推移的依存 `cloudpickle`・`narwhals` が無く、`--no-deps` のイメージで joblib が読めなかった → 追加（`pip check` がスモークで検出） ④スモークの合成 DB にテナント 1 が無く `ml_model` の FK 違反 → CI 専用 DB にだけ追加 ⑤成果物（uid 10001 所有）を検査側が読めなかった → 同イメージ内で読取り権限を付与。いずれもテスト・検査側またはイメージ定義の修正で、検査を緩めていない。

---

## Stage 3-F1（claude/stage3-ml-inference-h7e43j）: T3.6-A ML の Kotlin 推論基盤（特徴量・モデル検証・推論・ゴールデン比較）
### 結果（T3.6 の前半を完了。**本番で動作する推論ではない**: bot-run への統合は T3.8、学習は T3.6-B（Stage 3-F2）。本番の確率パイプラインは引き続き `legacy`。v2 は未承認・無効・自動切替なし）
- **範囲外のまま**: Python の学習・ml-train イメージ・実 pkl の読込み/変換の実行・実 JRA-VAN データ・Windows 実機・GCP・Terraform・Discord・bot-run・モデルの ACTIVE 昇格・v2 への切替。**Stage 3-F2 には進んでいない。**
- **実装したクラス**（`:domain` の `jp.jrabot.domain.ml.*`。ML_DESIGN §11.1）: `StrictJson`/`CanonicalJson`、`FeatureSchema`/`FeatureNames`/`PedigreeHash`、`LegacyFeatureBuilder`/`PointInTimeFeatureBuilder`/`FeatureSource`/`InMemoryFeatureSource`/`LegacyRows`、`LogisticScoring`/`ModelDocument`/`ModelLoader`/`ModelTrust`/`VerifiedModel`、`LegacyProbabilityPipeline`/`V2ProbabilityPipeline`/`FallbackReason`/`ProbSource`、`ModelRegistryReader`/`ModelRunPin`/`ModelSession`/`LocalFileModelStore`/`InMemoryModelRegistry`、`DatasetExporter`、`MlInference`。`:persistence`: `MlModelRegistryRepository`（`ml_model` の SELECT のみ）。`WinDecisionEvaluator.evaluate` に任意引数 `mlInput`（既定 null で T3.1/T3.2 は不変）。`:golden-runner`: 旧 `WinProbabilityModel` の互換モジュール `GoldenMl`（次元の切詰め/ゼロ埋めはここだけ）。Python: `tools/ml`（正準 JSON・署名・データセット契約と合成成果物）。
- **feature_schema**: `{version, names, pedigree_dim, hash_algo, missing_policy, hash}`。`hash` = `sha256:` + 正準 JSON の SHA-256。calibration=40 次元（`sha256:66e188f1…e418`）、alpha=35 次元（`sha256:adc23ffb…e87a`）、market=5 次元（`sha256:b86195e2…8959`）。名前の順序が変わるとハッシュが変わる（テスト済み）。Kotlin が JSON を書き、Python が名前の一覧を持たずに hash を独立に再計算して一致を確認。ユーザー指示の項目名 `sha256` は ML_DESIGN §5.2 に合わせて `hash` に（D-154）。
- **legacy / v2 の差**（ML_DESIGN §11.5）: legacy は旧の分岐順・二重ブレンド（H4）・旧 `prob_source` 文字列（H10）を再現し、理由コード・段階・馬ごとの出どころ（E1/E2）だけ追加。v2 は単回ブレンド・段階的フォールバック・正確なラベル・市場のみのときはブレンドなし。合成 calibration モデルで、市場確率との L1 距離は legacy 0.5882 / v2 0.5908、legacy と v2 の最大絶対差 0.0138。**v2 が高精度かは未証明（実データ・バックテスト前）。**
- **Kotlin 推論の計算精度**: すべて `Double`。`dot` は特徴量順の逐次加算、`exp/ln` は `kotlin.math`。旧 Python との差は**最大絶対誤差 1.066e-14**（ML_DESIGN §7.2 の許容 1e-9 に対して十分小さい。特徴量ベクトル G6 は誤差 0）。NaN/Infinity は拒否（`NonFiniteComputationException`・`JsonFloat` の不変条件）。
- **モデル署名検証の結果**: Ed25519（RFC 8032 のテストベクトルを Python で確認、Kotlin は JDK の `Signature`）。未署名（0 埋め）・別鍵の署名・署名の 1 ビット反転・内容 1 バイト改ざん・別モデルの署名の流用 → すべて `MODEL_SIGNATURE_INVALID`。署名は有効だが中身が不正な 13 種（sha256 不一致・次元不一致・スキーマ hash 不一致・kind とスキーマの不整合・他テナント・未対応版・未知キー・algorithm 不正・非正準・NaN・範囲外の数値）が期待の理由コードで拒否される。検証前のモデルは `VerifiedModel` 以外では推論できない（型で保証）。テスト用の鍵は決定的なシード（秘密ではない。本番の鍵は不要）。
- **ModelStore / レジストリの完成範囲**: 読取り契約（`find`/`active`）・run 開始時の固定（`ModelRunPin`）・検証つき読込み（`ModelSession`。レジストリの sha256・feature_schema_hash・kind と照合）・ensemble セット（メンバー検証）・CANDIDATE は使わず自動昇格しない・合成のローカル `ModelStore`（作成のみ・上書きしない）。**昇格 API は作っていない（Stage 4）。GCS の実バケットには書いていない。** bot-run への統合は T3.8。
- **ゴールデン**: 旧 Python（f7dbf69・numpy 2.5.3 固定）から **26 ケース / 880 呼び出し**（T3.2 時点の 21 / 810 + 新規 5 ケース / 70）。**Kotlin が検証したのは 25 ケース / 874 呼び出し（全一致）**。未対応は `GD10-DEFAULTS-001`（旧関数の既定値。T1.20）の 1 ケース / 6 呼び出しだけ（成功に数えない）。新規: `G6-FEATURES-001`（特徴量 40/35・血統ハッシュ・H3/H8）、`G3-ML-NORM-002`、`G4-ENSEMBLE-002`、`G5-BLEND-002`、`G7-LEGACY-FAIL-001`（旧実装側の失敗時の挙動。フォールバックの数値・旧ラベルが一致）。G1/G2 の既存フィクスチャも Kotlin で比較。既存フィクスチャは `--add-new` で不変（例外は G1 の 7 呼び出し: 下記）。新しいロード契約の失敗系（G7 の新設計の仕様）は旧出力ではなく Kotlin 単体テストと `tools/ml` の署名つき成果物で検証。
- **ゴールデンが見つけた欠陥・不具合**（実装前の精読では見落としていた）: ①**旧 `_sum_chaku_table` の癖**: 要素が 3 未満の行は `top3 += 全行の top2 の累計`（距離/馬場/状態の 3 着内率が 1 を超え得る）。Kotlin の `ChakuTable` に再現（D-159）。②**ゴールデン基盤の不具合**: `jsonutil.normalize` が numpy 配列を `.item()` していたため、G1-MODEL-001 の `model_extract_features` 7 呼び出しは旧実装の戻り値ではなく基盤の `ValueError` が期待値として記録されていた（T2.6 から）。修正し、その 7 呼び出しだけを更新（D-153。**既存フィクスチャの更新はこの 1 件だけで、許容誤差の拡大ではない**）。
- **旧互換と新方式の区別**: 旧互換（`Legacy*`）は旧の欠陥（H3 過去走リーク・H8 `now` 基準・H2 切詰め・H4 二重ブレンド）を**そのまま**再現してゴールデンの基準にし、新方式（`PointInTime*`・v2・ロード契約）は別のクラス・別のテスト。新方式の数値を旧互換の期待値へ上書きしていない。旧実装の再現対象外（`model_type=auto`・`ensemble_beta=auto`・『ml:legacy(no_odds)』・mtime キャッシュ・pickle）は MIGRATION_MATRIX §10 に分類。
- **H1〜H10 の確認**: ML_DESIGN §11.10。H1/H10 は記録を是正（数値不変）、H2 はロード契約で拒否、H3/H8 は新方式で構造的に解消、H4 は legacy で維持・v2 で解消案（未承認）。実データでの影響は未確認（G-ML-1/2）。
- **学習側への引き継ぎ契約**（ML_DESIGN §11.7・§11.8）: データセット（gzip CSV + manifest・列・split・as_of・sha256・`sample_weight` の式）、モデル出力形式、署名、品質ゲート案を定義。合成入力で CSV を生成し、Python（`tools/ml/dataset_contract.py`）が独立に検証。旧 pkl 変換ツールの仕様とローカル手順（実装・実行は未実施）。
### テスト（ローカル: JDK 21・`LC_ALL=C.UTF-8`・MySQL 8.0.46。**JDK 25 と MySQL 8.4/9.7 の合否ではない**: D-163）
| 区分 | 結果 |
|---|---|
| `:domain` 単体 | **811 件成功**（T3.5 時点の 667 + 新規 144: `StrictJsonTest` 34・`ModelLoaderTest` 28・`PedigreeHashTest` 17・`FeatureBuilderTest` 16・`ProbabilityPipelineTest` 15・`MlEndToEndTest` 12・`DatasetExporterTest` 11・`CanonicalJsonTest` 5・`FeatureSchemaTest` 5・`FeatureSchemaFixtureTest` 1）。うちモデル署名検証・fail closed は `ModelLoaderTest`、FeatureBuilder は `FeatureBuilderTest`、ProbabilityPipeline は `ProbabilityPipelineTest`/`MlEndToEndTest` |
| `:golden-runner` 単体 | 20 件成功（旧 14 + `GoldenMlTest` 6） |
| Kotlin vs 旧 Python ゴールデン | **25 ケース / 874 呼び出し 全一致、最大絶対誤差 1.066e-14**（未対応 1 ケース / 6 呼び出し） |
| `tools/golden`（Python） | 90 件成功（`GOLDEN_LEGACY_PATH` あり・スキップ 0）、`generate --check`・`verify` 成功 |
| `tools/ml`（Python） | 20 件成功（正準 JSON・RFC 8032 ベクトル・署名・成果物の決定的再生成・データセット契約） |
| `tools/ci` | 119 件成功（`ml_tools` の判定とゲートを追加） |
| `:persistence` 単体 / 統合（MySQL 8.0.46） | 33 / **167 件中 166 成功**（新規 6: `MlModelRegistryIntegrationTest` 5 + `EnumContractIntegrationTest` 1）。失敗 1 件は `WiringIntegrationTest.サーバは想定した版で動いている`（`mysql:8.4` 固定の版確認。ローカルが 8.0 のため。T3.4 から同じ。CI は 8.4/9.7） |
| ktlint・`checkModuleDependencies`・repo hygiene | 成功 |
| `:cloud-gcp`・`:bot`・`:ingest`・`:dashboard`・`:build-logic` のテスト | **ローカルでは未実施**（Maven Central の 429 で依存を解決できなかった: `google-cloud-storage`・`junit-platform-launcher`）。これらは変更していない。**CI の結果が正（D-056）** |
### 未解決（詳細は UNRESOLVED.md。今回解消したのは C-14〜C-19 のみ）
T1.20（実 SQLite・実 config・実 pkl）、T1.21（legacy/v2 の切替はユーザー判断待ち）、P-1/P-2/P-3（承認待ち）、T1.16（クラウド保存・Discord 等の規約確認待ち）、T1.17（公式仕様書の確認待ち）、T3.0（Windows 実機 PoC 未完了）、T5.0（infra 同期待ち。`MODEL_VERIFY_PUBKEY` の書式を追加）、本番 GCP（未検証）、MySQL 9.7・Connector/J 26.x（本番採用未決）。**Windows エージェントの実機検証が済んだことにはしていない。** 新規: F-5（ML の実データ検証）・G-17（T3.6-B/T3.8 への引き継ぎ）・G-18（ML の未承認の改善案）。
### Stage 3-F2（T3.6-B）への引き継ぎ（**自動では進まない**）
ML_DESIGN §11.7・§11.8、STAGE2_HANDOFF §14.12、UNRESOLVED G-17。要点: ①データセット契約を `export-dataset` に配線（`FeatureSource`/`DatasetSource` の `:persistence` 実装、`DatasetStore` への manifest 保管）②Python 学習 Job（`dataset_contract` で検証してから学習。正準形のモデル JSON + Ed25519 署名 + `CANDIDATE` の INSERT。昇格しない）と ml-train イメージ ③品質ゲートの数値をユーザーと確定 ④pkl 変換ツールの実装（実行は利用者のローカル）⑤馬マスタの時点履歴 ⑥release-manifest の `feature_schema_hash` の単一値問題（UNRESOLVED G-17 ⑧）。
### ブランチ・PR・CI
`claude/stage3-ml-inference-h7e43j`（main の 2e4fa11 から）。PR: https://github.com/puttun777/jra-bot-web/pull/22 （**マージはユーザーの判断**）。
CI（head 6b446a8, run 40080811615）: build (Temurin 25)・db integration (MySQL 8.4 / 9.7)・golden・ml tools・release tools・agent python 3.12/3.13・agent windows・images・changes and repo hygiene・**ci gate がすべて成功**（connectorj26 は非ゲートで skipped）。
経緯: 初回 CI（ae3fe45）は `ml tools` が失敗した。合成データセットを `*.csv.gz` で置いたが、実データ混入防止のガード（`.gitignore`・`check-repo-hygiene.sh`）が `*.csv.gz` を禁止していたため Git に入っていなかった。ガードは緩めず、フィクスチャを非圧縮 `.csv` で保持し、テスト/CI がメモリ上・一時ファイルで gzip してから契約検証する形に直した（実行時の保管形式 `{dataset_id}.csv.gz` は変更なし）。

## Stage 3-E（claude/stage3-windows-agent）: T3.5 Windows 取得エージェント（Python）と ingest の mode/plan/update
### 結果（T3.5 の Mock・CI 範囲で完了。**実機（JV-Link・DPAPI・タスクスケジューラ）は未検証。実取得は公式仕様の確認待ちで開始されない。P-1/P-2 は承認待ちのまま**）
- **再開の経緯**: 使用上限で中断後、`agent/` は未コミットのまま残っていた（ブランチ・ファイルとも無事）。203 件の再実行を確認してからコミットして保護（e8c470b）。
- **Python**: 目標 3.13（64bit）・最小 3.12（`>=3.12,<3.14`）。根拠は VERSIONS §14（cryptography 50.0.2・pywin32 312 の win_amd64 wheel を 3.12〜3.14 で確認。**3.14 は実機未検証なので対象外**）。実行時の依存は `cryptography` のみ。
- **agent/ の構成と機能**: `agent/README.md`。署名（P-1）・gzip・再送・バックオフ、SQLite WAL（取得済み＝FETCHED／投入済み・送信待ち＝QUEUED／送信中＝SENDING／再試行待ち＝RETRY_WAIT／ACK 済み／DEAD）、取得セッション `fetch`（P-2）、`fetched_ts`/`queued_ts`/`acked_ts`、Ed25519 ビーコン、状態機械、COM ワーカー（別プロセス・ウォッチドッグ・再起動上限）、`SpecRegistry` の仕様ゲート、取得計画、更新 manifest、多重起動防止、DPAPI（ctypes）、CLI（verify-config / verify-queue / status / diagnose / run ほか）、PoC、`scripts/*.ps1`（既定ドライラン。実機には何もインストールしていない）。
- **サーバ側**: `GET /v1/agent/mode`（認証なし・DB 不使用）・`plan`（検証済み仕様のみ・既定 idle）・`update`（署名済み manifest をそのまま返す）。**`register` は管理者認証が無いため保留**（CONTRACTS §3.3.2）。
- **T3.4 の実装の穴を発見・修正（D-144）**: ACK の応答だけが失われて再送されると、重複 ACK に cursor が付かず `acked_ts` が止まる（データ欠損ではなく活性の問題。セキュリティ問題は見つかっていない）。加算だけの修正で、結合テストで確認。
### テスト
| 区分 | 結果 |
|---|---|
| Python 単体・統合（Mock）、ローカル Python 3.13 | **203 件成功**（署名ベクトル 21・キュー 33・送信 24・ビーコン/状態機械 24・設定/秘密 33・ワーカー 8・取得 13・シナリオ 8・計画/更新/ロック 39 ほか）。Windows 専用 4 件は Linux では skip（Windows ランナーで実行） |
| ruff / mypy（strict） | 成功（55 ファイル） |
| `tools/ci` | 114 件成功（agent の判定 5 件を追加） |
| Kotlin（ingest 単体 35・統合 50 の下限、`AgentSigningVectorsTest`・`PlanProviderTest`・`AgentInteropIntegrationTest`） | **ローカルでは未実施**（Maven Central の 429 で Gradle が解決できなかった）。CI の結果が正（D-056） |
| 実 JV-Link・実 DPAPI・タスクスケジューラ・スリープ復帰 | **未実施**（実機。`agent/POC.md`） |
### 実機未検証・未完了
G-AGENT-1〜6 の実機確認、JVRead/JVGets のバイト列、実 COM のハング回復、DPAPI（Windows ランナーのみ）、`register`、更新の公開配布、取得計画の自動生成、実データのパーサ（T1.17）。
### ユーザー承認待ち
D-5（P-1）・D-6（P-2）・D-7（P-3）は**承認済みにしていない**。他は UNRESOLVED の D 分類のまま。
### T3.6 への引き継ぎ
ML（T3.6）は agent と独立。前提: 実データ形式のパーサは未実装。T5.0 に G-13/G-14（ビーコンの再署名、配布、環境変数）。
### ブランチ・PR
`claude/stage3-windows-agent`（main の 5aa8672 から）。**PR #21**（main 向け、マージはユーザー）。
### CI
- **コードを含む最新コミット d612c16 の run 40059683314: ci gate 成功**（agent python 3.12/3.13・agent windows・build・golden・release tools・db integration 8.4/9.7（`AgentInteropIntegrationTest` を含む ingest 統合）・images が成功。connectorj26 は設計どおり skipped）。
- **途中の失敗と修正**: ① ruff の指摘（`with` の結合・書式）② `:ingest` 単体の全滅 — 旧テスト用の仮ハンドラ `ProbeController` が本物の `/v1/agent/mode|plan` と衝突（ローカルは Maven Central の 429 で Gradle が動かず事前に気づけなかった）③ 統合テストの期待値 2 件（D-144 で古い取得にも cursor を返す／STOPPED は claim 前に HALTED）。いずれもテスト側の更新で、コードの欠陥ではない。
- 本記録のコミット（docs のみ）でも CI が走る（結果は PR のチェックを参照）。

## Stage 3-D（claude/stage3-ingest-api）: T3.4 取込 API・認証・冪等・取得の事実・バックフィル基盤
### 結果（T3.4 の範囲で完了。**本番で動作する取込ではない**: 実データ形式のパーサは未実装、署名方式は承認待ち、Windows エージェント本体は T3.5）
- **要件変更（本人 1 名・私的・非営利）を反映**: REQUIREMENTS §0 を新設、CLAUDE.md・README・ARCHITECTURE・DATA_SOURCE_COMPLIANCE・STAGE2_HANDOFF ほかの「初期利用者 2 名」を履歴付きで改訂（D-139）。`tenant_id`・Repository のテナント境界・認証認可は維持。新規利用者の登録・招待・共有は作らない。
- **JRA-VAN 規約（第7条・第9条）**: 公式ページの**検索抜粋**で条文の構造を確認（原文は 403 で直接読めていない）。T1.16 を再スコープ（2 人利用・配布・一般公開・JRADB は対象外）。**クラウド保存・生データ保管・Discord 通知の可否は未確認のまま維持**し、問い合わせを 3 問に絞った。**規約確認（COMP-1）と実データのクラウド投入の確認（COMP-4）を別項目で管理**（DATA_SOURCE_COMPLIANCE §0、D-140）。
- **SDK 5.0.0（T1.17）**: 64bit JV-Link の正式提供、32/64bit の共存と別利用キー、公式 Python サンプル（3.14）、**SDK 本体 5.0.0 と仕様書の文書版 4.9.0.1 は別**を確認（検索抜粋）。古い「64bit 未確認」の記述を修正。**公開の確認と実機動作を区別**（T3.0 は未完了。手順は SDK 5.0.0 対応に更新）。仕様書 12 項目は**すべて未確認**（PDF に到達できず。JVLINK_DESIGN §12）。D-141。
- **認証方式（最優先の確認）**: 契約 0.4.6 の「HMAC(token) 署名＋サーバはハッシュのみ保存」は**暗号学的に検証不能**（単体テストで確認）。導出鍵 HMAC＋KEK（AES-256-GCM、AAD で行に束縛）を実装。Cloud KMS・Ed25519 の代替との比較は CONTRACTS §3.3.1。**契約変更なので提案 P-1（未承認）**。KEK が無ければ `/v1/agent/**` は全て 503（未認証で保存しない）。D-130。
- **実装したもの**
  | 層 | 内容 |
  |---|---|
  | `:db-migration` | **V3**（V1/V2 は不変）: `agent` の署名鍵列＋`agent_id` 全体 UNIQUE、`ingest_batch` の取得セッション列、`ingest_fetch`、`ingest_backfill`、`combo_odds_snapshots.odds_high`、複合 FK 用 UNIQUE。`R__grants.sql` に新表の権限（app は署名鍵の列を読めない） |
  | `:domain` `ingest` | `EnvelopeValidator`（JSON 木から検証。未知キー・`tenantId` 拒否、UUIDv7、Base64/SHA-256、件数・サイズ、時刻）、`AgentSigning`/`SigningKeyCipher`/`AgentAuthenticator`/`AgentCredentialIssuer`、`BatchIngestor`（claim→1 トランザクション処理→完了の印）、`FetchPolicy`（取得の事実の条件）、`ObservationApplier`（`RaceDayUpdater`・`RaceScheduleUpdater`・オッズ・combo）、`RecordParser`（実データは Unsupported=保留）、`BackfillService`、ハートビート検証、`ComboKeys` |
  | `:persistence` | DAO/Repository 10 組（Agent・Nonce・Heartbeat・Batch・Raw・Fetch・Cursor・Gap・Backfill・ComboOdds）、`SpringUnitOfWork`（READ COMMITTED、デッドロックは全体再実行）、`DbScheduleFetchFactReader` |
  | `:ingest` | `AgentAuthFilter`（モードガードの後）、`AgentApiController`（`POST /v1/agent/ingest`・`heartbeat`）、gzip/JSON の上限、合成の正規化 JSON パーサ（**ローカルのみ**）、`UnavailableHeartbeatMirror`（GCS への写しは未実装） |
  | `:bot` | `ScheduleFetchFactReader` を DB 実装に（`NoScheduleFetchFacts` の本番配線を廃止）。必要スキーマ版 3 |
- **実測で見つけた欠陥（テストで検出して修正）**: ① 同一 batchId の 8 並行送信で 1 本が 500（重複キーの S ロック→`FOR UPDATE` の X ロック待ち合いでデッドロック）→ `INSERT … ON DUPLICATE KEY UPDATE` に変更 ② 先走りバッチが失敗行で連番を占有して別の batchId での再送を妨げた → 事前の `peek` で行を作らず 409 ③ `FetchStore` と `ScheduleFetchFactReader` を 1 クラスで実装すると Bean が曖昧になった → 分離 ④ 3 アプリの `requiredSchemaVersion` が揃わないと release-manifest の検証が失敗する（`tools/release`）→ dashboard も 3 に統一 ⑤ ingest の本番起動に KEK が要るため image smoke を更新 ⑥ **RA だけを取り込むと `race_day` が無く有効な予定が STALE になる／確認済みの日が YSCH で再確認されない**（YSCH の PLANNED が CONFIRMED の日を INVALID_TRANSITION で拒否し確認時刻が進まない）→ RA で開催日を CONFIRMED にし、YSCH の PLANNED は先へ進んだ日では確認時刻だけ進める ⑦ 認証・モードガードのパス書き換え迂回のテストを追加（ハンドラは認証済みの呼び出し元が無ければ 500 で何も保存しない）。
### テスト（ローカル、JDK 21、**MySQL 8.0.46。8.4/9.7 の合否ではない**）
| 区分 | 結果 |
|---|---|
| `:domain` 単体 | **667 件成功**（T3.3 の 632 ＋ 新規 35: `EnvelopeValidatorTest` 13・`AgentAuthTest` 10・`FetchPolicyTest` 9・`HeartbeatAndBackfillTest` 3） |
| `:ingest` 単体 | 31 件成功（起動設定の新規 3: 本番の KEK 必須・本番で正規化レコード不可・オプション不正） |
| `:ingest` MySQL 統合（**新規 37**） | **46 件成功**（`AgentAuthIntegrationTest` 10・`IngestIdempotencyIntegrationTest` 9・`IngestPipelineIntegrationTest` 18・既存 `IngestReadyIntegrationTest` 9）。署名不正・nonce 再利用・時刻ずれ・失効・期限切れ・OpsMode STOPPED/UNKNOWN・テナント越境・展開爆弾・ログに秘密なし・同一 batchId の 8 並行 ×5 ラウンド・途中失敗の巻き戻り・ACK 直前の失敗・YSCH 取得の事実（途中／EOF／予定なし／失敗／古い）・source_version・オッズ upsert・combo キー正規化・1 倍未満の隔離・カーソル・バックフィル・逆順更新の並行 |
| `:persistence` 統合 | 161 件中 160 成功（新規 `IngestStoresIntegrationTest` 8 件: 全体一意の agent_id、nonce、カーソル、ギャップ、バックフィルの重複排除と越境 FK、取得の事実、UnitOfWork のデッドロック再実行）。**失敗 1 件は `WiringIntegrationTest`（`mysql:8.4` 固定の版確認。ローカルが 8.0 のため。CI では 8.4/9.7 で実行）** |
| `:db-migration` | 単体 57・統合 44 成功（V3 の適用・権限・FK を反映） |
| `:bot` 単体 / 統合（T3.3 の回帰） | 67 / 31 成功 |
| `:dashboard` 単体 / 統合 | 30 / 9 成功 |
| ktlint・`checkModuleDependencies`・Python `tools/ci`（57）・`tools/release`（52）・repo hygiene | 成功 |
| Kotlin vs 旧 Python ゴールデン | **18 ケース / 661 呼び出し 全一致、最大絶対誤差 0**（T3.1〜T3.2 から不変。未対応 3 ケース/149 呼び出しは成功に数えない） |
| **未実施（ローカル）** | MySQL 8.4 / 9.7 の統合テスト（Docker 不可。CI）、Python ゴールデン（CI）、イメージのビルド・スモーク（CI）。完了判定の正は GitHub Actions（D-056） |
### 環境・セッション
`java -version`: OpenJDK 21（JDK 25 なし。ローカルは `-PjdkVersion=21`）。`./gradlew`: 9.7.1（Maven Central の 429 を再試行。**UTF-8 ロケール（`LC_ALL=C.UTF-8`）が必要**。無いと日本語のテスト名でビルドが壊れる）。`docker info`: クライアントのみでデーモンなし。apt の MySQL 8.0.46 を起動してテスト支援の外部 MySQL モード（D-129）で実行。本番 GCP・Cloud SQL・実 JRA-VAN データには接続していない。Terraform・infra・参照元は未変更。
### 未解決事項
**`docs/UNRESOLVED.md` に分類 A〜G で整理した（現在の状態の正本）。** ユーザーが対応すべきもの: D-1（規約の一読と Q1〜Q3）、D-2（T1.20 の取得）、D-3（T1.21）、D-4（T3.3 運用値の暫定推奨値の承認）、**D-5（署名方式 P-1）・D-6（取得セッション P-2）・D-7（オッズ境界 P-3）**、D-8〜D-12。
### 次の作業
- T3.5（Windows 取得エージェント）は **D-5・D-6 の承認が前提**。`plan`・`mode`・`update`・`register` の HTTP、`HeartbeatMirror` の GCS 実装、KEK の運用が残る（UNRESOLVED G-1〜G-4）。実データ形式のパーサは T1.17（仕様書 12 項目）の確認後。
### ブランチ
`claude/stage3-ingest-api`（main の 7cd8fdc から作成。PR は main 向け、マージはユーザー）。CI の結果と PR 番号は下の「CI・PR」。
### CI・PR
- **PR #20**（main 向け。マージはユーザー）: https://github.com/puttun777/jra-bot-web/pull/20
- **CI（コードを含む最新コミット cdc599f、run 40003205466）: ci gate 成功**。成功 — changes and repo hygiene / build (Temurin 25: ktlint・依存規則・単体・Kotlin ゴールデン比較) / golden tests (Python 3.12) / release tools and ci scripts / **db integration (MySQL 8.4)・(MySQL 9.7)**（db-migration・persistence 161・bot 31・ingest 46・dashboard 9 の下限と期待クラスを CI が検証）/ images (build + smoke, no push)。設計どおり skipped — connectorj26（Connector/J 26.x の変更なし）。ローカルで失敗していた `WiringIntegrationTest` は CI（8.4/9.7）では成功。
- 本記録のコミット（docs のみ）でも CI が走る。その結果は PR のチェックを参照（ここには追記しない）。

## Stage 3-C（claude/stage3-calendar-lease）: T3.3 開催日制御・calendar-check・bot-run リース・手動補正
### 結果（T3.3 の範囲で完了。**本番で動作する Bot ではない**。bot-run の業務ループは T3.8、実データ取込は T3.4）
- **着手前の確認**: main は PR #18（T3.2）マージ後の 5cb4c3a（ci gate 成功を確認済みの f7f0048 を含む）。V1/V2 は**書き換えていない**、**V3 も作っていない**（理由は D-125）。参照元・infra は未変更。作業ブランチは `claude/stage3-calendar-lease`。
- **実装したクラス**
  | 層 | クラス | 内容 |
  |---|---|---|
  | `:domain` calendar | `EffectiveCalendar`・`EffectiveDay`・`DayVerdict` | DB の予定 + 取得の事実 + 手動補正から有効な開催日を導出（曜日・祝日なし。UNKNOWN と開催なしの確認を区別。D-123） |
  | | `RaceScheduleTransitions`・`RaceDayTransitions`・`BotRunStateMachine` | 状態遷移表（無効な遷移・終端からの復活を拒否） |
  | | `SourceVersion`・`RaceDataKubun` | 取得元の版の比較（作成日 → 区分。0/9 は強い値。D-126） |
  | | `ScheduleEvent`・`EventJson`・`ChangeResult` | 履歴（種類と race_id の整合・決定的な dedup_hash） |
  | | `RaceScheduleUpdater`・`RaceDayUpdater`・`RaceScheduleObservation` | 取得内容の反映（古い版の拒否・遷移検証・TC・TC 取消・競合の再判定） |
  | | `CalendarOverride`・`CalendarOverrideService`・`OverrideValue` | 手動補正（監査情報必須・期限・取消・追記と取消だけ。認可はしない） |
  | | `WatchSettings`・`WatchWindow`・`CalendarSettings`・`CalendarCheckPolicy`・`CalendarCheckService`・`CalendarCheckReason` | 監視ウィンドウ（設定は既定なし）と起動判断・理由の enum・起動要求（モード再確認つき） |
  | | `ManualStartPolicy`・`ScheduleFetchFactReader`（`NoScheduleFetchFacts`・合成用 `FixedScheduleFetchFacts`） | 手動起動の検証、取得の事実の口 |
  | `:domain` bot | `BotRunLease.kt`（`LeaseRequest`・`LeaseSettings`・`AcquireResult` ほか）・`BotRunRepository` の拡張 | `acquire`・`heartbeat`・`transition`・`saveCheckpoint`（`tryStart`・`update` は削除） |
  | `:persistence` | `RaceDayDao/RepositoryImpl`、`ScheduleEventDao/ScheduleEventStore/RepositoryImpl`、`CalendarOverrideDao/RepositoryImpl`、`BotRunRepositoryImpl`（書換え）、`RaceScheduleRepositoryImpl`（履歴つき）、`inTransactionRetryingDeadlock` | 追加 SQL: `RaceDayDao`（selectById/selectByDate/insert/updateIfVersion/advanceConfirmed）・`ScheduleEventDao`（selectByHash/selectByRace/selectByDate/insert）・`CalendarOverrideDao`（selectById/selectByDate/revoke。insert は自動生成）・`BotRunDao`（selectByIdForUpdate/selectByDate） |
  | `:bot` | `CalendarCheckTaskHandler`・`UnconfiguredJobLauncher`・`CalendarSettingsParser`・`DbFailures` | `--task=calendar-check`（D-128）。他タスクは引き続きスタブ |
  | `:db-migration` | `R__grants.sql` | `app` に `race_schedule_event` の INSERT だけを追加（手動補正の履歴を同一トランザクションで残すため。D-125） |
- **イベント履歴の実装範囲**: 実装・検証した種類: ADDED・DELETED・CANCELLED・TIME_CHANGE・REVOKED（予定の更新）、DAY_STATUS・POSTPONED（開催日）、MANUAL_OVERRIDE・MANUAL_REVOKE（手動補正）、STALE・FETCH_FAILURE（追記 API のみ。**生成元は T3.4/reconcile**）。型だけ: SCRATCH・JOCKEY_CHANGE・COURSE_CHANGE・WEATHER。更新と履歴は同一トランザクション（履歴が失敗したら更新も戻る: FK 違反で実測）、`(tenant_id, dedup_hash)` で冪等、競合・不在では履歴なし、追記専用（更新・削除 API なし、DB 権限は INSERT のみ）。**レース状態の MONITORING 以降の進行は履歴の種類が DDL に無く、T3.3 では DB へ反映しない**。
- **リース処理方式**（D-127）: INSERT（UNIQUE）→ 拒否されたら別トランザクションで稼働中の行を**主キーで行ロック**→ 有効なら AlreadyActive、`now >= lease_expires_at` なら旧行を ABORTED にして新行を挿入（奪取）。heartbeat・遷移・checkpoint は行ロックの下で所有者・状態・期限を検証して版つきで更新（奪取された旧実行は全操作が Lost）。デッドロック 1213 は取得だけ・有限回（3）・トランザクション単位で再試行し、他のエラーと上限超過は投げる。**並行テストで実際に見つけた欠陥**: 副インデックスで先にロックする実装は、主キーから更新する遷移とロック順が逆でデッドロックした → 順序を「主キー → 副インデックス」にそろえて解消（その後 3 回連続で全件成功）。
- **実装した C01〜C28**: 完了 19（C02〜C06・C08・C09・C11〜C15・C17〜C20・C23・C24・C26）、一部 7（C01・C07・C10・C16・C21・C22・C27）、**未対応 2（C25・C28）**。内訳と残りは RACE_CALENDAR_DESIGN §11。**完成扱いにしていないもの**: 全レース SETTLED までの業務（C01）、実行中の DRAINING（C07）、実行中 run の次サイクル反映（C10）、エージェント停止と実データ鮮度（C16）、状態の再導出・二重通知なし（C21）、実行中の STOPPED・SIGTERM の業務処理（C22）、確定成績の照合（C25）、結果欠損の reconcile（C27）、14 時間の最大稼働（C28）。
### テスト
| 区分 | 結果（ローカル、JDK 21） |
|---|---|
| `:domain` 単体 | **632 件成功**（T3.2 の 488 + 144。新規: `SourceVersionTest`・`TransitionsTest`・`ScheduleEventTest`・`CalendarOverrideTest`・`WatchWindowTest`・`EffectiveCalendarTest`・`ScheduleUpdaterTest`・`CalendarCheckPolicyTest`/`CalendarCheckServiceTest`・`CalendarSourceGuardTest`・`LeaseAndDayModelTest` ほか。T3.1/T3.2 の計算・ゴールデンは**未変更**） |
| `:persistence` 単体 | 33 件成功（`DeadlockRetryTest`・`BotRunAcquireContentionTest` など DB なし） |
| `:persistence` 統合（**ローカルの MySQL 8.0.46。8.4/9.7 ではない**） | **153 件成功**（RaceDay・ScheduleEvent・CalendarOverride・BotRun のリースと並行・DeadlockRetry・越境・権限・Enum 契約・時刻の往復を含む） |
| `:db-migration` 統合（同上） | 44 件成功（`app` の履歴 INSERT の許可と UPDATE/DELETE の拒否を追加） |
| `:bot` 単体 / 統合（統合は同上） | 67 件 / 31 件成功（`CalendarCheckIntegrationTest` 22 件: C01〜C24 の導出と起動要求を本物の DB・本番の Spring 配線で確認。起動基盤・時計・取得の事実だけ合成の Fake） |
| ktlint・`checkModuleDependencies` | 成功 |
| Python `tools/ci` | 109 件成功（CI の下限と期待クラスの更新に合わせて `test_workflows` を更新） |
| **未実施（ローカル）** | **MySQL 8.4 / 9.7 の統合テスト（Docker 不可。CI で実行）**、Kotlin ゴールデン比較・Python ゴールデン（変更なし。CI の `build`/`golden` が実行）、ingest/dashboard/cloud-gcp のテスト（未変更。CI）、イメージのビルド。完了判定の正は GitHub Actions（D-056） |
### 並行実行テストの結果（ローカルの MySQL 8.0.46）
2 本・8 本の同時取得（各 10 ラウンド）で勝者は常に 1 つ、期限切れのリースへ 8 本が同時に奪取して勝者 1 つ（旧行は ABORTED 1 行）、期限ちょうどの heartbeat と奪取の同時実行（40 ラウンド）は片方だけが勝つ、終了と新規取得の同時実行（40 ラウンド）で実行中の行は常に 1 つ以下、別テナント・別日付 8 本は全員成功、DB 切断中は例外（取得できたことにしない）で復旧後は動く、実際のデッドロック 2 本は再試行で両方成功。リース・デッドロックの 2 クラスは 3 回連続で成功。**8.4/9.7 での並行テストは CI の結果が正**（未確認）。補足: ingest/dashboard の統合テスト（今回未変更。ingest 9・dashboard 9）も同じローカルの MySQL で実行し、dashboard の『NORMAL 以外の書込みの拒否では DB 接続が増えない』（サーバの接続数の差が 1 であること）が 1 回だけ 2 になって失敗した（同じローカルサーバに別の JVM の接続が混ざった可能性が高く、再実行 2 回は成功。今回の変更とは無関係に見えるが、原因は未特定の再現なしの揺らぎとして記録）。
### 環境・セッション
`java -version`: OpenJDK 21（JDK 25 なし。ローカルは `-PjdkVersion=21`）。`./gradlew`: 9.7.1（Maven Central の 429 を再試行）。`docker info`: クライアントのみでデーモンなし（使えない）。そのため apt の MySQL 8.0.46 を直接起動してローカルの事前確認に使い、テスト支援クラスに**ローカル専用**の外部 MySQL モードを追加した（D-129。CI では使わない）。本番 GCP・Cloud SQL・実 JRA-VAN データには接続していない（合成データのみ）。
### 未解決のまま保持（変更なし）＋ T3.3 で増えたもの
- 変更なし: T1.16・T1.17・T1.20・T1.21・T3.0・infra I-H1〜I-H9・T5.0・実 GCP の検証ゲート・MySQL 9.7 の本番採否・Connector/J 26.x の採否・`0<odds<1` の取込境界・T3.8 の本格 checkpoint・SIGTERM・本番 release-manifest・ml-train イメージ・branch protection の required checks。
- **新規（ユーザー確認が要る）**: ①`CALENDAR_WATCH_START_MINUTES`（120/240 の食い違い）・`PRESTART`・`SETTLE_DEADLINE`・`STALE_AFTER`・リース期限/heartbeat 間隔の運用値と、`prestart` の解釈（D-122）。②同一作成日の区分の順位・9 と 0 の優先・TC の版（T1.17 の一次確認。D-126）。③取得の事実（YSCH 成功）を DB のどこに持つか（T3.4）。④MONITORING 以降の履歴の種類（V3 の要否。T3.8）。⑤`app` への `race_schedule_event` INSERT の付与の承認（契約 0.4.6。T5.0 で infra と同期）。⑥`JobLauncher` の GCP 実装（T5 系）。
### CI・PR
- **PR #19**（main 向け。マージはユーザー）: https://github.com/puttun777/jra-bot-web/pull/19
- **CI（コードを含む最新コミット 5f1175d、run 39981783406）: ci gate 成功**。成功 — changes and repo hygiene / build (Temurin 25) / golden tests / release tools and ci scripts / **db integration (MySQL 8.4)・(MySQL 9.7)**（persistence 統合 153・bot 統合 31 などの下限と期待クラスを CI が検証）/ images (build + smoke, no push)。設計どおり skipped — connectorj26（Connector/J 26.x の変更なし）。
- **途中の失敗と修正**: 最初のコミット 5c57f00 の run 39980918312 は **images のスモークが失敗**（`calendar-check` が実装済みで `CALENDAR_*` が必須になり、旧スタブ前提のスモークが exit 2 になった）→ `tools/release/image_smoke.py` を実装に合わせて修正（5f1175d。`calendar-check` は空の予定で exit 75 `SCHEDULE_UNKNOWN` を検査、スタブの検査は `reconcile`）。コードの不具合ではなくスモークの更新漏れ。
- **docs のみのコミット a6e45c8 の CI で `db integration (MySQL 9.7)` が失敗**（`CalendarCheckIntegrationTest` の C23 が `java.net.BindException: Address already in use`）。原因はテストの作り: 共有の TCP 中継を止めて**同じポートで再開**する際に、他のプロセスの接続がそのポートを取ることがある（コードの欠陥ではない。直前のコード同一のコミットでは 8.4/9.7 とも成功）。C23 を専用の中継・専用のコンテキストにし、再開しない形へ修正（ローカルで 3 回連続成功）。再開を使う既存テスト（`SchemaVersionVerifierIntegrationTest` など）は未変更。
- 本記録のコミット自体も PR 全体の差分で判定されるため同じジョブが走る（その結果は PR のチェックを参照。ここには追記しない）。
### 次の作業（T3.4 への引き継ぎ。今回は着手していない）
- T3.4（取込 API・実データ取得）が使う入口と未決事項は `docs/STAGE2_HANDOFF.md` §14.9。取込側が `RaceScheduleUpdater`・`RaceDayUpdater`・`ScheduleEventWriter` を呼び、**取得の事実**（`ScheduleFetchFactReader` の実装）と `race_day.last_confirmed_at` の更新（`RaceDayUpdater.confirm`）を必ず行う（さもないと全日が UNKNOWN/STALE になり calendar-check は起動しない = fail closed）。
- T3.8 が使う `BotRunRepository` の操作と、残りの C ケース（C07・C10・C16・C21・C22・C27・C28）は同ファイル。
### ブランチ
`claude/stage3-calendar-lease`（main の 5cb4c3a から作成。PR は main 向け、マージはユーザー）。

## Stage 3-B（claude/stage3-combo-strategy）: T3.2 ワイド・三連複の確率計算・買い目戦略
### 結果（T3.2 の範囲で完了。本番で動作する Bot ではない）
- **着手前の確認**: PR #17（T3.1）は main にマージ済み（f41b701）。参照元は固定コミット `f7dbf69fd8940121782870de902e8c449e6ca149`（読み取り専用。変更なし）。numpy は 2.5.3 のまま。**既存 14 ケースのフィクスチャは 1 バイトも変えていない**（`generate --add-new`。`generate --check` は 21 ケースで一致）。作業ブランチは `claude/stage3-combo-strategy`（環境指定のサフィックス付きの名前で作成）。
- **実装したもの**（`:domain` の `jp.jrabot.domain.decision` に追加。T3.1 の型は作り直さず拡張。D-112）:
  | クラス | 旧 Python の対応 | 内容 |
  |---|---|---|
  | `PlackettLuce` | `suggest_multiple_bet_strategies` 内の `_score`/`_pl_order_prob`/`_top3_set_prob`/`_wide_pair_prob` | 重み `clamp(p,1e-12,0.99)^clamp(α or 1.0,0.1,5)`、着順確率、三連複（6 通りの和）、ワイド（他の全馬の三連複の和）。W の前計算とメモ化（ビット単位で同じ値: D-114） |
  | `ComboOdds`・`WideKey`・`SanrenpukuKey`・`WideOddsRange` | `wide_odds_map`/`sanrenpuku_odds_map` | 実オッズの注入（昇順に正規化したキー。DB 非依存。鮮度は持たない: D-117） |
  | `ComboOddsEstimate`・`LegacySimpleComboProbability` | `estimate_wide_odds`/`estimate_trifecta_odds`・`calculate_wide_prob`/`calculate_trifecta_prob` | 推定オッズ（丸め・上下限なし）／旧の粗い近似（旧実装でも判定に未使用のため単独の関数。GD05-COMBO-001 用） |
  | `ComboSettings`・`ComboRaceContext`・`ComboTicket`・`OddsSource`・`ComboTickets.deduplicated` | `top_k_partners` ほかの引数・`_pick_box_candidates`・`tickets[]`・`notify/discord.py` の `_deduplicate_tickets` | 設定、1 レースの共有入力（遅延生成の PL・BOX 候補）、買い目、重複除去の規則（通知用。生成では除かない: D-116） |
  | `BetStrategyBuilder`（拡張）・`StrategyEntry.tickets`/`kumi` | `suggest_multiple_bet_strategies` のワイド/三連複 | 流し・BOX・E8 の段階・EV 降順の点数上限・最良の買い目の Kelly・`ticket_allocation`・見送りエントリ・最推奨（E7）・`best_only`・総投資上限での買い目の縮小 |
  | `SignalOrdering`・`WinDecisionEvaluator.evaluate(horses, realOdds)` | `evaluate_odds` のソートと統合 | edge/hit_rate の並びを独立した部品に。同点の賭式優先を単体で検証 |
  | `:golden-runner`（`GoldenCalls`） | - | `suggest_multiple_bet_strategies` の直接呼び出し、実オッズ配列の注入、買い目の JSON 化、推定オッズ/旧近似の関数 |
- **単勝の既存結果は不変**: T3.1 の 10 ケース/380 呼び出し（26,759 個の浮動小数）は全一致のまま（最大絶対誤差 0）。単体テスト（T3.1 の 387 件）も全て緑（推定オッズの経路が `UnsupportedDecisionScopeException` だったテスト 1 件だけ、新しい挙動に合わせて書き換えた）。
### 旧 Python との比較（ローカル実測。CI の結果は下の「CI・PR」）
| 項目 | 結果 |
|---|---|
| フィクスチャ全体 | 21 ケース / **810 呼び出し**（既存 14 ケース/562 + 今回追加 7 ケース/248） |
| **Kotlin が検証した（対応）** | **18 ケース / 661 呼び出し — 全一致**。T3.1 の 10 ケース/380 + 既存の `GD05-COMBO-001` 33（今回 Kotlin 対応）+ **今回追加の 7 ケース/248**（`GD05-PL-005` 42 / `GD05-FLOW-006` 17 / `GD05-BOX-007` 22 / `GD05-REAL-008` 35 / `GD05-FILTER-009` 48 / `GD07-COMBO-STAKE-010` 50 / `GD06-EVAL-011` 34）。比較した浮動小数 **62,903 個**（うち今回追加の 7 ケース 36,111 個） |
| **未対応（比較していない。成功に数えない）** | **3 ケース / 149 呼び出し** — `G1-MODEL-001` 73（ML 推論: T3.6）、`G2-HASH-001` 70（特徴量ハッシュ: T3.6）、`GD10-DEFAULTS-001` 6（旧関数の既定値の突合: T1.20） |
| 検証済みの割合 | 661 / 810 呼び出し = 81.6%（**『全件一致』ではない**） |
| 最大絶対誤差 | **0**（許容は各ケースの `comparison_rules` のまま: 確率・EV・金額 1e-9、正規化 1e-12。全値がビット一致。`pow`（α≠1: 0.1〜5.0）も一致。別の CPU/libm では最後の 1〜2 桁が揺れ得るため許容誤差は維持） |
| 比較の感度（変異テスト） | 14 件の変更を入れて確認: 11 件はゴールデンが不一致になる（EV の `<=`→`<`、下限の `<`→`<=`、上限を縮小の前に適用、BOX の『軸が候補内』条件の除去、相手の頭数 max(1,k)/max(3,k)、EV 昇順、配分を常に best_only、α 下限、確率下限、重みの合計を素の加算に）。**不一致にならない 3 件は意図どおり**: ①市場比上限と `max_prob` の適用順（`min` の可換で結果が同じ）、②三連複オッズ 0.0 を『有る』扱い（EV −1 で除外されるので出力が同じ。単体テストは『無い』扱いの経路の見送りを確認）、③三連複の 6 通りの和の順序（最後の数桁の差で、許容誤差内）。元に戻して再確認済み |
| 検証できない範囲 | 実オッズの分布・取得失敗・鮮度（旧実装に鮮度の概念が無い。T3.4）、実データでの正確性（合成入力のみ）、旧関数既定値の突合（T1.20） |
### テスト
| 区分 | 結果（ローカル、JDK 21） |
|---|---|
| `:domain` 単体 | **488 件成功**（旧 387 + 新 101: `PlackettLuceTest` 20 / `ComboOddsTest` 13 / `ComboStrategyTest` 47 / `WinDecisionComboTest` 21） |
| `:golden-runner` 単体 | **14 件成功**（旧 9 + 新 5: 推定オッズ/旧近似、直接呼び出しの形、evaluate_odds の実オッズ、未対応の判定 2。実フィクスチャの分類を 810 = 661 + 149 に更新） |
| ktlint / checkModuleDependencies | 成功（`ktlintCheck` `:domain:check` `:golden-runner:check` `checkModuleDependencies`） |
| Python ゴールデン | **82 件成功**（旧 74 + `test_combo_inputs` 8）、`generate --check`（21 ケース）・`verify` 成功 |
| Python ci/release | 109 件成功（変更なし） |
| 未実施（ローカル） | 他モジュール（persistence/bot/ingest/dashboard/cloud-gcp）のビルド・DB 統合・イメージ・build-logic のテスト（変更なし）。完了判定の正は GitHub Actions（D-056） |
- **主な検証項目**: Plackett–Luce の旧 Python 値との一致（6 頭×α 3 通り・5 頭）、恒等式（3 頭で和 1、全馬同確率の閉じた式 n=5/8/18、4 頭の和 1 と 3）、馬番順の不変（1e-12）、α の端、確率 0/負/1 超、重複・0 以下・非有限の拒否、前計算とメモ化が素朴な旧実装の写しとビット単位で同じ（18 頭・816 通り）、段階の順序と各境界（縮小→上限→市場比上限→下限→EV。`ちょうど` の等号）、流しの相手・BOX 候補・人気 0・同順位・オッズ順、実オッズの優先・欠損・0.0、`ticket_allocation`（大文字小文字・未知の文字列）、Kelly（min/max_bet・確率源の係数）、最推奨（E7・同値は先勝ち）、`best_only`、総投資上限での買い目の縮小（買い目 = scale 倍、最推奨 = scale²）、hit_rate の賭式をまたぐ同点、見送りシグナル、18 頭の性能。
### 性能（E10。ローカル JDK 21、ウォームアップなし、1 回目の実測）
| 条件（合成） | Kotlin | 旧 Python |
|---|---|---|
| 18 頭の全ワイド 153 + 全三連複 816 の確率（`PlackettLuce`） | 約 2 ms | - |
| `evaluate` 18 頭・全馬を軸・実オッズ全組合せ（`top_k_partners=6`・`box_size=4`/5） | 約 8〜29 ms | 約 23〜27 ms（`evaluate_odds`） |
| 同・最大（`top_k_partners=17`・`box_size=18`） | 約 0.3〜0.5 秒 | 約 0.5 秒 |
JIT のウォームアップや環境で変わる目安。数値の仕様は変えていない（D-114）。表示用の文字列は残る買い目だけ作る（`oddsNote`/`kumi`）。
### 意図的な差異・互換（詳細は D-113〜D-118・D-121）
- **旧挙動を維持（未承認の数値変更なし）**: `pipeline_version=legacy`、`fixes.*` はすべて false。E6（縮小後の min_bet 再判定）・E12・E14・H4・H10 の改善案は未実装。最推奨の賭け額は総投資上限で scale²、買い目の賭け額は scale（合計が一致しない）。`ticket_allocation` は `proportional_ev`（大小無視）以外はすべて best_only 扱い。流しと BOX が同じ組合せなら重複が点数上限を消費する。
- **旧実装の精読で判明（再現済み。D-121）**: `calculate_wide_prob`/`calculate_trifecta_prob` は旧実装でも未使用の粗い近似で、判定の確率は Plackett–Luce。E11 の『ワイドは見送りエントリを返さない』は誤り（ワイドも三連複も返す）。三連複の確率は引数の順で最後の数桁が変わる。`axis_place3` は未使用。
- **入力検証の差異（D-117・D-118）**: 実オッズのキーは昇順に正規化（旧は逆順だと黙って不一致）、馬番が 0 以下・重複した並びで買い目の確率を計算するときは拒否（確率が不要な経路は T3.1 と同じ）、非有限の確率/α/実オッズ・`low > high` は拒否。
- **重複した買い目（D-116）**: 通知層の `_deduplicate_tickets` と同じ規則を `ComboTickets.deduplicated` に置いた。生成段階では除かない（除くと点数上限と按分の数値が変わるため）。生成段階での除去は承認待ち。
### 未対応・範囲外（T3.2 の完了範囲と残りを区別）
- ML 経路・特徴量（T3.6）、実オッズの DB 取得・鮮度（T3.4）、取込 API（T3.4）、Windows エージェント（T3.5）、Discord 本文（T3.7）、時間帯スケジュール・adaptive_relaxation・bot-run のオーケストレーション（T3.8）、バックテスト（T3.9）、開催日制御（T3.3）。`StubTaskHandler` は置き換えていない。bot-run のループ・checkpoint・SIGTERM は未実装・未検証のまま。
### セッションの環境
`java -version`: OpenJDK 21（JDK 25 なし。ローカルのビルドは `-PjdkVersion=21`、CI は JDK 25）。`./gradlew`: 9.7.1 が動作（`LC_ALL=C.UTF-8` が必要。Maven Central の 429 が出るため再試行した）。`docker info`: Docker 29.9.1（未使用）。Python 3.13.16（venv の numpy 2.5.3 で旧実装を実行）。
### 未解決のまま保持（このセッションでは変更していない）
T1.16（JRA-VAN 利用条件）、T1.17（JV-Link 公式仕様）、T1.20（実 SQLite・設定・モデル）、T1.21（数値挙動の承認: E6/E12/E14/H4/H10、生成段階の重複除去、買い目の賭け額の整合）、T3.0（Windows 実機 PoC）、infra I-H1〜I-H9、T5.0（infra 契約同期）、Cloud SQL・GCS 等の実環境検証（G-*）、MySQL 9.7 の本番採否、Connector/J 26.x の採否、`0<odds<1` の取込境界の扱い、デッドロック 1213 の再試行、bot-run の checkpoint・SIGTERM、本番 release-manifest、ml-train イメージ。branch protection の required checks を `ci gate` のみにする作業（ユーザー作業）も未確認。
### CI・PR
- **PR #18**（main 向け。マージはユーザー）: https://github.com/puttun777/jra-bot-web/pull/18
- コード・テスト・docs を含むコミット d511856 の CI run **39954862059**: **ci gate 成功**。成功 — changes and repo hygiene / build (Temurin 25)（`Kotlin vs legacy golden comparison` ステップを含む）/ golden tests (Python 3.12) / db integration (MySQL 8.4)。設計どおり skipped（未実施）— images / release tools and ci scripts / connectorj26。所要の目安: build 約 3 分、db 8.4 約 4 分、golden 約 1 分。
- 本記録のコミット自体も PR 全体の差分で判定されるため同じジョブが走る（その run の結果は PR のチェックを参照。ここには追記しない）。
- CI で未確認: ステップ内の比較出力（`GITHUB_STEP_SUMMARY`）の中身の目視。ジョブ全体の成功（比較不一致・申告と対応表の不一致ならステップが失敗する）で確認した。
- **CI の方針**: `.github/workflows/ci.yml` は未変更（D-120）。変更判定は `kotlin_core`・`kotlin_golden`・`golden`・`docs` → `build`（Kotlin と旧 Python の比較を含む）・`golden`・DB 統合（MySQL 8.4 のみ）。images・Connector/J 26.x・MySQL 9.7 は走らない。`domain unit` の件数下限（387）とクラス一覧は、次に CI 設定を変更する PR で 488 と新クラスに更新する。
### 次の作業（T3.3 への引き継ぎ。今回は着手していない）
- T3.3 は開催日制御（RACE_CALENDAR_DESIGN の実装: スキーマ、calendar-check、bot-run リース、手動補正、C01〜C28 のテスト）。T3.2 の計算エンジンとは独立。
- T3.2 の成果を使う後続: T3.4（DB から `ComboOdds` を作る Repository 実装。**キーは昇順に正規化される**・**取得時刻の鮮度は呼び出し側で判定する**）、T3.7（通知は `ComboTickets.deduplicated` で複数シグナルの買い目をまとめる。表示用の `oddsNote`/`kumi` は旧実装と同じ文字列）、T3.8（`ComboSettings` の運用値は example config を正に決める: D-016）。
- 先に決めておくとよいこと: `0<odds<1` の扱い、運用の既定値（example config）、生成段階の重複除去・買い目の賭け額の整合の承認（T1.21）。
### ブランチ
`claude/stage3-combo-strategy`（main の f41b701 から作成。PR は main 向け、マージはユーザー）。

## Stage 3-A（claude/stage3-core-decision）: T3.1 EV・Kelly・確率・判定・急落フィルタ
### 結果（単勝の範囲で完了。本番で動作する Bot ではない）
- **着手前の確認**: main（PR #16 マージ後、9718c7b）の CI は成功（run 39942398688、全ジョブ success）。PR #13（numpy 2.5.4）は未マージ・クローズ、PR #14（Gradle 更新）は対象外。numpy は 2.5.3 固定のまま、フィクスチャの再生成・上書きはしていない（既存 10 ファイルの sha256 は不変）。参照元は固定コミット `f7dbf69fd8940121782870de902e8c449e6ca149`（読み取り専用。変更なし）。
- **実装したもの**（`:domain` の `jp.jrabot.domain.decision`。純粋 Kotlin、Spring/Doma/Jackson/GCP SDK なし）: `EvMath`（EV・期待回収・期待利益）、`KellyMath`（Kelly 割合・推奨賭け額・`LegacyZeroDivisionException`）、`MarketProbability`（生の暗黙確率・0.99 上限・正規化と ±10% 補正）、`MarketBlend`（判定側の logit ブレンド・クリップ・再正規化）、`SnapshotFilters`/`SnapshotsNewestFirst`/`RecentSnapshotsProvider`（急落・人気急上昇。並びを型で保証）、`WinStake`（縮小・Kelly 係数・賭け額）、`BetStrategyBuilder`/`BetStrategy`（単勝 + ワイド/三連複の見送りエントリ・最推奨・best_only・上限縮小）、`WinDecisionEvaluator`/`WinDecisionConfig`/`WinDecisionResult`（単勝の判定・理由コード・見送りシグナル・ソート・max_signals・総投資上限）、`DecisionFixes`/`PipelineVersion`、`DecisionQuantities`（`ProbRaw`/`ProbUsed`/`Ev`/`Ratio`/`BetType`）、`PythonCompat`（`min`/`max`・`sum`・`repr`・`.2f`/`.1%`）。**金額は旧実装の「点」の `Double`**（`Money` ではない）。
- **ゴールデンの Kotlin 側**: 新モジュール `:golden-runner`（検証専用。`:domain` のみ依存、Jackson。イメージ非搭載。D-106）、`tools/golden/kotlin_compare.py`（対応/未対応を分けて報告）、`catalog.KOTLIN_PORT`（対応表）、`generate --add-new`、ゴールデン 4 ケース追加（D-108）。CI の `build` ジョブに比較ステップを追加し、`classify` の `kotlin_golden` タグで `tools/golden`（.md 以外）・`golden-runner` の変更でも必ず走るようにした（`:domain` は従来どおり）。DB 統合・イメージは追加で動かさない。
### 旧 Python との比較（ローカル実測。CI の結果は下の「CI」）
| 項目 | 結果 |
|---|---|
| フィクスチャ全体 | 14 ケース / **562 呼び出し**（旧: 10 ケース / 447。T3.1 で 4 ケース / 115 呼び出しを追加） |
| **Kotlin が検証した（対応）** | **10 ケース / 380 呼び出し — 全一致**（`GD01-EV-001` 137 / `GD01-KELLY-002` 68 / `GD02-PROB-001` 18 / `GD02-NORM-002` 15 / `GD03-FILTER-001` 20 / `GD09-EVAL-001` 7 / 追加: `GD03-SNAPSHOT-002` 10 / `GD04-WIN-HITRATE-003` 19 / `GD08-SELECT-003` 33 / `GD07-SIZING-004` 53）。比較した浮動小数 26,759 個 |
| **未対応（比較していない。成功に数えない）** | **4 ケース / 182 呼び出し** — `G1-MODEL-001` 73（ML 推論: T3.6）、`G2-HASH-001` 70（特徴量ハッシュ: T3.6）、`GD05-COMBO-001` 33（ワイド/三連複: T3.2）、`GD10-DEFAULTS-001` 6（旧関数の既定値の突合: T1.20） |
| 検証済みの割合 | 380 / 562 呼び出し = 67.6%（**『全件一致』ではない**） |
| 最大絶対誤差 | **0**（許容は各ケースの `comparison_rules` のまま: 確率・EV・金額 1e-9、正規化 1e-12。全値がビット一致。`np.log`/`np.exp` と `kotlin.math` の差も出なかった。別の CPU/libm では最後の 1〜2 桁が揺れ得るため許容誤差は維持） |
| 例外 | `GD01-KELLY-002` の E5（`odds=1.0` の ZeroDivisionError）を型で一致（メッセージは各ケースの rules で ignore） |
| 比較の感度 | 閾値の比較演算子を 1 つ変えると（`>` → `>=`）`GD03-FILTER-001`・`GD03-SNAPSHOT-002` が不一致になることを確認（元に戻した） |
### テスト
| 区分 | 結果（ローカル） |
|---|---|
| `:domain` 単体 | **387 件成功**（旧 214 + 新 173: `PythonCompat` 51 / `EvMath` 16 / `KellyMath` 18 / `MarketProbability` 22 / `SnapshotFilters` 7 / `MarketBlend` 7 / `WinStake` 17 / `WinDecisionEvaluator` 35） |
| `:golden-runner` 単体 | **9 件成功**（型の忠実さ・未対応の判定・例外の対応・実フィクスチャの分類 562 呼び出し = 380 + 182） |
| build-logic（依存規則） | 21 件成功（`:golden-runner` の許可表と『誰も依存できない』を追加） |
| ktlint / checkModuleDependencies | 成功（`:check`） |
| Python ゴールデン | **74 件成功**（旧 62 + `kotlin_compare` 9 + `add_new` 3）、`generate --check`（14 ケース）・`verify` 成功 |
| Python ci/release | **109 件成功**（旧 107 + classify 2。`golden-runner`・`tools/golden` で build が走ること、Dockerfile/.dockerignore の許可、ワークフローの必須ステップ） |
| 未実施（ローカル） | 他モジュール（persistence/bot/ingest/dashboard/cloud-gcp）のビルド・DB 統合・イメージ — ローカルは JDK 21（`-PjdkVersion=21`）で、Maven Central の 429 もあり CI に任せる。完了判定の正は GitHub Actions（D-056） |
### 意図的な差異・互換（詳細は D-105・D-109・D-110、MIGRATION_MATRIX §8.0）
- **旧挙動を維持（未承認の数値変更なし）**: `pipeline_version=legacy`、`fixes.*` はすべて false（E6・E12・E14・H10 の改善案は未実装。true にすると `UnsupportedDecisionScopeException`）。E5 は旧と同じく除外（理由 `ODDS_AT_PAR` を付けるのみ）。H4 の二重ブレンド、H10 の係数 0.8、E6 の scale² も旧のまま。
- **旧実装の精読で判明（再現済み）**: 固定額賭けは `bet_strategy` の隠れ（`= None`）で一度も使われない / `best_strategy` と `strategies` の同一オブジェクトによる scale² / ワイドも見送りエントリを返す（E11 の記述を訂正）/ `meta.ev_shrink_factor` の `or 1.0` / 補正制限 ±10% は実質ノーオペレーション（E13）/ `0<odds<1` の Kelly は正の値（**入力境界で拒否するかは未解決: T3.4・ユーザー判断**）。
- **入力検証の差異**: 非有限のオッズ・設定値、負の `max_signals`、未承認 `fixes`、推定オッズのワイド/三連複判定は拒否または例外（旧は黙って流す／意図しないスライス）。`WinDecisionConfig` は運用の既定値を持たない（D-016。`legacyFunctionDefaults()` は比較用）。
### 未対応・範囲外（T3.1 の完了範囲と残りを区別）
- ML 経路（`_normalize_with_correction`・ensemble・特徴量・E1/E2）: T3.6。ワイド/三連複の買い目・Plackett–Luce・E8〜E11: T3.2（このセッションでは着手しない）。時間帯スケジュール・adaptive_relaxation: T3.8。DB のスナップショット取得: T3.4。Discord 本文: T3.7。`StubTaskHandler` は置き換えていない。bot-run のループ・checkpoint・SIGTERM は未実装・未検証のまま。
### セッションの環境
`java -version`: OpenJDK 21（JDK 25 なし。ローカルのビルドは `-PjdkVersion=21`、CI は JDK 25）。`./gradlew`: 9.7.1 が動作（ロケールを `LC_ALL=C.UTF-8` にしないと日本語名のテストレポートで失敗する）。`docker info`: Docker 29.9.1（本セッションでは未使用）。Python 3.13.16（venv の numpy 2.5.3 で旧実装を実行）。
### 未解決のまま保持（このセッションでは変更していない）
T1.16（JRA-VAN 利用条件）、T1.17（JV-Link 公式仕様）、T1.20（実 SQLite・設定・モデル）、T1.21（数値挙動の承認: E6/E12/E14/H4/H10）、T3.0（Windows 実機 PoC）、infra I-H1〜I-H9、T5.0（infra 契約同期）、Cloud SQL・GCS 等の実環境検証（G-*）、MySQL 9.7 の本番採否、Connector/J 26.x の採否、bot-run の checkpoint・SIGTERM、本番 release-manifest、ml-train イメージ。branch protection の required checks を `ci gate` のみにする作業（ユーザー作業）も未確認。
### CI・PR
- **PR #17**（main 向け。マージはユーザー）: https://github.com/puttun777/jra-bot-web/pull/17
- コード・テスト・docs を含むコミット 35f9e79 の CI run **39946864540**: **ci gate 成功**。全ジョブ成功 — changes and repo hygiene / build (Temurin 25)（`Kotlin vs legacy golden comparison` ステップを含む）/ release tools and ci scripts / golden tests (Python 3.12) / db integration (MySQL 8.4)・(9.7) / images (build + smoke, no push)。`connectorj26` は設計どおり skipped（未実施。Connector/J 26.x の変更なし）。
- 本コミット（この記録）自体も、PR 全体の差分で判定されるため同じジョブが走る。その run の結果は PR のチェックを参照（ここには追記しない）。
- CI で未確認: ステップ内の比較出力（`GITHUB_STEP_SUMMARY`）の中身の目視。ジョブ全体の成功（比較不一致・申告と対応表の不一致・件数不足ならステップが失敗する）で確認した。
### 次の作業（T3.2 への引き継ぎ）
- T3.2 はワイド/三連複: `BetStrategyBuilder` の見送りエントリの部分を、Plackett–Luce の確率（`_wide_pair_prob`/`_top3_set_prob`、重みは `p^α` で α を [0.1, 5] にクリップ、p は [1e-12, 0.99]）・実オッズ・流し/BOX・`*_max_prob`/`*_max_over_market`/`*_min_prob`・`ticket_allocation` に置き換える。`WinDecisionConfig.requireRealOddsForCombo=false`・実オッズ受け渡しの `UnsupportedDecisionScopeException` を外す。ゴールデンは `GD05-COMBO-001`（33 呼び出し、単純推定関数）を `KOTLIN_PORT` の supported に移し、GD05/GD06 の Plackett–Luce 経路（E8〜E11）のケースを `generate --add-new` で追加する（既存フィクスチャは書き換えない）。
- `StrategyEntry.tickets`（現在は見送りエントリの空配列のみ）と、`BetSignal`/`SkipSignal` のワイド/三連複の経路、`hit_rate_bet_type_priority` の賭式順序のテストを足す。`max_total_bet_per_race` の tickets の stake 縮小も T3.2。
- 先に決めておくとよいこと: `0<odds<1` の扱い、運用の既定値（example config）— T1.20/T3.8。
### ブランチ
`claude/stage3-core-decision`（main の 9718c7b から作成。PR は main 向け、マージはユーザー）。

## numpy 固定と Dependabot 設定（claude/stage2-numpy-pin-dependabot。D-103）
- PR #13（numpy 2.5.3→2.5.4）の golden 失敗は、`requirements.txt` だけが上がり `PINNED_NUMPY`・フィクスチャ・sha256 と不一致になったため。ゴールデンの再現性を優先して 2.5.3 を維持し、`tools/golden` の numpy を Dependabot version updates の対象外にした。`requirements.txt`・`constants.py`・フィクスチャ・sha256 は未変更。更新手順は `tools/golden/README.md`。#13 は閉じる（ユーザー判断）。Stage 3 には進んでいない。
- 未検証: ignore がセキュリティ更新を妨げないこと（実環境の最初のセキュリティ PR で確認）。

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
- run **39937556877**（head 703a100、PR #15。`tools/ci` の変更のため connector/j 以外の全ジョブ）: **8 ジョブすべて成功、`ci gate` 成功**、connector/j は設計どおり skipped（未実施）— changes and repo hygiene / release tools and ci scripts / golden tests (Python 3.12) / build (Temurin 25) / db integration (MySQL 8.4・9.7) / images (build + smoke, no push) / ci gate。課金の目安（ジョブごと分単位切り上げの合計）約 20 分: 1+1+1+2+4+5+5+1。
- 先行 run 39937331660（head 75bda39、golden だけの変更）: golden は成功、`ci gate` が D-102 の不具合で失敗 → 703a100 で修正。
- docs だけの追記コミット（f476361）の run 39938260679 も同じ 8 ジョブが成功。**PR の変更判定は『コミット単位』ではなく『PR 全体（base との差分）』**なので、`tools/ci` を含む本 PR では docs だけの push でも全ジョブが走る（約 20 分/push）。したがって **docs だけの経路（changes + gate のみ）の実 CI での成功は、本 PR では確認できない**（未確認。マージ後の最初の docs だけ・golden だけの PR で確認する）。CI 設定を含む PR は push を少なくまとめること。
- この記録のコミット自体も同じ理由で全ジョブが走る。その run の結果は PR のチェックを参照（ここには追記しない）。
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
