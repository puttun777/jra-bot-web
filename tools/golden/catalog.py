"""対象一覧（MIGRATION_MATRIX §7.5 の GD01〜GD10 と ML_DESIGN §7.3 の G1〜G7）。

status: implemented = 想定した範囲を旧実装から生成済み / partial = 一部の関数・入力のみ生成済み / planned = 登録のみ（フィクスチャなし）。
"登録しただけ" の項目を「実装済み」と数えないこと。case_ids は fixtures/ に実在するケースだけを書く。
"""

CATALOG = [
    # --- MIGRATION_MATRIX §7.5 ---
    dict(id="GD01", title="EV・Kelly", status="implemented", case_ids=["GD01-EV-001", "GD01-KELLY-002"],
         remaining="既存 scripts/test_*.py の入力の取り込みは未実施（手動実行スクリプト型で、合成グリッドで代替）"),
    dict(id="GD02", title="確率推定・正規化", status="partial", case_ids=["GD02-PROB-001", "GD02-NORM-002", "GD07-SIZING-004"],
         remaining="市場確率のみ（use_ml=False）。decision 側のブレンド+クリップ+再正規化（G5）は GD07-SIZING-004 の evaluate_odds 経由で生成済み（T3.1）。ML 経由の正規化は未実施（T3.6）"),
    dict(id="GD03", title="急落・人気変動フィルタ", status="implemented", case_ids=["GD03-FILTER-001", "GD03-SNAPSHOT-002", "GD04-WIN-HITRATE-003"],
         remaining="DB のスナップショット取得（odds_snapshots の降順読み）は T3.4。連続変化を見る案（E14 の C）は旧実装に無いので対象外"),
    dict(id="GD04", title="閾値・時間帯境界", status="partial", case_ids=["GD09-EVAL-001", "GD04-WIN-HITRATE-003"],
         remaining="ev_threshold・hit_rate_min_ev は生成済み（T3.1）。時間帯スケジュール・adaptive_relaxation は run_daemon.py 側（時刻依存・設定依存）で未実施（T3.8）"),
    dict(id="GD05", title="ワイド", status="partial", case_ids=["GD05-COMBO-001"],
         remaining="単純推定関数のみ。suggest_multiple_bet_strategies 内の Plackett–Luce・実オッズ・流し/BOX（E8〜E11）は未実施（Stage 3 T3.2）"),
    dict(id="GD06", title="三連複", status="partial", case_ids=["GD05-COMBO-001"],
         remaining="GD05 と同じ。_top3_set_prob・流し/BOX の重複は未実施（Stage 3 T3.2）"),
    dict(id="GD07", title="賭け額・予算制限", status="partial", case_ids=["GD01-KELLY-002", "GD09-EVAL-001", "GD07-SIZING-004", "GD08-SELECT-003"],
         remaining="suggest_bet_amount・単勝の賭け額・max_total_bet_per_race の比例縮小（E6 の旧挙動）・bet_type_mode=best_only は生成済み（T3.1）。ワイド/三連複の ticket_allocation は未実施（T3.2）"),
    dict(id="GD08", title="候補選別・ソート", status="partial", case_ids=["GD09-EVAL-001", "GD08-SELECT-003", "GD04-WIN-HITRATE-003"],
         remaining="単勝の edge/hit_rate ソート・同点・max_signals・notify_skips は生成済み（T3.1）。ワイド/三連複の信号を含む賭式優先の順序は未実施（T3.2）"),
    dict(id="GD09", title="異常系・フォールバック", status="partial", case_ids=["GD01-KELLY-002", "GD09-EVAL-001", "GD07-SIZING-004"],
         remaining="odds=1.0（E5）・odds 0・人気 0・ML 無効時の市場フォールバックは生成済み。ML 例外時のラベル（E1）、bet_strategy 例外（ワイド/三連複）は未実施（T3.2/T3.6）"),
    dict(id="GD10", title="設定値・デフォルト", status="partial", case_ids=["GD10-DEFAULTS-001"],
         remaining="関数シグネチャの既定値のみ。config.example.yaml 全キーとの突合は実 config/example の読込が必要で未実施（T1.20）"),
    # --- ML_DESIGN §7.3 ---
    dict(id="G1", title="線形+Platt+市場ブレンド", status="implemented", case_ids=["G1-MODEL-001"],
         remaining="合成重みの WinProbabilityModel のみ（実 pkl 不使用）"),
    dict(id="G2", title="ハッシュ特徴", status="partial", case_ids=["G2-HASH-001"],
         remaining="不正サロゲート（errors=ignore）の入力は JSON で表現できないため未実施"),
    dict(id="G3", title="正規化・補正", status="partial", case_ids=["GD02-NORM-002"],
         remaining="estimate_prob_normalized のみ。decision の _normalize_with_correction は内部関数で未実施"),
    dict(id="G4", title="ensemble", status="planned", case_ids=[],
         remaining="calib/alpha/coverage/β の組合せ。evaluate_odds 内部＋ calibration/alpha の pkl 相当の合成モデルが必要",
         required_input="合成 calib/alpha モデル、odds 有無の組合せ、ensemble_beta"),
    dict(id="G5", title="decision 側ブレンド+クリップ+再正規化", status="partial", case_ids=["GD07-SIZING-004"],
         remaining="ML 無効の市場フォールバック経路を evaluate_odds 全体の出力（p_final・ratio・賭け額）で間接的に検証済み（α・クリップ上限の組合せ）。ブレンド単体の中間値は未抽出。ML 確率に対するブレンドは T3.6"),
    dict(id="G6", title="特徴量ベクトル", status="planned", case_ids=[],
         remaining="合成 SQLite（インメモリ）に馬・過去走・レース条件を投入し build_feature_vector を実行。age は固定時刻の注入が必要（storage.db 依存）",
         required_input="合成 DB 行、固定時刻"),
    dict(id="G7", title="失敗系（次元不一致・NaN・署名等）", status="planned", case_ids=[],
         remaining="モデルロード拒否は新設計（スキーマ/署名）の仕様で旧実装に対応物が無い。旧実装側の挙動（pkl ロード失敗時のフォールバック）のみ後続で検討",
         required_input="合成の壊れたモデル表現"),
]
CATALOG_BY_ID = {c["id"]: c for c in CATALOG}
VALID_STATUS = ("implemented", "partial", "planned")


