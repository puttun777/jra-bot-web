# LANGUAGE_MIGRATION（静的型付け言語への移行: 棚卸し・移行先・順序・検証）

作成: 2026-11-02（要件変更: 本人がコードを読んで保守できるよう、自分たちで保守する Python を通常開発・CI・運用の必須経路から外す。D-250）。
**この文書が言語移行の正本**。進捗は `COMPLETION.md`（L1〜L9）と `PROGRESS.md`。infra 側の詳細は `jra-bot-infra/docs/PULUMI_MIGRATION.md`。

## 0. 方針（D-250〜D-252）
| 対象 | 移行先 | 理由・制約 |
|---|---|---|
| Web・bot・ingest・業務ロジック | Kotlin/JVM（既存） | 変更なし |
| ML 学習・評価・品質ゲート・成果物生成 | **Kotlin/JVM**（新モジュール `:ml-train`。`:domain` の推論・正準 JSON を再利用） | 学習ライブラリを `:domain` に入れない（依存規則を維持） |
| GCP リソース定義 | **Pulumi + TypeScript**（infra `pulumi/`） | CDKTF は保守終了のため不採用。state は GCS の DIY backend（Pulumi Cloud の有料契約を前提にしない） |
| infra の運用 CLI（start/stop/status/deploy/guard） | **TypeScript**（infra `ops/`） | |
| Windows の JV-Link 取得エージェント | **C#/.NET 10（LTS）**（web `agent/`。同じリポジトリ） | COM は小さな境界に隔離。実機確認までは『動作する』と書かない |
| CI・リリース補助（差分分類・gate・preflight・整合・テスト件数・manifest・スモーク） | **TypeScript**（web `tools/`） | GitHub Actions の YAML・最小限のシェルは維持 |
| ゴールデン（旧新比較） | 固定フィクスチャ + **Kotlin**（`:golden-runner` が比較まで行う） + TypeScript（来歴・hash 検証） | 旧 Python の再生成は CI の必須経路から外す（§5 で失われる検証と代替を明記） |
| DB 移行ツール（SQLite → MySQL） | **Kotlin/JVM**（新モジュール `:data-migration`） | |
| フロントエンドの処理 | **TypeScript**（`dashboard/src/main/ts`。tsc でビルドした JS を配信） | 大規模フレームワークへは作り直さない |

**版（公式情報で確認。2026-11-02）**: Node.js **24.22.2**（LTS "Krypton"。26 系は 2026-10 に LTS 化した直後で、Pulumi の対応表明前のため採らない）、TypeScript **6.0.3**（`@pulumi/pulumi` 3.273.0 の peerDependency が `typescript < 7`。7.0 は JS API が無く Pulumi の TS 実行と非互換）、`@types/node` 24.19.2、.NET **10**（LTS。SDK 10.0.1xx）、JDK 25（既存）。詳細と確認元は `VERSIONS.md` §16。

**禁止（D-250）**: Python をシェルから呼ぶだけ・コンテナに隠すだけ・Python で計算して結果だけ返す構成。新旧の恒久的な二重管理。テストを削って件数を合わせる・skip の成功扱い・型検査の無効化・ゴールデン期待値の書換え。

## 1. 棚卸し（2026-11-02 時点の main: web 37b043e / infra e21e9e4）
行数は空行・コメント込み。『検証』は移行後に必須とする検査。

