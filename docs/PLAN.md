# PLAN（段階・タスク・依存）

凡例: `[ ]`未着手 `[x]`完了 / `←` は依存。リポジトリ: **W**=jra-bot-web **I**=jra-bot-infra。セッションはリポジトリ単位で継続可能。

## 第0段階 ― リポジトリ確認・要件・計画（今回）
- [x] T0.1 3リポジトリのアクセス/visibility/default branch/書込権限確認（W,I）
- [x] T0.2 参照元の概観調査（変更なし）
- [x] T0.3 CLAUDE.md / docs 一式作成（W,I）
- [x] T0.4 作業ブランチで commit・push（W,I）
- [x] T0.5 PR 作成（main は Stage 0 のコミットで初期化済み。D-001 解消）

## 第1段階 ― 詳細調査・費用比較・技術選定・設計
- [x] T1.1 参照元の機能棚卸し → `MIGRATION_MATRIX.md`（2026-10-22 完了。精読が必要な箇所は同ファイル §4「精読範囲」に TODO 化）(W)
- [x] T1.2 JV-Link 調査（2026-10-22 設計完了: JVLINK_DESIGN.md / DATA_SOURCE_COMPLIANCE.md。公式一次資料は未確認＝T1.17）：Windows/32bit/COM、データ種別、取得頻度、エージェント方式、**JRA-VAN 利用規約**（2人利用 vs 一般公開、クラウド保管・再配布）(W) ← T1.1
- [x] T1.3 開催日/発走予定（2026-10-22 設計完了: RACE_CALENDAR_DESIGN.md）の取得元と、代替/中止/延期/時刻変更の検出方式設計 (W) ← T1.2
- [x] T1.4 バージョン調査 → `VERSIONS.md`（2026-10-22 暫定選定。T2.1 で最終確定）：JDK, Kotlin, Spring Boot, Gradle, Doma+doma-kotlin+processor(KSP可否), Flyway, MySQL+JDBC, Terraform+provider, GitHub Actions, フロント (W) 
- [x] T1.5 フロント選定（2026-10-22 FRONTEND_DESIGN.md。採用案 A）（Streamlit 7画面の調査 → 候補比較）(W) ← T1.1
- [x] T1.6 （infra PR #1 マージ済み: jra-bot-infra/docs/CLOUD_COSTS.md）クラウド費用比較 → `CLOUD_COSTS.md`（GCP/Azure/AWS × 条件A/B/C、無料枠とクレジット分離、停止中残額、DB停止/自動再起動仕様、確認日・通貨・為替）(I) 
- [ ] T1.7 クラウド・DB 決定（DECISIONS）← T1.6, T1.4
- [ ] T1.8 運用モード正本・STOPPED 実現方式（DB 停止/自動再起動/Terraform 非干渉）設計 (I) ← T1.7
- [x] T1.9 ML 方針（2026-10-22 ML_DESIGN.md。採用案 A）（Kotlin再実装 vs Python維持、学習/推論分離、非pickle形式）(W) ← T1.1
- [ ] T1.10 アーキテクチャ確定・CONTRACTS 0.1.0 確定 (W,I) ← T1.3,T1.5,T1.7,T1.8,T1.9
- [x] T1.11 既存 SQLite からのデータ移行方針（2026-10-22 DATA_MIGRATION_DESIGN.md。実ファイル未取得のため Runbook 化）（実DBの所在をユーザーに確認）(W)

## 第1段階 追加タスク（T1.4 で判明）
- [ ] T1.12 Spring Boot 4.2.0 GA 後の 4.2.x 移行計画（D-014）
- [x] T1.13 （JV-Link/開催日 + decision.py/bet_strategy.py/fetch_historical_data.py 精読済み 2026-10-22。MIGRATION_MATRIX §6・§7。残る未読は軽微: fetch_historical_data の全行、bet_strategy 三連複流しの中間部）MIGRATION_MATRIX の未精読箇所の精読（jvlink_provider パーサ、decision/bet_strategy の分岐、fetch_historical_data）— T1.3/T3.x の前に必要な分から

## 第1段階 追加タスク（T1.2/T1.3 で判明, 2026-10-22）
- [x] T1.15 STOPPED 時の Windows エージェント契約（CONTRACTS §7）
- [ ] T1.16 **ユーザー**: JRA-VAN への問い合わせ Q1〜Q8（DATA_SOURCE_COMPLIANCE §6）と 2 人目の契約有無の確認
- [ ] T1.17 JV-Link/JV-Data 公式仕様書（SDK 同梱 PDF）で確認: JVOpen option 表、JVGets、JVWatchEvent、YS/TC/AV/CC/WE/JC/RA 項目位置、HR 払戻桁、RA 発走時刻の有無、0B14/0B16、64bit 版の一般提供・COM 登録（**仕様書は Git に入れず、結論のみ docs に記録**）
- [ ] T1.18 infra 側 docs への同期（CONTRACTS §7.5 のビーコン/ingest 義務、PLAN）
- [ ] T1.19 エージェント PoC 計画（第3段階冒頭で実機検証: 64bit JV-Link+pywin32、バイト取得、ハング検知）

