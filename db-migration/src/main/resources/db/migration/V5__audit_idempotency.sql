-- V5: 評価結果の一意性と、通知タイミングの一意性・通知内容のスナップショット（包括バグ監査 E・B・D / D-217 / UNRESOLVED D-17。承認済み 2026-10-29）。
-- V1〜V4 は書き換えない。設計と検証: docs/AUDIT_FIX_DESIGN.md §3。
--
-- 方針: 2 つの一意性を別の表で持つ。
--   * 評価結果: `race_evaluations (tenant_id, race_id, snapshot_at)`。snapshot_at は**オッズの観測時刻**なので、新しいオッズなら変わる。
--     旧データ（legacy_id あり）には重複があり得る（D-066）ので、**新方式の行（legacy_id IS NULL）だけ**を UNIQUE にする（生成列。NULL は重複とみなされない）。
--     既存の行は変更も削除もしない。
--   * 通知タイミング: `race_timing_notice (tenant_id, race_id, timing_centi)` の PK。発走の何分前の評価か（30 分前・5 分前…）で一意。
--     オッズの観測時刻が変わっても、同じレース・同じタイミングは 1 回しか確定できない。checkpoint に依存しない。
--
-- 適用の前に、新方式の行に重複が無いことを確認する（あれば最初の ALTER が ER_DUP_ENTRY で失敗し、何も変更されない。自動では削除しない）:
--   SELECT tenant_id, race_id, snapshot_at, COUNT(*) FROM race_evaluations WHERE legacy_id IS NULL GROUP BY 1, 2, 3 HAVING COUNT(*) > 1;
-- 失敗しうる文を先に置く（失敗したときに表だけが残る中途半端な状態にしない）。

ALTER TABLE race_evaluations
  ADD COLUMN uq_native TINYINT AS (IF(legacy_id IS NULL, 1, NULL)) VIRTUAL,
  ADD UNIQUE KEY uq_race_evaluations_native (tenant_id, race_id, snapshot_at, uq_native);

-- 通知タイミングの確定 + 通知内容の不変のスナップショット。追記専用（UPDATE・DELETE しない）。
--   * timing_centi: 発走の何分前か × 100 の整数（30 分 = 3000、5 分 = 500）。換算規則は domain の TimingKey（0.01 分刻み、0 超 1440 分以下）。
--   * notice_json: 通知する内容そのもの（レース表示・Signal の ID と表示モデル）。notice_schema はその版、notice_sha256 は正準 JSON の SHA-256。
--     通知は**この行から**作る（再計算しない）。通知する項目が 0 件でも、評価済みのタイミングとして行を作る。
--   * 行がある = そのタイミングは確定済み。行があって該当 Signal が UNSENT のまま = 送信前に中断された可能性（自動では再送しない: D-18）。
CREATE TABLE race_timing_notice (
  tenant_id        BIGINT NOT NULL,
  race_id          CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  timing_centi     INT UNSIGNED NOT NULL,
  run_id           CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  evaluation_id    BIGINT NOT NULL,
  odds_observed_at DATETIME(6) NOT NULL,
  notice_schema    SMALLINT UNSIGNED NOT NULL,
  notice_json      JSON NOT NULL,
  notice_sha256    CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  fired_at         DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id, race_id, timing_centi),
  KEY ix_race_timing_notice_eval (tenant_id, evaluation_id),
  CONSTRAINT fk_race_timing_notice_race FOREIGN KEY (tenant_id, race_id) REFERENCES races (tenant_id, race_id),
  CONSTRAINT fk_race_timing_notice_eval FOREIGN KEY (tenant_id, evaluation_id) REFERENCES race_evaluations (tenant_id, id),
  CONSTRAINT ck_race_timing_notice_sha CHECK (notice_sha256 REGEXP '^[0-9a-f]{64}$'),
  CONSTRAINT ck_race_timing_notice_centi CHECK (timing_centi BETWEEN 1 AND 144000)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