### 1.1 web
| # | 対象（行数） | 現在の役割 | 呼出元 → 入出力契約 | 維持する機能・障害時の挙動 | 既存テスト | 移行先 / 移行後の検証 | 本人・実機・承認 |
|---|---|---|---|---|---|---|---|
| W-CI1 | `tools/ci/classify.py`（221） | 変更ファイル → 実行ジョブの決定 | ci.yml `changes` → `GITHUB_OUTPUT`（mode/reason/run_*/db_matrix/db_suites） | **fail closed**（不明ファイル・差分取得失敗・想定外イベント → 全部実行）、出力の改行除去 | `test_classify.py` | `tools/src/ci/classify.ts`。同じ規則表（移行後のパスに更新）・同じ出力キー。Python の試験ケースを全件移植 | なし |
| W-CI2 | `tools/ci/gate.py`（98） | 最終ゲート `ci gate`（required check） | ci.yml `gate` ← `toJSON(needs)` | 必要なジョブは success のみ可。skipped を成功に数えない。changes 失敗は全体失敗 | `test_gate.py` | `tools/src/ci/gate.ts`（同一の判定表・要約） | なし |
| W-CI3 | `tools/ci/consistency.py`（108） | Flyway 版・requiredSchemaVersion・bot-run 必須環境変数・DAO 表と GRANT・infra 契約の整合 | ci.yml / preflight | 1 項目でも食い違えば失敗。infra が無ければ『未実施』と表示 | （preflight 経由） | `tools/src/ci/consistency.ts` + 単体（偽リポジトリで各違反を検出） | なし |
| W-CI4 | `tools/ci/preflight.py`（428） | push 前のローカル検証（CI と同じ分類・同じ Verify ブロック） | 開発者 | 実行できない検査は『未実施』、JDK/MySQL の差異を記録、`build/preflight/last.json` | `test_preflight.py` | `tools/src/ci/preflight.ts`（同じ CLI オプション）。Python 系の手順は各移行の完了とともに削除 | なし |
| W-CI5 | `tools/ci/run_tests.py`（29）・`.github/scripts/verify_test_results.py`（37）・ci.yml 内の `python3 - <<PY`（JUnit XML 集計 7 か所） | テスト件数・skip・失敗・必須クラスの検査 | ci.yml | 0 件・skip・失敗・期待クラスの欠落は失敗 | なし | `tools/src/ci/verify-junit.ts`（1 つの CLI に統合）+ 単体 | なし |
| W-RL1 | `tools/release/manifest.py`・`cli.py`・`repo_facts.py`・`jsonschema_lite.py`（518） | release-manifest（本番/プレビュー）の生成・検証 | images.yml、infra deploy（`release-manifest.v2.json` スキーマ） | 仮 digest・タグのみ・未検証ロールバックの拒否、決定的出力、終了コード 0/1/2 | `test_manifest.py` | `tools/src/release/*.ts`。スキーマ検証は **Ajv（strict）**（未対応キーワードで失敗 = 旧 lite と同じ fail closed）。schema v3（`runtime.python` を削除）は ML 移行時に infra と同時に変更 | なし |
| W-RL2 | `tools/release/image_smoke.py`（656） | 5 イメージのセキュリティ・起動スモーク（docker CLI + 使い捨て MySQL） | images.yml、preflight | 実行できなかった検査は FAIL、テスト用パスワードの漏えい検査 | `test_dockerfile.py` | `tools/src/release/image-smoke.ts`。ml-train の検査は Kotlin 化（W-ML）に合わせて JVM 用へ | なし |
| W-ML1 | `ml/src/ml_train/*`（約 2,100） | 学習 Job（データセット読込・契約検証・標準化・L2 ロジスティック回帰（sklearn lbfgs）・直近重み・時系列分割・Platt・指標・市場比較・品質ゲート・モデル JSON・正準化・hash・Ed25519 署名・GCS・DB 登録・SIGTERM） | Cloud Run Job `ml-train`（args `train --tenant-id … --dataset-id … --config … [--gate …]`）→ JOB_RESULT 1 行 JSON・終了コード 0/1/2/75 | CANDIDATE/REJECTED のみ（ACTIVE にしない）、冪等、STOPPED で DB に触れない、改ざん検出、失敗時の後始末 | pytest 128、`MlTrainInteropIntegrationTest`（Kotlin⇄Python 5）、イメージスモーク 14 | **`:ml-train`（Kotlin）**。数値は Python 実装で作った固定フィクスチャと**許容差付き**で比較（§4） | 実データでの学習は B（T1.20/T1.21） |
| W-ML2 | `ml/src/ml_train/legacy_pkl/*`（310） | 旧 pickle モデル → 新 JSON 変換（許可リスト Unpickler。ローカル専用） | 本人の PC（`convert-pkl --confirm-own-local-file`） | クラウドでは拒否、許可リスト外の global を拒否 | `test_legacy_pkl.py` | Kotlin の**構造だけを読む pickle パーサ**（実行しない。§4.3）。旧モデルの実ファイルでの確認は B | 旧モデルの扱いは本人判断（UNRESOLVED D） |
| W-ML3 | `tools/ml/*`（canonical・signing・dataset_contract・make_test_artifacts。約 460） | 正準 JSON・署名・データセット契約の参照実装、合成成果物の生成 | ci.yml `ml-tools`、ml-train イメージ、`repo_facts.feature_schemas` | 合成成果物はコミット済みで CI が再生成一致を確認 | 20 | Kotlin（`:domain` の `CanonicalJson` を正とし、生成器は `:ml-train` のテスト側）。TS 側は feature schema hash の再計算だけ | なし |
| W-GD1 | `tools/golden/*`（約 2,900） | 旧実装（jra-bot f7dbf69、Python）でフィクスチャを生成・検証、Kotlin 出力との比較 | ci.yml `golden`・`build` | フィクスチャは CI が書き換えない、sha256・来歴の検証、未対応ケースを成功に数えない | 93 | 比較は `:golden-runner`（Kotlin）へ、来歴・hash 検証は TS へ。**旧実装からの再生成は必須経路から外す**（§5） | なし |
| W-AG1 | `agent/src/jra_agent/*`（約 4,500） | Windows 取得エージェント（JV-Link COM ワーカー子プロセス・監視・SQLite キュー・署名・ビーコン・モード・DPAPI・更新・診断・タスク） | 本人の Windows PC → ingest API（CONTRACTS §6〜§8。署名ベクトル `agent/tests/vectors/signing_vectors.json`） | ハング検知と子プロセス終了、再送・重複防止、停止モード、パストラバーサル拒否 | pytest 213 + Windows 4 + `AgentInteropIntegrationTest`（実 Python エージェント ⇄ Kotlin ingest） | **C#/.NET 10**（`agent/`）。署名ベクトル・相互運用試験を C# 版で通す（§3） | 実機 COM は B（R4） |
| W-AG2 | `agent/poc/*`・`agent/interop/driver.py`・`agent/scripts/*.ps1` | 実機 PoC・相互運用試験のドライバ・タスク登録 | 本人・ingest 統合試験 | ドライラン既定 | test_cli_poc | C# の `poc` サブコマンドと `interop` ドライバ、PowerShell は .exe 用に更新 | 実機は B |
| W-FE1 | `dashboard/src/main/resources/static/app.js`（156） | 画面の処理 | ブラウザ | CSP（インライン script なし）・innerHTML 不使用 | 静的検査 | `dashboard/src/main/ts/*.ts` → Gradle で tsc → `static/js/`（生成物は Git に入れない） | 目視の受入は本人 |
| W-DK1 | `Dockerfile` の ml-train（Python 3.13 + venv） | ml-train イメージ | images.yml | 非 root・読取り専用・ENTRYPOINT exec 形式 | スモーク | JVM の ml-train ステージ（他の JVM イメージと同じ基盤） | なし |
| W-WF1 | ci.yml の Python ジョブ（golden/ml-tools/ml-train/agent/agent-windows/release-tools）、compat-connectorj26.yml・images.yml の Python 呼出し、dependabot の pip | — | — | — | — | 各移行の PR で置換（node/dotnet のジョブへ）。dependabot は npm・nuget へ | なし |

