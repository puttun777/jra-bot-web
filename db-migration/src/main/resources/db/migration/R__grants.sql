-- R__grants.sql  (T2.3 / CONTRACTS §8.2 / D-051 / D-065)
-- 用途別 DB ユーザーの権限。Repeatable Migration なので、内容を変えると次の migrate で再適用される。
--
-- 前提（infra の責務）:
--   * ユーザー app / ingest / bot / mltrain は infra（Terraform）が先に作る。無いユーザーへの GRANT は MySQL がエラーにする
--     （黙って作らない）→ migrate は失敗（終了コード 1）し、権限は変わらない。
--   * migrate ユーザーは infra が `GRANT ALL PRIVILEGES ON <schema>.* TO migrate WITH GRANT OPTION` までを付ける。
--     自分自身の権限は本ファイルでは変更しない（自分で自分を REVOKE してしまうのを避ける）。
--   * Cloud SQL で migrate が GRANT/REVOKE できるか、新規ユーザーの既定権限は未確認（G-DB-6）。権限が足りないときは
--     強い権限（SUPER など）を付けて回避せず、失敗として報告する。
--
-- 方式: 「リセット → 必要な権限だけ GRANT」。リセットは、対象の全テーブルについて
--   GRANT SELECT → REVOKE ALL PRIVILEGES（表・列レベル）を行う。権限が無い状態でも REVOKE がエラーにならず、
--   過去の版で付けた権限が残らない。スキーマ全体（db.*）も同様にリセットするので、スキーマ全体への強い権限が
--   誤って付いていても外れる。
-- テナントは行単位では DB で強制できない（MySQL に行レベル権限が無い）。権限は表・列の単位までで、
--   テナント分離は複合 FK・DAO の必須引数・SQL の静的検査・越境テストで担保する（CONTRACTS §8.2）。
-- 新しい表（V3 以降）を足したら、本ファイルの「リセット」の対象と GRANT の両方に加える
--   （GrantsMatrixTest が、表の一覧と本ファイルの食い違いを検出する）。
--
-- 設計判断（D-065）: app は契約の「全テナント表の SELECT」から、生レコード（ingest_raw）、nonce、移行の隔離表、
--   エージェントのトークンハッシュ・登録コードのハッシュの列を除く（ダッシュボードが使わず、漏えい時の影響が大きいため）。
--   mltrain は ml_model の SELECT/INSERT だけ（ステータスの昇格は app）。INSERT する行の status が CANDIDATE であることは
--   DB では強制できないので、ml-train の DAO とテストで担保する。

-- ===== リセット: app =====
GRANT SELECT ON `${flyway:defaultSchema}`.* TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.* FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`tenant` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`tenant` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`app_user` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`app_user` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`code_venue` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`code_venue` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`races` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`races` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_horses` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`race_horses` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`horses` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`horses` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`horse_past_runs` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`horse_past_runs` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`odds_snapshots` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`odds_snapshots` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`combo_odds_snapshots` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`combo_odds_snapshots` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`results` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`results` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`payouts` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`payouts` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`signals` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`signals` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`bets` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`bets` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`bet_legs` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`bet_legs` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_evaluations` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`race_evaluations` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ticket_evaluations` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ticket_evaluations` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`model_metrics` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`model_metrics` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_cursor` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ingest_cursor` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`import_quarantine` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`import_quarantine` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`audit_log` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`audit_log` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_day` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`race_day` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_schedule` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`race_schedule` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_schedule_event` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`race_schedule_event` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`calendar_override` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`calendar_override` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`bot_run` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`bot_run` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`agent` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`agent` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`agent_registration_code` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`agent_registration_code` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`agent_heartbeat` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`agent_heartbeat` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`agent_nonce` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`agent_nonce` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_batch` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ingest_batch` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_raw` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ingest_raw` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_gap` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ingest_gap` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_fetch` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ingest_fetch` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_backfill` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ingest_backfill` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`horse_master_history` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`horse_master_history` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_timing_notice` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`race_timing_notice` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ml_model` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ml_model` FROM 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`flyway_schema_history` TO 'app'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`flyway_schema_history` FROM 'app'@'%';

