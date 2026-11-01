# OPERATIONS（アプリ側の運用）
最終更新: 2026-11-01。インフラ（起動・停止・デプロイ・復元）は infra の `docs/OPERATIONS.md`。ここは**アプリの挙動と、困ったときの見方**。実 GCP・実データでの運用は未経験（`docs/COMPLETION.md` の B）。

## 1. 1 日の流れ（正常系）
| 時刻（JST） | 動くもの | 結果の見方 |
|---|---|---|
| 6:00〜18:00（15 分ごと） | `calendar-check`（Scheduler） | 開催日・窓の判定。起動すべきなら `bot-run` を起動（Cloud Run Jobs）。ログ `CALENDAR_DECISION` |
| 最初の発走の約 2 時間前〜 | `bot-run`（1 日 1 本。リースで二重実行を防ぐ） | 開催日が `RUNNING`。発走の 30 分前・5 分前に評価し、買い目を Discord へ。ログ `EVALUATED` |
| 全レース発走後〜結果確定 | `bot-run` が結果・払戻の取込を待つ | 開催日 `SETTLING` → 全レース確定で `COMPLETED`。締切（最終発走 + 設定分）までに揃わなければ `INCOMPLETE`（FAILED で通知） |
| 21:30（Scheduler） | `reconcile` | `INCOMPLETE` や異常終了で残った日を、取り込み済みの結果から回復（通知は送らない） |
| 毎月 1 日 4:00 | `retention` | **既定はドライラン**（件数をログに出すだけ）。削除は `RETENTION_EXECUTE=true`（本人の承認後） |

## 2. ダッシュボードの見方
- 状態バー: 運用モード（通常/起動中/停止処理中/停止中）、**予定が未更新**（最終確認から 120 分超 → 赤）、次の発走、直近の bot 実行（失敗は赤）。
- 『成績』は**購入していない仮想の損益**。通知した推奨を、確定した結果・払戻から毎回計算する（保存しない）。『集計に含めていないもの』の件数（結果待ち・発走時刻不明・壊れた通知内容）を必ず見る。
- 管理者は『運用』タブで**手動補正**（中止・発走時刻の設定・開催なし/あり・延期・実行の許可/禁止）を追加/取消できる。すべて `audit_log` に残る。停止中（NORMAL 以外）は 409。

## 3. 症状別の対処
| 症状 | 原因の例 | 対処 |
|---|---|---|
| 状態バーが『予定が未更新』 | Windows 取得エージェントが止まっている、ingest が受け付けていない | infra の `ops-status`、エージェントのログ。復旧後に予定が更新されれば消える。**更新されない間は bot は通知しない（fail closed）** |
| 通知が来ない | モードが NORMAL でない／予定が古い／オッズが古い（`maxOddsAge`）／Discord の Webhook 不正 | 『運用』の実行記録の理由（`EVALUATION_OPPORTUNITY_LOST` ほか）、エラー用 Discord、Cloud Logging の `CALENDAR_DECISION`/`UNRESOLVED_RACES` |
| 実行が `INCOMPLETE_AT_DEADLINE` | 結果・払戻の取込が遅い | 取込が済めば `reconcile` が `COMPLETED` にする。急ぐなら infra から `reconcile` を手動実行 |
| `COMMITTED_BUT_UNSENT` の警告 | 通知を確定した直後に落ちた等 | **自動再送しない**（二重通知より取りこぼしを選ぶ: D-18）。成績の画面と Discord を見て、必要なら手動で確認 |
| `RECONCILE_STALE_RUN` の警告 | bot-run が強制終了しリースだけ残った | reconcile が回復済み。原因（OOM・タイムアウト）をログで確認 |
| 手動補正が反映されない | 取消済み・期限切れ・別の優先ルール（DENY_RUN > CANCEL > ALLOW_RUN …） | 『運用』の一覧。優先順位は `RACE_CALENDAR_DESIGN` §7 |
| 管理操作が 409 | モードが NORMAL でない | infra の `ops-start` |
| 403 | 許可リスト（`ALLOWED_USERS`）・`app_user` の行・`ADMIN_USERS` の不一致 | 3 つのうち 1 つでも欠けると拒否（deny by default）。設定を確認 |

## 4. 設定の置き場所
環境変数の一覧と意味は `docs/CONTRACTS.md` §5（infra が Terraform で渡す）。**運用値（CALENDAR_*・BOT_RUN_*）は本人の確定待ち**（`docs/UNRESOLVED.md` D）で、コードに既定値は無い。retention の削除を有効にする・保持日数を変えるのは `RETENTION_*`（任意）。

## 5. ローカルでの確認
- 事前検証: `python3 -m tools.ci.preflight`（CI と同じ範囲を選ぶ。JDK 25・Docker・MySQL を使う。重い統合試験は CI に任せてよい）。
- 最小: `./gradlew ktlintCheck :domain:test :bot:test :dashboard:test`、Python は `python3 -m tools.ci.run_tests --min-tests 100`。
- 合成データだけを使う。実データ・秘密・学習済みモデルを Git に入れない（`.github/scripts/check-repo-hygiene.sh` が検査）。

## 6. 初期導入・移行
- 順序: infra `docs/OPERATIONS.md` §1（tfstate → Secret の箱 → 基盤 apply → DB 停止 → release → deploy → ops-start）。本人の作業は `docs/USER_ACTIONS.md`。
- SQLite → MySQL の実データ移行（R24）は**未実装**（旧データの確認 T1.20 と COMP-4 が先）。設計は `docs/DATA_MIGRATION_DESIGN.md`。それまで旧システムを止めない。
