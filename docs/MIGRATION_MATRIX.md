# MIGRATION_MATRIX（T1.1）

調査日: 2026-10-22 / 参照元: `puttun777/jra-bot` main `f7dbf69`（65 commits, Public, MIT）/ 調査者: Claude（読み取りのみ）。
分類: **K**=Kotlin移行 / **P**=Python等で維持（別プロセス）/ **S**=段階移行（暫定維持→後で移行）/ **W**=Windows専用エージェント（K or P で実装、クラウド外）/ **D**=設計上置換（機能は新方式で実現、コード移植なし）/ **X**=移行しない（理由を記載）
優先度: P0=必須の中核 / P1=主要機能 / P2=あると良い / P3=後回し可。
行数は参照元の概算。**精査が浅い箇所は「要精査」と明記**し、第2/3段階の着手時に再確認する。

## 1. 機能マトリクス
| ID | 機能 | 実装ファイル | 概要 | 依存 | 分類 | 移行方法と理由 | 主なリスク | 優先 | テスト方法 |
|---|---|---|---|---|---|---|---|---|---|
| F01 | JV-Link 取得（COM 呼出し） | `data/jvlink_provider.py` (2.7k) | `win32com` で JVInit/JVOpen/JVRTOpen/JVRead/JVSkip/JVClose。蓄積系(RACE, RCOV/RCVN)と速報系(0B12,0B15,0B30,0B31,0B33,0B35)。`lastfiletimestamp`(14桁)で差分取得。当日/翌日レースを複数 dataspec からマージ。`race_days_back` で取りこぼし防止 | Windows, JV-Link 32bit COM, pywin32, SID, 利用規約 | **W** | Linux では不可。ユーザーPC上の取得エージェントとして分離し、**生レコード(Shift_JIS固定長) or 構造化JSON**を認証付き ingest API へ送る。エージェントの言語は T1.2 で決定（COM 利用なら .NET/Python 32bit が有力、Kotlin/JVM は 32bit JRE + JACOB 等で非現実的） | COM 32bit 制約、規約上クラウド保管の可否、PC 停止時の欠損、2.7k 行の巨大クラス | P0 | 録画した生レコードのフィクスチャ(実データ非含有の合成/匿名化)でパーサ比較 |
| F02 | 固定長レコードのパース（RA/SE/UM/オッズ/払戻/結果） | 同上 `_parse_*`, `_slice_sjis` | Shift_JIS バイト位置で切出し。Race/OddsData/RaceResult/HorseMaster を生成 | F01 | **K**（パーサをサーバ側に置く案を優先） | エージェントは生レコード転送に徹し、パースは Kotlin（テスト容易・Linux 実行可）。**代替**: エージェント側パース。T1.2 で決定 | 桁位置・文字コード・欠損の再現性 | P0 | 旧実装パーサと同一入力の出力比較（ゴールデン） |
| F03 | 開催日/発走予定の決定 | `jvlink_provider.get_today_races`, `run_daemon.py` L770-800 | **専用の開催カレンダーなし**。取得できた Race の `date`==今日（`include_tomorrow` で翌日含む）で「開催日」とみなす。土日祝判定はしていない（要件と整合）。代替/中止/延期/時刻変更の検知ロジック**は見当たらない** | F01, `races.post_time` | **K（新規設計）** | 専用カレンダー + 変更履歴 + 手動補正を新規実装（T1.3）。参照元は挙動の仕様源にならない | 変更検知が未実装＝新規要件。ソース(JV-Link 速報の発走時刻変更/出走取消系)の確認要 | P0 | シナリオテスト（通常/平日/代替/中止/延期/時刻変更/取得失敗/手動補正） |
| F04 | 時刻ユーティリティ | `utils/time.py` | `parse_post_time`(分≥60/時≥24の繰上げ対応)、`minutes_to_post`、ポーリング間隔(>30:300s, >5:60s, else 30s) | なし | **K** | domain に純関数として移植。**`datetime.now()` が naive でマシンTZ依存**→ Asia/Tokyo の `Clock` を注入 | コンテナ(UTC)で発走判定がずれる最重要リスク | P0 | 境界値(12:60, 24:xx, 5/30分ちょうど, 日跨ぎ, DST なし) の表駆動テスト |
| F05 | EV・期待回収・期待利益 | `ev/ev_calc.py` | `EV=p*odds-1`、p<=0/odds<=0 は -1、期待利益=stake*EV | なし | **K** | domain。純関数。数値は `BigDecimal` でなく **Double 同等(IEEE754)** で旧実装と一致させる（丸めは表示層のみ） | 浮動小数の差異 | P0 | 旧実装出力との許容誤差比較(1e-12)＋境界 |
| F06 | Kelly・投資額 | `ev/ev_calc.py` `calc_kelly_fraction`/`suggest_bet_amount` | f=(pb−q)/b、上限 `max_fraction`、min/max クリップ、戦略文言 | F05 | **K** | domain。bankroll は「点」単位 | min_bet 未満→見送りの仕様差（docs/KELLY_* に経緯あり、要精査） | P0 | `scripts/test_kelly_bet_amount.py` 相当 + ゴールデン |
| F07 | 勝率推定・正規化 | `ev/probability.py` | 1/odds、レース内正規化、±10% 補正制限(correction_limit)後に再正規化、ML 可用時は ML 優先・失敗時フォールバック | F10(ML) | **K** | domain。モジュールグローバル `_use_ml_model` のキャッシュ状態は廃止し、明示的な戦略注入に | ML 失敗時の暗黙フォールバックをどう扱うか（ログ・メトリクス化） | P0 | 数値ゴールデン、頭数1/オッズ0/全0 の異常系 |
| F08 | ワイド・三連複の確率/推定オッズ/買い目 | `ev/bet_strategy.py` (803行) | 推定オッズ(区分係数)、Plackett-Luce 的組合せ確率(`plackett_luce_alpha`)、流し/BOX、確率の min/max クリップ、市場比上限、`ev_shrink_factor`、`best_only`/`proportional_ev` 配分、実オッズ必須(`require_real_odds_for_combo`) | F05-F07, 組合せオッズ(F02) | **K** | domain。設定項目が多いので型付き設定クラスへ。**ロジックの全分岐は第3段階で精読**（本調査では signature のみ確認） | 分岐の多さ、設定の組合せ爆発、暗黙デフォルトの差（関数既定値 vs config 既定値が異なる: 例 `ev_shrink_factor` 0.7 vs 0.55） | P0 | 設定プリセット×合成レースのゴールデン |
| F09 | 購入判定・シグナル生成 | `ev/decision.py` (1,012行) | `evaluate_odds`（~40引数）: 急落(`check_odds_drop`, 古いスナップ比 >threshold)、人気急上昇、EV 閾値、priority_mode(edge/hit_rate)、hit_rate_min_ev(時間帯別 far/mid/near)、単勝オッズ上限/確率下限/ratio 下限、確率クリップ、見送り理由集計、複数賭式 | F05-F08, スナップショット履歴 | **K** | domain の `DecisionEngine`。引数群は `DecisionConfig`/`MarketContext` に構造化。見送り理由は enum 化し race_evaluations に保存 | 引数の暗黙結合・巨大関数、`recent_snapshots` の並び順（新しい順で `[-1]` が最古）の取り違え | P0 | 旧実装との入出力ゴールデン（シグナル有無・金額・理由コード） |
| F10 | ML モデル（推論） | `ml/model.py`, `ml/predict.py`, `ml/features.py` | **numpy のみのロジスティック回帰**（特徴: odds, popularity, log_odds, inv_popularity, 交互作用 + 拡張）、Platt 校正、市場ブレンド(logit混合)、alpha(オッズ無し,馬/コース/血統ハッシュ特徴)・calibration・ensemble・legacy。mtime によるキャッシュ再読込 | numpy, pickle, DB(特徴量取得) | **K（推論）** | モデルが線形(sigmoid)なので **重みを JSON/NPZ にエクスポートして Kotlin で推論**可能。pickle は引き継がない(D-008)。特徴量生成(ハッシュバケット `_hash_bucket/_hash_sign`)の再現が鍵 | 特徴量の数値一致(ハッシュ関数: 要確認)、旧 pkl が手元にしか無い、ensemble_beta/tune 結果の管理 | P0 | 同一特徴量→同一確率の比較(許容1e-9)、モデルJSONのスキーマ検証 |
| F11 | ML 学習・データセット・チューニング | `ml/train.py`, `dataset_builder.py`, `scripts/{train,train_ml_model,build_dataset,retrain_models,tune_params,evaluate_model,diagnose_training_data}.py` | DB→特徴量→parquet(pandas/pyarrow)→勾配降下(lr 0.1, 800 epoch, L2)→Platt→`*_latest.pkl`。`retrain_models` が一括。`tune_params` が ensemble_beta/半減期を最適化 | pandas, pyarrow, numpy, DB | **P（学習）** | 学習は Python 維持（オフライン/手動、または月次ジョブ）し、**出力を非 pickle のモデル成果物(JSON/NPZ+メタ)に変更**。学習と推論を分離(要件)。将来 Kotlin 化は P3 | 学習環境の再現、MySQL からの読み込みへ切替、32bit 制約は消える（クラウド/新PC は 64bit） | P1 | 小データで学習結果の再現性、成果物スキーマ検証 |
| F12 | 自動再学習（常駐内） | `run_daemon.py` L505-750, 965-990 | 新規結果件数/間隔で非同期学習、`legacy` のみ対象 | F11 | **D** | bot-run 内では行わず、**独立の ml-train ジョブ**へ（STOPPED/コスト管理のため）。トリガは手動 or 週次 | 学習中のモデル差替えの原子性 | P2 | モデル成果物の原子的切替テスト |
| F13 | Discord 通知 | `notify/discord.py` (694行) | Webhook、2000字制限の分割(`_split_content`)、429 の `Retry-After` 解釈、リトライ(3回/5s)、メンション(`mention`, `mention_user_id`+allowed_mentions)、グルーピング、重複買い目除去、エラー用 webhook(`error_webhook_url`) | requests, Webhook(秘密) | **K** | `:bot` に `Notifier` 抽象 + Discord 実装(Spring `RestClient`)。Webhook は Secret Manager。送信済み判定(`signals.sent`)を冪等化(送信前に claim) | 二重送信/未送信（再実行時）、Discord 側仕様、メンション設定 | P0 | MockWebServer で分割/429/リトライ、メッセージ文面のゴールデン |
|  |  |  |  |  |  | **T3.7 実装済み（2026-10-27）**: 分類 K。Discord クライアントは JDK `HttpClient`（D-182）。旧の自動リトライは 429 と接続前の失敗だけに縮小（D-183）。旧 `mention` は移植しない（D-185）。旧のグループ再送・5xx 再送は移植しない。ゴールデン G8（分割・Retry-After・重複除外・本文・理由文）は旧実装と全一致、HTTP/配送は Fake サーバの単体テスト。**実 Discord は未検証** |  |  |
| F14 | 常駐監視ループ | `scripts/run_daemon.py` (2,010行) | 設定ロード→DB→provider→(補てん情報取込 `ingest_cover_data`)→無限ループ: 当日レース取得→各レース `minutes_to_post` が監視ウィンドウ(開始 240/120 分前〜終了 −5 分)内ならオッズ取得→snapshot 保存→評価→通知→結果取得/決済→自動再学習→sleep(間隔可変)。エラー時 `error_wait_seconds`、`no_race_wait_seconds`。adaptive_relaxation | 全て | **D+K** | 無限ループを廃し、**bot-run（有限ジョブ）**＋内部の有限スケジューラ(発走時刻ベース)に再設計。ロジック部分(F05-F09)は domain へ、オーケストレーションは `:bot` 新規。多重起動は run ロック | 2k 行に埋もれた副作用の取りこぼし(結果の保存→決済→評価の順序)、設定二重定義(watch_start 240/120 がYAML内で重複) | P0 | 録画オッズ列を再生するシミュレーションテスト(旧と同じ通知列になるか) |
| F15 | 結果取得・払戻・決済 | `storage/{repo,bets_repo,evaluation_repo}.py`, `scripts/{settle_bets,settle_evaluations,backfill_results*,fetch_payout_from_jvlink,verify_*}.py` | 結果(着順/単勝払戻)保存、払戻(`payouts`: market,key)保存、paper bet の open→決済(単勝/ワイド/三連複)、評価の決済 | F01/F02, DB | **K** | `:domain` に決済ロジック(純関数) + `:persistence`。冪等(同一 race の再決済で二重計上しない) | 組合せキーの正規化(`_normalize_combo_key`)の差、払戻欠損 | P0 | 既知レースの的中/外れ/同着/取消ゴールデン |
| F16 | DB スキーマ・アクセス | `storage/schema.sql`(15 tables), `db.py`(動的 migrate), `*_repo.py` | SQLite。races, odds_snapshots, signals(24列), results, jvlink_timestamps, horses, race_horses, horse_past_runs, model_metrics, payouts, combo_odds_snapshots, race_evaluations, ticket_evaluations, bets, bet_legs。起動時に `ALTER` 等で動的移行 | sqlite3 | **K** | Flyway V1 を再設計（MySQL）。型・一意制約・FK・インデックスを明示。JSON 文字列列(pedigree_json 等)は MySQL `JSON` か正規化を T1.10 で判断。時刻は文字列保存→DATETIME(6) へ | 既存データ移行（実DBの所在不明＝未解決）、文字列日付/ISO 混在、SQLite の型緩さ | P0 | Testcontainers(MySQL)で DAO、旧SQLiteからの変換スクリプトの件数/合計比較 |
| F17 | 設定管理 | `utils/config.py`, `config/config.example.yaml`, `ui/pages/05_Config.py` | YAML + `${ENV}` 展開、`JVLINK_SOFTWARE_ID`/`DISCORD_WEBHOOK_URL`/`DISCORD_ERROR_WEBHOOK_URL` を env 優先、`validate_config` | python-dotenv | **K** | Spring `@ConfigurationProperties` + 検証(Jakarta Validation)。秘密は環境変数/Secret Manager。可変運用パラメータは管理者のみ DB/設定で変更（監査ログ） | YAML 全キーの網羅漏れ、デフォルト値の食い違い | P1 | 旧 YAML(example) を読み込み新設定と全キー対応表のテスト |
| F18 | Streamlit ダッシュボード（新） | `apps/dashboard/streamlit_app.py`(2k), `pages_evaluations.py` | Model/ML学習状況、Overview、Race Detail、Horse Detail、**Performance(成績集計)**、Signals、Ops/Health（ログtail・PID確認・テーブル件数・取込状態） | Streamlit, sqlite(`ui/db_read*.py`) | **K（UI 再実装）** | フロントは T1.5 で選定。Ops/Health のうち PID/ローカルログは **D**（クラウドでは bot 状態/最終更新/次回実行/Cloud Logging リンクに置換） | 画面が巨大で仕様がコードに埋没、集計 SQL が UI に混在 | P1 | 画面別に旧画面のスクリーンショット/数値と比較、API の契約テスト |
| F19 | Streamlit 旧UI | `ui/app.py`, `ui/pages/01-06` | 初期版のマルチページ(Status, Races, Signals, Odds, Config, Evaluations)。README の起動方法は `apps/dashboard/` 側 | 同上 | **X（機能は F18 に包含）** | 旧版と推定。ただし `04_Odds`(オッズ履歴)、`05_Config`(設定表示)が新版に無い場合は F18 へ取り込む（**要精査**）。コードは移植しない | 取りこぼし | P3 | F18 側で同機能を確認 |
| F20 | 成績集計・バックテスト | `ui/db_read.py` (`calculate_performance_from_bets/signals`), `ev/verification.py`, `scripts/report_performance.py`, `ui/db_read_evals.py`(`calculate_evaluation_performance`), `model_metrics`(brier, logloss, calibration) | 回収率・的中率・EV 乖離、シグナル由来/bets 由来/評価由来の3系統集計、期間/競馬場/賭式フィルタ。**集計ロジックが UI モジュール内（1.2k+0.8k行）** | DB | **K** | domain の集計サービスに抽出（UI から分離）し API で提供。3系統のうち正を T1.10 で整理（`DASHBOARD_PERFORMANCE_V2.md` を参照） | 集計定義の揺れ（signals vs bets）、丸め、母数の定義 | P1 | 既知データで旧集計値と一致（件数/回収率/的中率） |
| F21 | 過去データ/補てん取込 | `ingest_cover_data`, `scripts/{fetch_historical_data(1.7k),ingest_cover_data,ingest_alpha_features,backfill_*}.py` | 過去1年のレース/結果、馬マスタ/過去走/出走表(alpha 特徴量土台)の一括取込 | F01 | **W+K** | 取得はエージェント(バックフィルモード)、保存は ingest API。大量取込は冪等 upsert・チャンク・再開(checkpoint) | JV-Link 取得量/時間、規約、クラウド側の書込量 | P1 | 小期間バックフィルの件数一致、再実行で件数不変 |
| F22 | タイムスタンプ/進捗管理 | `jvlink_timestamp_repo.py` | データ種別ごとの `lastfiletimestamp` を保存し差分取得 | DB | **K**（正本=サーバ） | サーバ側で保持しエージェントが問い合わせ(再開の正本をクラウドに)。エージェント再インストールでも継続 | エージェントとサーバの二重管理 | P0 | 再実行/再開テスト |
| F23 | 定期実行・運用スクリプト | `run_daemon_*.bat/ps1`, `check_daemon_status.py` | Windows 上で 32bit Python 常駐を起動(UTF-8 設定・ログ)。PID/ログで状態確認 | Windows | **D** | Cloud Scheduler + Cloud Run Jobs（クラウド側）、エージェント側は Windows サービス/タスクスケジューラ。起動停止は運用モード(NORMAL/STOPPED)に従う | エージェントの死活監視 | P0 | infra 側 E2E（T5.x） |
| F24 | 障害対応・エラー通知 | `run_daemon.py` L520-600、`notify/discord.py` | エラー用 webhook、`error_wait`、ML 失敗時フォールバック、DB ロック再試行(`db_read.get_connection`) | Discord | **K+D** | 失敗種別を構造化(再試行可/不可)、Cloud Monitoring アラート + Discord。DB ロックは MySQL では不要 | 沈黙失敗（except: pass が多い） | P1 | 障害注入テスト（取得失敗/Discord 5xx/DB 切断） |
| F25 | ログ | `utils/logging.py` | ファイル/コンソール | なし | **D** | 構造化ログ(JSON) to stdout → Cloud Logging | — | P2 | — |
| F26 | データ型/ドメインモデル | `data/models.py`, `provider_base.py` | Race, OddsData(単勝+wide/三連複), Signal, RaceResult。`OddsProvider` 抽象 + Dummy provider | なし | **K** | domain の値オブジェクトへ。`DummyProvider` はテスト/ローカル用 fake として保持 | — | P0 | 単体 |