-- ===== リセット: ingest =====
GRANT SELECT ON `${flyway:defaultSchema}`.* TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.* FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`tenant` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`tenant` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`app_user` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`app_user` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`code_venue` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`code_venue` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`races` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`races` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_horses` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`race_horses` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`horses` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`horses` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`horse_past_runs` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`horse_past_runs` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`odds_snapshots` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`odds_snapshots` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`combo_odds_snapshots` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`combo_odds_snapshots` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`results` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`results` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`payouts` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`payouts` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`signals` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`signals` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`bets` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`bets` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`bet_legs` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`bet_legs` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_evaluations` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`race_evaluations` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ticket_evaluations` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ticket_evaluations` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`model_metrics` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`model_metrics` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_cursor` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ingest_cursor` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`import_quarantine` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`import_quarantine` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`audit_log` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`audit_log` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_day` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`race_day` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_schedule` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`race_schedule` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_schedule_event` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`race_schedule_event` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`calendar_override` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`calendar_override` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`bot_run` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`bot_run` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`agent` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`agent` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`agent_registration_code` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`agent_registration_code` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`agent_heartbeat` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`agent_heartbeat` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`agent_nonce` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`agent_nonce` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_batch` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ingest_batch` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_raw` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ingest_raw` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_gap` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ingest_gap` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_fetch` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ingest_fetch` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_backfill` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ingest_backfill` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`horse_master_history` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`horse_master_history` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_timing_notice` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`race_timing_notice` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ml_model` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ml_model` FROM 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`flyway_schema_history` TO 'ingest'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`flyway_schema_history` FROM 'ingest'@'%';

-- ===== リセット: bot =====
GRANT SELECT ON `${flyway:defaultSchema}`.* TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.* FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`tenant` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`tenant` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`app_user` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`app_user` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`code_venue` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`code_venue` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`races` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`races` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_horses` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`race_horses` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`horses` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`horses` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`horse_past_runs` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`horse_past_runs` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`odds_snapshots` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`odds_snapshots` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`combo_odds_snapshots` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`combo_odds_snapshots` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`results` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`results` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`payouts` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`payouts` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`signals` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`signals` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`bets` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`bets` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`bet_legs` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`bet_legs` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_evaluations` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`race_evaluations` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ticket_evaluations` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ticket_evaluations` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`model_metrics` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`model_metrics` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_cursor` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ingest_cursor` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`import_quarantine` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`import_quarantine` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`audit_log` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`audit_log` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_day` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`race_day` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_schedule` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`race_schedule` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_schedule_event` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`race_schedule_event` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`calendar_override` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`calendar_override` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`bot_run` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`bot_run` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`agent` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`agent` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`agent_registration_code` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`agent_registration_code` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`agent_heartbeat` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`agent_heartbeat` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`agent_nonce` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`agent_nonce` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_batch` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ingest_batch` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_raw` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ingest_raw` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_gap` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ingest_gap` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_fetch` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ingest_fetch` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_backfill` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ingest_backfill` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`horse_master_history` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`horse_master_history` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_timing_notice` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`race_timing_notice` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ml_model` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ml_model` FROM 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`flyway_schema_history` TO 'bot'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`flyway_schema_history` FROM 'bot'@'%';

