# PROGRESS（jra-bot-web）

最終更新: 2026-10-22（第1段階 / Stage 1-C 前半: T1.2・T1.3・T1.15 完了、T1.13 一部）

## 現在の段階
**第1段階 進行中**。完了: T1.1, T1.4, T1.6(infra), T1.2, T1.3, T1.15。T1.13 は JV-Link/開催日関連のみ完了。
残り（Stage 1-C 後半・別セッション）: T1.5（フロント）、T1.9（ML）、T1.11（データ移行方針）、T1.13 残り、T1.7（クラウド選定=ユーザー判断）、T1.8 確定、T1.10（設計確定）、T1.12。

## 今回の完了（claude/stage1-jvlink-calendar）
- `docs/JVLINK_DESIGN.md`: 参照元 JV-Link 実装の精査（API・dataspec・差分取得・パーサ・エラー処理）、疑義・欠陥一覧、実行環境制約（64bit 版 JV-Link）、エージェント言語比較（Python 暫定）、Windows/Kotlin 分担、生レコード転送、ACK ベースのチェックポイント、ローカルキュー、PC 停止時の欠損対策、更新・配布、監視・復旧
- `docs/RACE_CALENDAR_DESIGN.md`: 検出能力の確度表、開催日/レースの状態遷移（mermaid）、データフロー、スキーマ案（`race_day` / `race_schedule` / `race_schedule_event` / `calendar_override` / `bot_run`）、calendar-check と bot-run の分離、二重実行防止・冪等・再開、手動補正、テストケース C01〜C28
- `docs/DATA_SOURCE_COMPLIANCE.md`: JRA-VAN 規約の出典整理、許可/禁止/未確認の分類（16 項目）、2 人利用の代替案、問い合わせ Q1〜Q8、段階的運用方針
- `docs/CONTRACTS.md` 0.2.0-draft: ingest 別サービス化、エージェント認証、封筒、ステータス意味、**§7 STOPPED 時動作契約**（モードビーコン、状態機械、復帰手順、infra への義務）
- `docs/ARCHITECTURE.md` §7、`MIGRATION_MATRIX.md` §6、`REQUIREMENTS.md`、`DECISIONS.md`（D-018〜D-031）、`PLAN.md`（T1.2/1.3/1.15 完了、T1.16〜T1.19 追加）を更新

## 主な発見（次セッション向け）
1. **JV-Link 64bit 版 Ver.5.0.0**（開発者向け 2026-08-04、一般は9月以降）。32bit 前提が変わる。32/64 は別の利用キー。**利用キーは端末ごと**（同一キーの複数端末は不可）。
2. **参照元は開催変更系（YSCH, 0B14）を使っていない**。YSCH=開催スケジュール、0B14=速報開催情報(WE/AV/JC/**TC=発走時刻変更**/CC)で**全置換・取消あり**。レース中止はデータ区分 `9`、削除は `0`。いずれも第三者解説（一次未確認）。
3. 参照元の疑義: HR 払戻の桁（10 倍ずれの可能性）、文字列経由の cp932 再エンコード、レース ID に回・日目なし（16桁キーを総当たり）、JVInit エラー表が独自、`option=0`、状態がメモリ上、lastfiletimestamp が読込完走と独立に保存。
4. **直接 IAP は機械クライアントを通せない** → ingest は別サービス（D-020）。
5. **規約**: 第7条は「本人が個人として私的に使用」以外の複製・公表等を禁止、第三者にさせることも禁止。→ **2 人目は第三者**。クラウド保存・ダッシュボード・Discord 通知は**未確認**。一般公開は JRADB が必要。→ 案 A（契約者ごとに論理分離, `tenant_id`）を採用方針に。
6. 開催日の延期/代替の JV-Data 上の表現は未確認 → 自動検知を保証せず手動補正を用意。

## テスト結果
該当なし（設計・調査のみ）。

## 未完了・未解決（ユーザー判断/確認が必要）
1. **JRA-VAN への問い合わせ（Q1〜Q8）を送るか**、回答までの運用方針（Phase 1=本人 PC のみ。クラウドに実データを置くのは回答後）を承認するか（DATA_SOURCE_COMPLIANCE §8）。
2. **2 人目が自分の Data Lab. 契約と PC を用意できるか**（案 A の前提）。
3. 一般公開は JRADB 前提で当面行わない、ことの承認。
4. T1.7 クラウド選定（infra の CLOUD_COSTS 参照）。Cloud SQL 東京実額等の確認。
5. T1.17: SDK 同梱の公式仕様書で確認する項目（PLAN 参照）。仕様書は Git に入れない。
6. この環境から jra-van.jp / developer.jra-van.jp / docs.* に接続できず、公式一次資料は未読。規約・仕様は WebSearch の引用と第三者解説に依存（各文書に確度を明記）。

## 次に実行する内容
- **Stage 1-C 後半（別セッション, 推奨 Sonnet / Medium）**: T1.5（Streamlit 画面群の精査→フロント選定）、T1.9（ML 方針: 重み JSON エクスポート、学習/推論分離）、T1.11（SQLite→MySQL データ移行方針）、T1.13 残り（decision.py / bet_strategy.py の精読）。いずれも T1.7（クラウド）と独立。
- その後 **T1.10（設計確定, 推奨 Opus か Sonnet High）**: 本 PR の案（D-018〜D-031）の確定、:ingest 追加、tenant_id、スキーマ V1 の方針。
- infra 側: T1.18（CONTRACTS §7.5 の同期）、T1.7 判断後に T1.8 確定。

## Git
- ブランチ: `claude/stage1-jvlink-calendar`（base: main 5b52d1e）
- コミット/PR: push 後に記録
