# V6 提案（Flyway V6。**承認前は Flyway に入れない**）
最終更新: 2026-11-01。承認するのは本人だけ（包括委任の対象外）。承認の返事は『V6 を承認』の一言でよい（下の SQL・GRANT・影響・検証を読んだ上で）。

## 0. 結論（先に）
D-234（紙上成績は DB に書かず毎回計算する）により、`T39_DESIGN.md` §9 の V6 案のうち **`ticket_evaluations` の一意制約・`model_metrics` の列追加・`backtest_run` / `backtest_item` は不要になった（取り下げ）**。
retention に必要な `DELETE` 権限は **V5 の `R__grants.sql` で `bot` に付与済み**（`odds_snapshots`・`combo_odds_snapshots`・`ingest_raw`・`ingest_batch`・`race_schedule_event`・`bot_run`）で、**新しい GRANT は要らない**（`COMPLETION.md` R17 の『V6 が必要』は誤りだったので訂正）。
残る V6 は **レース単位の状態履歴のための ENUM 追加 1 件だけ**で、これも『無くても動く』（代替あり）。

## 1. 変更内容（1 件）
```sql
-- V6__race_schedule_event_status.sql
ALTER TABLE race_schedule_event
  MODIFY COLUMN event_type
    ENUM('ADDED', 'DELETED', 'CANCELLED', 'TIME_CHANGE', 'SCRATCH', 'JOCKEY_CHANGE', 'COURSE_CHANGE', 'WEATHER', 'DAY_STATUS',
         'POSTPONED', 'REVOKED', 'MANUAL_OVERRIDE', 'MANUAL_REVOKE', 'FETCH_FAILURE', 'STALE', 'STATUS')
    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  ALGORITHM = INSTANT;
```
- 既存の列定義（文字集合・照合順序・NOT NULL）は V2 と同一で、**末尾に `'STATUS'` を足すだけ**。ENUM の末尾追加は MySQL 8.4 / 9.7 で `ALGORITHM = INSTANT`（メタデータのみ。行の書換えなし）。INSTANT で実行できなければ文が失敗する（暗黙のコピーにならない）ので安全側。
- `requiredSchemaVersion` は 5 → 6。`R__grants.sql` の変更**なし**（`bot` は既に `race_schedule_event` に INSERT）。

## 2. 何のためか / 無いとどうなるか
| | |
|---|---|
| 用途 | bot-run がレースの状態（MONITORING → OFF → RESULT_PENDING → RESULT_PARTIAL → SETTLED）を変えた**履歴**を `race_schedule_event(event_type='STATUS', old_value, new_value)` に残す。現在の状態は既に `race_schedule.status` にある |
| 無い場合 | 現在の状態は分かる。履歴（いつ・何から何へ）は `race_day` の日単位の履歴（`DAY_STATUS`）と `bot_run` の記録から導出して表示する（現状の実装）。**機能は動く。監査の粒度が粗い** |
| 判断の目安 | 履歴の粒度を重視するなら承認。不要なら却下（V6 は作らず、COMPLETION R11 を『導出表示で完了』に変更） |

## 3. 影響・リスク
- 既存データ: 影響なし（INSTANT、行の書換えなし）。ロック: メタデータロックが一瞬かかるだけ。
- 互換: 旧アプリ（V5 まで）は `STATUS` を書かず読まない。旧アプリが `STATUS` の行を読んでも ENUM の文字列として扱うだけ。**ロールバック**: アプリを旧版に戻す。V6 自体は戻さない（ENUM に余分な値が残るだけ。無害）。
- 本番 DB への適用: **本人の承認が別途必要**（V5 と同じく deploy ワークフローの `apply_pending_migrations`）。

## 4. 検証（承認後に実施。承認前は SQL を Flyway に入れないので未実施）
1. `:db-migration` 統合試験（MySQL 8.4 / 9.7）: V5 の状態の DB に V6 を適用 → `event_type` に `STATUS` が入ること、既存行が不変、`INSERT … 'STATUS'` が `bot` で成功し `app` は INSERT 済みの権限どおり、不正値は拒否。
2. `INFORMATION_SCHEMA` で列定義が V2 の定義 + `STATUS` だけであること。
3. `tools.ci.consistency`（Flyway 版・スキーマ版・manifest の整合）。

## 5. 承認が必要な理由
スキーマ変更（不可逆ではないが、本番 DB への影響があり得る変更）は包括委任の対象外（CLAUDE.md）。**承認するまで Claude は V6 を Flyway に追加しない。**