## 第1段階 追加タスク（Stage 1-C 後半で判明, 2026-10-22）
- [ ] T1.20 **ユーザー（実環境）**: ① 実 SQLite の所在・取得、② `SELECT prob_source, COUNT(*) FROM signals GROUP BY 1` と `SELECT model_type_used, COUNT(*) FROM race_evaluations GROUP BY 1`（H1: ML が実際に使われていたか）、③ 実 `config.yaml` の値（D-016: 関数既定との食い違い項目の運用実態）、④ `*.pkl` の所在
- [ ] T1.21 ユーザー判断: ML の確率パイプライン `legacy` / `v2`（D-037）、E1〜E15 のうち挙動を変更する項目（D-044）、フロント案 A の承認（D-032）
- [ ] T1.22 MIGRATION_MATRIX §7.3 の E 項目ごとの新仕様決定（T1.10 で一括）

## 第2段階 ― 基盤（W）
- [ ] T2.1 Gradle KTS マルチプロジェクト雛形、Version Catalog、依存検査（bot⇄dashboard禁止）← T1.4,T1.10
- [ ] T2.2 :domain 値オブジェクト（Odds, Probability, Money 等）・Repository interface
- [ ] T2.3 :db-migration Flyway V1（MySQL。DATA_MIGRATION_DESIGN §2・§3 の DDL。V2=カレンダー/ingest/ml_model/app_user）← T1.10
- [ ] T2.4 :persistence Doma DAO/Entity/変換（Testcontainers 統合テスト）← T2.2,T2.3
- [ ] T2.5 CI（build/lint/unit/DB統合/イメージ）、Renovate/Dependabot ← T2.1
- [ ] T2.6 ゴールデンテスト基盤（旧実装出力フィクスチャ生成スクリプト、実データ非含有）

## 第3段階 ― bot（W）
- [ ] T3.1 EV/Kelly/確率/判定/急落フィルタを :domain へ移植し旧実装と比較 ← T2.2,T2.6
- [ ] T3.2 ワイド/三連複 確率・推定オッズ・bet_strategy 移植
- [ ] T3.3 開催日制御（RACE_CALENDAR_DESIGN の実装: スキーマ、calendar-check、bot-run リース、手動補正、C01〜C28 のテスト）← T1.3,T2.4
- [ ] T3.4 取込 API とデータ取得（冪等 upsert、バックフィル、再開、多重起動防止）← T2.4
- [ ] T3.5 Windows 取得エージェント（JVLINK_DESIGN の実装: 薄い Python、キュー、ビーコン、署名更新）+ `:ingest` サービス ← T1.2,T1.17,T3.4
- [ ] T3.6 ML: Kotlin `FeatureBuilder`/`ProbabilityPipeline`、モデル JSON/レジストリ、`export-dataset`、`ml-train`（Python Job）、pkl→JSON 変換ツール、ゴールデン G1〜G7 ← T1.9
- [ ] T3.7 Discord 通知（分割・Retry-After・リトライ）
- [ ] T3.8 bot-run オーケストレーション（監視ウィンドウ、結果反映後終了）← T3.1–T3.7
- [ ] T3.9 バックテスト・成績集計（settle/recovery）

## 第4段階 ― dashboard（W）
- [ ] T4.1 認証/認可（IAP JWT 検証、許可リスト、ロール、deny by default）← T2.4
- [ ] T4.2 API（DTO、Entity 非公開）← T4.1
- [ ] T4.3 UI（日本語、レスポンシブ、最終更新、bot状態、次回予定、未更新表示）← T1.5,T4.2
- [ ] T4.4 管理者操作（手動補正等）、モード表示
- [ ] T4.5 セキュリティテスト（認可マトリクス）

## 第5段階 ― インフラ（I、一部 W）
- [ ] T5.1 Terraform 基盤（state、WIF、ネットワーク、Secret、Registry）← T1.7
- [ ] T5.2 Web/Job/DB/Scheduler リソース、IAP、スケールゼロ
- [ ] T5.3 deploy workflow（release-manifest 入力、migrate→更新）
- [ ] T5.4 start/stop/status workflow_dispatch、排他（concurrency）、部分失敗表示、安全な再実行
- [ ] T5.5 Terraform が mode/DB 起動状態を変更しないことの検証（plan 差分テスト）
- [ ] T5.6 監視/アラート/予算、ロールバック手順
- [ ] ※ apply・課金リソース作成は **ユーザー承認後**

## 第6段階 ― 統合・レビュー
- [ ] T6.1 旧新比較の総合結果 → `TEST_RESULTS.md`
- [ ] T6.2 セキュリティレビュー
- [ ] T6.3 `OPERATIONS.md`、ドキュメント整理
- [ ] T6.4 PR 整理（W,I）

## 依存の要点
T1.1 → T1.2 → T1.3 → T3.3 / T1.4,T1.7,T1.10 → T2.x / T1.6 → T1.7 → T5.x。第1段階の T1.6/T1.8（I）は T1.1〜T1.5（W）と独立に別セッション可。