### 1.2 infra
| # | 対象（行数） | 現在の役割 | 呼出元 → 入出力契約 | 維持する機能・障害時の挙動 | 既存テスト | 移行先 / 移行後の検証 | 本人・承認 |
|---|---|---|---|---|---|---|---|
| I-OP1 | `ops/jrabot_ops/engine.py`・`state.py`・`beacon.py`・`ports.py`（約 560） | start/stop の状態機械・リース・CAS（GCS generation）・ビーコン署名 | ops-start/stop.yml → 終了コード・JSON・`ops-state.json`（CONTRACTS §3） | 中断・キャンセル・再実行の安全性、force、監査情報 | `test_ops.py` 等 77 | `ops/src/*.ts`（strict・実行時検証・generation は文字列/bigint）。**既知問題 5 件を修正**（§6） | 実行は B |
| I-OP2 | `gcloud_adapter.py`（222） | gcloud/GCS/Cloud SQL/Run/Scheduler の操作 | engine | 失敗の分類 | test_adapters | `ops/src/gcp/*.ts`（gcloud CLI の JSON を zod で検証） | 実 GCP は B |
| I-OP3 | `deploy.py`・`status.py`・`plan_guard.py`・`schema_lite.py`・`cli.py`（約 590） | deploy（manifest 検証・migrate → 各アプリ更新・ヘルス・ロールバック判定）、status、plan-guard、CLI | deploy.yml / ops-status.yml / terraform-*.yml | 不明な変更・削除・置換は拒否 | test_deploy/status/plan_guard | TS。plan-guard は **Pulumi preview の JSON**（`pulumi preview --json`）に合わせて作り直す | なし |
| I-TF1 | `terraform/*.tf`（約 1,500） | GCP 全リソース | terraform-plan/apply.yml | ignore_changes（運用モード・DB 状態・ingress・Scheduler・イメージ）、削除保護 | `terraform test`（mock 17） | **Pulumi TypeScript**（`pulumi/`）。対応表・mock 試験（`pulumi.runtime.setMocks`）・preview guard。**apply/up は行わない** | up・import は本人承認 |
| I-WF1 | workflows の setup-python・`python3 -c`（JSON 生成） | — | — | — | — | setup-node + TS CLI | なし |

