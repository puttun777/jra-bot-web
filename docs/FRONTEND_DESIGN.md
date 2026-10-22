# FRONTEND_DESIGN（T1.5 既存ダッシュボード調査とフロント技術選定）

調査日: 2026-10-22。範囲: 設計のみ（実装は第4段階）。参照元: `apps/dashboard/streamlit_app.py`（2,052 行）、`apps/dashboard/pages_evaluations.py`、`src/jraev/ui/db_read*.py`、旧 `ui/`（マルチページ版）。版数は `VERSIONS.md`。認可・データ分離は `DATA_SOURCE_COMPLIANCE.md` §4・`CONTRACTS.md` §3。

## 1. 参照元ダッシュボードの実態
- **完全に読み取り専用**。書込み・設定変更・手動操作は無い（`st.button` による更新操作もなし）。認証・認可もない（ローカル用途）。
- 画面は 1 ファイルのサイドバー選択（`st.sidebar.selectbox`）で切替。**8 ページ**: Overview / Race Detail / Horse Detail / Performance / Signals / Ops/Health / Model / Evaluations（依頼書の 7 画面 + `Evaluations`）。
- 更新方法: `st.cache_data(ttl=30)` による 30 秒キャッシュ。自動更新（ポーリング）は無く、ブラウザ再読込・ウィジェット操作で再実行。
- 集計ロジックは UI 層 (`db_read.py` 1.3k 行、`db_read_evals.py` 0.8k 行) に混在 → サーバ側サービスへ抽出（MIGRATION_MATRIX F20）。
- 旧 `ui/`（Status / Races / Signals / Odds / Config / Evaluations）は初期版。Odds（オッズ推移 line_chart + 統計）と Config（設定表示）に相当する機能は新版では Race Detail 内／存在しないため、**Race Detail のオッズ推移として維持、Config は管理者用の読み取り専用「設定サマリ」として維持**（削除しない）。

## 2. 画面別調査と移行方針
凡例: ★=要 API。認可: V=閲覧者（自テナント）、A=管理者。

### 2.1 Overview（`/`）
| 項目 | 内容 |
|---|---|
| 目的 | 日付範囲のレース一覧と評価状況の概観 |
| 表示 | 総レース数 / 総シグナル / 通知済み / 見送り / 評価済み / 未評価（メトリクス 6 つ）、レース表、**見送り理由トップ**（`rejection_summary_json` 集計） |
| 操作 | 日付（今日/期間: `date_input` 開始・終了）、競馬場フィルタ、データソース切替（evaluations / signals）、レース選択（→ Race Detail への案内） |
| 更新 | 30 秒キャッシュ |
| 新 UI | ヘッダに**状態バー**（NORMAL/STOPPED、bot 状態、最終更新時刻、次回実行予定、取得失敗/未更新バッジ）を全ページ共通で追加。メトリクスはカード、レース表は 1 行タップで Race Detail へ遷移（URL `/races/{raceKey}`）。見送り理由は横棒チャート |
| API ★ | `GET /api/overview?from&to&track&basis` |
| 認可 | V |

### 2.2 Race Detail（`/races/{raceKey}`）
| 項目 | 内容 |
|---|---|
| 目的 | 1 レースの詳細分析 |
| 表示 | レース見出し、**シグナル一覧**（市場フィルタ）、**オッズ推移**（`px.line`、市場選択 + 馬番マルチ選択）、**結果とシグナルの突合**（着順・払戻・的中） |
| 操作 | レース ID テキスト入力（→ 新 UI は URL パス）、市場フィルタ、馬番選択 |
| 新 UI | 入力式から**URL 遷移**に変更（共有・戻る・ブックマーク可）。オッズ推移は ECharts の折れ線（ズーム/凡例切替、スマホはピンチ）。ワイド/3連複の実オッズ表（combo_odds）は別タブ |
| API ★ | `GET /api/races/{raceKey}`、`/signals`、`/odds-history?market&umaban`、`/results` |
| 認可 | V |

