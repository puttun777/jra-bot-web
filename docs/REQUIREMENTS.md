# REQUIREMENTS（アプリ全体の要件・正本）

最終更新: 2026-10-23（§0 利用者・利用目的の要件変更を追加）/ 第0段階の記述は履歴として残す。インフラ固有の仕様は jra-bot-infra `docs/REQUIREMENTS.md` が正本。

## 0. 利用者・利用目的（2026-10-23 要件変更。**この節が §1・§4 の「初期利用者」の記述に優先する**）
**このシステムは、開発者本人 1 名だけが、私的・非営利の目的で使う。**

| 項目 | 内容 |
|---|---|
| 利用者 | 本人 1 名のみ。JRA-VAN Data Lab. の契約者も本人。取得データは本人しか閲覧しない。Discord 通知もダッシュボードも本人専用 |
| 行わないこと | 第三者へのデータ提供・送信、友人との共有、他人の JRA-VAN 契約の使用、商用提供、広告収益化、課金サービス化、**サービスの一般公開** |
| リポジトリ | jra-bot-infra は **Private のまま**。jra-bot-web は GitHub Actions の無料利用のため Public にできる（2026-10-26 の公開準備: D-180。**Visibility の変更はユーザーの最終操作**）。コードの公開とサービスの公開は別で、本番 GCP・DB・Discord Webhook・ダッシュボードは本人専用・非公開のまま。公開してもライセンスは付与しない（All rights reserved）。実データ・秘密鍵・学習済み実モデル・JV-Link SDK 資料は Git に入れない |
| 将来の複数人運用 | **要件外**。実装・公開・許可しない（検討する場合は新しい要件として規約確認から始める） |

**構造として維持するもの**（単一利用者でも省略しない）: `tenant_id`（全データテーブルの主キーの一部）、Repository のテナント境界、認証・認可（deny by default）、エージェントの厳密な認証、秘密情報をログに出さない。
**無効にするもの（初期運用）**: 許可リスト（`ALLOWED_USERS`・`app_user`）は**本人 1 名だけ**。新規利用者の登録・招待・データ共有の機能は**作らない**。自動のユーザー登録も作らない。エージェント登録コードは本人が発行する 1 回限りのもの（招待ではない）。
規約上の整理は `DATA_SOURCE_COMPLIANCE.md`、未解決事項の管理は `UNRESOLVED.md`。**本人専用の非公開クラウドにデータを置いてよいかは規約上まだ確認できていない**（実データのクラウド投入ゲート）。

## 1. 目的と範囲
既存 JRA Bot（Python / SQLite / Streamlit / JV-Link COM）を Kotlin / Spring Boot の Web アプリへ段階的にリプレースする。
~~初期利用者は最大2人~~（**2026-10-23 に本人 1 名へ変更: §0**）。~~将来 100〜1,000 人・一般公開に対応できる設計~~ は要件外（技術的なテナント構造の拡張性は維持するが、複数人運用は行わない）。高額構成や不要な拡張機能は導入しない。

優先順位: ①指定ユーザーだけの安全なアクセス ②既存主要機能の正確な移行 ③継続運用費の削減 ④全体の安全な一括起動/停止 ⑤サポート中の新しい技術 ⑥将来の拡張性

## 2. 参照元の初期調査結果（第0段階・要点のみ。詳細は第1段階で MIGRATION_MATRIX.md へ）
`puttun777/jra-bot`（**Public**, MIT, default `main`, 最終 f7dbf69）。Python 約3万行。

| 領域 | 場所 | 所見 |
|---|---|---|
| データ取得 | `src/jraev/data/jvlink_provider.py` (2.7k行) | **win32com で JV-Link COM を呼ぶ（Windows/32bit 依存）**。Dummy provider あり。固定長 Shift_JIS レコードのパース、RT/蓄積系の dataspec、`ingest_cover_data` による過去データ取り込み |
| 常駐処理 | `scripts/run_daemon.py` (2k行) | ポーリングループ（far300s/mid60s/near30s）、発走前 240〜-5 分監視、通知・見送り、リトライ、アダプティブ緩和。開催日判定は単純でなく provider の当日レース取得に依存（要精査） |
| EV/判定 | `src/jraev/ev/` | EV、Kelly、確率推定/正規化、ワイド/三連複の確率・推定オッズ、急落/人気急上昇フィルタ、検証（回収率） |
| ML | `src/jraev/ml/` | **numpy のみ**の自前モデル（32bit 環境都合）、`pickle` 保存、特徴量(hash bucket 含む)、dataset builder(parquet)、train/tune/predict。ensemble 推論 |
| 通知 | `src/jraev/notify/discord.py` | Webhook、分割送信、Retry-After 対応、リトライ、メンション |
| DB | `src/jraev/storage/` | **SQLite**。13 テーブル + bets/bet_legs（races, odds_snapshots, signals, results, jvlink_timestamps, horses, race_horses, horse_past_runs, model_metrics, payouts, combo_odds_snapshots, race_evaluations, ticket_evaluations）。アプリ内 `_migrate` による動的移行 |
| UI | `apps/dashboard/streamlit_app.py`(2k行) + `ui/` | Streamlit。Overview / Race Detail / Horse Detail / Performance / Signals / Ops-Health / Model。ログ・PID 確認などローカル運用前提の機能あり |
| 設定 | `config/config.example.yaml` | YAML + `${ENV}` 展開。watch/discord/ml 等。Webhook は環境変数 |
| スクリプト | `scripts/` (約90本) | 大半は診断/検証/一回限り。有用なものは settle_bets, backfill_*, retrain_models, evaluate_model 等 → 第1段階で棚卸し |
| 同梱物 | `models/*.json` のみ | pkl・dataset・実 DB は Git に無い（**既存の実 DB の所在/移行方法は未解決事項**） |