## 2. 移行順序（各段階で動作可能な状態を保つ）
1. **L1 CI・リリース補助（web）**: TS 版を追加 → 同じ PR で workflow を TS 版へ切替 → 旧 Python（tools/ci・tools/release・verify スクリプト）を削除。golden/ml/agent の Python ジョブはこの段階では残す（分類規則は TS 版が持つ）。
2. **L2 infra ops（TS）**: 既知問題の回帰試験を先に書く → TS 実装 → workflow 切替 → Python 削除。
3. **L3 ML（Kotlin）**: **先に Python 実装で数値フィクスチャを生成して固定**（生成元コミット・入力・hash を記録）→ `:ml-train` 実装 → 固定フィクスチャと許容差比較 → イメージ・スモーク・相互運用試験を JVM へ → Python（ml/・tools/ml）削除。release-manifest schema v3（`runtime.python` 削除）を web・infra 同時に。
4. **L4 golden**: Kotlin の比較器を `:golden-runner` へ、来歴・hash 検証を TS へ → golden ジョブ（Python）を廃止。
5. **L5 agent（C#）**: COM PoC → 通常ロジック（Fake）→ 署名ベクトル・ingest 相互運用試験を C# で → Windows CI → Python 削除。
6. **L6 Pulumi**: 対応表 → Pulumi 定義 + mock 試験 → preview guard → workflow（preview まで）→ Terraform は**本人の import/up 承認まで残す**（§7。実リソースが無いと確認できれば、承認後に削除）。
7. **L7 フロント TS**、**L8 残機能**（COMPLETION の C）、**L9 リリース経路**。