## 2. scripts/ 配下の棚卸し（.py 70本 + 起動 .bat/.ps1 4本）
判断欄: **K**=機能を新実装に取り込む / **T**=テスト・ゴールデン資産として参照 / **D**=診断→運用ツール（admin 機能/Runbook）に置換 / **X**=移行しない（理由記載。削除はしない＝参照元は不変）

| 区分 | スクリプト | 用途 | 判断 | 理由 |
|---|---|---|---|---|
| 常駐/実行 | run_daemon.py, run_once.py | 本体/1回実行 | K（F14） | run_once は bot-run のドライラン/手動実行の仕様源 |
| 起動 | run_daemon_{32bit,utf8}.{bat,ps1} | Windows 起動 | D | エージェント用に再設計 |
| DB | init_db, run_migration, test_db_init, check_db, check_db_counts, verify_db, check_data_persistence | 初期化/移行/確認 | D | Flyway + ヘルス/件数 API |
| 取込 | fetch_historical_data, ingest_cover_data, ingest_alpha_features, read_local_jvlink_files, backfill_results, backfill_results_payout, fetch_payout_from_jvlink, backfill_bets_from_signals | 過去取込/補完 | K（F21, F15）/ read_local_jvlink_files は T | ローカルファイル読みはパーサ検証用 |
| ML | build_dataset, train, train_ml_model, retrain_models, tune_params, evaluate_model, diagnose_training_data | 学習系 | P（F11） | Python 維持 |
| 決済/成績 | settle_bets, settle_evaluations, report_performance, verify_bets_settlement, verify_results, verify_performance_v2, check_bets_results, check_settlement, check_results_table, check_results_payout, check_missing_result(_detail), debug_performance(_calculation), debug_daily_eval_status | 決済/集計/検証/デバッグ | K（settle, report）/ 他は T（期待値の根拠）/ debug は D | 検証系は旧新比較の期待値作成に再利用 |
| 通知 | send_test_notification, test_notification_with_ml | テスト通知 | D | 管理者用「テスト通知」機能（F13） |
| JV-Link 診断 | check_jvlink, check_jvlink_status, check_datatype_enabled, check_race_datatype, check_data_timing, check_timing_detail, open_jvlink_settings, analyze_jvlink_docs, read_jvlink_docs | 環境/データ種別/タイミング確認 | D | エージェントの「自己診断」コマンドへ集約。docs 解析は X（一回限りの調査）だが**調査結果は T1.2 で参照** |
| JV-Link 試行錯誤 | test_jvopen_{simple,call,6params,with_6params}, test_jvinit_unknown, test_jvgets_direct, test_jvread_simple, test_jvlink_pattern, test_race_data, test_datatype_detail | API 呼出し方の試行 | X | 呼出し規約が `jvlink_provider` に確定済みの試行錯誤。ただし**引数の癖は T1.2 の知見として docs に転記**してから参照のみ |
| 単体検証 | test_ev_calculation, test_kelly_bet_amount, test_betlist_config, test_performance_calculation, test_date_range_check, test_ml_horse_features, test_dashboard_*, verify_dashboard_data | 手動スクリプト型テスト（pytest ではない） | T | 期待値・境界値をゴールデンへ移植 |
| その他 | check_daemon_status | PID/ログ確認 | D | 状態 API |

