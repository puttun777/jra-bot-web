# UNRESOLVED（未解決事項の管理表）

作成: 2026-10-23（Stage 3-D / T3.4）。**2026-10-24 Stage 3-E（T3.5）・Stage 3-F1（T3.6-A）・Stage 3-F2（T3.6-B）、2026-10-27 Stage 3-H（T3.8）で更新**。**未解決事項の正本はこの表**。PROGRESS.md の各段階の「未解決」は履歴で、現在の状態はここを見る。
**根拠のない項目を「解決済み」にしない**。A は根拠（出典）を必ず書く。出典が検索抜粋で原文を直接読めていないものは、その旨を書く（本環境のプロキシは jra-van.jp / developer.jra-van.jp を 403 で拒否する）。

| 分類 | 意味 |
|---|---|
| **A** | 公式資料で解決済み（根拠つき） |
| **B** | 本人 1 名・非営利への要件変更（REQUIREMENTS §0）により対象外 |
| **C** | 今回（T3.4）の実装・テストで解消 |
| **D** | ユーザーの運用判断待ち |
| **E** | 公式確認待ち（JRA-VAN への問い合わせ、公式仕様書の確認） |
| **F** | 実機・実環境での検証待ち |
| **G** | 後続タスク待ち |

---

## 重点項目の現在の状態（Stage 3-F1 / T3.6-A 時点。詳細は各 ID）
**今回の ML 実装で解消できたのは C-14〜C-19 だけ**。以下はいずれも**未解決のまま**（Windows エージェントの実機検証が済んだことにしない）。
| 項目 | ID | 状態 | T3.6-A での変化 |
|---|---|---|---|
| T1.20: 実 SQLite・実 config・実 pkl | D-2・F-5 | 利用者の取得待ち。pkl はクラウドで読まない | 変換ツールの**仕様**とローカル手順を書いた（ML_DESIGN §11.9。実装・実行は未実施）。合成モデルのゴールデンは実施済み |
| T1.21: legacy / v2 の切替 | D-3 | ユーザー判断待ち（現状維持 = `legacy` が推奨） | v2 を独立実装したが**未承認・本番で無効・自動切替なし**。高精度とは未証明 |
| P-1: エージェント認証方式 | D-5 | 承認待ち | 変化なし |
| P-2: fetch フィールド | D-6 | 承認待ち | 変化なし |
| P-3: 1 倍未満のオッズの隔離 | D-7 | 承認待ち | 変化なし |
| T1.16: クラウド保存・Discord 等の規約確認 | E-1〜E-3・COMP-3/4 | 公式確認待ち。**公式回答まで実データをクラウドへ投入しない** | 変化なし（テストは合成データのみ） |
| T1.17: 公式仕様書の詳細確認 | E-5・G-16 | 12 項目すべて未確認 | 変化なし |
| T3.0: Windows 実機 PoC | F-1 | 未完了 | 変化なし |
| T5.0: infra 同期 | G-9・G-14 | 第 5 段階の前の必須前提 | `MODEL_VERIFY_PUBKEY` の書式（CONTRACTS 0.4.9）を追加 |
| ML 品質ゲートの閾値・署名鍵運用（T3.6-B） | D-13・D-14・D-15 | 未承認。閾値の既定値は持たず、設定が無ければ REJECTED（fail closed） | 新規（Stage 3-F2）。実データでの学習・評価は未実施 |
| 本番 GCP | F-2 | 未検証（Stage 5 まで保留） | 変化なし（GCS の実バケットに書いていない） |
| MySQL 9.7 の本番採用 | D-10 | 未決（8.4 を本番既定のまま） | 変化なし |
| Connector/J 26.x の採用 | D-10 | 未決 | 変化なし |

## A. 公式資料で解決済み
| ID | 内容 | 根拠 | 注意 |
|---|---|---|---|
| A-1 | JRA-VAN SDK 5.0.0 は 2026-08-04 に公開され、**64bit 版 JV-Link が正式に追加**された。32bit 版も Ver.5.0.0 に更新。旧記述「64bit 版の公式提供が未確認」は誤り（修正済み: JVLINK_DESIGN §9・§12） | developer.jra-van.jp topic 959・45・459、jra-van.jp/news/20260804a（**検索抜粋。原文は直接読めていない**） | **公開の確認であって、実際の Windows PC での動作確認ではない（→ F-1）** |
| A-2 | 32bit と 64bit は同一 PC に共存できるが、**それぞれ別の利用キー**が必要 | 同上 | |
| A-3 | 公式 Python サンプル（開発ガイド Python 3.14、SDK 同梱の JV-Data 構造体(Python)・サンプル(Python3.14)）がある | topic 949・45 | 動作は未検証 |
| A-4 | **SDK 本体の版（5.0.0）と、JV-Link インターフェース仕様書・JV-Data 仕様書の文書版（4.9.0.1、2024-08-07 付）は別**。5.0.0 同梱の文書が 5.0.0 かを尋ねる投稿があり、公式回答は未確認（→ E-4） | topic 45・459 | 「仕様書も 5.0.0」と書かない |
| A-5 | 利用規約の構造: 第7条は「本人が自ら個人として私的に使用する以外」の複製・公表等と、第三者にさせることを禁じる。第9条は禁止行為の列挙（JV-Link の解析・JV-Link 以外からのサーバアクセスを含むと読める） | jra-van.jp/info/rule.html（**検索抜粋。全文は未読。第9条の正確な項目と最新の改定日は未確認**） | これは「条文の構造」の確認であり、**クラウド保存の可否の確認ではない（→ E-1〜E-3）** |

## B. 要件変更（本人 1 名・非営利・非公開）により対象外
| ID | 旧項目 | 扱い |
|---|---|---|
| B-1 | T1.16 の Q3（2 名での共有・承認の方法）、案 A〜D（2 人利用の設計案）、2 人目の契約有無の確認 | **対象外**。必須タスク一覧から外す。DATA_SOURCE_COMPLIANCE §4・§6 に履歴として残す |
| B-2 | T1.16 の Q4（他の契約者へ取得ソフトを配布） | 対象外（本人の PC だけで使う部分は Q3 に統合: E-3） |
| B-3 | T1.16 の Q6（一般公開・分析結果のみの公開、JRADB の要否）、一般公開（旧 Phase 3）、JRADB による商用提供 | 対象外 |
| B-4 | T1.16 の Q7（学習済みモデルの共有） | 対象外（共有しない。Git に入れない） |
| B-5 | 将来の複数人運用（100〜1,000 人・友人との共有） | **要件外**。実装・公開・許可しない。`tenant_id` の構造・Repository のテナント境界・認証認可は維持。新規利用者の登録・招待・データ共有は作らない |
| B-6 | FRONTEND_DESIGN の「初期 2 人利用の工数」前提 | 利用者 1 名に読み替える（採用案 A の判断は変わらない。D-032 の承認は D-3 に残る） |

