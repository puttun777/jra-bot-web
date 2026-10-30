# 公開前レビュー（jra-bot-web。2026-10-30）

目的: ポートフォリオとして Public にする前の確認。**この文書は公開設定を変更しない**（Visibility の変更は所有者だけ）。スキャンが通ったことは「公開して絶対に安全」を意味しない。秘密値そのものはここに書かない。

## 1. 確認した範囲
| 対象 | 方法 | 結果 |
|---|---|---|
| 現在のコード・設定・サンプル | CI の `repo-hygiene`（`.github/scripts/check-repo-hygiene.sh`）と同じ規則の手元確認、下の履歴スキャン | 追跡禁止の拡張子・PDF・jar（wrapper 以外）・2MB 超なし |
| 全ブランチ・タグから到達可能な履歴 | 32 の ref（ローカル・リモートのブランチ。タグは 0）と PR の head 29 件を取得し、到達可能な 144 コミットを `git grep -E` で検査。パターン: 秘密鍵ヘッダ、Discord Webhook URL、AWS アクセスキー ID、Google API キー、GitHub トークン、Slack トークン、JWT 形、`"private_key":`、`sk-` 形 | **該当は秘密鍵ヘッダのみ**: `ErrorReporterTest.kt`・`test_dockerfile.py` の**ダミー文字列**（本文は 14 文字の `MIIBVQIBADANBg` 等で、鍵ではない。長い Base64 行なし）|
| 履歴に入ったファイル名 | `git log --all --name-only` | 禁止拡張子（`.sqlite` `.db` `.pkl` `.pem` `.key` `.tfstate` `.tfvars` `.pdf` `.env` 等）は一度も追跡されていない。`.csv` 2 件は合成の ML 試験データ（`synthetic-*.csv`）、`gradle-wrapper.jar` は標準の Wrapper |
| 大きな blob（1MB 超） | `git cat-file --batch-check` | `tools/golden/fixtures/` の合成フィクスチャ JSON 3 件（約 1.3MB）。合成入力から旧実装で生成したもの |
| 個人情報（メール・電話・ホームディレクトリのパス） | 履歴に対する正規表現 | 個人のメールアドレスなし。コミットの作者は `noreply` 形式（`Claude`、所有者の GitHub noreply、dependabot）。電話番号形の一致は数値の試験データ（偽陽性）。`/home/<名前>/`・`C:\Users\<名前>` なし |
| Actions の成果物 | API で一覧 | 41 件、全て `images-dry-run`（最大 約 6.5KB、保持 14 日、未失効）。manifest プレビューとスモーク結果のみ |
| 分岐保護 | API | **Free プランの Private では設定不可**（API が 403「Upgrade to GitHub Pro or make this repository public」）。Public にすれば設定できる |

## 2. 確認できていない範囲（公開前に所有者または次回セッションで）
1. **Actions の実行ログ全文**（112 回分）: ログ取得 API はこのセッションのプロキシ経由では使えなかった。ログに秘密が出ない設計（Secrets 不使用、`contents: read`）ではあるが、**中身は未確認**。公開すると過去のログも見える（Private→Public でログの可視性が変わる）ため、不安なら公開前に所有者が不要な実行ログを削除する（**削除は私が勝手に実行しない**）。
2. PR・Issue のコメント本文、Dependabot の PR 本文、Wiki（無効）、リポジトリ設定（Secrets 一覧・Deploy keys・Webhooks）。
3. GitHub 内部に残る到達不能オブジェクト（削除済みブランチ・過去の PR の force push 前の内容）。`refs/pull/*/head` は上で確認したが、`refs/pull/*/merge` や到達不能コミットは未確認。
4. 依存ライブラリのライセンス一覧の網羅（`NOTICE.md` は主要なものと旧実装の MIT のみ）。第三者コードの取り込み（コピー）の有無の網羅的な確認（ソースにコピーした記憶・記録は無いが、機械的には未確認）。
5. GitHub のシークレットスキャン機能（push protection / 既知パターンの検出）は有効化していない・結果も未確認。有効化は所有者が設定する。
6. 学習済みモデル・JV-Link SDK・実データが**含まれていない**ことは、拡張子・履歴・`repo-hygiene` で確認したが、内容が偽装された巨大でない JSON 等までは確認していない。

## 3. 認証情報について
今回の検査では、**実在する認証情報は見つからなかった**。見つかった場合の手順（参考）: 場所と種類だけを記録 → 発行元で失効・再発行 → 履歴からの除去（`git filter-repo` 等＋force push）は影響が大きいので所有者が判断 → 現在のファイルから消すだけでは解決にならない（履歴・fork・キャッシュに残る）。

## 4. 権利・ライセンス（所有者の判断が必要）
- 現状: **ライセンスなし（All rights reserved）**。旧実装 `puttun777/jra-bot` は MIT（同一所有者）で、その由来部分の表示は `NOTICE.md`。
- JRA-VAN の規約上、**ソース公開の許可は得ていない**（`docs/UNRESOLVED.md` E-7）。データ・SDK・仕様書は含まない。
- ライセンスの選択肢（**私は確定しない**）:
  - A. 現状維持（All rights reserved）: 閲覧のみ。ポートフォリオの目的には足りる。再利用を許さない。**推奨（E-7 と COMP-4 が解決するまで）**。
  - B. MIT: 旧実装と揃う。簡単で採用担当者に分かりやすい。許諾を取り消せない点に注意。
  - C. Apache-2.0: 特許条項つき。依存の多くと揃う。
  - 判断の前に: E-7 の回答、`tools/golden/fixtures`（旧実装由来の合成データ）と `NOTICE.md` の整合、第三者表示の網羅（上の 2-4）。
- 公開してもアプリの許可ユーザー制限は緩めない（本人専用。サービスは非公開）。

## 5. 公開前に所有者が行う操作（私は実行しない）
1. Actions ログ・成果物の扱い（上の 2-1）を決める。
2. GitHub の Secret scanning と Push protection を有効化。公開後に分岐保護（required check `ci gate`）を設定。
3. ライセンスの決定（上の 4）。
4. E-7（JRA-VAN への確認）。
5. Visibility を Public に変更するかの最終判断（`docs/DECISIONS.md` D-180）。