> 全スクリプトの完全読解は行っていない（冒頭 docstring と命名・行数で用途推定）。**K/T と判断したものは第3段階の着手時に本文を精読**すること（TODO: 精読済みかを下表に記録）。

## 3. DB テーブル → 移行対象
| テーブル | 主な内容 | 主な利用箇所 | 移行 | メモ |
|---|---|---|---|---|
| races | レース条件（距離/馬場/天候コード等） | F03,F14 | K | 開催カレンダーは別テーブル新設 |
| odds_snapshots | 単勝オッズ時系列 | F09 | K | 巨大化する→パーティション/保持期間を T1.10 |
| combo_odds_snapshots | ワイド/三連複オッズ | F08 | K | 同上、行数が最大になる見込み |
| signals / bets / bet_legs | 通知シグナル、ペーパー bet | F13,F15,F20 | K | signals は 24 列、sent 管理 |
| results / payouts | 着順、払戻(market,key) | F15 | K | |
| horses / race_horses / horse_past_runs | 馬マスタ、出走、過去走（JSON 列含む） | F10,F21 | K | JSON 列の扱い要決定 |
| race_evaluations / ticket_evaluations | 評価（見送り理由、買い目評価） | F09,F20 | K | |
| model_metrics | brier/logloss/校正 | F11,F18 | K | |
| jvlink_timestamps | 差分取得位置 | F22 | K | サーバ正本 |

## 4. 横断リスク・未解決（T1.1 で判明）
1. **タイムゾーン**: `datetime.now()` が naive。新実装は `Clock`(Asia/Tokyo) 必須。旧実装の保存時刻が JST 前提かを実 DB で確認（D-006）。
2. **開催変更検知は参照元に無い** → 新規要件（T1.3）。
3. **設定の既定値二重化**: 関数既定値と config.example の既定値が異なる（例 `ev_shrink_factor` 0.7/0.55, `box_size` 4/3, `top_k_partners` 6/4, watch_start 240/120）。ゴールデンテストは「config.example を正」とした設定で行い、関数既定値は使わない（D-009）。
4. **集計ロジックが UI 内**（F20）、signals/bets/evals の3系統。正の定義を決める。
5. **pickle**: 旧 `*.pkl` は repo に無く、実体は利用者ローカル。モデル移行手順（pkl→JSON エクスポート、Python 側で一度だけ実行）を T1.9 で定義。
6. **実 DB の所在**（未解決）と移行ツールの要否。
7. **規約**: JRA-VAN データのクラウド保管・複数人閲覧・公開可否（T1.2）。
8. **精読範囲**: `jvlink_provider.py` は T1.13(2026-10-22) で取得・パーサ・エラー処理を精読済み（§6）。`decision.py`/`bet_strategy.py` の分岐、`fetch_historical_data.py` は未精読（第3段階で精読）。

## 5. 優先順（第2/3段階への入力）
P0 の順: F26/F04/F05/F06/F07（基礎純関数）→ F16（DB）→ F08/F09（判定）→ F10（推論）→ F13（通知）→ F15（決済）→ F02/F22/F03（取込・開催）→ F14（オーケストレーション）→ F01（エージェント）。P1: F11, F17, F18, F20, F21, F24。


## 6. T1.13 追加精読の結果（2026-10-22, JV-Link・開催日関連）
詳細は `JVLINK_DESIGN.md` §1、`RACE_CALENDAR_DESIGN.md` §0。
| 対象 | 追記 |
|---|---|
| F01 JV-Link 取得 | 使用 API は Init/Open/RTOpen/Read/Skip/Close/SetSavePath/SetPort のみ。`JVGets`/`JVStatus`/`JVWatchEvent` 等は未使用。dataspec は RACE, RCVN/RCOV, 0B30/31/33/35, 0B12, 0B15。**YSCH と 0B14 は未使用**。**分類は W のまま**、実装はエージェント(Python 暫定, D-018)。64bit 版 JV-Link の登場で「32bit 必須」前提が変わる |
| F02 パーサ | RA/SE/HR/O1/O3/O5/UM の位置一覧を JVLINK_DESIGN §1.4 に整理。**HR 払戻の桁解釈に 10 倍ずれの疑義**、文字列経由の cp932 再エンコードによる欠落リスク、レース ID が回・日目を欠く問題。**分類 K（server）のまま**、再現せず仕様書で正を確認(T1.17) |
| F03 開催日/発走予定 | 専用ロジックなし(確認済)。新設計=YSCH+RA+0B14+手動補正、`race_day/race_schedule/…` スキーマ、calendar-check/bot-run 分離。**分類 K（新規）のまま**。**T3.3 実装済み**（開催日の導出・手動補正・履歴・calendar-check・bot-run リース。一部は T3.4〜T3.9 待ち: RACE_CALENDAR_DESIGN §11） |
| F14 常駐ループ | `post_time` が `00:00`/8時前/18時超なら監視窓の開始に置換する暗黙処理、`result_fetched` などメモリ状態、`datetime.now()` を確認。**新設計では UNKNOWN 明示・DB 状態・Clock 注入** |
| F21/F22 補てん取込・タイムスタンプ | RCOV/RCVN は「非蓄積系ソフト向け補てん情報」で、蓄積用途では DIFN 等が本来。lastfiletimestamp は JVOpen 成功時に保存(読込完走と独立)→ **ACK 後のみ前進・オーバーラップ読み直し**へ |
| 新規 F27 取込 API(ingest)/エージェント | 認証・冪等・キュー・ビーコン。契約 CONTRACTS §3・§7 |
| 新規 F28 開催カレンダー/bot 起動制御 | RACE_CALENDAR_DESIGN |
| 未精読(継続) | `decision.py`/`bet_strategy.py` の分岐、`fetch_historical_data.py`(1.7k 行)。T1.13 の残り（T3.1 の前） |