## 3. C# エージェントの互換性（L5）
- 通信: CONTRACTS §6〜§8 の HMAC 署名・エンベロープ・ビーコンを、既存の署名ベクトル（`agent/tests/vectors/signing_vectors.json`。Python と Kotlin が一致済み）で検証する。**型が合うことと通信互換は別**で、Kotlin ingest との相互運用試験（実プロセス）で確認する。
- キュー（SQLite）: スキーマは同じ表定義を維持し、旧 Python 版のキューファイルをそのまま開ける（schema_version を検査。未送信行は再送）。資格情報（DPAPI）は同じスコープ（CurrentUser）・同じ形式なら引き継げるが、**実機で未確認**。移行手順は `USER_ACTIONS.md`。
- COM: JV-Link は 32bit の COM サーバ。エージェントのワーカーは **x86** でビルドし、STA スレッドで呼ぶ。型は公式 SDK の型ライブラリ（`JVDTLab.JVLink`）に基づく小さなインターフェイスに隔離する（§JVLINK_DESIGN 14）。SDK の DLL・仕様書は Git に入れない。

## 4. ML の互換性（L3）
### 4.1 推論互換と学習の再現性を分ける
- **推論互換**: 同じモデル JSON から同じ確率（Kotlin の `LogisticScoring` は既存。Python と一致済み）。変更なし。
- **学習の再現性**: ソルバが sklearn lbfgs → Kotlin の Newton 法（同じ目的関数）に変わるため、**重みの完全一致は前提にしない**。目的関数は強凸（L2）で最適解は一意なので、両者とも最適解の近傍に収束する。許容差は §4.2。
### 4.2 許容差と根拠（D-253 で確定。2026-11-03 にレビュー 低3 で試験の実値に合わせて訂正: D-257）
削除前の Python 実装（sklearn 1.9.1 の lbfgs、**tol=1e-6**）で生成した固定フィクスチャ（合成データ）と比較する。Kotlin は同じ目的関数を Newton 法で勾配 1e-10 まで詰める。試験の許容差:
| 量 | 許容差（試験） | 根拠 |
|---|---|---|
| 目的関数値（同じ定義で計算） | Kotlin ≤ Python かつ相対差 ≤ 1e-6 | 同じ強凸な目的関数の最適解。lbfgs は勾配 ≤ tol で止まるので Python の方が最適値から離れる |
| 重み・切片・未校正の確率（ソルバ単体・学習 Job） | 絶対 1e-4 | 停止時の勾配 tol と、スケールした Hessian の最小固有値（データの条件数）で決まる。**データによらない上界ではない**ので、フィクスチャのデータで実測した値（下）の 40 倍以上の余裕を取った |
| Platt の a・b（ソルバ単体、500 行・80 行） | 絶対 1e-6 | 1 次元で条件数が小さい |
| 校正後の確率・校正の傾き/切片 | 絶対 1e-3（学習 Job の 60 行）・1e-4（指標の単体） | 学習 Job の合成データは校正用が 3 日分で分離に近く、Platt の a が −78 になる（a・b の差が確率に増幅される） |
| logloss（学習 Job） | 絶対 1e-3 | 上の確率の差から決まる |
| 閉じた式で決まる指標（logloss・Brier・AUC・分位点・信頼度ビン・正規化。同じ入力の確率から計算） | 絶対 1e-12 | 計算の順序の違いだけ |
| AUC（順位だけで決まる）・分割・直近重み・標準化・model_id・品質ゲートの判定と理由 | 完全一致（重み・標準化は 1e-12） | 閾値付近のケースはフィクスチャに含めない（作為的に境界へ寄せない） |

**実測（2026-11-02。D-257）**: ソルバ単体の 6 ケースで重みの差 ≤ 2.4e-6、Platt（500 行）の a・b の差 ≤ 2.8e-10。学習 Job の計算部分（合成データセット 60 行 × 4 ケース）で目的関数の相対差 ≤ 2.6e-11（いずれも Kotlin ≤ Python）、校正後の確率の差 ≤ 8.2e-5（分離に近い校正データで Platt の a が −78 になるケースを含む）、指標・判定・分割・model_id（旧 producer を渡したとき）は一致。
### 4.3 旧 pickle（**実装済み**: `RestrictedPickle`。ML_DESIGN §16）
Python を呼ばない方法として、Kotlin で **pickle の opcode を構造として読むだけのパーサ**（任意の global を呼び出さない。許可した numpy 配列・旧クラスの形だけを組み立てる）を実装する。実ファイルでの確認・旧モデルを引き継ぐかは本人判断（B）。再学習する場合の影響は ML_DESIGN §11.9。

