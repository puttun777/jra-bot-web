-- V4: 馬マスタの時点履歴（T3.6-B / UNRESOLVED G-17 ⑤ / D-166）。V1〜V3 は書き換えない。
--
-- 問題: `horses` は最新の 1 行だけを持ち、着回数の表（chaku_by_*_json）・脚質傾向（style_tendency_json）・race_count は**累積値**なので、
--   過去のレースの時点（asOf）の値を復元できない。現在値を過去の値として学習に使うと、そのレースの結果が混入する（H3 と同じ種類のリーク）。
-- 方針: 累積値の**版**を追記専用で記録し、`known_at <= asOf` の最新の版だけを参照する。
--   * `known_at` は『この版の内容が判明していたと言える最も早い時刻』（取得エージェントの取込時刻など、**データ自身の時刻ではなく観測の時刻**）。
--     時点を特定できない版は記録しない（推測で known_at を過去に付けない）。
--   * 履歴が無い過去の時点の値は**欠損として扱う**（現在値で補完しない）。過去データの復元は、時点を特定できる実データが得られた後（未完了: UNRESOLVED）。
--   * 血統・生年月日・馬名などの不変の属性は `horses` のまま（履歴の対象外）。
--   * 追記専用: 更新・削除はしない。同じ (tenant_id, ketto_num, known_at) の再投入は主キーで拒否する（冪等な再取込は呼び出し側で INSERT IGNORE 相当を扱う）。
CREATE TABLE horse_master_history (
  tenant_id               BIGINT NOT NULL,
  ketto_num               VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  known_at                DATETIME(6) NOT NULL,
  source                  VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  style_tendency_json     JSON NULL,
  chaku_by_surface_json   JSON NULL,
  chaku_by_condition_json JSON NULL,
  chaku_by_distance_json  JSON NULL,
  race_count              INT UNSIGNED NULL,
  created_at              DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id, ketto_num, known_at),
  CONSTRAINT fk_horse_master_history_horse FOREIGN KEY (tenant_id, ketto_num) REFERENCES horses (tenant_id, ketto_num),
  CONSTRAINT ck_horse_master_history_source CHECK (source REGEXP '^[a-z0-9_-]{1,32}$')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