## C. 今回（T3.4）の実装・テストで解消
| ID | 内容 | 結果 |
|---|---|---|
| C-1 | **取得の事実（YSCH 成功）を DB に持つ場所**（T3.3 の引き継ぎ #3） | V3 の `ingest_fetch`（D-134）。`ScheduleFetchFactReader` の DB 実装（`DbScheduleFetchFactReader`）を bot に配線。「取得成功・予定あり／なし」「取得失敗」「取得していない」「途中まで」「古い」を区別（テスト: `IngestPipelineIntegrationTest`、`IngestStoresIntegrationTest`）。手動補正は既存の `calendar_override` |
| C-2 | 複数バッチに分割された取得の途中で成功確定しない | EOF まで連続して読了し、全レコードを解析・適用できたときだけ事実になる。事実の範囲は「見えた最後の開催日」まで（D-134） |
| C-3 | バッチ単位の冪等性（初回／重複／内容違い／処理中 409／引き継ぎ／途中失敗の巻き戻り／ACK 直前の失敗） | 実装・MySQL 統合テスト（D-133） |
| C-4 | 並行実行の整合（同じ batchId・同じレコードの同時送信、逆順更新のデッドロック） | 実装・テスト。claim は `INSERT … ON DUPLICATE KEY` で S→X ロックの待ち合いを避ける（D-133）。バッチの処理は READ COMMITTED の 1 トランザクションで、デッドロックは全体を再実行 |
| C-5 | 認証方式の整合性（契約 0.4.6 の「HMAC＋ハッシュ保存」は検証不能） | 暗号学的に検証し、導出鍵 HMAC＋KEK 暗号化を実装・テスト（D-130）。**プロトコル変更の承認は D-5** |
| C-6 | ACK・再開・カーソル | ACK 後（EOF まで連続読了）にだけ前進。後戻りしない。バックフィルと失敗では動かさない（D-135） |
| C-7 | バックフィル基盤 | `ingest_backfill` の状態管理・重複排除・再開・取りこぼし区間（`ingest_gap`）の解消（D-138）。実際の過去取得は T3.5 以降 |
| C-8 | T3.3 引き継ぎ: `STALE`・`FETCH_FAILURE` イベントの生成元 | `FETCH_FAILURE` は取得失敗時に生成（実装済み）。`STALE` は reconcile（T3.8）→ G-6 |
| C-9 | テナント越境の防止（ingest） | エージェントの行からテナントを決める（本文は信用しない）。複合 FK で `ingest_fetch`・`ingest_backfill` の越境参照を DB が拒否（D-131） |
| C-11 | **T3.5 Windows エージェント（Mock・Linux CI・結合テストで検証した範囲）**: 署名クライアント（P-1。既知ベクトルを Python/Kotlin 両方が検査）、gzip・再送・バックオフ、SQLite WAL キュー、取得セッション `fetch`（P-2）の生成・保存・送信・再開、ACK/カーソル（`acked_ts` はサーバの cursor でだけ前進）、Ed25519 ビーコン、状態機械（STOPPED/UNKNOWN で取得も送信もしない・復帰手順）、COM ワーカーのウォッチドッグ、取得計画、多重起動防止、DPAPI（Fake で検証。実 DPAPI は Windows ランナー）、署名付き更新の検証 | agent/ の Python 203+4 件（2026-11-03 に C#/.NET 10 へ移行: D-261。C# の xunit 296 件〔Windows 専用 4 を含む〕と同じ観点を検査）、Kotlin ingest との結合（`AgentInteropIntegrationTest`。C# のドライバで 4/4）。**実 JV-Link・実機の動作は未検証（F-1）。P-1/P-2 の本番採用は D-5/D-6 の承認待ち** |
| C-12 | **T3.4 の実装の穴: ACK 応答の喪失で `acked_ts` が止まる**（重複 ACK に cursor が無い。D-144） | サーバに加算のみの修正（重複 ACK に fetch・cursor）。結合テストで確認 |
| C-13 | `GET /v1/agent/mode`・`plan`・`update` の HTTP（G-2 の一部） | 実装（D-149）。plan は検証済み仕様のみ・既定 idle |
| C-10 | app の `race_schedule_event` INSERT の最小権限の妥当性（T3.3 の確認事項） | **妥当性を検証**: app は手動補正の履歴（MANUAL_OVERRIDE・MANUAL_REVOKE）の INSERT だけで、UPDATE・DELETE は不可（`GrantsIntegrationTest`）。ingest・bot も INSERT のみ。→ ユーザーの承認と infra 同期は D-9 / G-9 |
| C-14 | **T3.6-A: ML の Kotlin 推論基盤**（Stage 3-F1）: `FeatureBuilder`（40/35 次元・血統ハッシュ 16 次元・旧互換と point-in-time）、ロジスティック回帰推論（Platt・市場ブレンド・±50 クリップ）、`ProbabilityPipeline`（legacy/v2・ensemble・市場フォールバック・理由コード）、モデル JSON の検証（正準 JSON・sha256・Ed25519・feature_schema_hash・次元）、レジストリの読取りと run 開始時の固定、合成のローカル `ModelStore` | Kotlin と旧 Python のゴールデン 26 ケース/880 呼び出し中 25 ケース/874 呼び出しが全一致（最大絶対誤差 1.07e-14）。Kotlin 単体・MySQL 統合（ローカル 8.0）・Python（tools/ml）。ML_DESIGN §11、D-152〜D-164。**本番で動作する推論ではない**: bot-run への統合は T3.8、学習は T3.6-B（G-17） |
| C-15 | H3（過去走のラベルリーク）・H8（`age_years` の時刻依存）の**新方式での構造的な解消** | `PointInTimeFeatureBuilder`（過去走は `run_date < レース日`、age はレース日基準、asOf が発走後なら拒否）。旧互換 `LegacyFeatureBuilder` は H3/H8 を**そのまま**再現（G6、旧新比較の基準）。**H3/H8 の実データでの影響の大きさは未確認**（G-ML-1/T1.20） |
| C-16 | H2（alpha の特徴量ミスアライン）の**ロード契約での拒否** | 重み長 ≠ スキーマの次元は `INVALID_MODEL_DIMENSION` で拒否（切詰め/ゼロ埋めをしない）。旧挙動は検証専用の `GoldenMl` でのみ再現。**旧 pkl の実際の次元は未確認**（T1.20） |
| C-17 | H1/H10 の**記録の是正**（E1・E2: 分類 R） | 理由コード・段階・馬ごとの正確な出どころを保持。旧ラベルは `legacyLabel` に残し Kelly 係数の数値は不変。H1 の**実態**（旧 Bot が実際に市場確率だったか）は実データの `prob_source` 分布が必要で未確認（D-2 の手順 2） |
| C-18 | モデル署名・改ざん検知・署名前の推論禁止 | `ModelLoader`（署名 → 正準形 → sha256 → スキーマ → 次元の順に fail closed）、`VerifiedModel`。テスト用の決定的な鍵（本番の秘密鍵は不要・Git に無い）。**本番の鍵の発行・保管・ローテーションは未決（G-3/D-12）** |
| C-19 | 学習側（Stage 3-F2）への引き継ぎ契約の定義 | データセットの列・split・as_of・sha256・manifest、`sample_weight` の式、モデル出力形式、署名、品質ゲートの**案**（ML_DESIGN §11.7・§11.8）。Python の検証（`tools/ml/dataset_contract.py`）と合成データセットで確認。**品質ゲートの数値は未確定（承認後）** |
| C-20 | **T3.6-B: `export-dataset` の配線**（Stage 3-F2）: `FeatureSource`/`DatasetSource` の MySQL 実装（`:persistence`。SQL 自体が asOf・レース日で時間を制限する）、`DatasetPublisher`（CSV → manifest の順。manifest が確定の印）、`bot --task=export-dataset`（D-174） | `MlSourceIntegrationTest`（過去走は `run_date < レース日`、オッズは `observed_at <= asOf`、他テナント不可視、結果なしのレースは `RESULT_INCOMPLETE`）、`ExportDatasetTaskHandlerTest`、`MlTrainInteropIntegrationTest`（Kotlin 出力 → Python 学習 → Kotlin `ModelLoader` で検証）。合成データのみ。**実データでの出力は未実施** |
| C-21 | **G-17 ⑤ 馬マスタの時点履歴**（D-166） | V4 `horse_master_history`（追記専用）と `HorseMasterAtTime.resolve`（現行 `updated_at ≤ asOf` なら現行、そうでなければ asOf 以前の最新履歴、無ければ累積値を空にして `MASTER_NEWER_THAN_AS_OF`）。履歴の追記は `HorseMasterHistoryStore`。**実運用での追記（取込側が版を足す）は T3.8 以降**。既存データに履歴は無いので、過去分の PIT は履歴が貯まるまで不完全 |
| C-22 | **G-17 ④ GCS の `ModelStore`/`DatasetStore`**（D-172） | `GcsModelStore`/`GcsDatasetStore`（作成のみ・`ifGenerationMatch=0`・署名先/JSON 最後）。**偽の `ObjectClient` による単体テストと、HTTP クライアントのローカル疎通のみ**。実 GCS への接続は未検証（G-19） |
| C-23 | **ml-train（学習 Job。2026-11-02 に Kotlin へ移行: D-257。下の記述は Python 版のときの記録で、意味は Kotlin 版も同じ）**（D-165〜D-171）: データセット検証・v2 学習（L2 ロジスティック回帰 + Platt + 直近重み）・評価・品質ゲート（fail closed）・署名つきモデル JSON・保管・レジストリ登録（CANDIDATE/REJECTED のみ。ACTIVE へ昇格しない・削除/更新しない） | pytest 128 件・ruff・mypy strict。生成したモデル JSON を Kotlin `ModelLoader` が検証できる（署名・正準形・sha256・スキーマ・次元）。**合成データのみ。実データでの精度・品質ではない** |
| C-24 | **G-17 ⑧ release-manifest の `feature_schema_hash`**（D-177） | schema v2: `feature_schemas`（calibration/alpha/market の hash）+ `feature_schema_hash`（その正準 JSON の sha256）。プレビューは Kotlin のフィクスチャから算出。**本番 manifest は生成しない（第 5 段階のゲート）** |
| C-25 | **旧 pkl → JSON 変換ツールの実装**（D-175。T1.20 の一部） | 許可リスト式 Unpickler（任意の pickle を安全にするものではない）、次元不一致は中止、署名、ローカル専用。**合成の旧形式 pkl だけで検証。実 pkl の変換は未実施（F-5）** |
| C-27 | **T3.7 Discord 通知**（D-181〜D-189） | `DiscordNotifier`（429・Retry-After・接続前の失敗だけ再試行・5xx/タイムアウト/切断/一部送信は UNKNOWN）、`SignalNotificationDispatcher`（claim → 送信 → 記録、同時実行で勝者 1 つ、`recoverStaleClaims`）、URL 検証（SSRF）、メンション制御、`ErrorReporter`。Fake HTTP サーバの単体テストで検証。**実 Discord への送信は未検証（G-22）。exactly-once は保証しない** |
| C-28 | **G-8 BACKFILL・OpsMode での通知停止** | `NotificationOrigin`（既定値なし。LIVE 以外は通知せず状態も変えない）、毎回モードを読み NORMAL のみ、停止要求。単体テスト。呼出し元の対応は G-21 |
| C-29 | **G-17 ⑥ `format_reason_from_features`** | 旧互換（H3 を含む。ゴールデン G8-REASON-006 が旧 Python と全一致）と point-in-time（asOf 以前だけ。レース後に更新された馬マスタの累積値は出さない）を分離。合成データのみ。実データの通知は COMP-4 まで送らない |
| C-30 | **T3.8 bot-run の業務ループ**（D-190〜D-203） | 開始検証（運用モード → 有効な開催日 → `CalendarCheckPolicy`）→ リース → 再検証 → checkpoint 復元 → 古い claim の回収 → ML 固定 → 監視ループ（判定・記録・通知）→ 終了。小さな部品に分割（`jp.jrabot.domain.botrun`）。`:domain` 971 件（うち bot-run 71）・`:bot` 130 件・MySQL 統合（ローカル 8.0.46）で通し。**合成データのみ。実 DB の本番・実 Discord・実 JRA-VAN データでは未検証** |
| C-31 | **G-21 ①〜⑤（通知の接続）・G-8** | `dispatchRace` を呼ぶ（出どころは `--trigger` から: LIVE / BACKFILL / 判定不能）、`NoticeViews.from` + 馬名 + `reasonFacts` の理由文で `RaceNoticeBatch`、SIGTERM で `StopFlag`、起動時 `recoverStaleClaims`、`ErrorReporter` を呼ぶイベントの決定（D-201）。NOT_SENT も `SEND_UNKNOWN`（API 変更はしていない）。Fake HTTP サーバ（127.0.0.1）で 1 日分・429・5xx・BACKFILL を確認。⑥管理画面の SEND_UNKNOWN 表示は Stage 4 |
| C-32 | **G-17 ①②（ML の接続）** | `ModelRunPin.capture` → `ModelSession`（署名・sha256・スキーマの照合）を run の開始時に 1 回、`MlInference` → `MlProbabilityInput` → `WinDecisionEvaluator`、`race_evaluations` へ `model_type_used`・`model_id`・`fallback_reason`、ML 有効で市場確率のみのときの run あたり 1 回のアラート、point-in-time の特徴量。署名不正・CANDIDATE のみ・成果物なし・レジストリ障害・元データ欠損のフォールバックを合成モデルで確認。**予測精度の証明ではない**。モデルの昇格・v2・品質閾値は未実施のまま |
| C-33 | **T3.8 の二重起動・SIGTERM・モード** | 有効なリースの間は 2 つ目が始まらない（DB の UNIQUE が最終防壁）、期限切れの奪取と checkpoint の引継ぎ、奪取された古い実行の更新禁止、リース喪失で何も書かず 75、SIGTERM（`INTERRUPTED`・exit 0/75）、STOPPED（`STOPPED_BY_MODE`）、UNKNOWN の連続 3 回（75）、強制終了からの復旧で重複通知なし |
| C-34 | **T3.8 レビュー修正（2026-10-27）**（D-204〜D-207） | ①5 分前の強化の癖を確認し `TightenMode`（OFF/CORRECTED/LEGACY_QUIRK）に ②`hit_rate_min_ev_schedule` を明示設定のみで移植（旧の式の境界表と一致するテスト）③運用モードの確認間隔を独立（`modeCheckInterval` 15 秒。UNKNOWN 3 回連続は 30 秒で停止・45 秒以内、STOPPED は 15 秒以内。heartbeat 60 秒・リース 300 秒は不変）④`30,5` の統合試験（30 分前・5 分前・重複防止・再起動・BACKFILL・MySQL） |
| C-26 | **ml-train イメージ（5 つ目）と CI**（D-176・D-178） | Dockerfile の `ml-train` ステージ、イメージのスモーク（合成データで学習・署名・登録）、`ci.yml` の `ml-train` ジョブと `ci gate` への統合。**イメージのビルドとスモークの合否は GitHub Actions（images）で確認。push はしない** |
| C-35 | **監査 C1/C2（2026-10-28。D-208/D-209）**: 全レース発走後の終了を分類。予定を信頼できず評価を逃したら FAILED `EVALUATION_OPPORTUNITY_LOST`（exit 1）。データ不足の取りこぼしは SUCCEEDED `ALL_RACES_OFF_WITH_GAPS` + 警告。評価 0 件・中止・通知対象なしは正常のまま | 解消。`BotRunIntegrityTest`。LOST_DATA を FAILED にするかは D-19 |
| C-36 | **監査 E/B の暫定対策（D-210/D-211）**: checkpoint 欠損・破損で通知済みのレースを止める／既存 Signal と計算結果が違えば通知しない（初回の空 checkpoint は正常） | 暫定対策を実装（`BotRunIntegrityTest` 他）。**恒久対策は D-17（承認待ち）** | → **V5 で恒久化（C-41）**
| C-37 | **監査 D（D-212）**: 分離レベルを REPEATABLE READ に固定 + デッドロック再試行。RR/RC 指定の両方で同時挿入が 1 行（試験あり） | 解消（設定で保証）。UNIQUE は D-17 |
| C-38 | **監査 G2（D-213）**: レース待ちの保留レコードを新しいレースの到着後に再適用／バッチ内は依存するレースを先に適用／再送された保留の更新（DB 変更なし） | 解消。結果・払戻の依存は G-29 |
| C-39 | **監査 G3（D-214）**: asOf より後に更新された `races` 行の天候・馬場を使わない（フラグ + 学習対象外）。ライブ・旧互換は不変 | 解消。**過去の天候・馬場の復元は不可（G-28）** |
| C-40 | **監査 C・F・G6・G9・G10・A・G1・G7・G8（D-215/D-216）**: 待機後のモード再確認（エージェント）、Discord の再試行前のモード確認、更新 zip の展開前検査、Dockerfile のコメント、エラー通知の畳んだ件数、`signal_sent` の意味の明記、実オッズ無しの買い目の試験、サイズ制限は修正不要、Job の余白を契約に明記 | 解消（契約 0.4.14）。Job の実際の設定は T5.0（G-9） |
| C-41 | **Flyway V5（2026-10-29。D-218〜D-221）**: 新方式の行だけの UNIQUE、`race_timing_notice`（通知タイミングの DB 上の一意性と不変スナップショット）、bot SELECT/INSERT・app SELECT、スキーマ版 5、確定した行からの通知、checkpoint の欠損・破損・古い状態でも再通知しない、評価機会の喪失は FAILED | 解消。ローカル MySQL 8.0.46 で検証。**CI 未検証（F-9）** |

