# jra-bot-web — Claude 共通ルール

JRA Bot (参照元 `puttun777/jra-bot`, Python/SQLite/Streamlit) を Kotlin/Spring Boot の Web アプリへ段階的にリプレースするアプリ側リポジトリ。
インフラは別リポジトリ `puttun777/jra-bot-infra`（Terraform / GitHub Actions デプロイ / 起動停止）。

## 利用者・利用目的（2026-10-23 要件変更）
このシステムは**開発者本人 1 名だけ**が、**私的・非営利**で使う（JRA-VAN 契約者も本人）。第三者へのデータ提供・共有、商用、広告、サービスの一般公開はしない。**ソースコードの公開（Public リポジトリ化）とサービスの一般公開は別**（Public 化の判断は D-180。Visibility の変更はユーザーだけが行う。公開しても本番 GCP・DB・Discord は非公開のまま）。`tenant_id`・テナント境界・認証認可は省略しない。新規利用者の登録・招待・共有機能は作らない。詳細は `docs/REQUIREMENTS.md` §0。
**規約の確認と、実データのクラウド投入の確認は別。公式回答（`docs/UNRESOLVED.md` COMP-4）が得られるまで実データをクラウドへ投入しない**（テストは合成データ）。

## 絶対ルール
1. 参照元 `jra-bot` は **読み取り専用**。変更・commit・push・PR をしない。
2. **infra は Private 維持**。本リポジトリの Visibility は**ユーザーだけが変更する**（Claude は変更しない。Public 化後も実データ・秘密・学習済み実モデル・JV-Link SDK 資料を Git に入れない: 5）。新規リポジトリを作らない。
3. `main` へ直接 push しない・保護設定を迂回しない。作業ブランチ → PR。**通常の開発 PR のマージは、下の『開発の委任（2026-11-01）』の条件を満たしたとき Claude が行ってよい**。本人判断事項（下記）を含む PR はマージしない。
4. 承認なく課金リソースを作成しない／実データを削除しない。クラウド操作は計画・コードまで（適用はユーザー承認後）。
5. 秘密情報・実データ（JV-Link 由来データ, DB ダンプ）・学習済みモデル・ビルド生成物を Git に含めない。
6. 版数は公式情報で確認し固定（Version Catalog）。Alpha/Beta/RC/Snapshot は採用しない。推測で決めない。
7. 既存機能を黙って削除しない。必ず `docs/MIGRATION_MATRIX.md` に分類（Kotlin移行 / 別プロセス維持 / 段階移行）を残す。
8. 追加のAPI従量課金を前提にしない。

## 言語方針（2026-11-02 要件変更。D-250）
本人がコードを読んで保守できるよう、**自分たちで保守する Python を通常開発・CI・運用の必須経路から外す**（Python を残すことを前提にしない）。移行先: アプリ・業務ロジック・**ML 学習/評価**・DB 移行ツール = Kotlin/JVM、infra の資源定義 = **Pulumi + TypeScript**（CDKTF は不採用）、運用 CLI・CI/リリース補助・フロント = **TypeScript**（strict。any・二重キャスト・non-null assertion で検査を回避しない。外部入力は実行時検証）、Windows 取得エージェント = **C#/.NET 10**（同じリポジトリの `agent/`）。Python をシェル・コンテナに隠す／Python で計算して結果だけ返す構成は禁止。新旧の恒久的な二重管理をしない。正本と進捗は `docs/LANGUAGE_MIGRATION.md`。

## 開発の委任（2026-11-01 更新・2026-11-02 追記）
- 通常の開発（実装・既存部品への接続・テスト・独立レビュー・修正・commit/push・PR 作成・CI 修正・マージ・次の作業への移行）は、**段階ごとの『次へ』の指示を待たずに**継続する。段階が変わった・PR をマージした・作業量が多い、という理由だけで終了しない。使用量・セッションの上限で中断するときは『開発完了』とせず、未完了の範囲と再開手順を PROGRESS に記録する（自動再開の機能が無ければ、設定済みと書かない）。
- **マージ条件**: ① 最新の対象コミットで必要な CI が成功（`ci gate` と、実行されたジョブを個別に確認。skipped を成功扱いにしない）② 高・中の実装上の指摘が解消済み ③ 修正後の独立レビューが完了 ④ 保護設定・必須レビューを満たす ⑤ 本人承認が必要な変更を含まない ⑥ 未検証の main の変更と組み合わせない。サブエージェントのレビューを GitHub 上の正式な第三者 Approve と偽らない。
- **本人判断・外部確認が必要（包括委任の対象外）**: GCP 課金リソースの作成・Terraform apply・本番デプロイ・本番 DB への適用、実データの削除・不可逆な移行、追加料金の有効化・プラン変更、リポジトリの Public 化、COMP-4 未解消での実データのクラウド投入とそれに由来する通知、未承認の v2・数値仕様・品質閾値の有効化、再承認が必要とされた V6 の最終 SQL・GRANT。
- **push 前の標準手順**: ① 意味のある機能単位の実装をまとめる ② ローカル事前検証 `npm ci --ignore-scripts && npm run preflight`（TypeScript。CI と同じ変更分類で必要な検査を選ぶ。Node 24・JDK 25・Docker・MySQL 8.4/9.7 を使う。実行できなかった検査は『未実施』と明記）③ 独立レビュー ④ 指摘を修正 ⑤ 影響する検証を再実行 ⑥ 最終 diff・作業ツリーを確認 ⑦ 検証したコミット/ツリーを PROGRESS に記録 ⑧ まとめて push し PR を作成/更新。ローカル commit は細かくてよいが、**修正のたびに push して CI を起動しない**。既知の検証失敗が残る状態で push しない。CI はローカルで再現できない部分（Windows・実 IAP・GHA 固有）の確認に使う。
- 独立レビューは同時最大 1 名。通常は Sonnet・High。**Opus・High にする重要レビュー**: Terraform→Pulumi 移行、認証・認可・IAM・WIF、暗号・署名・資格情報、状態機械・リース・CAS・再実行、COM 連携と Windows のプロセス制御、決済・金額計算、ML の学習・校正・数値計算・品質評価、DB 制約・競合（契約枠内で使えるとき）。棚卸し・移行設計は Opus・High、通常の実装・CI 修正は Sonnet・High、文書・単純修正は Sonnet・Medium を基本とする。Extra high は難しい問題が解消しないときだけ、Max は常用しない。**主担当のモデルはセッションの設定で決まり、Claude 自身は切り替えられない**（切替は利用者の `/model`）。モデル設定を変更できない環境では変更したと言わない。追加の API 従量課金・使用量の追加購入・プラン変更は有効化しない。