## 7. T1.13 追加精読の結果（判定・買い目・履歴取得、2026-10-22）
対象: `ev/decision.py`(1,012)、`ev/bet_strategy.py`(803)、`ev/ev_calc.py`、`ev/probability.py`、`scripts/fetch_historical_data.py`(1,682)、`scripts/run_daemon.py` の評価呼出し部、`config/config.example.yaml`、`scripts/test_ev_calculation.py` ほかテスト。関連設計: `ML_DESIGN.md`（確率パイプライン）。**精読の範囲**: decision.py と bet_strategy.py は全分岐を読了（bet_strategy の三連複「流し」部分は構造がワイドと同型であることを確認し、中間 80 行は目視していない）。`fetch_historical_data.py` は構造・JV-Link 呼出し・保存先を確認（本文 1.7k 行の全行は未読）。

### 7.1 判定フロー（`evaluate_odds`）
1. 入力なしなら空。2. **確率決定**（ML → 市場フォールバック、`ML_DESIGN §1.3`）。3. **市場ブレンド（2 回目）**→ 上限クリップ → 再正規化。4. 馬ごとのループ:
   a. `priority_mode=="hit_rate"` のみ: `win_odds_max`（odds > 上限で除外）→ `win_prob_min`（p_final < 下限）→ `win_ratio_min`（p/p_mkt < 下限）。
   b. 急落/人気急上昇フィルタ（`recent_snapshots_func` があるとき）。
   c. `suggest_multiple_bet_strategies`（例外時は bet_strategy=None → その馬は `continue`、**理由集計なし**）。
   d. `best_strategy` が無ければ `continue`（理由なし）。
   e. EV 閾値: `best.ev <= threshold` で除外（`hit_rate` かつ `hit_rate_min_ev` 指定ならそれ、他は `ev_threshold`）。**境界は「閾値ちょうどは除外」**。
   f. `suggested_bet <= 0`: 見送り（`notify_skips` ならスキップ通知用シグナルを追加）。
   g. 単勝のみ `win_max_over_market`（p/p_mkt > 上限で除外、**理由集計なし**）。
   h. シグナル生成（EV 再計算の不一致を警告ログ）。
5. ソート: `edge`=EV 降順 / `hit_rate`=(p_final, ev, 賭式優先の小さい idx) 降順。`max_signals` 件に切詰め。
6. `max_total_bet_per_race`: 選択シグナルの賭け額合計が上限超なら**全体を比例縮小**（`min_bet` 未満になり得る）。

### 7.2 設定値の優先順位と暗黙のデフォルト
| 項目 | 優先順位 | 暗黙のデフォルト・食い違い |
|---|---|---|
| EV 閾値 | `hit_rate_min_ev`（hit_rate かつ指定時）> `ev_threshold`。さらに **daemon が時間帯スケジュール `hit_rate_min_ev_schedule`（far≥30 分前 / mid≥5 分前 / near）と `adaptive_relaxation`（無通知が続くと EV 緩和、下限 `min_ev_floor`）で上書きして渡す**（run_daemon.py L1285〜） | 関数既定 `ev_threshold=0.08`、example は 0.30（hit_rate 時 0.20/0.22/0.16） |
| ensemble β | config 数値 > `auto`（`ensemble_params_latest.json`、14 日失効→0.25、β<0.05→0.25、calibration 不可→1.0） | コード既定 0.35（config 無し）／ `auto` の失敗は 0.25 |
| 市場ブレンド α | 引数 `ml_market_blend_alpha` > `ml.market_blend_alpha` > 0.25。**モデル内と decision 側で 2 回適用** | `train_model` 既定 0.6、モデルクラス既定 1.0 |
| 確率上限 | 引数 > `ml.win_prob_clip_max`(0.35) > 0.35。`enable_prob_clip` 既定 True | クリップ後に**再正規化**するため上限を超え得る |
| 賭式モード | 引数 `bet_type_mode` | 関数既定 `multi`、example は `best_only` |
| 買い目設定 | 引数 > （daemon が config を渡す） | 関数既定と example が多数不一致: `ev_shrink_factor` 0.7/0.55、`box_size` 4/3、`top_k_partners` 6/4、`max_tickets_per_type` 6/4、`wide_max_prob` 0.35/0.18、`sanrenpuku_max_prob` 0.20/0.08、`max_bet` 10/6、`drop_threshold` 0.20/0.25、`surge_threshold` 3/4、`watch_start_minutes` 120/240（example 内でも 240 と 120 が重複定義） |
| Kelly 分数 | `kelly_fraction`(0.25) × **確率ソース係数 m**。m は `prob_source` が "ml_features"=1.0 / "ml_model"=0.9 / "market"=0.8 / その他=0.8 | decision が渡す `prob_source` は "ml:alpha"/"ml:ensemble(...)"/"market:fallback(...)" 等で**どれにも一致せず常に 0.8** → **実効 Kelly 分数は 0.2** |
**移行方針（D-016 の具体化）**: ゴールデンテストと新設定クラスの既定値は **example config を正**とし、関数既定値は使わない。食い違い項目は「どちらが運用実態か」を T1.20 で利用者の実 `config.yaml` から確認（実 config は秘密を含み得るため Git に入れない）。

### 7.3 暗黙の挙動・欠陥候補（新実装で再現せず、仕様として決める）
| # | 内容 | 影響 | 新実装の方針 |
|---|---|---|---|
| E1 | ML 例外の握り潰しで市場確率へ無言フォールバック（`prob_source` が "market:fallback(ml_disabled)" と誤表示。H1: ML_DESIGN） | 判定の根拠が不明 | フォールバック理由を明示・記録・アラート（D-036） |
| E2 | ensemble の `prob_source` 判定が**ループ末尾の変数**（最後の馬の odds_ok/pc/pa）を参照 | ラベルが不正確 | 馬ごと/レース単位で正しく記録 |
| E3 | `win_max_over_market` による除外・`bet_strategy` 例外・`best` 無しは**見送り理由カウントに入らない** | 見送り集計が実態と乖離（Overview の「見送り理由トップ」） | 全除外を理由コード化（enum） |
| E4 | EV 不一致警告が常時発生し得る: 買い目の `prob`（表示用の生確率）と `ev`（`ev_shrink_factor` 適用後の確率で計算）が別、signal の `ev_calc` は生確率で再計算 | ログ汚染、`ratio` は生確率基準 | EV/確率/比率の基準を一つに定義し両方を保存 |
| E5 | `calc_kelly_fraction(prob, odds)` は `odds==1.0` で `b=0` → **ZeroDivisionError**。decision の `try/except` で握り潰され、その馬が理由なしで除外 | 単勝 1.0 倍（ありうる境界）が無言で落ちる | odds ≤ 1.0 は明示的に Kelly=0・見送り理由 `ODDS_AT_PAR` |
| E6 | `max_total_bet_per_race` の比例縮小が `min_bet` を無視 | 実際には `min_bet` 未満の賭け額になる | 縮小後に min_bet 再判定 or 縮小禁止を仕様化 |
| E7 | `best_strategy` は `suggested_bet>0` の中で EV 最大、無ければ全体の EV 最大（見送り候補が best になり得る） | 見送り通知の対象選定 | 仕様として明示 |
| E8 | ワイドは**オッズ下限側で EV 計算**（保守的）、確率は Plackett–Luce の `P(2頭とも3着以内)`（重み `p^α`、α は [0.1, 5] にクリップ）、`p` は 0.99 クリップ → `ev_shrink_factor` → `*_max_prob` → 市場比上限 `p ≤ k·R/odds` → `*_min_prob` → EV>0 | 分岐が多く、順序が結果を変える | 段階をパイプライン化し順序を固定、ゴールデンで全段階を検証 |
| E9 | 流し相手 = 人気 1 位から `top_k_partners` 頭（`popularity>0` のみ）、BOX 候補 = 人気上位 `max(3, box_size)` 頭で、**軸馬が BOX 候補に含まれるときだけ BOX を追加**。重複買い目の除去は通知層（`_deduplicate_tickets`） | 人気データ欠損時の挙動（オッズ順にフォールバックするのは BOX 候補のみ） | 仕様として固定 |
| E10 | 三連複の確率は 6 通りの順列の PL 確率の和（`_top3_set_prob`）、ワイドは他全馬との和 → 計算量 O(n·6·n)。ペアごとに再計算 | 1 レースの評価が重くなり得る（n=18 で数百〜数千回/サイクル） | 重みの前計算・メモ化 |
| E11 | `require_real_odds_for_combo` 既定 True: 実オッズが無い買い目は除外（`continue`）。戦略が空だと「実オッズ未取得」の見送りエントリを返す（**ワイドも三連複も**。固定コミットの精読で確認: D-109③・D-115。旧記述の『ワイドは返さない』は誤り） | 賭式間の非対称は無い | 旧のまま固定（T3.2 で実装） |
| E12 | `Kelly` の `max_fraction` 引数は無視される経路あり（`max_fraction=1.0` 固定で呼ぶ） | docstring と挙動の乖離 | 仕様を設定に反映 |
| E13 | `estimate_prob(odds)` は 0.99 上限、`estimate_prob_normalized` は ±10% 補正（正規化の後に元確率基準の範囲に押し戻す） | 合計は再正規化で 1 | `ProbabilityPipeline` に取り込み |
| E14 | 急落フィルタは `recent_snapshots[-1]`（リストは新しい順なので**最古**）との比較のみ。連続的な変化は見ない | — | 仕様として固定。スナップショットの並びを型で保証 |
| E15 | 時刻依存: decision 内に `datetime.now()` は `ensemble_params` の経過日数のみ。daemon 側は `minutes_to_post`（naive）でウィンドウ/EV スケジュールを決定 | TZ ずれ | `Clock` 注入（F04） |

