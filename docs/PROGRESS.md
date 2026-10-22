# PROGRESS（jra-bot-web）

最終更新: 2026-10-22（第1段階 / T1.1・T1.4 完了）

## 現在の段階
**第1段階 進行中**。完了: T1.1, T1.4。残り（別セッション）: T1.2, T1.3, T1.5, T1.6(infra), T1.7, T1.8(infra), T1.9, T1.10, T1.11, T1.12, T1.13。

## 完了済み
- 第0段階一式（main に取り込み済み, 2c59374）
- **T1.1** `docs/MIGRATION_MATRIX.md`: 機能 26 項目（F01–F26）の分類・移行方法・リスク・優先度・テスト方法、scripts/ 約 74 本の棚卸し、DB 15 テーブルの移行対象、横断リスク
- **T1.4** `docs/VERSIONS.md`: 版数の暫定選定、互換性表、Doma×Kotlin(kapt/KSP) 調査、フロント候補
- `DECISIONS.md` に D-009〜D-016、`PLAN.md` 更新

## 主な発見（次セッション向け要約）
1. 参照元は **開催日/発走変更の検知ロジックを持たない**（取得できた Race の日付で判定）→ 新規設計（T1.3）。
2. `utils/time.py` は naive `datetime.now()`（TZ 依存）→ Clock(Asia/Tokyo) 注入が必須。
3. ML は **numpy ロジスティック回帰 + Platt + 市場ブレンド**で、重み JSON エクスポート → Kotlin 推論が現実的（pickle は引き継がない）。学習は Python 維持。
4. 設定の関数既定値と config.example の既定値が食い違う（D-016）。
5. 成績集計ロジックが UI モジュール内（`ui/db_read*.py` 約 2k 行）、signals/bets/evals の 3 系統。
6. Doma は **kapt のみ公式**（KSP なし）。Kotlin 2.4.21 + kapt は Doma CI で検証済み（D-009）。
7. Spring Boot 4.1.x の OSS サポートは 2027 年前半までの見込み。4.2 は RC1 公開済み（D-014）。
8. Cloud SQL MySQL 9.7 は **エディション未指定だと Enterprise Plus が既定**（費用注意, D-015）。
9. このクラウド環境から docs.spring.io / readthedocs / docs.gradle.org / dev.mysql.com 等に接続不可。Maven Central・GitHub・services.gradle.org・releases.hashicorp.com・npm・kotlinlang.org は可。WebSearch は可。

## テスト結果
該当なし（調査・文書のみ）。Flyway jar のクラス版数を実測した以外に実行検証はない。版数の疎通は T2.1。

## 未完了・未解決事項
- ユーザー確認待ち（Stage 0 から継続）: 既存実 DB/モデルの所在、JV-Link 用 Windows PC、JRA-VAN 規約範囲（T1.2）、GCP プロジェクト/請求。
- 要確認（VERSIONS §4）: Boot 4.1 サポート終了日、Kotlin 2.4.21×Boot 4.1.2、Kotlin×JDK 25、Flyway×MySQL 9.7、Connector/J 26.x の位置づけ、doma compile plugin の版。
- MIGRATION_MATRIX の未精読箇所（§4-8）。

## 次に実行する内容（推奨順）
1. **T1.6 / T1.8（infra リポジトリ・別セッション）**: CLOUD_COSTS.md、運用モード方式。MySQL エディション（Enterprise 指定）を前提に評価。
2. **T1.2**（JV-Link・規約）→ **T1.3**（開催日設計）→ **T1.5**（フロント）→ **T1.9**（ML）→ **T1.7/T1.10**（決定・設計確定）。
3. T1.13 精読は T1.3/T1.9 と並行して必要箇所から。

## Git
- ブランチ: `claude/stage1-research-versions`（base: main 2c59374）
- コミット/PR: 下記に push 後記録