リスク所見: pickle のロード（任意コード実行リスク）、データ利用条件（README は JRA-VAN 利用規約準拠と記載。クラウド保管/複数人利用/一般公開の可否は第1段階で一次情報確認）。

## 3. 機能要件
### 3.1 移行対象
JV-Link / 開催情報 / レース検知 / オッズ監視 / 期待値 / 確率推定 / 判定 / ML 学習・推論 / Discord 通知 / DB スキーマとデータ / ダッシュボード / バックテスト / 成績集計 / 設定管理 / 定期実行 / 障害対応。
各機能を **Kotlin移行 / 別プロセス維持 / 段階移行** に分類し、黙って削除しない。

### 3.2 同等性
旧実装と新実装に同じ入力を与え、EV・確率・判定・金額・丸め・境界値・欠損値・タイムゾーン(Asia/Tokyo)の結果を比較する（ゴールデンテスト。旧実装の出力はフィクスチャとして保存、実データは含めない）。

### 3.3 JV-Link
Windows / COM のため Linux コンテナで直接実行不可（**JV-Link 64bit 版 Ver.5.0.0 が 2026-08 に開発者向け提供開始**。一般提供状況は T1.17 で確認）。**Windows 取得エージェント**として分離し、取得データを認証付き API でクラウドへ送信する。STOPPED 中は送信を抑止（§5）。ML は Python 維持可。**学習と推論を分離**する。

### 3.4 bot の実行
- JRA の実際の開催日・発走予定に基づく（単純な土日祝判定は禁止）。通常/平日/代替開催、中止、延期、発走時刻変更、取得失敗、手動補正に対応。TZ は Asia/Tokyo。
- 必要時間帯だけ起動し、結果反映完了後に終了。軽量な「開催予定確認」と重い「bot 本処理」を分離。
- 再実行・多重起動防止・冪等性・リトライ・バックフィル・進捗からの再開。
- Web アクセスを契機に bot を起動しない。

### 3.5 ダッシュボード
曜日を問わず閲覧可。bot 停止中でも保存済みデータ・分析結果・履歴・成績を表示。日本語UI、PC/スマホ対応、最終更新日時、bot 状態、次回実行予定、未更新/取得失敗の表示、管理者限定操作、API の認証・認可。Web はステートレス、初期はスケールゼロ可能な構成を優先。

## 4. 非機能要件
- セキュリティ: 許可リスト（**本人 1 名のみ**。2026-10-23 変更）以外拒否。初期設定未完了時は **deny by default**。一般利用者/管理者/機械連携（Windows エージェント、CI）の認証・権限を分離。将来公開時も管理機能は保護。DB 用 Entity を API に直接公開しない。
- 再現性: 依存は Version Catalog で固定。Renovate または Dependabot を導入。
- テスト: 単体、DB 統合（Testcontainers）、旧実装比較、セキュリティ検証。
- 費用: 初期は最小構成。比較は infra 側 `CLOUD_COSTS.md`（第1段階）。
- データ/秘密: 秘密情報・実データ・大容量モデル・生成物は Git 不可。

## 5. 運用モード（アプリ側の責務）
`NORMAL` / `STOPPED`。希望モード（desired）と実リソース状態（actual）を区別して保持・表示する。
- NORMAL: Web 常時可、DB 可、bot は開催日の必要時間帯のみ。非開催日に NORMAL へ戻しても bot は無条件起動しない。
- **2026-11-09 の運用方針（D-279。費用の最小化・案 1b）**: 通常は **STOPPED** とし、**開催日など必要なとき（月 約 100 時間）だけ NORMAL** にする。画面（Web）は NORMAL の間だけ使える（「Web 常時可」は NORMAL の間の要件として読む）。切替は既存の ops-start / ops-stop。開催日に合わせた自動の切替は後続（PLAN）。
- STOPPED: Web 新規受付停止、bot/定期/再試行停止、Windows エージェント送信抑止、DB 停止/休止、データ維持、意図しない再起動防止。
アプリは `GET /internal/ops/mode`（契約案、CONTRACTS.md）でモードを提供し、bot・エージェントは実行前に必ず確認する。DB 停止中は取得不能になるため、**モードの正本はクラウド側のインフラ設定（infra）**とし、DB 内の値は表示用のコピーとする（DECISIONS D-005）。

## 6. 技術方針（確定は第1段階。第0段階では版数を決めない）
Kotlin / Spring Boot / Gradle Kotlin DSL / Doma / Flyway / MySQL(第一候補) / Docker Compose / Testcontainers / Terraform / GitHub Actions。フロントは Streamlit の調査後に選定。JDK・DB は採用環境の最新 LTS 系列。Doma の Kotlin 向けアノテーション処理（KSP 等）は**公式ドキュメントで確認してから**決定する。

## 7. 範囲外（今は行わない）
課金リソース作成、本番デプロイ、実データ移行の実行、アプリ本格実装（第2段階以降）。