### 7.4 浮動小数・丸め
- 全て Python `float`（IEEE754 倍精度）。**丸め処理は計算内に無い**（表示のみ `:.1%`/`:.2f`）。Kotlin は `Double` で同順序の演算を行う（結合順序の入替えを禁止）。許容: 確率 1e-9、EV/金額 1e-9（絶対）。
- 境界クリップ: `1e-6`/`1-1e-6`、logit 入力ガード、`z` を ±50、`p` 0.99、odds 下限 0.1（`max(odds, 0.1)`）。これらの定数をテスト対象にする。

### 7.5 ゴールデンテストの必要ケース（`evaluate_odds` 系）
| ID | 観点 | ケース |
|---|---|---|
| GD01 | EV・Kelly | `ev_calc` 境界: prob=0/1、odds=0/0.1/1.0/1.01/999、Kelly 負、min_bet 境界（`bet_before_clip == min_bet`）、max_bet クリップ（既存 `test_kelly_bet_amount.py`/`test_ev_calculation.py` の入力を取り込む） |
| GD02 | 確率 | 頭数 1/2/18、全 odds 同一、極端な人気馬、odds≤0 を含む、正規化後の和=1、補正制限の境界 ±10% |
| GD03 | フィルタ | 急落（ちょうど閾値/超過）、人気急上昇（差=閾値）、スナップショット 0/1/2 件、`hit_rate` の 3 フィルタ境界 |
| GD04 | 閾値 | `best.ev == threshold`（除外）、`hit_rate_min_ev` あり/なし、時間帯スケジュール境界（mins=30, 5）、adaptive_relaxation の下限 |
| GD05 | ワイド | 実オッズあり/なし、`require_real_odds`、軸が BOX 候補内/外、`top_k_partners` 不足、`*_max_over_market` 発動、`min_prob` 発動、PL の α 端（0.1/5） |
| GD06 | 三連複 | 同上 + 流し/BOX の重複、頭数 3 未満 |
| GD07 | 賭け額 | `best_only` / `proportional_ev`、`ev` が全て 0 以下、`max_total_bet_per_race` 縮小と min_bet、`bet_type_mode=best_only` の剪定 |
| GD08 | 選別 | `max_signals`、`edge`/`hit_rate` ソートの同点（p 同値→EV→賭式優先）、`notify_skips` |
| GD09 | 異常系 | ML 失敗→市場フォールバックのラベル、`bet_strategy` 例外、odds=1.0（E5）、人気 0 の馬のみ |
| GD10 | 設定 | example config 全キー、関数既定との差異（D-016）、欠損キー |
- 期待値の生成: 参照元を固定コミット（f7dbf69）で実行し JSON 化（合成入力のみ）。E1〜E15 は「旧の挙動（記録）」と「新仕様」を分けてテストし、新仕様に変更する箇所は DECISIONS に記録して承認を得る。

### 7.6 `fetch_historical_data.py`（過去データ取得）
| 項目 | 内容 | 移行 |
|---|---|---|
| 目的 | 過去 1 年（`--from-date/--to-date`）のレース・結果・出走馬・過去走・払戻の一括取得 | F21: **エージェントのバックフィルモード**（`RACE` を `option=1`/セットアップ `option=4` で年単位に分割）+ サーバ側パース |
| JV-Link 呼出し | `RACE` を `JVOpen(…, option=1 or 4)`。`option=4` ではセットアップの**ダイアログが出る** | 参照元は **Win32 UI オートメーション（`send_key`、ウィンドウハンドル操作）でダイアログを自動応答**。新設計ではバックフィルを**対話セッションで利用者が起動**し、UI 自動操作は採用しない（脆弱・保守困難）。ダイアログ有無は T1.17 で確認 |
| 保存 | `races`（FK 対策に最小 `races` 行を先に upsert）、`results`、`race_horses`、`horse_past_runs`（バッチ upsert）、払戻（HR） | サーバの取込（冪等 upsert）に統一。FK 順序の問題はサーバの依存順で解消 |
| 日付 | 範囲指定をローカル `datetime` で解釈 | JST 固定の `LocalDate` |
| 再開 | 実行中断時は同コマンド再実行（`skip_existing`）。進捗の永続化は無い | ACK ベースのチェックポイント（JVLINK_DESIGN §5） |
| 既知の癖 | `SE` 先頭ファイルのスキップ、`JVRead` のファイル切替/EOF 判定、巨大 `max_reads`（1,000,000） | 総読込上限・タイムアウトを設定化 |

### 7.7 既存テストの位置づけ
`scripts/test_*.py` は pytest ではなく手動実行のスクリプト型。`test_ev_calculation.py`（EV・期待回収・期待利益の一貫性、`ratio==expected_return` 等のアサート）、`test_kelly_bet_amount.py`（min_bet 未満→見送り、以上→推奨額）が数値期待の根拠になる。判定全体（`evaluate_odds`）、ワイド/3連複、フィルタ、設定優先順位を検証する自動テストは**存在しない** → GD01〜GD10 を新規に作る。

## 8. T1.22 欠陥候補 E1〜E15 の新仕様整理（2026-10-22, Stage 1-D②）
**前提**: 旧挙動を「正常な仕様」とはみなさない。一方で、**ユーザーが承認していない数値挙動の変更は、本番の確定仕様にしない**（D-044・D-055）。各項目を次の 3 つに分類する。
- **R（記録の修正）**: 判定結果・賭け額・通知対象の数値は変えない。ラベル、理由コード、ログ、性能だけを直す。設計で確定し、承認は不要。旧新比較では「数値が一致」かつ「記録が正しい」ことを確認する。
- **F（仕様として固定）**: 旧挙動を明文化してそのまま残す。承認は不要。ゴールデンテストで固定する。
- **C（数値挙動の変更案）**: 新仕様の案を用意する。ただし**既定は旧挙動**とし、設定フラグ `jrabot.decision.fixes.<id>=false` で切り替える。本番で有効にするには、旧新比較とバックテストの後に**ユーザーの承認**が要る（T1.21）。

