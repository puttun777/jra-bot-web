# TEST_RESULTS（検証結果の総括）
最終更新: 2026-11-01（web main = f35c167、infra main = e21e9e4）。**合成データだけ**での結果。実 GCP・実機（Windows/JV-Link）・実データ・実 Discord での検証は未実施（`docs/COMPLETION.md` の B）。

## 1. 自動試験（CI の `ci gate` が成功した範囲）
| 領域 | 内容 | 件数（2026-11-01 のローカル実行〔CI も同じ試験〕） |
|---|---|---|
| Kotlin 単体 | domain 1,095 / persistence 33 / cloud-gcp 108 / bot 144 / ingest 35 / dashboard 57 / golden-runner 20 / db-migration 58 | 失敗 0・スキップ 0 |
| MySQL 統合（8.4 と 9.7 の両方） | Flyway V1〜V5・GRANT・永続化・bot-run 通し・reconcile・retention・監査・取込・dashboard | CI で成功（件数下限を `verify_test_results.py` が検査） |
| 旧新ゴールデン | 旧 Python と Kotlin の数値の全一致（EV/Kelly/確率/ワイド/三連複/決済ほか 35 ケース中 34 対応・不一致 0） | `tools/golden`（85 件以上・スキップ 0） |
| Python | agent（213+・Linux と Windows）/ ml-train（128）/ ml tools / CI スクリプト（147） | 成功 |
| イメージ | 5 イメージのビルド + 起動スモーク（177 項目） | 成功（dashboard の起動不具合を CI のスモークが検出し修正: D-245） |
| infra | `terraform fmt/validate/test`（mock 17）+ ops 単体 77 + workflow YAML | CI（`ci gate`）で成功 |

## 2. 通し試験（合成データ）— R30
`BotRunOrchestratorIntegrationTest` の通し: 開催日の監視 → 評価 → Signal → 通知（Fake）→ 発走後の結果・払戻の取込 → 開催日 `SETTLING` → `COMPLETED`（履歴は `race_schedule_event` に SYSTEM の DAY_STATUS 3 件）→ `PerformanceReadService` が MySQL 上で単勝の紙上成績（件数・的中・推奨額・実払戻）を計算。`ReconcileIntegrationTest`（INCOMPLETE の回復・冪等・暫定払戻では完了にしない）、`RetentionIntegrationTest`（既定ドライラン・削除・PENDING/実行中の保護・テナント分離）。
**未作成**: 取得エージェント → ingest の HTTP → DB → bot までを 1 本で通す試験（モジュール間の依存禁止のため、各段をそれぞれ試験している: ingest 統合 58・agent 相互運用・bot 通し）。

## 3. 旧新比較（意図した差異）
`docs/MIGRATION_MATRIX.md` §8 の E1〜E17。今回の追加: **E17** 旧ワイドの的中判定（1・2 着だけ）は引き継がず、確定払戻の行で判定する（D-240）。ゴールデンは旧の定義のまま検証している。

## 4. セキュリティ試験（認可マトリクス）
| 主体 \ 操作 | 読取り `/api/v1/*` | 管理 `/api/v1/admin/*`（読取り） | 書込み（管理） | `/internal/*` |
|---|---|---|---|---|
| 未認証・不正 JWT・別 audience・期限切れ | 401 | 401 | 401 | 401 |
| 許可リスト外・無効行・ADMIN 行が ADMIN_USERS に無い | 403 | 403 | 403 | — |
| VIEWER | 200 | **403**（パスの細工 `//` `;` `%61` `./` でも） | 403 | — |
| ADMIN | 200 | 200 | CSRF 条件を満たし NORMAL のときだけ（それ以外 403/409） | — |
| ops サービスアカウント | 403（人としては不可） | 403 | 403 | 200（ID トークン検証後） |
- テナントはクエリで指定できず、本人の行から決まる（他テナントのデータが見えないことを単体・MySQL で試験）。
- CSRF: ヘッダ無し・フォーム形式・別オリジン・別サイトからの書込みは 403（実コンテキストの試験）。
- 監査: 管理操作は変更と**同じトランザクション**で `audit_log` に残る。監査の失敗で変更も取り消される（試験）。`app` は `audit_log` を UPDATE/DELETE できない（MySQL 試験）。
- 静的 UI: インライン script・外部オリジン・innerHTML・eval を使わない（静的検査）。CSP は自オリジンのみ。

## 5. 独立レビュー（Opus、読取り専用。同時 1 名）
| 対象 | 結果 |
|---|---|
| T3.8/V5・T4.1・紙上成績 | D-230/D-236（高 0・中 2+3） |
| T3.9③・reconcile・事前検証 | D-237/D-238（高 1・中 5+3） |
| infra（Terraform・ops・workflow） | 3 回: 高 4・中 8+4+1 を修正 |
| Stage 4・R2・R14〜R17 | D-246/D-247（高 0・中 7+3） |
修正後の再確認が済んだものだけをマージした。

## 6. 実施できなかった検証（成功と偽らない）
- 実 GCP: `terraform plan/apply`、Cloud Run Jobs の起動、IAP の JWT、GCS の CAS、Cloud SQL の停止/起動、workflow の実行（G-OPS-1〜11）。
- Windows 実機: JV-Link の COM（`agent/poc`）。実データ形式のパーサ（JV-Data の公式仕様待ち）。
- 実 Discord への送信、ブラウザでの UI の目視。
- ローカルの `images`（Docker 内の Gradle 取得の TLS）は CI に任せた。
