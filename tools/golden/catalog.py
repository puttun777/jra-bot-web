"""対象一覧（MIGRATION_MATRIX §7.5 の GD01〜GD10 と ML_DESIGN §7.3 の G1〜G7）。

status: implemented = 想定した範囲を旧実装から生成済み / partial = 一部の関数・入力のみ生成済み / planned = 登録のみ（フィクスチャなし）。
"登録しただけ" の項目を「実装済み」と数えないこと。case_ids は fixtures/ に実在するケースだけを書く。
"""

CATALOG = [
    # --- MIGRATION_MATRIX §7.5 ---
    dict(id="GD01", title="EV・Kelly", status="implemented", case_ids=["GD01-EV-001", "GD01-KELLY-002"],
         remaining="既存 scripts/test_*.py の入力の取り込みは未実施（手動実行スクリプト型で、合成グリッドで代替）"),
    dict(id="GD02", title="確率推定・正規化", status="partial", case_ids=["GD02-PROB-001", "GD02-NORM-002", "GD07-SIZING-004"],
         remaining="市場確率のみ（use_ml=False）。ML 経由の正規化は G3-ML-NORM-002（T3.6-A）。decision 側のブレンド+クリップ+再正規化（G5）は GD07-SIZING-004 と G5-BLEND-002"),
    dict(id="GD03", title="急落・人気変動フィルタ", status="implemented", case_ids=["GD03-FILTER-001", "GD03-SNAPSHOT-002", "GD04-WIN-HITRATE-003"],
         remaining="DB のスナップショット取得（odds_snapshots の降順読み）は T3.4。連続変化を見る案（E14 の C）は旧実装に無いので対象外"),
    dict(id="GD04", title="閾値・時間帯境界", status="partial", case_ids=["GD09-EVAL-001", "GD04-WIN-HITRATE-003"],
         remaining="ev_threshold・hit_rate_min_ev は生成済み（T3.1）。時間帯スケジュール・adaptive_relaxation は run_daemon.py 側（時刻依存・設定依存）で未実施（T3.8）"),
    dict(id="GD05", title="ワイド", status="implemented",
         case_ids=["GD05-COMBO-001", "GD05-PL-005", "GD05-FLOW-006", "GD05-BOX-007", "GD05-REAL-008", "GD05-FILTER-009", "GD06-EVAL-011"],
         remaining="合成入力のみ（実オッズ・実馬場の分布は T1.20/T3.4 以降）。suggest_multiple_bet_strategies は evaluate_odds と同じ経路（賭けの方式の引数は None）で直接呼ぶ。"
                   "実オッズの鮮度・欠測の扱いは旧実装に無く対象外。旧欠陥 E8〜E10 は旧挙動のまま記録（T3.2）。実オッズの辞書は昇順のキーだけ（逆順キーは旧実装が一致しない）"),
    dict(id="GD06", title="三連複", status="implemented",
         case_ids=["GD05-COMBO-001", "GD05-PL-005", "GD05-FLOW-006", "GD05-BOX-007", "GD05-REAL-008", "GD05-FILTER-009", "GD06-EVAL-011"],
         remaining="GD05 と同じ（三連複の流し・BOX・重複・頭数 3 未満を含む）。三連複の『実オッズ 0.0』は旧実装で『無い』扱い"),
    dict(id="GD07", title="賭け額・予算制限", status="implemented",
         case_ids=["GD01-KELLY-002", "GD09-EVAL-001", "GD07-SIZING-004", "GD08-SELECT-003", "GD07-COMBO-STAKE-010", "GD06-EVAL-011"],
         remaining="suggest_bet_amount・単勝の賭け額・max_total_bet_per_race の比例縮小（E6 の旧挙動。買い目の stake の縮小を含む）・bet_type_mode=best_only・ワイド/三連複の ticket_allocation（T3.2）は生成済み。"
                   "E6 の改善案（縮小後の min_bet 再判定）は未承認で対象外"),
    dict(id="GD08", title="候補選別・ソート", status="partial", case_ids=["GD09-EVAL-001", "GD08-SELECT-003", "GD04-WIN-HITRATE-003", "GD06-EVAL-011"],
         remaining="edge/hit_rate ソート・同点・max_signals・notify_skips は生成済み（単勝は T3.1、ワイド/三連複を含む並びは T3.2 の GD06-EVAL-011）。"
                   "賭式をまたぐ完全な同点（確率・EV が一致）は合成入力で作れないため、旧実装の比較は Kotlin の単体テスト（SignalOrdering）で固定している"),
    dict(id="GD09", title="異常系・フォールバック", status="partial", case_ids=["GD01-KELLY-002", "GD09-EVAL-001", "GD07-SIZING-004"],
         remaining="odds=1.0（E5）・odds 0・人気 0・ML 無効時の市場フォールバックは生成済み。ML 例外時のラベル（E1）は G7-LEGACY-FAIL-001（T3.6-A）。bet_strategy 例外（ワイド/三連複）は未実施"),
    dict(id="GD10", title="設定値・デフォルト", status="partial", case_ids=["GD10-DEFAULTS-001"],
         remaining="関数シグネチャの既定値のみ。config.example.yaml 全キーとの突合は実 config/example の読込が必要で未実施（T1.20）"),
    dict(id="GD11", title="結果の決済（勝ち馬券キー・評価の決済・単勝 bets の決済・払戻の取得）", status="implemented",
         case_ids=["GD11-WINKEYS-001", "GD11-SETTLE-EVAL-002", "GD11-SETTLE-WIN-003"],
         remaining="合成 SQLite のみ。旧 settle_evaluations_for_race は sqlite3.Row.get が無く常に例外になる（E16）ため、行に .get を持たせた上で比較（意図どおりの値）。"
                   "ワイド/三連複の bets の決済（settle_open_combo_bets_for_race）は bets の扱い（Q3）が保留のため対象外。同着・返還・取消の払戻の単位は旧実装に分岐が無く、JV-Data 仕様の確認後（G-5/G-16）"),
    # --- ML_DESIGN §7.3 ---
    dict(id="G1", title="線形+Platt+市場ブレンド", status="implemented", case_ids=["G1-MODEL-001"],
         remaining="合成重みの WinProbabilityModel のみ（実 pkl 不使用）。Kotlin は :golden-runner の互換モジュール（旧 _align_feature_matrix の切詰め/ゼロ埋めを含む）で比較し、"
                   ":domain の安全なロード契約（次元が違えば拒否）とは分けている。T3.6-A で model_extract_features の 7 呼び出しを更新（ゴールデン基盤の正規化の不具合: D-153）"),
    dict(id="G2", title="ハッシュ特徴", status="partial", case_ids=["G2-HASH-001", "G6-FEATURES-001"],
         remaining="不正サロゲート（errors=ignore）の入力は JSON で表現できないため、ゴールデンではなく Kotlin 単体テスト（PedigreeHashTest）で旧 Python の値と照合。"
                   "16 次元の集計は G6-FEATURES-001（祖先名の重複・全角・9 件目以降・空名）で検証"),
    dict(id="G3", title="正規化・補正", status="implemented", case_ids=["GD02-NORM-002", "G3-ML-NORM-002"],
         remaining="市場確率（GD02-NORM-002）と ML 経路（G3-ML-NORM-002: モデル出力 → _normalize_with_correction → 判定側ブレンド）。_normalize_with_correction は evaluate_odds の入れ子関数で"
                   "直接呼べないため、evaluate_odds の出力（p_final・EV・賭け額）で検証する"),
    dict(id="G4", title="ensemble", status="implemented", case_ids=["G4-ENSEMBLE-002"],
         remaining="calibration + alpha + β × カバレッジの組合せ（オッズ無し・片方無し・両方無し・β の端/範囲外・最後の馬のラベル）。ensemble_beta=auto（外部ファイルと 14 日失効）と model_type=auto（ファイルの有無で選ぶ）は"
                   "新仕様（成果物が β を持つ・レジストリの ACTIVE で決める）で置き換えるため対象外"),
    dict(id="G5", title="decision 側ブレンド+クリップ+再正規化", status="implemented", case_ids=["GD07-SIZING-004", "G5-BLEND-002"],
         remaining="ML 確率に対する判定側ブレンド（α の端・境界、モデル内ブレンドの実行時上書き H4）・上限クリップ・再正規化・引数と ml_cfg の優先順位を G5-BLEND-002 で検証"),
    dict(id="G6", title="特徴量ベクトル", status="implemented", case_ids=["G6-FEATURES-001"],
         remaining="合成 SQLite（旧スキーマ）と固定時刻で build_feature_vector / build_feature_vector_alpha を比較（40/35 次元・血統ハッシュ 16 次元・H3/H8 を含む旧挙動を再現）。"
                   "新方式（point-in-time）は旧実装に対応物が無いので Kotlin 単体テスト（FeatureBuilderTest）で検証。format_reason_from_features（通知文）は T3.7"),
    dict(id="G8", title="Discord 通知（分割・Retry-After・重複除外・本文・理由文）", status="implemented",
         case_ids=["G8-SPLIT-001", "G8-RETRY-002", "G8-DEDUP-003", "G8-SINGLE-004", "G8-GROUPED-005", "G8-REASON-006"],
         remaining="旧 notify/discord.py のうちネットワークを使わない部分（HTTP 送信の関数は差し替え・requests はスタブ）。旧実装の自動リトライ（5 回・5xx も再送）と HTTP の挙動は比較対象外で、"
                   "新しい配送の安全規則（5xx/タイムアウトを再送しない・NOT_SENT/UNKNOWN の区別）は旧出力ではなく Kotlin の単体テスト（Fake HTTP サーバ）で検証する。"
                   "旧 mention（自由文字列）は移植しない。旧 send_signal が必須キー欠落で False を返す経路・strategies の無い単勝のみの戦略の分岐は対象外。実 Discord への送信は未検証"),
    dict(id="G7", title="失敗系（次元不一致・NaN・署名等）", status="partial", case_ids=["G7-LEGACY-FAIL-001"],
         remaining="旧実装側の失敗時の挙動（ML 無効・モデル無し・feature_names 不一致=H1・未知の model_type）は G7-LEGACY-FAIL-001 で数値とラベルを比較。"
                   "新しいモデルのロード失敗（署名/ハッシュ/スキーマ/次元/NaN）は旧実装に対応物が無い新設計の仕様なので、旧出力ではなく Kotlin 単体テスト（ModelLoaderTest・MlEndToEndTest）と"
                   "tools/ml の署名付き合成成果物で検証する。旧実装の『モデルがあるのに特徴量の作成で失敗 → ml:legacy(no_odds)』経路は再現しない（H2 と同種の無意味な予測）"),
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
        "G1-MODEL-001", "G2-HASH-001",                                                                             # T3.6-A: 既存フィクスチャ（線形+Platt+市場ブレンド / ハッシュ特徴）
        "G6-FEATURES-001", "G3-ML-NORM-002", "G4-ENSEMBLE-002", "G5-BLEND-002", "G7-LEGACY-FAIL-001",             # T3.6-A で追加: 特徴量 / ML 経路（正規化・ensemble・ブレンド・失敗時）
        "G8-SPLIT-001", "G8-RETRY-002", "G8-DEDUP-003", "G8-SINGLE-004", "G8-GROUPED-005", "G8-REASON-006",        # T3.7 で追加: Discord 通知の分割・429 の待機・重複除外・本文・理由文
        "GD11-WINKEYS-001", "GD11-SETTLE-EVAL-002", "GD11-SETTLE-WIN-003",                                         # T3.9 ①: 決済（SettlementCalculator）
    ],
    "unsupported": {
        "GD10-DEFAULTS-001": "旧関数のシグネチャ既定値の突合。運用の既定値は T1.20/T3.8 で決める（D-016）",
    },
}