| # | 旧挙動（要約は §7.3） | 分類 | 新仕様（案） | 変更の影響 | 検証方法 | 承認 |
|---|---|---|---|---|---|---|
| E1 | ML の例外を握り潰し、市場確率へ黙ってフォールバック。表示は `market:fallback(ml_disabled)` で、実際の理由と異なる | R | フォールバックの数値は同じ（市場確率）。理由を enum（`MODEL_NOT_ACTIVE`/`FEATURE_SCHEMA_MISMATCH`/`SIGNATURE_INVALID`/`LOAD_ERROR`/`INFERENCE_ERROR`）で `signals`・`race_evaluations` に記録し、run 単位で Discord のエラー通知を 1 回送る。**「ML が有効のはずなのにフォールバックした」ことを隠さない** | 通知の数値は不変。エラー通知が増える | G7（失敗系）、GD09。実データの `prob_source` の分布（T1.20、G-ML-1） | 不要 |
| E2 | ensemble の `prob_source` が、ループの最後の馬の変数で決まる | R | 馬ごとに確率源を持ち、レース単位では「全馬が同じ源ならその源、混在なら `MIXED`」 | ラベルだけ | 単体テスト（混在するケース） | 不要 |
| E3 | `win_max_over_market` による除外、`bet_strategy` の例外、`best` 無しが、見送り理由の集計に入らない | R | 除外の全経路に理由コード（enum）を付ける。旧ラベルとの対応表を持つ | Overview の「見送り理由トップ」の件数が**旧画面と変わる**（正しくなる） | GD03・GD09。旧新比較では理由コード別の差を説明可能にする | 不要（表示差を PR に明記） |
| E4 | 買い目の `prob`（生の確率）と `ev`（shrink 後の確率で計算）の基準が異なり、EV 不一致の警告が常に出得る | R | `prob_raw`・`prob_used`（shrink 後）・`ev`（`prob_used` 基準）・`ratio`（**旧どおり `prob_raw` 基準**）を別々に保存する。警告は「同じ基準どうしの比較」だけで出す | 判定は不変。保存する列が増える | GD01 | 不要 |
| E5 | `odds==1.0` で Kelly が ZeroDivisionError になり、その馬は理由なしで落ちる | R | `odds ≤ 1.0` は明示的に Kelly=0 とし、理由 `ODDS_AT_PAR` で候補から外す（**旧と同じく候補に残さない**ので、E7 の `best` にもならない） | 数値は不変、理由が付く | GD01・GD09（odds=1.0/1.01） | 不要 |
| E6 | `max_total_bet_per_race` の比例縮小で `min_bet` を無視する | **C** | 案: 縮小後に `min_bet` 未満になった買い目を EV の低い順に外し、残りで再配分する（合計は上限以内） | 賭け額・点数が変わる | GD07。バックテスト（T3.9）の回収率・点数の差 | **要** |
| E7 | `best_strategy` は `suggested_bet>0` の中で EV が最大のもの。無ければ全体で EV 最大（見送り候補が best になり得る） | F | 旧のとおり。ただし `best.is_skip=true` を付け、見送り通知の対象であることを型で区別する | なし | GD08 | 不要 |
| E8 | ワイドはオッズの下限で EV を計算する。確率は PL（α を [0.1,5] にクリップ）→ 0.99 クリップ → shrink → 上限 → 市場比上限 → 下限 → EV>0 の順 | F | 段階をパイプラインとして**この順序で固定**し、各段階の中間値を記録可能にする | なし | GD05（全段階の中間値を比較） | 不要 |
| E9 | 流しの相手は人気 1 位から `top_k_partners` 頭（`popularity>0` のみ）。軸馬が BOX 候補に入るときだけ BOX を足す。重複の除去は通知層 | F | 旧のとおり。重複の除去は `:domain` の買い目生成に移す（出力は同じ） | なし | GD05・GD06 | 不要 |
| E10 | 三連複・ワイドの確率を毎回 O(n·6·n) で再計算する | R | 重みを前計算してメモ化する（数値は 1e-12 以内で一致） | 性能だけ | GD05・GD06 + 性能テスト（n=18） | 不要 |
| E11 | `require_real_odds_for_combo` で、実オッズが無く買い目が空のとき、**ワイドも三連複も**『実オッズ未取得』の見送りエントリを返す（精読で確認。旧記述の非対称は誤り: D-109③） | F | 旧のまま（T3.2 で実装済み）。『ワイドも返す』への統一案は不要（すでに対称）。実オッズ必須でない（`false`）ときは買い目が空ならエントリなし | 変更なし | GD05-REAL-008・GD06-EVAL-011 | 不要 |
| E12 | Kelly の `max_fraction` 引数が無視される経路がある（1.0 固定で呼んでいる） | F（既定）/ **C** | 既定は旧のまま（1.0）。案: 設定値 `max_fraction` を適用する | 賭け額が減る方向に変わる | GD01・GD07、バックテスト | 適用するなら**要** |
| E13 | `estimate_prob` は 0.99 が上限。`estimate_prob_normalized` は ±10% で補正する | F | `ProbabilityPipeline` の段階として固定 | なし | G3・GD02 | 不要 |
| E14 | 急落フィルタは `recent_snapshots[-1]`（新しい順のリストなので**最古**）とだけ比較する | F（既定）/ **C** | 既定は旧のまま。スナップショットの並びを型（`SnapshotsNewestFirst`）で保証する。案: 連続する変化も見る | フィルタの発動回数が変わる | GD03 | 連続判定にするなら**要** |
| E15 | decision 内の `datetime.now()`（経過日数）、daemon の `minutes_to_post` が naive な時刻 | R | `Clock` を注入し、JST の aware な時刻で計算する（日本に夏時間は無いので数値は不変）。テストでは固定時刻にする | なし（境界の時刻はテストで確認） | GD04（mins=30/5 の境界） | 不要 |

