# PROGRESS（jra-bot-web）

最終更新: 2026-10-22（第1段階 / Stage 1-C 後半: T1.5・T1.9・T1.11・T1.13 完了）

## 現在の段階
**第1段階 ほぼ完了（設計・調査）**。完了: T1.1〜T1.6, T1.9, T1.11, T1.13, T1.15。
残り（Stage 1-D）: **T1.7（クラウド選定）、T1.8（運用モード方式の確定）、T1.10（設計確定）**、T1.12（Boot 4.2 移行計画）、ユーザー確認 T1.16/T1.20/T1.21。

## 今回の完了（claude/stage1-ui-ml-migration）
- `docs/FRONTEND_DESIGN.md`: Streamlit **8 ページ**（依頼の 7 + Evaluations）+ 旧 `ui/` の画面別調査（目的・表示・操作・フィルタ・更新・API・認可・モバイル）、新規画面（状態バー・カレンダー・管理操作）、技術比較（A Thymeleaf+htmx / B React+TS+Vite / C Vaadin / D Streamlit 維持）、**採用案 A**、構成方針、見直しゲート
- `docs/ML_DESIGN.md`: 現行 ML の精査、**疑義 H1〜H10**、方式比較（**採用案 A: 学習=Python 独立 Job / 推論=Kotlin**）、特徴量の時点整合（Kotlin に一本化）、JSON モデル形式（スキーマ・署名）、レジストリ、フォールバック、推論パイプライン（legacy / v2）、数値許容、ゴールデン G1〜G7、移行手順
- `docs/DATA_MIGRATION_DESIGN.md`: 15 テーブルの棚卸し（動的追加列含む）、SQLite 固有 SQL/時刻混在、MySQL スキーマ方針と DDL 案、Doma 整合、Flyway/後方互換、**テナント設計**、移行パイプライン P0〜P6（チャンク再開・隔離・検証・ロールバック）、並行稼働/カットオーバー、保持ポリシーと費用、実ファイル取得後の Runbook
- `MIGRATION_MATRIX.md` §7: decision.py / bet_strategy.py / fetch_historical_data.py の精読結果（設定優先順位、暗黙の既定値、欠陥候補 E1〜E15、ゴールデン GD01〜GD10）
- `ARCHITECTURE.md` §8、`CONTRACTS.md` 0.3.0（ingest/ml-train イメージ、モデル成果物・データセット・署名鍵、§5.1）、`RACE_CALENDAR_DESIGN.md`（識別子を `race_id`+`kai/nichime` に更新）、`DECISIONS.md`（D-032〜D-044）、`PLAN.md`

## 主な発見（次セッション向け）
1. **旧 ML は本番で市場確率にサイレントフォールバックしていた可能性が高い（H1）**。同梱メトリクスは `calibration_skipped`・alpha のみ（35 次元）。`retrain_models.py` が alpha の `feature_names` を 35 名にし、`decision.py` は 40 名と比較して不一致→例外握り潰し。`scripts/train.py` 経由の alpha なら 5 列ずれる（H2）。**`signals.prob_source` の分布で実証できる**。
2. **学習のラベルリークの疑い（H3）**: 過去走の集計が対象レース日で絞られておらず、同梱 alpha メトリクスは AUC 0.91 と不自然（Brier 0.143 は一定予測 ≈0.065 より悪い）。
3. **市場ブレンドが二重に適用**（モデル寄与 ≈0.25×0.25）、Kelly の確率ソース係数が常に 0.8（実効 0.2）、`odds=1.0` で ZeroDivision→無言で除外、見送り理由の集計漏れ、など E1〜E15。
4. 設定の関数既定値と example config の食い違いが多数（EV 閾値 0.08/0.30、ev_shrink 0.7/0.55 ほか）。実運用値は利用者の実 `config.yaml` が必要。
5. SQLite は **JST のローカル時刻（T 区切り）と UTC（CURRENT_TIMESTAMP、スペース区切り）が混在**。`INSERT OR REPLACE` が 11 箇所、`key`/`rank` は MySQL 予約語。レースは `race_id`(12桁) で足りるため**回・日目なしでも移行可能**。
6. combo オッズを保存する設定にすると**年 2.7 億行規模**。既定は保存しない設計に。
7. 旧 Streamlit は**完全に読み取り専用・認証なし**。管理操作・認可・テナント分離は新規要件。
8. 旧 `fetch_historical_data.py` は **Win32 の UI オートメーション**でセットアップ・ダイアログを自動応答。新設計では採用しない（対話セッションで利用者が起動）。

## テスト結果
該当なし（設計・調査のみ。参照元は読み取りのみ。数値の確認は参照元の同梱 JSON と、コード読解による）。

## 未完了・未解決（ユーザー判断/確認）
1. **T1.20（実環境）**: 実 SQLite の所在、`prob_source`/`model_type_used` の分布（H1）、実 `config.yaml`、`*.pkl` の所在。
2. **T1.21（判断）**: フロント案 A の承認（D-032）、ML の `legacy`/`v2`（D-037）、E1〜E15 のうち挙動を変更するもの（D-044）。
3. **Stage 1-C 前半から継続**: JRA-VAN 問い合わせ Q1〜Q8、2 人目の契約有無、一般公開は当面行わない承認、T1.17（公式仕様書での確認）。**2 人目の利用方法・クラウド保存の適法性は未確認のまま**（DATA_SOURCE_COMPLIANCE）。移行は「ローカル MySQL でリハーサル → 規約回答後にクラウド」の段階制。
4. T1.7（クラウド選定）、T1.8（STOPPED 方式確定）、T1.10（設計確定: `:ingest` 追加、tenant_id、race_id、パフォーマンス 3 系統の正準化、E 項目の新仕様）。
5. 環境制約: 公式ドキュメント（jra-van.jp、docs.* 等）に接続不可。Doma の `Instant`/JSON/UPSERT 対応、Thymeleaf+htmx の Boot 4.1.2 での実走、IAP JWT 検証の公式手順は未確認（各文書に明記）。

## 次に実行する内容
- **Stage 1-D（推奨: Opus または Sonnet High）**: T1.7 の判断支援（CLOUD_COSTS §7 の確認結果を反映）→ T1.8 確定 → **T1.10 設計確定**（本 PR までの案 D-018〜D-044 の確定・矛盾解消、CONTRACTS 1.0 候補、Flyway V1/V2 の最終スキーマ）。infra 側 T1.18 の同期もここで。
- 並行してユーザー: T1.20（実環境データの確認）、問い合わせ Q1〜Q8。
- その後 **第2段階**（Gradle/Spring Boot/Doma/Flyway/Testcontainers 基盤）。

## Git
- ブランチ: `claude/stage1-ui-ml-migration`（base: main 180a298）
- コミット/PR: push 後に記録