-- ===== リセット: mltrain =====
GRANT SELECT ON `${flyway:defaultSchema}`.* TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.* FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`tenant` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`tenant` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`app_user` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`app_user` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`code_venue` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`code_venue` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`races` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`races` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_horses` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`race_horses` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`horses` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`horses` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`horse_past_runs` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`horse_past_runs` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`odds_snapshots` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`odds_snapshots` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`combo_odds_snapshots` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`combo_odds_snapshots` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`results` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`results` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`payouts` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`payouts` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`signals` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`signals` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`bets` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`bets` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`bet_legs` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`bet_legs` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_evaluations` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`race_evaluations` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ticket_evaluations` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ticket_evaluations` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`model_metrics` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`model_metrics` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_cursor` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ingest_cursor` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`import_quarantine` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`import_quarantine` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`audit_log` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`audit_log` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_day` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`race_day` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_schedule` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`race_schedule` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_schedule_event` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`race_schedule_event` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`calendar_override` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`calendar_override` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`bot_run` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`bot_run` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`agent` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`agent` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`agent_registration_code` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`agent_registration_code` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`agent_heartbeat` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`agent_heartbeat` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`agent_nonce` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`agent_nonce` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_batch` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ingest_batch` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_raw` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ingest_raw` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_gap` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ingest_gap` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_fetch` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ingest_fetch` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_backfill` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ingest_backfill` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`horse_master_history` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`horse_master_history` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_timing_notice` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`race_timing_notice` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ml_model` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`ml_model` FROM 'mltrain'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`flyway_schema_history` TO 'mltrain'@'%';
REVOKE ALL PRIVILEGES ON `${flyway:defaultSchema}`.`flyway_schema_history` FROM 'mltrain'@'%';

-- ===== 付与: app =====
-- 通知タイミングと通知内容のスナップショット（V5。表示専用。将来の本人専用ダッシュボードで認証・認可を通った利用者だけが読む）。
GRANT SELECT ON `${flyway:defaultSchema}`.`race_timing_notice` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`horse_master_history` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_fetch` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_backfill` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`tenant` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`app_user` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`code_venue` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`races` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_horses` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`horses` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`horse_past_runs` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`odds_snapshots` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`combo_odds_snapshots` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`results` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`payouts` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`signals` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`bets` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`bet_legs` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_evaluations` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ticket_evaluations` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`model_metrics` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_cursor` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`audit_log` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_day` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_schedule` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_schedule_event` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`calendar_override` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`bot_run` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`agent_heartbeat` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_batch` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_gap` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ml_model` TO 'app'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`flyway_schema_history` TO 'app'@'%';
GRANT SELECT (tenant_id, agent_id, name, token_expires_at, status, created_at, created_by, revoked_at, revoked_by) ON `${flyway:defaultSchema}`.`agent` TO 'app'@'%';
GRANT SELECT (id, tenant_id, expires_at, created_at, created_by, used_at, used_by_agent_id) ON `${flyway:defaultSchema}`.`agent_registration_code` TO 'app'@'%';
GRANT INSERT, UPDATE ON `${flyway:defaultSchema}`.`calendar_override` TO 'app'@'%';
-- 手動補正の追記・取消と同じトランザクションで履歴（MANUAL_OVERRIDE・MANUAL_REVOKE）を残す（T3.3 / D-125）。追記（INSERT）だけで、更新・削除はできない。
GRANT INSERT ON `${flyway:defaultSchema}`.`race_schedule_event` TO 'app'@'%';
GRANT INSERT ON `${flyway:defaultSchema}`.`agent_registration_code` TO 'app'@'%';
GRANT UPDATE (status, revoked_at, revoked_by) ON `${flyway:defaultSchema}`.`agent` TO 'app'@'%';
GRANT INSERT ON `${flyway:defaultSchema}`.`audit_log` TO 'app'@'%';
GRANT INSERT ON `${flyway:defaultSchema}`.`bot_run` TO 'app'@'%';
GRANT UPDATE (status, active_flag, activated_at, activated_by, previous_model_id) ON `${flyway:defaultSchema}`.`ml_model` TO 'app'@'%';

-- ===== 付与: ingest =====
-- 馬マスタの累積値の版（V4。追記専用: SELECT と INSERT だけで、UPDATE・DELETE は付けない）。
GRANT SELECT, INSERT ON `${flyway:defaultSchema}`.`horse_master_history` TO 'ingest'@'%';
-- 取得セッション（取得の事実の正本）とバックフィル要求の状態（T3.4 / D-134・D-138）。バックフィル要求の作成は bot / 管理操作で、ingest は状態の更新だけ。
GRANT SELECT, INSERT, UPDATE ON `${flyway:defaultSchema}`.`ingest_fetch` TO 'ingest'@'%';
GRANT SELECT, UPDATE ON `${flyway:defaultSchema}`.`ingest_backfill` TO 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`tenant` TO 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`code_venue` TO 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`flyway_schema_history` TO 'ingest'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_schedule_event` TO 'ingest'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:defaultSchema}`.`races` TO 'ingest'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:defaultSchema}`.`race_horses` TO 'ingest'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:defaultSchema}`.`horses` TO 'ingest'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:defaultSchema}`.`horse_past_runs` TO 'ingest'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:defaultSchema}`.`odds_snapshots` TO 'ingest'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:defaultSchema}`.`combo_odds_snapshots` TO 'ingest'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:defaultSchema}`.`results` TO 'ingest'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:defaultSchema}`.`payouts` TO 'ingest'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:defaultSchema}`.`ingest_cursor` TO 'ingest'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:defaultSchema}`.`race_day` TO 'ingest'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:defaultSchema}`.`race_schedule` TO 'ingest'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:defaultSchema}`.`ingest_batch` TO 'ingest'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:defaultSchema}`.`ingest_raw` TO 'ingest'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:defaultSchema}`.`ingest_gap` TO 'ingest'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:defaultSchema}`.`agent_heartbeat` TO 'ingest'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:defaultSchema}`.`agent` TO 'ingest'@'%';
GRANT SELECT, UPDATE ON `${flyway:defaultSchema}`.`agent_registration_code` TO 'ingest'@'%';
GRANT INSERT ON `${flyway:defaultSchema}`.`race_schedule_event` TO 'ingest'@'%';
GRANT SELECT, INSERT, DELETE ON `${flyway:defaultSchema}`.`agent_nonce` TO 'ingest'@'%';