## 進め方（Claude Pro 節約）
- 単一エージェント（主担当）。Agent Teams 不使用。サブエージェントは独立レビュー/独立調査のみ・同時最大1。
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
4. commit → `git push -u origin <branch>`（ネットワーク失敗時のみ 2/4/8/16 秒で再試行）。push は上の『push 前の標準手順』を満たしてから。

## ドキュメント一覧（正本）
| ファイル | 内容 | 正本 |
|---|---|---|
| docs/REQUIREMENTS.md | アプリ全体の要件（正本） | web |
| docs/PLAN.md | 段階・タスク・依存 | web（infra は参照） |
| docs/PROGRESS.md | 進捗 | リポジトリ別 |
| docs/DECISIONS.md | 判断記録 | リポジトリ別 |
| docs/ARCHITECTURE.md | モジュール/責務/データ | web |
| docs/CONTRACTS.md | web⇔infra の成果物・環境変数・API 契約 | **web が正本**、infra 側は同期コピー参照 |
| docs/UNRESOLVED.md | **未解決事項の管理表**（分類 A〜G: 解決済み／対象外／今回解消／ユーザー判断／公式確認／実機検証／後続）。現在の状態の正本 | web |
| docs/STAGE2_HANDOFF.md | 第2段階の実装順・テスト区分・CI・再開手順・検証ゲート一覧 | web |
| docs/LANGUAGE_MIGRATION.md | **言語移行**（Python → Kotlin/TypeScript/C#）の棚卸し・移行先・順序・互換性・失われる検証 | web |
| docs/VERSIONS.md | 版数・互換性ゲート・Boot 4.2 移行計画 | web |
| docs/CI_AND_IMAGES.md | CI の実行範囲・required check（`ci gate`）・Docker イメージ・release-manifest・Dependabot・無料枠 | web |
| docs/MIGRATION_MATRIX.md / FRONTEND_DESIGN / ML_DESIGN / DATA_MIGRATION_DESIGN / RACE_CALENDAR_DESIGN / JVLINK_DESIGN / DATA_SOURCE_COMPLIANCE | 機能移行・各設計（E 項目は MIGRATION_MATRIX §8） | web |

## モジュール（Gradle Kotlin DSL マルチプロジェクト）
`:domain` `:persistence` `:db-migration` `:cloud-gcp` `:bot` `:ingest` `:dashboard`（D-045）と、検証専用の `:golden-runner`（Kotlin 側ゴールデンランナー。`:domain` だけに依存、イメージ非搭載: D-106）。bot / ingest / dashboard は相互依存しない。domain は Spring/Doma/クラウド SDK・学習ライブラリ非依存。TypeScript は `tools/`（CI・リリース補助。ルートの `package.json`）。`:ml-train`（ML 学習 Job。Kotlin。D-257。`:domain`・`:persistence`・`:cloud-gcp` に依存し、他のアプリと相互に依存しない）。Windows 取得エージェントは C#/.NET 10 の `agent/cs`（Gradle に組み込まない独立の .NET ソリューション。D-261）。**自分たちで保守する Python は残っていない**（`tools/golden` は D-258 で Kotlin へ、`agent/` は D-261 で C# へ移行済み。フィクスチャ・ベクトルだけが残る）。詳細は ARCHITECTURE.md。

## 実行環境（クラウドセッション）
- セッションごとにツールが異なり得る。作業冒頭で `java -version` / `node --version` / `dotnet --version` / `./gradlew --version` / `docker info` を確認し PROGRESS に記録（Ubuntu 24.04 のクラウドセッションでは `apt-get install openjdk-25-jdk-headless dotnet-sdk-10.0`、Node は nodejs.org の 24.x を SHASUMS256 で検証して展開できた: VERSIONS §16）。実行できないテストは「未実施」と書き、成功と偽らない。完了判定の正は GitHub Actions（D-056）。

## 規約
- 言語: コード識別子は英語、ドキュメント/UI/コミット本文は日本語可。タイムゾーンは Asia/Tokyo。
- コミットは意味のある単位。メッセージ末尾の attribution は環境指示に従う。