## D. ユーザーの運用判断待ち（承認するまで「承認済み」にしない）
| ID | 判断事項 | 選択肢（推奨を先頭） |
|---|---|---|
| D-1 | **規約本文（第7条・第9条）を一読し、JRA-VAN に Q1〜Q3 を送るか**（DATA_SOURCE_COMPLIANCE §0） | ① 一読して Q1〜Q3 を送る（推奨）／② 送らず、実データは当面ローカルだけで運用 |
| D-2 | **T1.20: 旧データ・設定の取得**（次の「T1.20 の具体的な手順」参照） | 手順に沿って集計値だけ報告 |
| D-3 | **T1.21**: ① ML の確率パイプライン `legacy`（推奨。現状維持。**T3.6-A で v2 を独立実装したが未承認・本番で無効・自動切替なし**）／`v2`（D-037）② E6/E11/E12/E14/H10・H2 は **承認するまで無効**（既定 off。「実装なし」が既定）③ フロント案 A（Thymeleaf＋htmx。D-032）の承認 | ①〜③ をそれぞれ「現状維持／承認」の二択で返答 |
| D-4 | **T3.3 の運用値（暫定推奨）**: `watch_start`=120 分、`prestart`=10 分、`settle_deadline`=90 分、`stale_after`=120 分、リース期限=300 秒、heartbeat 間隔=60 秒 | 暫定推奨値で承認／変更。**本番の既定値は確定していない**（`CALENDAR_*` に既定なし＝未設定なら起動失敗） |
| D-5 | **P-1 エージェント認証方式**（CONTRACTS §3.3.1）: 導出鍵 HMAC＋KEK 暗号化（実装済み。推奨）／Ed25519 署名（より強い。T3.5 の依存追加が必要） | どちらかを承認。承認前は契約の旧記述（0.4.6 の §3.3 本文）を正本とせず、T3.5 は P-1 で実装する |
| D-6 | **P-2 取込の追加フィールド**（`fetch`: id・seq・targetFrom/To・origin・backfillId）と応答の追加項目 | 承認（推奨）／別案。承認前は `fetch` なしで送ればデータは保存されるが、**取得の事実もカーソルも作られない** |
| D-7 | **P-3 `0 < odds < 1` の取込境界**: 隔離（推奨。既定で実装）／受け入れ（旧 Python と同じ）。`Odds` 型・計算は変えていない | 隔離を承認／受け入れ（`INGEST_ODDS_BELOW_PAR=accept`） |
| D-8 | ワイド・三連複オッズの DB 保存（D-043: 既定では保存しない。費用に影響） | 保存しない（既定）／有効化（`INGEST_STORE_COMBO_ODDS=true`） |
| D-9 | `app` への `race_schedule_event` の INSERT 付与の承認（契約 0.4.6。T5.0 で infra と同期） | 承認／手動補正の履歴を別経路にする（変更が要る） |
| D-10 | MySQL 9.7・Connector/J 26.x の本番採否（検証は維持。CI で 8.4/9.7 を毎回、Connector/J 26 は変更時のみ） | 8.4 を本番既定のまま維持（推奨）／9.7 へ |
| D-11 | 生レコードの保持期間（30 日案。E-1 の回答に依存） | 回答後に確定 |
| D-12 | KEK（署名鍵の暗号鍵）の運用: Secret Manager に置く／ローテーション手順／エージェント登録手順（`/v1/agent/register` と管理操作） | 第 5 段階の前に設計して承認（G-3） |
| D-13 | **ML の品質ゲートの数値閾値**（ECE・logloss・Brier・市場ベースラインとの比較・最小サンプル数 等）。現在は**既定値を持たず、設定が無ければ REJECTED（fail closed）**。閾値は未承認で、実装は暗黙の閾値を持たない（D-168） | 承認された閾値を設定ファイルで与える（実データの検証 G-ML-3 の後） |
| D-14 | **モデル署名鍵の運用**: 秘密鍵（`MODEL_SIGNING_KEY`）の置き場所（Secret Manager）・ローテーション・検証用公開鍵（`MODEL_VERIFY_PUBKEY`）の配布。REJECTED の通知と終了コードの扱い（D-170） | 第 5 段階の前に設計して承認 |
| D-15 | **モデル・データセットの保持期間と掃除**（CANDIDATE/REJECTED が増える。ml-train は削除しない設計） | 保持期間を決めて掃除の手順を設計（第 5 段階） |
| D-16 | **Public 化の最終判断**（Visibility 変更）: GitHub 側の設定確認・手動設定（CI_AND_IMAGES §9）と E-6（ソース公開への JRA-VAN 回答なし）のリスクを承知するか | ① 回答が得られるまで Private のまま ② リスクを承知して Public 化（推奨設定を適用してから）。Claude は Visibility を変更しない（D-180） |
| D-17 | ~~監査 E・B・D の恒久設計 V5~~ → **承認済み（2026-10-29）・実装済み**（D-218/D-219。通知内容の保存形式は構造化 JSON、`app` に `race_timing_notice` の SELECT）。残り: **CI（MySQL 8.4/9.7・JDK 25）での検証**と、本番 DB への V5 適用（**未承認**。T5.0 / 第 5 段階） | 解消（CI 未検証） |
| D-18 | ~~発火の確定後・送信前に落ちた場合~~ → **取りこぼしを承認（2026-10-29）**。自動再送しない（D-220）。将来のダッシュボードで見られる情報は D-220 | 解消 |
| D-19 | ~~データ不足で評価できなかったレースの終了状態~~ → **FAILED・exit 1・警告で承認（2026-10-29）**（D-221） | 解消 |
| D-20 | ~~T3.9 の設計の確認~~ → **回答済み（D-224）**: Q1 V6 は方針のみ承認（SQL は追加レビュー後・今は実装しない）、Q2 承認、Q3 保留（T1.20 後）、Q4 除外を維持し件数と率を報告、Q5 承認。**残り**: V6 の最終 SQL の再承認（NULL 正規化の生成列・新表の GRANT: docs/T39_DESIGN.md §9）、払戻の単位・同着・返還・取消の確認（旧実装 + JV-Data 仕様。T1.17） | V6 の再承認・E 項目