-- ===== 付与: bot =====
-- 通知タイミングの確定とスナップショットの保存（V5。SELECT と INSERT だけ。追記専用なので UPDATE・DELETE は付けない）。
GRANT SELECT, INSERT ON `${flyway:defaultSchema}`.`race_timing_notice` TO 'bot'@'%';
-- 学習用データセット（export-dataset）が時点ごとの馬マスタを読む（V4）。
GRANT SELECT ON `${flyway:defaultSchema}`.`horse_master_history` TO 'bot'@'%';
-- 取得の事実（ScheduleFetchFactReader。calendar-check が読む）とバックフィル要求（T3.4）。
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_fetch` TO 'bot'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:defaultSchema}`.`ingest_backfill` TO 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`tenant` TO 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`code_venue` TO 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`flyway_schema_history` TO 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`races` TO 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_horses` TO 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`horses` TO 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`horse_past_runs` TO 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`odds_snapshots` TO 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`combo_odds_snapshots` TO 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`results` TO 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`payouts` TO 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_cursor` TO 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`race_schedule_event` TO 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`calendar_override` TO 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ml_model` TO 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`agent_heartbeat` TO 'bot'@'%';
GRANT SELECT ON `${flyway:defaultSchema}`.`ingest_batch` TO 'bot'@'%';
GRANT SELECT (tenant_id, record_hash, batch_id, seq, dataspec, record_id, received_at, parse_status, parse_error) ON `${flyway:defaultSchema}`.`ingest_raw` TO 'bot'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:defaultSchema}`.`signals` TO 'bot'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:defaultSchema}`.`bets` TO 'bot'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:defaultSchema}`.`bet_legs` TO 'bot'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:defaultSchema}`.`race_evaluations` TO 'bot'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:defaultSchema}`.`ticket_evaluations` TO 'bot'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:defaultSchema}`.`model_metrics` TO 'bot'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:defaultSchema}`.`bot_run` TO 'bot'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:defaultSchema}`.`race_schedule` TO 'bot'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:defaultSchema}`.`race_day` TO 'bot'@'%';
GRANT SELECT, INSERT, UPDATE ON `${flyway:defaultSchema}`.`ingest_gap` TO 'bot'@'%';
GRANT INSERT ON `${flyway:defaultSchema}`.`race_schedule_event` TO 'bot'@'%';
GRANT DELETE ON `${flyway:defaultSchema}`.`odds_snapshots` TO 'bot'@'%';
GRANT DELETE ON `${flyway:defaultSchema}`.`combo_odds_snapshots` TO 'bot'@'%';
GRANT DELETE ON `${flyway:defaultSchema}`.`ingest_raw` TO 'bot'@'%';
GRANT DELETE ON `${flyway:defaultSchema}`.`ingest_batch` TO 'bot'@'%';
GRANT DELETE ON `${flyway:defaultSchema}`.`race_schedule_event` TO 'bot'@'%';
GRANT DELETE ON `${flyway:defaultSchema}`.`bot_run` TO 'bot'@'%';

-- ===== 付与: mltrain =====
GRANT SELECT, INSERT ON `${flyway:defaultSchema}`.`ml_model` TO 'mltrain'@'%';