# --- Kotlin 移植（Stage 3）の対応状況。`kotlin_compare` が Kotlin ランナーの申告（coverage）とこの表を突き合わせる ---
# supported: Kotlin（:golden-runner → :domain）で全呼び出しを実行でき、旧 Python の期待値と比較するケース。
# unsupported: 未実装のケース（理由つき）。**成功に数えない**。対応が増えたらここを移し、PROGRESS に記録する。
KOTLIN_PORT = {
    "supported": [
        "GD01-EV-001", "GD01-KELLY-002", "GD02-PROB-001", "GD02-NORM-002", "GD03-FILTER-001", "GD09-EVAL-001",   # T3.1 の対象（既存）
        "GD03-SNAPSHOT-002", "GD04-WIN-HITRATE-003", "GD07-SIZING-004", "GD08-SELECT-003",                         # T3.1 で追加（evaluate_odds の単勝判定）
        "GD05-COMBO-001",                                                                                          # T3.2: 単純推定関数（推定オッズ・旧 calculate_*_prob）。既存フィクスチャ
        "GD05-PL-005", "GD05-FLOW-006", "GD05-BOX-007", "GD05-REAL-008", "GD05-FILTER-009",                        # T3.2 で追加: suggest_multiple_bet_strategies（直接）
        "GD07-COMBO-STAKE-010", "GD06-EVAL-011",                                                                   # T3.2 で追加: 賭け額・配分 / evaluate_odds のワイド・三連複
    ],
    "unsupported": {
        "G1-MODEL-001": "ML の線形+Platt+市場ブレンド推論（T3.6）",
        "G2-HASH-001": "特徴量ハッシュ（T3.6）",
        "GD10-DEFAULTS-001": "旧関数のシグネチャ既定値の突合。運用の既定値は T1.20/T3.8 で決める（D-016）",
    },
}