### T1.20 の具体的な手順（まだ何も取得していない。取得済みとは扱わない）
| # | 取得するもの | 手順（利用者のローカルで。実データ・パスワード・利用キーは貼らない） | 報告する集計値 |
|---|---|---|---|
| 1 | 旧 SQLite の所在 | `config.yaml` の `db.path`（または既定の `data/*.db`）を確認 | ファイルのサイズ・最終更新日（中身は貼らない） |
| 2 | ML が実際に使われていたか（H1） | `sqlite3 <db> "SELECT prob_source, COUNT(*) FROM signals GROUP BY 1;"` と `"SELECT model_type_used, COUNT(*) FROM race_evaluations GROUP BY 1;"` | 2 つの表（種別ごとの件数） |
| 3 | 実 `config.yaml` の値（D-016: 関数既定との食い違い項目） | 食い違い項目（MIGRATION_MATRIX の D-016 一覧）の**値だけ**を抜粋。Webhook URL・キーは伏せる | 項目名と値の表 |
| 4 | `*.pkl` の所在 | `models/` などを検索し、ファイル名・サイズ・更新日・どのモデル種別かを記録（**pkl を信頼できない入力として扱い、ここでは読み込まない**） | 一覧（ファイル名・サイズ・日付）。変換は T3.6 |
上記が揃うまで、GD10（旧関数既定値の突合）と H1 は**未実施のまま**。