### 2.3 Horse Detail（`/races/{raceKey}/horses/{umaban}`）
| 項目 | 内容 |
|---|---|
| 目的 | 出走馬の馬情報と過去走 |
| 表示 | 馬名、馬マスタ（性別/生年月日/東西/調教師/血統/脚質傾向/着度数）、**過去走**表 |
| 操作 | レース ID と馬番の入力、過去走の件数スライダ（5〜20） |
| 新 UI | Race Detail の出走表から遷移（URL）。過去走件数は選択式（5/10/20）。血統は折りたたみ |
| API ★ | `GET /api/races/{raceKey}/horses/{umaban}`、`/api/horses/{kettoNum}/past-runs?limit` |
| 認可 | V（テナント分離：自契約のデータのみ） |

### 2.4 Performance（`/performance`）
| 項目 | 内容 |
|---|---|
| 目的 | 成績集計（回収率・的中率・損益）とドリルダウン |
| データソース切替（**3 系統**） | ① evaluations（`race_evaluations`、通知のみ/全件、確定状態、暫定を含むか）、② bets 台帳（推奨。source=paper/manual/auto、status=open/settled/canceled）、③ signals（補助。ペーパーのみ、結果未確定を除外、競馬場/賭式/期間フィルタ） |
| 表示 | 総合成績（評価数/通知・見送り/確定数/損益 ± ROI/的中率/的中数/外れ数/総投資/払戻/未確定、確定率）、**欠損一覧**、**日別**（表 + 折れ線 + ドリルダウン選択）、**場別**（表 + ドリルダウン）、**EV 帯別**（表 + 棒グラフ + ドリルダウン）、未決済/欠損一覧 |
| 操作 | 期間、市場（全/win/wide/sanrenpuku）、データソース、各種ラジオ/チェック、ドリルダウン選択 |
| 新 UI | 既存 3 系統は**すべて維持**（「基準」切替タブ）。既定は evaluations。集計はサーバのサービスで実装し API は集計済み DTO。表はスマホで**カード積み**、グラフは ECharts。ドリルダウンは行タップで下層テーブル（htmx で部分更新）。未確定/欠損は警告バッジ |
| API ★ | `GET /api/performance?basis&from&to&market&…`、`/performance/daily`、`/by-track`、`/by-ev-band`、`/missing` |
| 備考 | 3 系統の定義差（signals vs bets vs evals）を `MIGRATION_MATRIX` F20 の通り正準化（T1.10）。丸めは表示層のみ |
| 認可 | V |

### 2.5 Signals（`/signals`）
| 項目 | 内容 |
|---|---|
| 目的 | 過去シグナル（通知）の検索 |
| 表示 | シグナル一覧（件数付き）。列: 時刻/レース/市場/馬番/オッズ/確率/EV/通知済み/理由/確率ソース 等 |
| 操作 | 期間、市場、通知済み、競馬場の各フィルタ、**ソート列 + 昇降順**、**ページサイズ 20/50/100 + ページ番号** |
| 新 UI | サーバサイドのフィルタ/ソート/ページング（htmx）。URL にクエリを持たせ共有可能。スマホは列を絞った 1 行カード（詳細は展開） |
| API ★ | `GET /api/signals?from&to&market&sent&track&sort&dir&page&size` |
| 認可 | V |

### 2.6 Ops/Health（`/ops`）
| 項目 | 旧 | 新 |
|---|---|---|
| デーモン状態 | `logs/daemon.pid` と PID 生存確認 | **廃止（クラウドでは無意味）** → `bot_run` の状態・ハートビート、**Windows エージェント状態**（ハートビート・キュー深さ・最終 ACK）、**運用モード（NORMAL/STOPPED/STARTING/STOPPING）と desired/actual** |
| 今日の稼働カード | 今日のレース/シグナル/オッズ/結果件数 | 維持（+ 今日の `race_day` 状態、次回実行予定、取得失敗/ingest_gap） |
| 最新更新時刻 | races/odds/signals/results の最新時刻 | 維持（+ 鮮度判定: `last_confirmed_at`、STALE バッジ） |
| 補てん情報取り込み状態 | `lastfiletimestamp` の一覧 | 維持（データ種別ごとの `acked_ts` と更新時刻） |
| ログ | ローカルログ tail 200 行 + エラー/警告 20 件 | 構造化された**エラーイベント一覧**（直近 N 件。取得失敗、通知失敗、MODE 変更）。生ログは Cloud Logging（管理者へのリンク）。ブラウザへ生ログを出さない（規約・機微情報） |
| テーブル件数 | 全テーブル行数 | **管理者のみ**（負荷とテナント境界のため集計 API） |
| 認可 | 状態表示=V、詳細ログ/件数/手動操作=A |
| API ★ | `GET /api/ops/status`、`/api/ops/events`、`/api/admin/ops/table-stats` |