### 8.0 T3.1（Stage 3-A）の実装状況（2026-10-23。`:domain` の `jp.jrabot.domain.decision`、D-104〜D-111）
`pipeline_version=legacy`、`fixes.*` はすべて false（C の改善案は**未実装**。有効化は `UnsupportedDecisionScopeException`）。ゴールデンは Kotlin が検証したケースだけを数える（`tools/golden/catalog.py` の `KOTLIN_PORT`）。
| # | 分類 | Kotlin（T3.1）の扱い | 検証 |
|---|---|---|---|
| E1 | R | **実装（T3.6-A）**: `LegacyProbabilityPipeline`/`V2ProbabilityPipeline` が理由コード（`FallbackReason`）と段階（`FallbackStep`）を必ず残す。数値は旧と同じ（市場フォールバック）。アラート・`race_evaluations` への保存の配線は T3.8 | G7-LEGACY-FAIL-001（数値・旧ラベル）・`ProbabilityPipelineTest`・`MlEndToEndTest` |
| E2 | R | **実装（T3.6-A）**: 馬ごとの出どころ（`horseSources`）と、レース単位の `source`（同じ源ならその源、混在は `MIXED`）。旧ラベル（最後の馬の変数で決まる）は `legacyLabel` に保持 | G4-ENSEMBLE-002（旧ラベル）・`ProbabilityPipelineTest` |
| E3 | R | **実装**: 全除外経路に `DecisionReason`。旧集計と同じ数値の `LegacyRejectCounts` も返す（旧集計に無い経路: `WIN_OVER_MARKET`・`NO_STRATEGY`・`ODDS_AT_PAR`） | 単体 + GD04/GD08 |
| E4 | R | **実装**: `ProbRaw`/`ProbUsed`/`Ev`/`Ratio` を別項目で保持（`ev` は縮小後基準、`prob`/`ratio` は縮小前基準、`_calc_*` は検算値） | 単体 + GD07 |
| E5 | R | **実装**: `odds==1.0` で `LegacyZeroDivisionException`（旧 ZeroDivisionError）。判定側は除外し理由 `ODDS_AT_PAR`。数値は不変。`0<odds<1` の Kelly は旧実装では正の値（補正せず再現。D-105） | GD01-KELLY-002・GD09 |
| E6 | **C** | 旧挙動を実装（比例縮小・`min_bet` 再判定なし・最推奨の賭け額は scale²: D-109②）。T3.2: ワイド/三連複の `tickets[].stake` は scale 倍（最推奨の賭け額とは不一致になる旧挙動: D-121①）。改善案は**未実装・未承認** | GD08（総投資上限）・GD06-EVAL-011 |
| E7 | F | **実装**: 見送りエントリが最推奨になり得る（単勝を含まない `bet_types` で確認。T3.2: ワイド/三連複の買い目が最推奨のシグナル・見送りシグナルも） | GD08・GD06-EVAL-011 |
| E8 | F | **実装（T3.2）**: Plackett–Luce（α [0.1,5]・p [1e-12,0.99]）→ 縮小 → `*_max_prob` → 市場比上限 → `*_min_prob` → EV>0 の順を固定。ワイドは実オッズの下限で EV（D-113・D-115） | GD05-PL-005・GD05-FILTER-009・GD05-REAL-008 |
| E9 | F | **実装（T3.2）**: 流しの相手 = 人気>0 の馬を人気昇順（ワイド max(1,k)、三連複 max(3,k)）、BOX 候補 = 人気上位 max(3,box_size)（人気が全て 0 ならオッズ順）で**軸が含まれるときだけ**。重複の除去規則は `ComboTickets.deduplicated`（通知用）に置き、生成では除かない（数値が変わるため: D-116） | GD05-FLOW-006・GD05-BOX-007 |
| E10 | R | **実装（T3.2）**: 合計の前計算とメモ化（旧実装とビット単位で同じ。D-114） | 単体 + GD05-PL-005 |
| E11 | F | **実装（T3.2）**: 実オッズ未取得の見送りエントリはワイドも三連複も（D-115） | GD05-REAL-008・GD09 |
| E12 | F/**C** | 旧挙動を実装（判定の賭け額計算は `max_fraction=1.0` 固定。`suggest_bet_amount` の `max_fraction` は関数単体でのみ有効）。改善案は未実装・未承認 | GD01-KELLY-002・GD07 |
| E13 | F | **実装**（`estimate_prob_normalized`）。±10% 補正は再正規化で消える（実質ノーオペレーション: D-109⑤） | GD02-NORM-002 |
| E14 | F/**C** | **実装**: `SnapshotsNewestFirst` で並びを型で保証し、最古 1 件との比較（閾値の境界: 急落 `>`、人気急上昇 `>=`）。連続判定の案は未実装・未承認 | GD03-FILTER-001・GD03-SNAPSHOT-002 |
| E15 | R | 判定は時刻を読まない。`Clock` 注入はスケジュール（daemon 側）の移植時（T3.8） | - |
| H4 | - | `legacy`（二重ブレンド）を再現（`MarketBlend`）。v2 は未承認で未実装 | GD07-SIZING-004 |
| H10 | **C** | 旧挙動を実装（確率源ラベルの完全一致で、実際のラベルでは常に 0.8）。enum 化の案は未実装・未承認 | 単体 + GD09 |
| 新規 | F | `evaluate_odds` の `bet_strategy` 引数が隠れて固定額が使われない（D-109①）。Kotlin は固定額を持たない | GD09（`投資戦略: None`） |

#### T3.2（Stage 3-B）の実装状況（2026-10-23。D-112〜D-121）
- **実装した範囲**: ワイド・三連複の確率（Plackett–Luce）、推定オッズ、実オッズの注入、流し・BOX、E8 の段階、賭け額・`ticket_allocation`、単勝との最推奨比較、`WinDecisionEvaluator` への統合（`BetSignal`/`SkipSignal`/`StrategyEntry.tickets`・並び・総投資上限での買い目の縮小）。ゴールデンは 18 ケース/661 呼び出し（うち T3.2 の 8 ケース/281 呼び出し）が全一致、最大絶対誤差 0。
- **未実装・未承認（既定 off のまま）**: E6 の改善案、E12、E14、H4、H10、生成段階の重複除去、買い目の賭け額の合計と最推奨の賭け額の整合（scale² の旧挙動）。いずれも数値を変えるため T1.21 のユーザー承認待ち。
- **範囲外**: ML 経路（T3.6）、DB からの実オッズ取得・鮮度（T3.4）、通知本文（T3.7）、時間帯スケジュール（T3.8）。

### 8.1 ML 側の疑義（ML_DESIGN §2 の H）との対応
| 疑義 | 対応する項目 | 新仕様（案） | 本番への反映 |
|---|---|---|---|
| サイレントフォールバック（H1） | E1 | E1 のとおり（理由を明示して通知） | 設計で確定。**H1 が本当に起きていたかは実データで実証**する（T1.20 → G-ML-1） |
| 特徴量の次元の不一致（H2） | — | **パディングやスライスで次元を合わせない**。`feature_schema_hash` か次元が合わなければ読込を拒否し、理由 `FEATURE_SCHEMA_MISMATCH` を付けて市場確率へ（E1 の経路） | 旧挙動（先頭からスライス）は再現しない。H1 の経路では結果が旧と同じ（市場）になる見込みで、G-ML-1 で確認。**承認対象**（T1.21 の D-036） |
| 学習時のラベルリーク（H3） | — | 学習データは as-of（対象レース日より前の行だけ）で `export-dataset` が作る。旧の学習は再現しない | 推論には影響しない。新しいモデルを ACTIVE にするのは、品質ゲートと管理者の昇格（承認）の後 |
| 市場確率の二重ブレンド（H4） | — | `legacy`（二重のまま）/ `v2`（1 回だけ） | **既定は `legacy`**。v2 は旧新比較・バックテスト・閾値の見直しの後に、ユーザーが判断（D-037）。自動では切り替えない |
| Kelly 係数の誤適用（H10: 確率源係数 `m` が常に 0.8） | — | 案: 確率源の enum に対して係数表を明示する（`ML`/`ENSEMBLE`/`MARKET`） | `legacy` は 0.8 のまま再現。修正は **C**（`fixes.h10`）で、承認が要る |
| odds=1.0 の例外処理 | E5 | E5 のとおり | 設計で確定 |
- 本番の初期設定は `pipeline_version=legacy` とし、`fixes.*` はすべて false にする。R の項目はどの設定でも有効（数値は不変）。
- 新旧の比較は 2 段で行う。①合成データのゴールデン（第3段階、CI）。②実データのリプレイ（T1.20 の実 SQLite をローカルで使う。**クラウドには投入しない**。G-ML-2）。

## 9. T3.4 での分類の追記（2026-10-23。既存機能を黙って削除しない）
| 機能 | 分類 | T3.4 の状況 |
|---|---|---|
| F01 JV-Link 取得（`JVOpen`/`JVRTOpen`/`JVRead`） | **別プロセス維持**（Windows 取得エージェント。T3.5） | サーバ側の受け口（`POST /v1/agent/ingest`・`heartbeat`）と認証は T3.4 で実装。エージェント本体は T3.5 |
| F01 レコードのパース（旧 `jvlink_provider.py`） | **段階移行**（サーバ側 `RecordParser`） | 登録方式のみ。**実データ形式（YS/RA/TC/オッズ）のパーサは公式仕様の確認（T1.17）後**。それまで実データは保留（PENDING）で解析成功にしない |
| F21 過去データ/補てん取込・バックフィル | **段階移行** | `ingest_backfill`（要求・再開・ギャップ解消）の基盤を T3.4 で実装。過去取得の実行は T3.5 以降。旧 `backfill_*` スクリプトの人手運用は残る |
| `jvlink_timestamps`（旧） | **Kotlin 移行**（`ingest_cursor`） | ACK 後（EOF まで連続読了）にだけ前進。後戻りしない |

## 10. T3.6-A での分類の追記（2026-10-24。既存機能を黙って削除しない）
| 機能（旧） | 分類 | T3.6-A の状況 |
|---|---|---|
| `ml/features.py` `build_feature_vector[_alpha]`・`get_horse_feature_summary`・`_hash_bucket/_hash_sign` | **Kotlin 移行**（`LegacyFeatureBuilder` = 旧互換、`PointInTimeFeatureBuilder` = 新方式） | 旧互換は G6/G2 で旧 Python と一致（誤差 0）。新方式は H3/H8 を構造的に解消。旧 `_sum_chaku_table` の癖（`top3 += 累計 top2`）は式として再現（D-159）。旧の `format_reason_from_features`（通知文）は T3.7 |
| `ml/model.py` `WinProbabilityModel` の推論（`predict`・`predict_batch`・`predict_proba_from_features`・`_postprocess_prob`） | **Kotlin 移行**（`LogisticScoring`） | G1/G3 で一致（最大誤差 6e-15）。**`_align_feature_matrix`（次元の切詰め/ゼロ埋め）は本番に移さない**: 検証専用の `GoldenMl` にだけ再現 |
| `ml/model.py` `fit`（学習）・`train.py`・`retrain_models.py`・`tune_params.py`・`build_dataset.py` | **段階移行**（Python の ml-train ジョブ = T3.6-B / Stage 3-F2。データセットの書出しは `DatasetExporter` が契約を実装済み） | 学習は未実装（今回の対象外）。出力契約・品質ゲート案は ML_DESIGN §11.7・§11.8 |
| `ml/predict.py` `get_model`（mtime キャッシュ・失敗時 None・古いキャッシュで継続）・`load_model`（pickle） | **廃止 → 置換**（レジストリの ACTIVE + `ModelRunPin`（run 開始時に固定）+ 署名/sha256 検証つき JSON） | pickle は読まない（D-008・D-164）。「読み込めなければ古いキャッシュで継続」はしない（理由つきで市場確率へ） |
| `decision.py` `model_type=auto`（ファイルの有無で選ぶ） | **廃止 → 置換**（レジストリの ACTIVE な種別。設定は `legacy|calibration|alpha|ensemble` の明示） | `GoldenMl` は `auto` を未対応として拒否（旧仕様はファイル依存） |
| `decision.py` `ensemble_beta=auto`（`ensemble_params_latest.json`・14 日失効・β<0.05 なら 0.25） | **廃止 → 置換**（ensemble セット成果物が β と coverage_weights を持つ。ML_DESIGN §4.2） | 数値設定の β は再現（G4）。`auto` は未対応として拒否 |
| `decision.py` legacy 種別でモデルがあるのに特徴量作成に失敗 → `estimate_prob_normalized(model_path)` による『ml:legacy(no_odds)』（5 市場特徴量 + 次元パディングの予測） | **廃止**（H2 と同種の無意味な予測） | 市場フォールバックにして理由を記録（`MODEL_PREDICTION_FAILED` など）。旧との差は MIGRATION_MATRIX §8 E1 の範囲（記録の修正）を超えるが、旧の数値自体が欠陥（未承認の数値変更ではなく、再現しない旨をここに記録） |
| `decision.py` ML 例外の握り潰し・`prob_source` の誤表示（E1・E2・H10） | **Kotlin 移行（分類 R）** | 理由コード・段階の記録・馬ごとの正確な出どころ（`ProbSource`）を実装。旧 `prob_source` 文字列は `legacyLabel` に保持（Kelly 係数 H10 の数値は不変。enum 化 `kellyMultiplierBySourceEnum` は未承認で未実装） |
| `decision.py` 二重市場ブレンド（H4） | **Kotlin 移行（旧のまま）** | `legacy` で再現（G5）。v2（1 回）は独立実装・未承認・本番で無効 |
| `run_daemon.py` `auto_retrain`（F12） | **廃止**（独立ジョブへ。変更なし） | Stage 3-F2 以降 |
| モデルの pkl 保存 | **廃止**（JSON + Ed25519 署名） | 旧 pkl → JSON の変換は利用者のローカル操作（ML_DESIGN §11.9。実行は未実施） |


## 11. T3.7 で見つけた旧 `notify/discord.py` の欠陥（N1・N2）と F13/F24 の移行区分
| ID | 内容 | 区分 | 旧互換 | 新実装 |
|---|---|---|---|---|
| N1 | グループ通知の『ℹ️ 期待利益は投資額計算に使った確率(p=…)で計算』が、集約後の辞書に `prob` が無く 0.0 と比べるため、`prob_used_for_bet` があればほぼ常に出る | R（表示のみ。数値は不変） | `FormatMode.LEGACY_COMPAT` で再現（G8-GROUPED-005） | `CORRECTED`: 生の確率と 0.0001 超違うときだけ |
| N2 | `send_signals_grouped` は関数内の `logger` が EV 検算の不一致の分岐でしか代入されず、**通常の成功経路の末尾 `logger.info` が `UnboundLocalError`**。本文は送信済みなのに例外 → daemon は `mark_sent` せず、再起動後に再通知し得る（二重通知） | R | G8-GROUPED-005 が `raised: UnboundLocalError` を記録（Kotlin も同じ条件で一致を検証） | 例外にしない。結果は `DeliveryResult` で返し、`SENT`/`SEND_UNKNOWN` を記録 |
| N3 | daemon がグループ送信失敗で**全チャンクを再送**（`retry_count` 回）。一部のチャンクが届いた後の再送で二重通知 | R | 比較対象外 | 一部送信済みは `UNKNOWN`・再送しない（D-183） |
| N4 | `_post_webhook_with_retry` は 5xx・タイムアウトも再送（同一メッセージが重複し得る） | R | 比較対象外 | 5xx・要求タイムアウト・切断は `UNKNOWN`（再送しない） |
| N5 | 旧 `mention` は自由文字列を先頭に付ける（`@everyone` などが入り得る）。`mention_user_id` は任意の文字列から数字だけを抜き出す | R | 移植しない | 17〜20 桁の ID だけ。不正は exit 2 |
| F24 | エラー用 Webhook: 旧 `_send_error_webhook`/`_notify_error`（クールダウン 300 秒・`allowed_mentions` 固定・失敗は握りつぶす）は `ErrorReporter` として基盤だけ移植。**どのイベントから呼ぶか・D-14 の REJECTED 通知は T3.8/未承認のまま** | K（基盤）+ 後続 | - | 再帰しない・秘密を除く・クールダウン |

## 12. T3.8 での分類の追記（2026-10-27。旧 `scripts/run_daemon.py` の機能。既存機能を黙って削除しない）
旧 `run_daemon.main_loop`（2,010 行の 1 本のループ）の機能を、新しい構成のどこへ移したかを示す。区分: **K** = Kotlin へ移行済み（T3.8）、**S** = 別プロセスへ（既存の Job）、**P** = 段階移行（後続タスク）、**N** = 今回は移さない（理由とユーザー判断を併記）。

| 旧の機能（`run_daemon`） | 区分 | 新しい実装・理由 |
|---|---|---|
| 無限ループ＋`time.sleep(interval)` | K | `BotRunOrchestrator`（開始〜終了の状態機械。最大稼働・窓の締切・停止要求・モードで必ず終わる）。待機は `Pacer` |
| `get_polling_interval`（30 分超 / 5 分超 / それ以下で far/mid/near） | K | `PollingIntervals.forMinutesToPost`（分岐は同じ。間隔の運用値は環境変数で必須・既定なし: G-23） |
| `main_snapshot_minutes` / `extra_snapshot_minutes`（通知タイミング） | K（方式は暫定） | `SnapshotPolicy`。旧の『最大のタイミングで決まる』癖はそのまま。重複通知の抑止は新しい仕様『各タイミング 1 回』（D-196。ユーザー確認 G-23） |
| 5 分前の強化（`drop_threshold × 0.7`、`*_max_over_market × 0.8`） | K（opt-in。修正後と旧互換を区別） | `BOT_RUN_TIGHTEN_NEAR=true`（修正後）/ `legacy`（旧の癖）/ `false`（D-198・D-205）。数式は旧のまま |
| `snapshot_history_limit`（急落判定の履歴件数） | K | `BotRunConfig.snapshotHistoryLimit`（新しい順・既定なし） |
| `provider.get_odds`（オッズ取得）・`odds_repo.save_snapshot`・`race_horses` の補完保存 | S | Windows エージェント＋ingest（T3.4/T3.5）が取込み、bot-run は DB のスナップショットを読むだけ（保存しない） |
| オッズが取れない／古い（旧は鮮度の検査なし） | K（是正 R） | `OddsFreshness`。観測が `max_odds_age` を超えたら通知しない（RACE_CALENDAR_DESIGN §6.3）。旧は古いオッズでも通知し得た |
| `evaluate_odds`（判定）・Plackett–Luce・買い目 | K（T3.1/T3.2） | `RaceEvaluator` が接続するだけ。数値は変えない（ゴールデン 31 ケース / 988 呼び出し一致を維持） |
| ML 推論（`ml_cfg`・モデルパス・フォールバック） | K（T3.6-A + T3.8） | `MlRunStarter`（run 開始時に固定）。サイレントフォールバックは理由つき（E1）。H1 の可視化は `race_evaluations.fallback_reason` とアラート |
| `race_evaluations` / `ticket_evaluations` の保存（`enable_eval_persistence`・`persist_skipped`・`persist_top_n`） | K（一部）／P | `race_evaluations` は判定のたびに冪等に追記（D-200）。**`ticket_evaluations`（買い目ごとの内訳）は未実装**（G-25。T3.9 の成績集計で必要になる時に追加）。`persist_skipped`/`persist_top_n` は未移植（見送りは `rejection_summary_json` に件数で残す） |
| シグナルの保存・Discord 送信・`mark_sent` | K（T3.7 + T3.8） | `signals` に insert-if-absent → `SignalNotificationDispatcher`（claim → 送信 → 記録。at-most-once） |
| `_notify_error`（エラー用 Webhook） | K（T3.7 + T3.8） | `ErrorReporter` を呼ぶイベントを決定（D-201） |
| `include_tomorrow`（今日＋明日を同時に監視） | N | 1 回の run は 1 日（`--date`）。calendar-check が日ごとに起動する設計（RACE_CALENDAR_DESIGN）。明日分が必要なら別の run。ユーザー判断は不要（設計の違い） |
| 開催日・発走の推測（`active_races`・`watch_end`・曜日・00:00 の補正） | K（是正） | DB の有効予定（`EffectiveCalendar`）だけで決める。曜日・祝日で推測しない。発走時刻が不明なら補完せず通知しない（D-024） |
| 結果取得・払戻・`bets`／`race_evaluations` の決済（`result_fetched`・`settle_*`） | P（T3.9） | D-190。メモリの `result_fetched` は使わず、DB の `race_schedule.status` から再導出する設計 |
| 自動再学習（`auto_retrain`・`retrain_interval_hours`・`_run_training_async`） | S | `ml-train` Job（T3.6-B）。bot-run は学習しない（学習と推論の分離。モデルの昇格はしない） |
| `_maybe_cover_ingest`（取込の補完） | S | ingest の `plan`/backfill（T3.4/T3.5） |
| `hit_rate_min_ev_schedule`（far/mid/near で EV 閾値） | K（明示設定のみ。D-206） | `HitRateSchedule`。旧の式の境界（>=30 / >=5）まで一致。未設定は固定の `hitRateMinEv` |
| `adaptive_relaxation`（無通知が続くと EV 閾値を段階的に緩和） | N（T3.9 のバックテスト後に再判断。G-24） | 実装・有効化しない。状態を持つ調整で、再起動で巻き戻る旧の欠点もある |
| `status_log_interval_seconds`・`log_summary`・`log_no_signal_top`・`debug_rejections`（診断ログ） | P | 構造化ログ（`BOT_RUN_EVENT`）で代替。件数の集計ログは運用が必要としてから（Stage 5 の監視） |
| `KeyboardInterrupt` での停止 | K | SIGTERM → `INTERRUPTED`（D-202） |