## E. 公式確認待ち
| ID | 内容 | 状態 |
|---|---|---|
| E-1 / COMP-3 | **Q1**: 本人専用の非公開クラウド DB への送信・保存・加工（生レコード含む）、保管期間・削除義務 | 未確認。**規約だけでは断定できない**。回答が来るまで**実データをクラウドへ投入しない** |
| E-2 | **Q2**: 本人専用ダッシュボード／本人専用 Discord 通知（オッズ・EV・買い目）の可否と、通知に含められるデータの範囲 | 未確認 |
| E-3 | **Q3**: 自作取得ソフトを本人の PC だけで使うときの sid 登録の要否、32/64bit 併用時の利用キーの扱い | 未確認 |
| E-4 | SDK 5.0.0（64bit）に同梱の JV-Link インターフェース仕様書・JV-Data 仕様書の版（4.9.0.1 のままか） | 未確認（公式回答なし） |
| E-5 | **T1.17 の仕様確認項目 12 件**（「通常データのサーバ保持 1 年・速報 1 週程度」は二次情報で、公式の確認はこの項目に含まれる）（JVOpen option、JVRTOpen、JVRead、JVGets、0B14 の TC/AV/JC/CC/WE、YSCH の YS、RACE の RA、データ区分 0・9、取得日時・作成日時の規則、速報の保持期間、取消・変更・延期の表現、レコード長とバイト位置）。表は JVLINK_DESIGN §12.2 | **すべて未確認**（公式仕様書 PDF に到達できていない）。確認できた項目から仕様書名・版・節/ページを記録する。**実データのレコード形式のパーサは未実装（保留 PENDING）** |
| E-6 | 規約の**全文**（第7条・第9条）と最新の改定日（R18 の「2024-12-11 の改定」は個人ブログの報告で未確認） | 利用者が jra-van.jp/info/rule.html を直接確認（D-1 と同時） |
| E-7 | **ソースコードの Public 公開**が JRA-VAN の規約・著作権上問題ないか（JV-Link を呼ぶ `agent/` のコード、仕様書由来の可能性がある記載〔JVLINK_DESIGN §1.4。位置は削除済み、履歴には残る〕）。運用要件（本人 1 名・私的）とは別の論点 | **未確認（公式回答なし）。許可を得たものとは扱わない**。Public 化はユーザーの判断（D-180、DATA_SOURCE_COMPLIANCE §0.1） |

### 規約確認と、実データのクラウド投入の確認（別項目として管理）
| ID | 項目 | 状態 |
|---|---|---|
| COMP-1 | 規約本文（第7条・第9条）の確認 | **部分的**: 条文の構造は検索抜粋で確認（A-5）。全文は未読（E-7） |
| COMP-2 | 本人 1 名・私的・非営利の利用が第7条の例外（本人が自ら個人として私的に使用）に当たるか | 条文の構造上は当たると**読める**（法的助言ではない）。クラウド保存の可否は別（COMP-4） |
| COMP-3 | クラウド保存・生データ保管・Discord 通知の範囲（E-1・E-2） | **未確認** |
| **COMP-4** | **実データをクラウドへ投入してよいことの確認** | **未確認。公式回答が得られるまで実データを投入しない**（ゲート） |