### 2.7 Model（`/model`）
| 項目 | 内容 |
|---|---|
| 目的 | 使用中モデルと学習状況、キャリブレーション |
| 旧表示 | ML 有効/無効、モデルタイプ、使用パス、更新日時、サイズ、「異常に小さい」警告、全モデルファイル（Calibration/Alpha/Legacy）の状態表、`metrics_latest.json`（Brier/LogLoss/Accuracy/サンプル数/ensemble_beta）、`model_metrics`（Calibration 曲線） |
| 新 UI | **モデルレジストリ**の表示（`ML_DESIGN.md` §6）: 有効モデルのバージョン・種別・作成日時・学習期間・サンプル数・メトリクス・`feature_schema_hash`・整合性（sha256）。切替履歴。Calibration は信頼度曲線（ECharts）。ファイルパス・サイズ表示は廃止（成果物管理に置換）。**モデルの有効化/ロールバックは管理者のみ**（新機能、Stage 4 では表示のみでも可） |
| API ★ | `GET /api/models`、`/api/models/active`、`/api/models/{version}/metrics`、`/api/model-metrics?market&from&to`；`POST /api/admin/models/{version}/activate`（A） |
| 認可 | 表示=V、有効化=A |

### 2.8 Evaluations（`/evaluations`）
| 項目 | 内容 |
|---|---|
| 目的 | 通知有無にかかわらず評価したレースの結果と成績 |
| 表示 | パフォーマンスサマリ（総評価数/通知/見送り/的中率/回収率/総投資/総払戻/総利益）、評価一覧、レース選択 → チケット評価（買い目、p_model/p_mkt/p_final/ratio/EV/stake/rank/見送り理由）と結果 |
| 操作 | 期間、競馬場、判定（通知/見送り）、賭式フィルタ、レース選択 |
| 新 UI | 維持。Performance の evaluations 基準と相互リンク。チケット表は展開式 |
| API ★ | `GET /api/evaluations?…`、`/api/evaluations/{id}/tickets` |
| 認可 | V |

### 2.9 新規画面・機能（要件由来）
| 画面/機能 | 内容 | 認可 |
|---|---|---|
| **状態バー（全ページ）** | 運用モード（NORMAL/STOPPED）、bot 状態、**最終データ更新日時**、**次回実行予定**、**取得失敗・データ未更新の警告**。STOPPED 時は全ページ上部に固定バナー（「停止中: データは更新されません」）+ 閲覧は可 | V |
| カレンダー（開催予定） | `race_day` / 状態（PLANNED/CONFIRMED/CANCELLED/POSTPONED/UNCONFIRMED）と手動補正の有無（RACE_CALENDAR_DESIGN §8） | V（閲覧）/ A（補正） |
| 管理: 手動補正 | `calendar_override` の作成・失効（理由必須、期限） | A |
| 管理: bot 手動起動 | 開催検証つき（非開催日は拒否。`ALLOW_RUN` が必要） | A |
| 管理: エージェント管理 | 登録コード発行、失効、最終ハートビート | A |
| 管理: ユーザー | 許可リスト（IAP の許可）と `app_user`（テナント紐付け、ロール）の確認。**変更はインフラ設定経由**（IAP の許可はインフラ側） | A（閲覧のみ） |
| 設定サマリ（旧 Config） | 監視設定/投資設定の**読み取り専用**表示（秘密値は出さない） | A |
| STOPPED 表示 | **STOPPED では dashboard は ingress=internal で外部から到達不能**（CONTRACTS §5.3、D11）。停止ページ（LB）は固定費のため置かない。停止中の状態確認は infra の `status` ワークフロー（GitHub Job Summary）。STARTING/STOPPING 中は固定バナー付きで閲覧可、管理操作（書込）は NORMAL のみ（409）。モード取得失敗（UNKNOWN）は「運用状態を確認できません」バナー | — |
注: STOPPED バナーが出るのは STOPPING/STARTING の遷移中のみ（STOPPED は到達不能）。実装は「モード取得に失敗した場合は NORMAL 扱いにしない」（CONTRACTS §5.2）。

