# jra-bot-web — Claude 共通ルール

JRA Bot (参照元 `puttun777/jra-bot`, Python/SQLite/Streamlit) を Kotlin/Spring Boot の Web アプリへ段階的にリプレースするアプリ側リポジトリ。
インフラは別リポジトリ `puttun777/jra-bot-infra`（Terraform / GitHub Actions デプロイ / 起動停止）。

## 絶対ルール
1. 参照元 `jra-bot` は **読み取り専用**。変更・commit・push・PR をしない。
2. 本リポジトリと infra は **Private 維持**。新規リポジトリを作らない。
3. `main` へ直接 push しない。作業ブランチ `claude/<stage>-<topic>` → PR。**マージはユーザーが判断**。
4. 承認なく課金リソースを作成しない／実データを削除しない。クラウド操作は計画・コードまで（適用はユーザー承認後）。
5. 秘密情報・実データ（JV-Link 由来データ, DB ダンプ）・学習済みモデル・ビルド生成物を Git に含めない。
6. 版数は公式情報で確認し固定（Version Catalog）。Alpha/Beta/RC/Snapshot は採用しない。推測で決めない。
7. 既存機能を黙って削除しない。必ず `docs/MIGRATION_MATRIX.md` に分類（Kotlin移行 / 別プロセス維持 / 段階移行）を残す。
8. 追加のAPI従量課金を前提にしない。

## 進め方（Claude Pro 節約）
- 単一エージェント。Agent Teams 不使用。サブエージェントは独立調査/レビューで明確に有利な時のみ・同時最大1。
- 調査済みの内容は docs に保存し再利用。関連ファイルだけ読む。ログ全文を出さない。
- 小さな単位で実装→テスト→commit。テスト・セキュリティ検証は省略しない。報告は簡潔に。

## 作業開始時
1. `docs/PROGRESS.md`（現在の段階・次の作業）→ `docs/PLAN.md`（該当タスクと依存）を読む。
2. `git status` / ブランチ確認。マージ済みブランチなら default から作り直す。
3. 必要な時だけ `docs/REQUIREMENTS.md` `ARCHITECTURE.md` `CONTRACTS.md` `DECISIONS.md` を参照。

## 作業終了時（中断・使用量上限の前にも）
1. テスト実行結果を確認。失敗は隠さず記録。
2. `docs/PROGRESS.md` を更新（完了/未完了/変更/テスト結果/判断/未解決/次の作業/ブランチ・commit）。
3. 判断は `docs/DECISIONS.md` に ID 付きで追記。契約変更は `docs/CONTRACTS.md`（infra 側と整合）。
4. commit → `git push -u origin <branch>`（ネットワーク失敗時のみ 2/4/8/16 秒で再試行）。

## ドキュメント一覧（正本）
| ファイル | 内容 | 正本 |
|---|---|---|
| docs/REQUIREMENTS.md | アプリ全体の要件（正本） | web |
| docs/PLAN.md | 段階・タスク・依存 | web（infra は参照） |
| docs/PROGRESS.md | 進捗 | リポジトリ別 |
| docs/DECISIONS.md | 判断記録 | リポジトリ別 |
| docs/ARCHITECTURE.md | モジュール/責務/データ | web |
| docs/CONTRACTS.md | web⇔infra の成果物・環境変数・API 契約 | **web が正本**、infra 側は同期コピー参照 |
| docs/MIGRATION_MATRIX.md 他 | 第1段階以降で作成 | 作成時に決定 |

## モジュール（Gradle Kotlin DSL マルチプロジェクト）
`:domain` `:persistence` `:db-migration` `:bot` `:dashboard`。bot と dashboard は相互依存しない。domain は Doma/クラウド SDK 非依存。詳細は ARCHITECTURE.md。

## 規約
- 言語: コード識別子は英語、ドキュメント/UI/コミット本文は日本語可。タイムゾーンは Asia/Tokyo。
- コミットは意味のある単位。メッセージ末尾の attribution は環境指示に従う。