### 4.4 L3 で失われる検証と代替（D-257）
| 失われるもの | 代替 |
|---|---|
| CI ごとの sklearn（lbfgs）との比較（Python の ml-train の pytest） | 削除前に生成した固定フィクスチャ（`python-reference/ml_reference_v1.json`）との許容差比較（§4.2）。ソルバの不具合は目的関数値と勾配で検出する |
| Kotlin と独立な Python 実装による相互検証（`tools/ml`: 正準 JSON・feature_schema の hash・Ed25519・データセット契約の単体と、合成成果物の再生成の一致 `--check`） | 合成成果物（`domain/src/test/resources/ml`。削除前の Python が生成）はそのまま固定。Kotlin の `CanonicalJsonTest`・`FeatureSchemaFixtureTest`・`ModelLoaderTest` が検証し、ml-train の `Dataset` が Kotlin の書いたデータセットを読む。**再生成の一致の確認は無くなった**: 成果物を作り直す必要が出たら、Kotlin の生成器を書いても『旧 Python 由来』とは呼ばず、差分をレビューする |
| Python の convert-pkl の pytest | 削除前に固定した合成 pickle 42 件の判定・出力との一致（`legacy-pkl/expected.json`。pickle は Base64 で埋め込み、`.gitignore` の `*.pkl` を緩めない） |
| Kotlin → Python → Kotlin の結合試験 | Kotlin（export-dataset）→ Kotlin（ml-train の jar を別プロセス）→ Kotlin（ModelLoader）。言語の境界ではなくプロセスの境界の結合試験になった |

## 5. ゴールデン（L4）で失われる検証と代替（**2026-11-03 に実施: D-258**）
- 失われる: CI ごとの『旧実装（Python, f7dbf69）から再生成してフィクスチャと完全一致』の確認。
- 代替: フィクスチャは固定コミットの旧実装で生成済み（`tools/golden/fixtures/manifest.json` に生成元コミット・hash）。Kotlin の `golden-runner verify` が来歴・hash・合成データであること・Python の json.dumps と同じ形式（バイト単位）を検証し（当初案の TS ではなく Kotlin: 整数と浮動小数のトークンを区別して比べる必要があり、JSON.parse では区別できないため）、Kotlin は各ケースの比較規則で比較する。フィクスチャを更新する必要が生じた場合は、**本人の PC で旧実装（参照元 jra-bot）を使う一回限りの手順**とし、その結果を差分として PR に出す（通常開発・CI の必須経路ではない）。**TS や Kotlin で書き直した生成器の出力を『旧 Python 由来の証拠』とは呼ばない。**

## 6. infra ops の既知問題（L2 で修正）
1. dry_run が操作記録（operation）を上書きする → dry_run は書込みを一切しない。
2. CAS の応答が失われた後の再試行 → 再読取りで自分の書込み（operation id・seq）が成立しているかを確認してから判断。
3. 412（precondition failed）を一律成功扱い → 再読取りで自分の更新の成立を確認できたときだけ成功、それ以外は競合として失敗。
4. `rollback.min_app_release` の検査が契約（CONTRACTS §10）と違う → 契約どおりの比較。
5. キャンセル（SIGTERM/SIGINT）時にリースを解放せず失敗記録も残らない → 解放と FAILED の記録。

## 7. Pulumi（L6）の前提
- 実リソース・Terraform state の有無は推測しない。確認できる範囲（本セッションには GCP 認証が無い）では**未確認**。import/up/destroy は行わない。
- 詳細: infra `docs/PULUMI_MIGRATION.md`（対応表・state 設計・secret・ignoreChanges と refresh・preview guard）。