## 3. 共通 UI 要件
| 要件 | 設計 |
|---|---|
| 日本語 UI | 画面文言はすべて日本語（メッセージリソースで管理 `messages_ja.properties`）。数値は桁区切り、日時は JST 表示 `yyyy/MM/dd HH:mm`（保存は UTC）。曜日表示なし（開催日は DB 判定） |
| レスポンシブ | モバイルファースト。ブレークポイント 640px / 1024px。テーブルは 640px 未満で**カード積み**（`data-label` で列名表示）、横スクロールを避ける。グラフは幅 100% でリサイズ、凡例はタップで切替 |
| 少人数向け | 1 画面 1 目的、主要操作を上に。既定のフィルタ値は「今日」「直近 30 日」。保存フィルタは URL で共有（ブックマーク） |
| 更新 | 状態バーは htmx で 30〜60 秒ポーリング（`hx-trigger="every 30s"`）。長いテーブルは手動「更新」ボタン。bot 実行中のみ Overview/Race Detail が 15 秒更新 |
| アクセシビリティ | コントラスト比 AA、色だけに依存しない（EV 正負はアイコン併記）、フォーカス可視、44px タッチターゲット |
| ダークモード | `prefers-color-scheme` 対応（CSS 変数） |
| エラー/空状態 | 取得失敗・データ未更新・「該当なし」を明示（旧 UI の `st.info/warning` を踏襲）。**不明を 0 や空で隠さない** |

## 4. 技術比較
### 4.1 候補
| 案 | 構成 |
|---|---|
| **A** | Spring Boot（Kotlin）+ **Thymeleaf（SSR）+ htmx**、チャートは **ECharts**（自己ホスト JS）、CSS は自前 + 軽量クラスレス CSS。Node ビルド無し。`dashboard` 1 コンテナ |
| **B** | **React + TypeScript + Vite** の SPA。Spring Boot は JSON API のみ。ビルド成果物は dashboard コンテナの静的リソースに同梱（別ホスティングは費用・構成が増えるため非推奨） |
| C | Vaadin Flow（Java/Kotlin サーバ駆動 UI） |
| D | Streamlit 維持（Python 別サービス） |

