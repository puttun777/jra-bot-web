# PROGRESS（jra-bot-web）

最終更新: 2026-10-22

## 現在の段階
第0段階 完了（PR 作成のみブロック）。次は第1段階。

## 完了
- 3リポジトリの確認（下表）
- 参照元の概観調査（REQUIREMENTS §2）
- CLAUDE.md, docs/{REQUIREMENTS,PLAN,PROGRESS,DECISIONS,ARCHITECTURE,CONTRACTS}.md, .gitignore
- infra 側にも同等の docs 一式（別リポジトリ）

## リポジトリ確認結果
| repo | visibility | default | 状態 | 書込権限 |
|---|---|---|---|---|
| puttun777/jra-bot | **Public** | main (f7dbf69) | 内容あり、Python 約3万行、MIT | admin 権限あり（**変更しない**） |
| puttun777/jra-bot-web | Private | main（名目。実体は未作成） | **空**（コミット0） | admin/push 可 |
| puttun777/jra-bot-infra | Private | main（名目。実体は未作成） | **空**（コミット0） | admin/push 可 |
制約: 本セッションは 3 リポジトリを clone 済みで複数 push 可。ただし GitHub 操作は MCP ツール（gh 不可）。参照元は一切変更していない（`git status` clean）。

## 未完了
- PR 作成（D-001: base となる `main` が無い）
- 第1段階以降すべて（PLAN.md）

## テスト結果
該当なし（文書のみ）。

## 判断事項
DECISIONS.md D-001〜D-008。

## 未解決事項（ユーザー確認が必要）
1. **PR 用 `main` の作成**: 空リポジトリのため。どちらかを依頼:（a）GitHub 上で各リポジトリに README だけで初期化（Add a README）、または（b）「README のみの初期コミットを main に作ってよい」と指示。
2. 既存の実データ（SQLite DB、parquet、pkl）の所在と移行可否（Git 外）。
3. JV-Link を動かせる Windows 環境（常時 PC 等）を取得エージェントに使えるか。
4. JRA-VAN 利用規約の範囲（2 人利用、クラウド保管、将来公開）— 第1段階 T1.2 で一次情報確認。
5. GCP プロジェクト/請求の有無（第5段階まで不要）。

## 次に実行する内容（第1段階の開始条件）
- 開始条件: 上記1が解決していれば PR を作成して第0段階を締める（解決前でも第1段階の調査は着手可。ただし同ブランチを継続せず、PR 作成後は main から再作成）。
- 最初のタスク: T1.1 MIGRATION_MATRIX.md、T1.4 VERSIONS.md（W）。T1.6 CLOUD_COSTS.md（I、別セッション可）。

## Git
- ブランチ: `claude/stage0-bootstrap`（main 未作成）
- コミット: 下記を参照（push 後に更新）
