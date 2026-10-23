"""対象一覧（MIGRATION_MATRIX §7.5 の GD01〜GD10 と ML_DESIGN §7.3 の G1〜G7）。

status: implemented = 想定した範囲を旧実装から生成済み / partial = 一部の関数・入力のみ生成済み / planned = 登録のみ（フィクスチャなし）。
"登録しただけ" の項目を「実装済み」と数えないこと。case_ids は fixtures/ に実在するケースだけを書く。
"""

CATALOG = [
    # --- MIGRATION_MATRIX §7.5 ---
    dict(id="GD01", title="EV・Kelly", status="implemented", case_ids=["GD01-EV-001", "GD01-KELLY-002"],
         remaining="既存 scripts/test_*.py の入力の取り込みは未実施（手動実行スクリプト型で、合成グリッドで代替）"),
    dict(id="GD02", title="確率推定・正規化", status="partial", case_ids=["GD02-PROB-001", "GD02-NORM-002"],
         remaining="市場確率のみ（use_ml=False）。ML 経由の正規化、decision 側のブレンド+クリップ+再正規化（G5）は未実施"),
    dict(id="GD03", title="急落・人気変動フィルタ", status="partial", case_ids=["GD03-FILTER-001"],
         remaining="check_odds_drop / check_popularity_surge のみ。evaluate_odds 内の recent_snapshots_func 経由と hit_rate の 3 フィルタは未実施"),
    dict(id="GD04", title="閾値・時間帯境界", status="partial", case_ids=["GD09-EVAL-001"],
         remaining="ev_threshold 超過/以下のみ。hit_rate_min_ev・時間帯スケジュール・adaptive_relaxation は run_daemon.py 側（時刻依存・設定依存）で未実施"),
    dict(id="GD05", title="ワイド", status="partial", case_ids=["GD05-COMBO-001"],
         remaining="単純推定関数のみ。suggest_multiple_bet_strategies 内の Plackett–Luce・実オッズ・流し/BOX（E8〜E11）は未実施（Stage 3 T3.2）"),
    dict(id="GD06", title="三連複", status="partial", case_ids=["GD05-COMBO-001"],
         remaining="GD05 と同じ。_top3_set_prob・流し/BOX の重複は未実施（Stage 3 T3.2）"),
    dict(id="GD07", title="賭け額・予算制限", status="partial", case_ids=["GD01-KELLY-002", "GD09-EVAL-001"],
         remaining="suggest_bet_amount と単勝のみ。max_total_bet_per_race の比例縮小（E6）、ticket_allocation、bet_type_mode は未実施"),
    dict(id="GD08", title="候補選別・ソート", status="partial", case_ids=["GD09-EVAL-001"],
         remaining="edge ソートと max_signals のみ。hit_rate ソートの同点規則、notify_skips は未実施"),
    dict(id="GD09", title="異常系・フォールバック", status="partial", case_ids=["GD01-KELLY-002", "GD09-EVAL-001"],
         remaining="odds=1.0（E5）と ML 無効時の市場フォールバックのみ。ML 例外時のラベル（E1）、bet_strategy 例外、人気 0 のみの馬は未実施"),
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
    dict(id="G5", title="decision 側ブレンド+クリップ+再正規化", status="planned", case_ids=[],
         remaining="evaluate_odds の確率部分を抽出して比較。内部ネスト関数のためハーネス側の抽出方法の設計が必要",
         required_input="合成 odds、ml_market_blend_alpha、win_prob_clip_max"),
    dict(id="G6", title="特徴量ベクトル", status="planned", case_ids=[],
         remaining="合成 SQLite（インメモリ）に馬・過去走・レース条件を投入し build_feature_vector を実行。age は固定時刻の注入が必要（storage.db 依存）",
         required_input="合成 DB 行、固定時刻"),
    dict(id="G7", title="失敗系（次元不一致・NaN・署名等）", status="planned", case_ids=[],
         remaining="モデルロード拒否は新設計（スキーマ/署名）の仕様で旧実装に対応物が無い。旧実装側の挙動（pkl ロード失敗時のフォールバック）のみ後続で検討",
         required_input="合成の壊れたモデル表現"),
]
CATALOG_BY_ID = {c["id"]: c for c in CATALOG}
VALID_STATUS = ("implemented", "partial", "planned")