## F. 実機・実環境での検証待ち
| ID | 内容 |
|---|---|
| F-1 | **T3.0**: Windows 実機 PoC（G-AGENT-1〜6）。**未完了**（T3.5 で `agent/poc/` と手順 `agent/POC.md` を用意。Mock の合格のみ。**実機では何も実行していない**）。実機でだけ確認できること: COM 生成、JVRead/JVGets のバイト列、蓄積系/速報系の取得、実 COM のハング、DPAPI、タスクスケジューラ・スリープ復帰。手順は SDK 5.0.0 対応に更新済み（JVLINK_DESIGN §12.3）。64bit の動作（COM 生成、`JVRTOpen=-413` の報告への対処）は実機で確認するまで「動作する」と書かない |
| F-2 | 実 GCP での検証ゲート（G-OPS-1〜9、G-AUTH-1、G-APP-1、G-JOB-1、G-DB-1/5/6/7）。**本番 GCP は Stage 5 まで保留**（Terraform 変更・作成・apply はしない） |
| F-3 | 本 PR の CI（MySQL 8.4 / 9.7 の統合テスト、JDK 25）。ローカルは MySQL 8.0.46 での事前確認のみ（8.4/9.7 の合否ではない） |
| F-4 | 実データでの ingest の検証（COMP-4 のゲートが先。合成データだけで進める） |
| F-5 | **T1.20 / G-ML-1・G-ML-2（ML の実データ検証）**: 実 pkl の所在・次元・`feature_names`、旧 `signals.prob_source` の分布（H1）、旧記録値との一致（legacy パイプライン）。**pkl はクラウドで読まない**。変換ツールの仕様とローカル手順は ML_DESIGN §11.9（**実装・実行は未実施**）。合成モデルのゴールデンは実 pkl が無くても実施済み |
| F-6 | T3.6-A のローカル検証は JDK 21・MySQL 8.0.46。**JDK 25 と MySQL 8.4/9.7 の合否は GitHub Actions の結果だけが正**（D-163） |
| F-7 | **T3.8 のローカル検証は JDK 21・MySQL 8.0.46（ポート 3307。`JRABOT_TEST_MYSQL_EXTERNAL`）**。bot-run の MySQL 統合試験（`BotRunOrchestratorIntegrationTest`〔最小権限の `bot` ユーザー〕・`BotRunRepositoriesIntegrationTest`）はローカルで成功。**JDK 25・MySQL 8.4/9.7・Docker イメージ・GitHub Actions は未実施**（無料枠不足のため PR を作らず CI を起動していない）。`MlTrainInteropIntegrationTest`（`ML_TRAIN_PYTHON` が要る）と `WiringIntegrationTest` のサーバ版検査（8.4 を期待）はローカルでは失敗する（既知。環境の問題） |
| F-8 | **Cloud Run Jobs での SIGTERM**（猶予時間 G-OPS-4、`sun.misc.Signal` のハンドラが実コンテナで効くか、14 時間の Job タイムアウト G-OPS-5）。ローカルでは `USR2` の実シグナルでハンドラの経路を確認しただけ |
| F-9 | **監査・V5 のローカル検証は JDK 21・MySQL 8.0.46**。`MlTrainInteropIntegrationTest`（5 件）・`ml` の pytest 128 件・旧 Python との golden（32 ケース/994 呼び出し、Kotlin 対応 31 ケース/988 呼び出しが全一致）は 2026-10-29 に実行して成功（`ML_TRAIN_PYTHON` に `ml/requirements.txt` を入れたローカル venv）。**GitHub Actions（JDK 25・MySQL 8.4/9.7・Docker・required check `ci gate`）は未実施。T3.8・V5 は CI で未検証**。V5 の 8.4/9.7 での適用も未確認 |