### 4.2 評価（◎=優 ○=良 △=可 ×=不可）
| # | 基準 | A Thymeleaf+htmx | B React+TS+Vite | C Vaadin | D Streamlit |
|---|---|---|---|---|---|
| 1 | 既存 8 画面の再現性 | ○（表・フィルタ・ページング・メトリクスは自然。グラフは ECharts） | ◎（最も柔軟） | ○ | ◎（現状そのもの） |
| 2 | 開発・保守の容易さ | ○（Kotlin + テンプレ。言語 1 つ） | △（TS/React/ビルド/依存管理が追加） | ○ | △（Python 側を別運用） |
| 3 | 最新安定版の採用 | ○（Thymeleaf は Boot BOM 管理 3.1.5、htmx 2.0.x、ECharts 6.1.0） | ○（React 19.3.1、Vite 8.3.5、TS 7.0.2 ほか。更新頻度が高く追従コスト大） | △（Boot 4 対応の確認が必要） | ○ |
| 4 | Spring Boot 親和性 | ◎（Spring MVC/Security と一体） | ○（API + CORS/CSRF 設計が必要） | ◎ | × |
| 5 | Cloud Run 費用 | ◎（1 コンテナ、スケールゼロ、SSR で CPU 軽微） | ○（同 1 コンテナに静的同梱なら同等） | △（常時 WebSocket/セッション→スケールゼロと相性悪） | △（別サービス+常時稼働寄り） |
| 6 | 構成の単純さ | ◎ | ○ | ○ | △（言語・認証が別） |
| 7 | セキュリティ | ◎（JS 面が小さい、CSP 厳格化が容易、サーバ側認可） | ○（トークン管理/XSS 面/依存脆弱性が増える） | ○ | △（認可を別実装） |
| 8 | 画面拡張性 | ○（部分更新で十分。複雑な状態管理は不得手） | ◎ | ○ | △ |
| 9 | スマホ対応 | ○（CSS で対応。実績十分） | ◎ | △（レスポンシブ調整が難） | △ |
| 10 | チャート・表・フィルタ | ○（ECharts + サーバ側フィルタ/ページング） | ◎ | ○ | ◎ |
| 11 | 初期 2 人利用の実装工数 | ◎（最小） | △（約 1.5〜2 倍想定） | ○ | ◎（既存流用）だが認可・テナント分離で再実装が必要 |
| 12 | 100〜1,000 人への拡張 | ○（SSR は水平スケールしやすい。ステートレス） | ◎ | △ | △ |
| — | **要件との整合** | ◎ ステートレス/スケールゼロ/Kotlin 統一/ IAP 前提 | ○ | × スケールゼロ非整合 | × 言語統一・テナント分離に不向き |

### 4.3 選定案（D-032）
**A（Thymeleaf SSR + htmx + ECharts）を採用案とする。**
理由:
1. 現行ダッシュボードは「フィルタ → 表・メトリクス・チャート表示」が中心の**読み取り専用**で、SSR + 部分更新で十分に再現できる。複雑なクライアント状態管理が必要な画面は無い。
2. 単一コンテナ・Node ビルド無し・言語は Kotlin に統一でき、**初期 2 人利用の工数と保守が最小**。認可・テナント分離・CSRF をサーバ側に集約できる（ブラウザに渡すデータが少ない）。
3. Cloud Run のスケールゼロと相性が良く、SSR の CPU 使用は小さい。
4. 将来の B への移行余地を残す: **サービス層は JSON API（`/api/**`、DTO）を常に提供**し、画面は同じサービスを呼ぶ。SPA 化が必要になった場合は API を再利用できる。
5. B の優位（豊富なインタラクション・100〜1,000 人規模）は現時点で要件化されていない（一般公開は規約上も当面行わない: DATA_SOURCE_COMPLIANCE #8）。
**見直しゲート**: 第4段階終了時に、(a) 同時に多数のクライアント状態を持つ画面、(b) 100 人超の利用計画、(c) オフライン/PWA の要望 のいずれかが出た場合に B（または A の島状 SPA）を再評価。
**採用しない理由**: B=初期工数/保守増・攻撃面増、C=スケールゼロと不整合、D=言語・認証・テナント分離を別実装する負担（ただし**移行完了までの暫定ビューア**としては旧 Streamlit を参照元で使用可）。