## G. 後続タスク待ち
| ID | 内容 | 予定 |
|---|---|---|
| G-1 | ~~Windows 取得エージェント本体~~ → **T3.5 で実装（C-11）**。残り: 実機検証（F-1）、公式仕様の確認後に実取得のゲート（`jvlink/spec.py`）を外す、P-1/P-2 の承認（D-5/D-6） | T3.5 完了 / 実機 |
| G-2 | `POST /v1/agent/register`（登録コード）は**保留**（管理者認証 T4.1/T4.4 が前提。設計は CONTRACTS §3.3.2。それまでは `agent set-credential`）。`plan`・`mode`・`update` は T3.5 で実装済み（C-13）。**自動更新の公開配布・署名鍵の運用は未実装・未決** | T4.4 |
| G-3 | 署名鍵 KEK の発行・保管・ローテーション、エージェント登録の運用手順 | 第 5 段階の前（D-12） |
| G-4 | 最終接続の GCS への写し（`HeartbeatMirror` の GCS 実装。今回は `SKIPPED` を返す未配線の実装） | `:cloud-gcp` の後続 |
| G-5 | 実データ形式（JV-Data）のレコードパーサ（E-5 の確認後に `RecordParser` を追加） | T1.17 後 |
| G-6 | reconcile（`STALE` イベントの生成、YSCH から消えた開催日の扱い、結果欠損の補完）、MONITORING 以降の履歴の種類（V3 でさらに ENUM を足すか）、bot-run の checkpoint・SIGTERM | T3.8 / T3.9 |
| G-7 | ~~ML（FeatureBuilder・ProbabilityPipeline）~~ → T3.6-A（C-14）。~~ml-train・`export-dataset` の配線・`DatasetStore` への manifest 保管・ml-train イメージ・pkl→JSON 変換の実装~~ → **T3.6-B で実装（C-20〜C-26。合成データのみ）**。残り: 実データでの検証（F-5・G-ML-1〜3）・品質ゲートの閾値（D-13）・Cloud Run Job 化（第 5 段階） | 第 5 段階 / T1.20 |
| G-8 | ~~Discord 通知（BACKFILL では通知しない）~~ → C-27/C-28/C-31（bot-run が `NotificationOrigin` を `--trigger` から渡す） | 解消（T3.8） |
| G-9 | **T5.0（必須の前提）**: infra の文書を web CONTRACTS に追従（I-H1〜I-H9 ＋ 今回の追加: V3 の GRANT、環境変数 `AGENT_KEK_KEYS`・`AGENT_KEK_ACTIVE_KID`・`INGEST_*`、ingest の Secret、`app` の `race_schedule_event` INSERT） | 第 5 段階の前 |
| G-10 | 本番 release-manifest（`feature_schemas` を含む v2 の本番生成は第 5 段階のゲートが揃うまで禁止）、~~ml-train イメージ~~（T3.6-B で追加。レジストリへの push・digest は第 5 段階）、branch protection の required checks（`ci gate`。ml-train ジョブは gate に統合済み） | 第 5 段階 |
| G-11 | Spring Boot 4.2.0 の GA 後の移行（VERSIONS §7） | GA 後 |
| G-12 | レート制限（429）。契約にはあるが未実装。認証前の要求は本文 1 MiB までの読取りとエージェントの 1 件検索（DB）で、単一利用者・Cloud Run の同時実行数の制限で当面は許容 | T3.5 / 第 5 段階（Cloud Armor・同時実行数の設定と合わせて） |
| G-13 | **ビーコンの NORMAL の再署名**: infra は NORMAL を start 時にしか書かない。エージェントは『有効と確認してから 6 時間』を鮮度にしている（D-148）が、古い NORMAL（署名は有効）の再生を seq だけでは防げない（STOPPED→NORMAL の seq は増えるので巻き戻りは拒否できる。同じ seq の再生は防げない）。infra が NORMAL を定期的（例 1 時間）に再署名し `validUntil` を付けると窓を縮められる | T5.0（infra に確認） |
| G-14 | **T5.0 への追加（T3.5 分）**: エージェントの配布（C# の自己完結 exe〔`dotnet publish -r win-x64 --self-contained`〕・コード署名。D-261）、ビーコン公開鍵の同梱と鍵ローテーション手順、`INGEST_VERIFIED_FETCH_SPECS`・`INGEST_PLAN_ITEMS_JSON`・`INGEST_UPDATE_MANIFEST_PATH`、`/v1/agent/mode` が未認証であること（Cloud Armor・同時実行数で保護: G-12）、トークン期限の運用、更新 manifest の署名鍵（オフライン） | 第 5 段階の前 |
| G-15 | 取得計画の実際の生成（開催カレンダー + 欠損検知から `plan` を作る）。現在は運用者が与えた項目のうち検証済みのものだけ。YSCH の対象範囲は key の 16 桁（仮の表現。確認後に正式化） | T3.8 |
| G-16 | 実データ形式（JV-Data）のパーサ（サーバ）と、`SpecRegistry` の確認の記録（T1.17 の 12 項目。確認が 0 件のうちは実取得は始まらない） | T1.17 後 |
| G-17 | **T3.6-B / T3.8 への引き継ぎ（ML）**: ~~①bot-run の開始時に `ModelRunPin.capture`、使った `model_id` を `race_evaluations` に保存、`fallback_reason`・アラート（ML 有効で市場確率のみ）②`MlInference` を判定（`WinDecisionEvaluator` の `MlProbabilityInput`）へ配線~~ → C-32 ~~③`FeatureSource`・`DatasetSource` の `:persistence` 実装~~ → C-20 ~~④`ModelStore`/`DatasetStore` の GCS 実装と manifest の保管~~ → C-22（実 GCS は未検証: G-19）~~⑤馬マスタの時点履歴~~ → C-21（履歴の追記を取込側がいつ行うかは T3.8 以降）~~⑥`format_reason_from_features`（通知文。T3.7）~~ → C-29⑦`SnapshotsNewestFirst` など判定側の既存課題は変更なし ~~⑧release-manifest の `feature_schema_hash`~~ → C-24 | T3.7 / T3.8 |
| G-18 | **ML の未承認の改善案は未実装のまま**: v2 の本番有効化（旧新比較・バックテスト G-ML-3・閾値の再検討・承認の後）、H4 の二重ブレンド解消、H10 の Kelly 係数 enum 化（`kellyMultiplierBySourceEnum`）、`ensemble_beta` の自動調整、`class_weight` を使わない学習 | T1.21 / T3.9 |
| G-20 | **Discord の 2000 文字の数え方**（コードポイントか UTF-16 単位か）が公式資料で確認できなかった（このセッションから discord.com へ接続できず、検索結果は 429 の仕様のみ）。既定は余裕のある UTF-16 単位、1900 の安全幅は維持（D-184）。絵文字を含む本文が実 Discord で切れないかは**実送信で確認**が必要 | 実 Discord の検証（ユーザー承認・COMP-4 の後） |
| G-21 | **T3.8 への引き継ぎ（通知）**: ~~①〜⑤は T3.8 で実装 → C-31~~（元の記述: ①`SignalNotificationDispatcher.dispatchRace` を呼ぶ（`NotificationOrigin` は `--trigger` から。D-186 の案を確定。判定不能は UNDETERMINED）②`NoticeViews.from(DecisionSignal)` と出走表の馬名（`legacyBamei` と同じ決め方）から `RaceNoticeBatch` を作り、`PointInTimeFeatureBuilder.reasonFacts` の理由文を `reason` に入れる ③SIGTERM ハンドラで `StopFlag.request()`、開始時に `recoverStaleClaims`（リース保持中）④`ErrorReporter` を呼ぶイベント（通知失敗・設定エラー・ML の市場確率へのフォールバック・DB 不達など）の決定（D-14 の REJECTED 通知は未承認のまま）⑤**claim 後に確実に送れなかった（NOT_SENT）Signal も SEND_UNKNOWN になる**（状態を戻す API が無い）。『解放して次のサイクルで再試行』が必要なら契約と `SignalWriter` の変更（`CLAIMED → UNSENT` の許可）を提案して承認を得る ⑥管理画面（Stage 4）で SEND_UNKNOWN を表示）**残り: ⑥だけ（Stage 4）** | Stage 4 |
| G-22 | **T3.7 の未検証**: 実 Discord への送信（429 の実応答・Cloudflare の挙動・User-Agent の扱い・`allowed_mentions` の実挙動・実 Webhook の URL 形式）、Cloud Run からの到達性、Secret Manager からの注入（`DISCORD_MENTION_USER_ID` の追加は T5.0）、bot-run と MySQL を含む通知の統合試験（T3.8） | ユーザー承認後 / Stage 5 / T3.8 |
| G-19 | **T3.6-B の後続**: ①ml-train は `file://`（GCS ボリュームのマウント）のみ。`gs://` の直接読書きは未対応（D-171。Stage 5 で決める）②GCS アダプタの実 GCS・実認証での検証（Stage 5。本番 GCP に接続していない）③Dependabot の pip `/ml`（numpy は除外）④履歴の追記・過去分の PIT は履歴が貯まるまで不完全（C-21）⑤REJECTED の通知（D-14）| 第 5 段階 |
| G-23 | **bot-run の運用値と通知方式**: ~~通知方式~~ → **『評価タイミングごとに最大 1 回』で確定**（D-204。再起動での重複も防止）。~~旧の `current_snapshot_minutes` の癖~~ → 修正後/旧互換を区別（D-205）。**残り**: ①`BOT_RUN_*` の運用値は**未確定で既定値なし**。`bot/config/bot-run.sample.env` の値は開発・合成データ・CI 用の**暫定サンプル**（本番の既定ではない）②判定の設定は `legacy_function_defaults`（旧関数の既定値）しか選べない。実 config.yaml の運用値は T1.20 の後にユーザーが承認してから取り込む。判定閾値などの本番設定は未確定 | ユーザー判断（T1.20） |
| G-24 | ~~`hit_rate_min_ev_schedule`~~ → **移植済み・明示設定のみ有効**（D-206。C-34）。**`adaptive_relaxation`（無通知が続くと EV 閾値を段階的に緩和）は実装・有効化していない**。T3.9 のバックテスト後に再判断する | T3.9 の後（ユーザー判断） |
| G-25 | **T3.9 への引き継ぎ（bot-run の残り）**: ①結果・払戻・決済の反映と `race_schedule.status`（MONITORING → OFF → RESULT_PENDING → SETTLED）・`race_day.status`（RUNNING → SETTLING → COMPLETED / INCOMPLETE）の更新。それまで T3.8 の bot-run は発走後に即終了し、完了済みの日も窓の中では calendar-check が再起動する（無害だが無駄。D-190）②reconcile（`--task=reconcile` は引き続きスタブ。INCOMPLETE の回復、結果欠損の補完）③`INCOMPLETE_AT_DEADLINE`（時刻不明・信頼できないレースが残った run）の回復④`race_evaluations.settled_at/hit/payout_used` の更新（`bot` は UPDATE 権限あり）⑤バックテスト・成績集計 | T3.9 |
| G-26 | **取込側（ingest/agent）の履歴の追記**: `horse_master_history`（point-in-time の馬マスタ）は取込がいつ版を足すか未実装（C-21 の続き）。履歴が貯まるまで過去分の PIT は不完全で、累積値は空（`MASTER_NEWER_THAN_AS_OF`）になり得る。ML の精度に影響し得る（実データでの検証は F-5） | 取込の後続 / T1.20 |
| G-27 | **未使用の `ModelRegistryReader` の失敗の扱い**: レジストリや保管先の障害は `MODEL_STORAGE_ERROR`（理由つきの市場確率）として run を止めないが、障害が続いても通知は run あたり 1 回。運用で見逃さないための閾値（何 run 続いたら重大にするか）は Stage 5 の監視（T5.6）で決める | T5.6 |
| G-28 | **天候・馬場の履歴**（監査 G3。D-214）: 過去のレースの asOf 時点の値を復元できない。観測時刻つきの履歴表と WE 等のパーサが要る（DB 変更の承認が必要）。それまで該当行は学習・バックテストから除外される（件数は manifest の `skipped.conditions_newer_than_as_of`）。T3.9 のバックテストは除外件数を報告すること | T3.6 以降・承認後 |
| G-29 | **保留レコードの再適用の拡張**（監査 G2。D-213）: 結果・払戻など T3.9 の依存の理由コード、保持期間（30 日案）を過ぎた保留の扱い、実データ形式のパーサ追加後の再解析（G-5） | T3.9・T1.17 後 |
| G-30 | ~~V5 の実装~~ → **実装済み（D-218/D-219。CI 未検証）**。残り: 本番 DB への適用の承認、ダッシュボードで通知履歴を見る画面（T4.x。認証・認可を通った本人だけ） | 本番適用: 第 5 段階 |
| G-31 | **G3 の運用上の帰結（D-214/D-226/D-228）**: 遅れて取り込んだ過去レースは天候・馬場の as-of 時点の値を確認できず学習・バックテストから除外される（`skipped.conditions_newer_than_as_of`。**更新時刻が不明〈null〉の行も同じ**）。除外は安全側で維持。将来: 観測時刻つき履歴（LIVE のみ復元に使う。BACKFILL は使わない。取込時刻を観測時刻にしない。架空の履歴を作らない）の設計は docs/ML_DESIGN.md §15、天候・馬場なしスキーマとの比較は T39_DESIGN §8.1。**未解決**: WE レコードの発表時刻の意味（T1.17）、運用開始前の履歴は永久に無い、履歴表の DB 変更の承認、実データでの除外率を見ての選択 | T1.20・T3.6 以降・ユーザー判断

| G-32 | **PR #30 の独立レビューの残り（D-230）**: **M2** 確定済みで送れなかった通知（確定直後の checkpoint 保存失敗・強制終了など）が運用者に伝わらない → 再起動時に『確定済みで Signal が UNSENT のまま』を検出して 1 回だけ警告（再送しない）。低: L1 Discord の `sendAllowed` の `leaseProbe.holds()` が例外処理の外、L2（推測）表示値 NaN/Inf で 1 頭のためレース全体が失敗、L3（推測）再保留行の理由が更新されない、L4（推測）保留の再適用が ACK 前に同期で全件走査（索引は V6 候補）、L5 特徴量等の基準時刻が観測時刻でなく評価時刻、L6 `firedCenti` が `notice_json` まで読む、L7 V5 の FK と将来の retention、L8 `R__grants.sql` は V5 の表に依存（`-target=4` で失敗）。M1 は修正済み **【2026-11-01 更新】M2 は『確定したが送れなかった通知』を起動時に検出して警告する実装済み（再送はしない: D-18）。NOT_CONFIGURED 等の正当な未送信でも警告が出る点は G-36** |
| G-33 | **T3.9 ② 以降の未着手**: 結果・払戻の取込（ingest）、`RaceStatusUpdater`、`ResultSettler` と bot-run の結果待ち、`reconcile`、成績集計、バックテスト。①（決済の計算）は D-231 で完了（GD11 全一致）。V6 は未承認のため適用対象の Flyway に入れていない **【2026-11-01 更新】結果・払戻の取込・結果待ち・reconcile・retention・紙上決済（単勝/ワイド/三連複）・バックテスト（計算）は実装済み（D-231〜D-246）。残りは G-36 と COMPLETION.md の C/B** |
| G-34 | **LIVE の run が LIVE 以外の run に塞がれる（M1 再確認の指摘・中・運用）**: 実行権（リース）は日付単位。BACKFILL/RECONCILE/`--trigger` 省略（UNDETERMINED）の run が動いている間、LIVE の bot-run は `ALREADY_ACTIVE` exit 0 で起動せず、警告も出ない（`BotRunOrchestrator`）。選択肢: ① 起動拒否時に警告を出す ② 非 LIVE の run を別のリース種別にする（V6 候補）。低: 旧 origin なし checkpoint の扱い、UNDETERMINED/ABORTED 後の試験の追加。要判断（推奨: ①を T3.9 の中で実装し、②はユーザー判断） **【2026-11-01 更新】LIVE が非 LIVE の run に塞がれたときの警告を実装済み（D-232）** |
| G-35 | **T4.1 レビューの残り（D-236）**: A2 IAP の JWT の `email_verified` 未確認 → infra で IAP の許可主体を Google アカウントのみにし、実 JWT で確認（G-OPS-7）／A5 実 MySQL の利用者表を通す `/api` 試験と DB 障害時の挙動（T4.5）／A6 401 の `WWW-Authenticate`（任意）／P3 集計は単勝専用（ワイド・三連複の決済は買い目〔通知スナップショット〕から別入力で）。高の指摘なし **【2026-11-01 更新】実コンテキストの認可・CSRF 試験を追加（D-246）。IAP の実 JWT での確認は B** |
| G-36 | **D-246 の未対応（記録）**: requestId を応答/ログに出さない／combo_odds・予定履歴に `(tenant_id, 時刻)` の索引が無い（最後の DELETE が全走査。開催時間外に実行。索引は V6 の範囲）／retention の `shouldStop` は SIGTERM のみ／組オッズ削除後の古いバックテストの除外を数えない／`winUnsettled`/`comboUnsettled` は時点ごとの重複を含む／バックテスト ML の学習期間と評価期間の重なり検査（ML 未配線）／M2 の警告の理由の区別／レース単位の状態履歴は V6 待ち（任意。`V6_PROPOSAL.md`）／エージェント失効の管理操作は未実装／バックテストの実行基盤（運用値の設定を持つ場所）は未決 | 後続 |