### 4.4 A の構成方針
| 項目 | 方針 |
|---|---|
| ルーティング | `/`（Overview）、`/races/{raceKey}`、`/races/{raceKey}/horses/{umaban}`、`/performance`、`/signals`、`/evaluations`、`/ops`、`/model`、`/calendar`、`/admin/**` |
| 部分更新 | htmx（`hx-get` + `hx-target` + `hx-push-url`）。フィルタ変更・ページング・ソート・ドリルダウンはフラグメントを返す。**JS を書くのはチャート初期化のみ** |
| チャート | ECharts（自己ホスト、`/static/vendor/…`、CDN 不使用=CSP と可用性のため）。データは `<script type="application/json">` で埋め込み or `/api` から取得 |
| 状態管理 | **サーバ側セッションなし**（ステートレス）。ユーザー ID は IAP の JWT ヘッダ検証から都度導出。フィルタ状態は URL クエリ |
| API と DTO | `/api/**` は JSON DTO を返す（DB Entity 非公開）。HTML フラグメントも同じサービス層を呼ぶ。バージョニングは `/api/v1` を採用 |
| 認証/認可 | 直接 IAP の JWT（`X-Goog-IAP-JWT-Assertion`）を**アプリ側でも検証**（audience/issuer/署名）、`app_user` で テナント・ロールを解決、**未登録=403（deny by default）**。管理操作は `ROLE_ADMIN` + 監査ログ |
| CSRF | ステートレスな CSRF（署名付きトークン or Double Submit Cookie）。htmx は `hx-headers` でトークン付与。GET は副作用なし |
| CSP | `default-src 'self'; script-src 'self' 'nonce-…'; style-src 'self'; img-src 'self' data:; frame-ancestors 'none'`。htmx は `allowEval=false`、`selfRequestsOnly=true` |
| ページング | 全一覧はサーバサイド（`LIMIT/OFFSET` から keyset へ。大テーブルは日付範囲必須） |
| キャッシュ | 集計 API は 30 秒の短期キャッシュ（Caffeine、テナント+パラメータをキー）。更新時に無効化しない（簡素） |
| i18n | `messages_ja.properties` のみ（将来拡張に備えキー化） |
| テスト | MockMvc/Thymeleaf のレンダリングテスト、API 契約テスト、認可マトリクス（テナント越境・ロール）、ブラウザ E2E は Playwright（Chromium 同梱）で主要 3 画面 + スマホ幅のスクリーンショット比較 |
| ビルド | Gradle のみ。静的リソース（ECharts、htmx、CSS）は `src/main/resources/static` にバージョン固定で同梱（Dependabot 対象外のため `VERSIONS.md` で管理、SRI ハッシュ付与） |
| 配布 | `dashboard` コンテナ 1 つ（ingest/bot とは別） |

### 4.5 バックエンド API の必要性（まとめ）
全画面で DTO 返却の API が必要（§2 の API ★）。**集計はサーバ側 SQL/サービスに実装**し（UI でループしない）、`tenant_id` を必ず条件に含める（データ分離）。API とテンプレートが同一サービス層を使うため API 仕様は画面とずれない。

## 5. 画面移行の優先順位（第4段階の入力）
1. 共通レイアウト + 状態バー + 認可（deny by default）
2. Overview、Race Detail（オッズ推移）、Signals — 日常利用の中心
3. Performance（3 基準 + ドリルダウン）、Evaluations
4. Ops/Health（クラウド版）、Model（レジストリ）
5. Horse Detail、カレンダー、管理操作（補正・手動起動・エージェント）、設定サマリ

## 6. 未確認・リスク
| # | 内容 | 対応 |
|---|---|---|
| 1 | Thymeleaf 3.1.5 / Spring Security 7.1 / htmx 2.0.x の組合せは Boot BOM と npm の版確認のみで実走未検証 | T2.1 の疎通ビルド・第4段階冒頭のスパイク |
| 2 | 直接 IAP の JWT 検証（audience/ヘッダ）の実装詳細は公式 docs に到達できず未確認 | T4.1 で公式手順を確認（実リソース PoC は承認後） |
| 3 | 旧 `04_Odds`/`05_Config` に相当する画面が新版 Streamlit に無かった点 | 本書のとおり維持 |
| 4 | Performance 3 系統の正準化（定義差） | T1.10 で決定（D-059）: 統合せず 3 基準として定義を明示（評価基準=判定品質、台帳基準=実際の損益の正、シグナル基準=補助）。各基準の計算は旧 `calculate_*` と一致させる（DATA_MIGRATION_DESIGN §6.3）。既定表示は evaluations（旧どおり） |
| 5 | 8 画面すべてのウィジェットを網羅したわけではない（主要なフィルタ/表示/グラフを抽出）。実装時に画面ごとに旧画面とのスクリーンショット/数値比較を行う | 第4段階 |
