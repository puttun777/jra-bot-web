"""合成入力のシナリオ定義（旧実装の呼び出しや正規化は含まない）。実データは一切使わない。"""
from dataclasses import dataclass, field
from itertools import product

from . import constants as C, scenarios_ml as M

ABS = lambda path, tol: {"path": path, "mode": "abs_tol", "abs_tol": tol}  # noqa: E731


@dataclass(frozen=True)
class Case:
    case_id: str
    scenario_ids: tuple
    description: str
    source_functions: tuple
    calls: list
    default_abs_tol: float = C.TOL_PROB
    overrides: tuple = ()
    known_defects: tuple = ()
    notes: str = ""
    ml: bool = False  # ML 経路（合成 SQLite + 合成モデルの pickle）。execution_parameters に記録する


def _calls(prefix, function, arg_dicts):
    return [{"call_id": f"{prefix}-{i:03d}", "function": function, "args": a} for i, a in enumerate(arg_dicts)]


PROBS = [-0.1, 0.0, 1e-6, 0.1, 0.25, 0.5, 0.99, 1.0]
ODDS = [-1.0, 0.0, 0.1, 1.0, 1.01, 2.0, 10.0, 999.0]


def _ev_001():
    grid = [{"prob": p, "odds": o} for p, o in product(PROBS, ODDS)]
    calls = _calls("ev", "calc_ev", grid) + _calls("er", "calc_expected_return", grid)
    calls += _calls("ep", "calc_expected_profit", [{"stake": 10.0, "prob": p, "odds": o} for p, o in product([0.0, 0.3, 1.0], [0.0, 1.0, 2.5])])
    return calls


def _kelly_002():
    grid = [{"prob": p, "odds": o} for p, o in product([0.0, 0.05, 0.3, 0.6, 1.0], [0.0, 1.0, 1.01, 2.0, 5.0, 999.0])]
    calls = _calls("kf", "calc_kelly_fraction", grid)
    calls += _calls("kf1", "calc_kelly_fraction", [dict(g, kwargs={"max_fraction": 1.0}) for g in grid])
    sba = [
        {"prob": 0.4, "odds": 3.0, "kwargs": {}},                                   # kelly 0.1 → 10.0（max_bet 境界）
        {"prob": 0.4, "odds": 3.0, "kwargs": {"bankroll": 100.0, "min_bet": 10.0, "max_bet": 10.0}},
        {"prob": 0.34, "odds": 3.0, "kwargs": {"min_bet": 1.0}},                    # min_bet 未満 → 引き上げ
        {"prob": 0.34, "odds": 3.0, "kwargs": {"bankroll": 10.0, "min_bet": 0.5}},
        {"prob": 0.2, "odds": 3.0, "kwargs": {}},                                   # EV 負 → 0
        {"prob": 0.9, "odds": 3.0, "kwargs": {"max_fraction": 0.05}},
        {"prob": 0.5, "odds": 1.0, "kwargs": {}},                                   # odds=1.0（E5）
        {"prob": 0.5, "odds": 0.0, "kwargs": {}},
    ]
    return calls + _calls("sb", "suggest_bet_amount", sba)


def _prob_001():
    odds = [-1.0, 0.0, 0.5, 1.0, 1.01, 1.5, 2.0, 100.0, 999.0]
    return _calls("raw", "estimate_prob_raw", [{"odds": o} for o in odds]) + \
        _calls("ep", "estimate_prob", [{"odds": o} for o in odds])


def _norm_002():
    lists = [
        [], [5.0], [2.0, 3.0], [4.0] * 18,
        [3.0, 4.5, 7.0, 12.0, 25.0, 40.0],
        [1.1, 50.0, 80.0, 120.0, 200.0, 300.0],
        [2.0, 0.0, 5.0], [0.0, 0.0, 0.0], [-1.0, 4.0],
        [1.0, 1.0], [1.5, 3.0, 6.0, 12.0, 24.0, 48.0, 96.0, 999.0],
    ]
    calls = _calls("n", "estimate_prob_normalized", [{"odds_list": o} for o in lists])
    calls += _calls("nl", "estimate_prob_normalized", [
        {"odds_list": [3.0, 4.5, 7.0, 12.0, 25.0, 40.0], "correction_limit": c} for c in (0.0, 0.01, 0.10, 0.5)])
    return calls


def _hash_001():
    names = ["", "a", "Alpha", "alpha", "ＡＢＣ", "ABC", "シンセティックサイアー001", "合成母父　テスト", "ひらがなのなまえ",
             "漢字名称", " leading", "trailing ", "dup", "dup", "emoji🐎x", "NFCé", "NFD é"]
    calls = _calls("hb", "hash_bucket", [{"s": n, "dim": d} for n in names for d in (16, 1, 64)])
    calls += _calls("hs", "hash_sign", [{"s": n} for n in names])
    calls += _calls("hz", "hash_bucket", [{"s": "x", "dim": 0}, {"s": "x", "dim": -3}])
    return calls


MODEL_A = {"weights": [0.01, -0.05, -0.3, 1.2, -0.0002], "bias": -1.5}
MODEL_CAL = dict(MODEL_A, calib_a=1.1, calib_b=-0.2)
MODEL_BLEND = dict(MODEL_CAL, market_blend_alpha=0.6)
MODEL_SLICE = {"weights": [0.01, -0.05, -0.3], "bias": 0.1}
MODEL_PAD = {"weights": [0.01, -0.05, -0.3, 1.2, -0.0002, 0.7, -0.4], "bias": 0.0, "market_blend_alpha": 0.25}
MODEL_EXTREME = {"weights": [5.0, 5.0, 5.0, 5.0, 5.0], "bias": 100.0}
MODEL_NONE = {"weights": None, "bias": 0.0}
MODELS = {"plain": MODEL_A, "calib": MODEL_CAL, "blend": MODEL_BLEND, "slice": MODEL_SLICE,
          "pad": MODEL_PAD, "extreme": MODEL_EXTREME, "untrained": MODEL_NONE,
          "alpha0": dict(MODEL_A, market_blend_alpha=0.0), "alpha1": dict(MODEL_A, market_blend_alpha=1.0)}


def _model_001():
    pts = [(0.0, 0), (0.1, 1), (1.0, 1), (2.5, 2), (10.0, 5), (50.0, 12), (999.0, 18)]
    calls = []
    for name, m in MODELS.items():
        calls += _calls(f"mp-{name}", "model_predict", [{"model": m, "odds": o, "popularity": p} for o, p in pts])
    calls += _calls("mf", "model_extract_features", [{"model": MODEL_A, "odds": o, "popularity": p} for o, p in pts])
    odds = [2.5, 4.0, 8.0, 15.0, 30.0]
    pops = [1, 2, 3, 4, 5]
    for name in ("plain", "blend", "untrained"):
        calls += _calls(f"mb-{name}", "model_predict_batch", [{"model": MODELS[name], "odds_list": odds, "popularity_list": pops}])
    return calls


def _filter_001():
    snaps = lambda *rows: [{"odds": o, "popularity": p} for o, p in rows]  # noqa: E731
    drop = []
    for cur in (8.0, 8.01, 7.99, 5.0, 12.0):  # 最古 10.0、閾値 0.20 → cur=8.0 がちょうど境界
        drop.append({"recent_snapshots": snaps((9.0, 3), (10.0, 4)), "current_odds": cur})
    drop += [{"recent_snapshots": [], "current_odds": 5.0},
             {"recent_snapshots": snaps((9.0, 3)), "current_odds": 1.0},
             {"recent_snapshots": snaps((9.0, 3), (0.0, 4)), "current_odds": 5.0},
             {"recent_snapshots": snaps((9.0, 3), (-1.0, 4)), "current_odds": 5.0},
             {"recent_snapshots": snaps((1.0, 1), (10.0, 4), (20.0, 9)), "current_odds": 15.0},  # 最古=末尾要素のみ参照（E14）
             {"recent_snapshots": snaps((9.0, 3), (10.0, 4)), "current_odds": 5.0, "kwargs": {"drop_threshold": 0.5}}]
    surge = []
    for cur in (1, 2, 3, 4, 7):  # 最古の人気 6、閾値 3 → cur=3 がちょうど境界
        surge.append({"recent_snapshots": snaps((9.0, 5), (10.0, 6)), "current_popularity": cur})
    surge += [{"recent_snapshots": [], "current_popularity": 1},
              {"recent_snapshots": snaps((9.0, 5)), "current_popularity": 1},
              {"recent_snapshots": snaps((9.0, 5), (10.0, 6)), "current_popularity": 2, "kwargs": {"surge_threshold": 4}},
              {"recent_snapshots": snaps((9.0, 5), (10.0, 0)), "current_popularity": 0}]
    return _calls("od", "check_odds_drop", drop) + _calls("ps", "check_popularity_surge", surge)


def _combo_001():
    ol = [{"umaban": 1, "odds": 3.0, "popularity": 1}, {"umaban": 2, "odds": 6.0, "popularity": 2}]
    calls = _calls("wo", "estimate_wide_odds", [{"win_odds": o} for o in (-1.0, 0.0, 1.0, 3.0, 3.01, 10.0, 10.01, 100.0)])
    calls += _calls("wo18", "estimate_wide_odds", [{"win_odds": 5.0, "kwargs": {"num_horses": 8}}])
    calls += _calls("to", "estimate_trifecta_odds", [{"win_odds": o} for o in (-1.0, 0.0, 1.0, 3.0, 3.01, 10.0, 10.01, 100.0)])
    calls += _calls("wp", "calculate_wide_prob", [{"win_prob": p, "odds_list": ol, "target_umaban": 1} for p in (-0.1, 0.0, 0.1, 0.5, 0.66, 0.67, 1.0)])
    calls += _calls("wpe", "calculate_wide_prob", [{"win_prob": 0.3, "odds_list": [], "target_umaban": 1}])
    calls += _calls("tp", "calculate_trifecta_prob", [{"win_prob": p, "odds_list": ol, "target_umaban": 1} for p in (-0.1, 0.0, 0.1, 0.5, 0.55, 0.56, 1.0)])
    calls += _calls("tpe", "calculate_trifecta_prob", [{"win_prob": 0.3, "odds_list": [], "target_umaban": 1}])
    return calls


_RACE6 = [{"umaban": i + 1, "odds": o, "popularity": i + 1} for i, o in enumerate([3.0, 4.5, 7.0, 12.0, 25.0, 40.0])]


def _eval_001():
    def with_(i, odds):
        r = [dict(x) for x in _RACE6]
        r[i]["odds"] = odds
        return r
    base = {"ev_threshold": -0.5, "ev_shrink_factor": 1.0}
    cases = [
        {"odds": _RACE6, "kwargs": dict(base)},                                          # 通常
        {"odds": _RACE6, "kwargs": dict(base, max_signals=1)},
        {"odds": _RACE6, "kwargs": dict(base, ev_threshold=0.5)},                        # 閾値超過で全除外
        {"odds": _RACE6, "kwargs": dict(base, kelly_fraction=0.5, max_bet=1.0)},
        {"odds": with_(0, 1.0), "kwargs": dict(base)},                                   # 単勝 1.0 倍（E5）
        {"odds": [], "kwargs": dict(base)},
        {"odds": _RACE6[:1], "kwargs": dict(base)},
    ]
    return _calls("ev", "evaluate_odds", cases)


def _defaults_001():
    spec = [("decision", "evaluate_odds"), ("bet_strategy", "suggest_multiple_bet_strategies"),
            ("ev_calc", "calc_kelly_fraction"), ("ev_calc", "suggest_bet_amount"),
            ("decision", "check_odds_drop"), ("decision", "check_popularity_surge")]
    return _calls("sd", "signature_defaults", [{"module": m, "function": f} for m, f in spec])


# ---- T3.1（Stage 3-A）で追加: evaluate_odds の単勝判定（hit_rate・フィルタ・選別・賭け額・上限縮小）。既存ケースは変更しない ----
def _race(odds, pops=None):
    pops = pops or list(range(1, len(odds) + 1))
    return [{"umaban": i + 1, "odds": float(o), "popularity": p} for i, (o, p) in enumerate(zip(odds, pops))]


_ODDS18 = [4.2, 6.8, 9.6, 13.0, 16.0, 21.0, 26.0, 32.0, 40.0, 52.0, 66.0, 84.0, 110.0, 140.0, 180.0, 240.0, 360.0, 500.0]  # 逆数の和 < 1（EV が正の馬が多い）
_LOOSE = {"ev_threshold": -0.5, "ev_shrink_factor": 1.0}  # ほぼ全馬が閾値を通る（賭け額・選別を見るため）
_BIG = dict(_LOOSE, bankroll=2000.0, max_bet=50.0)  # 資金を増やし、頭数の多いレースでも多くの馬が min_bet を超える（同点・並びを見るため）


def _eval_calls(prefix, cases):
    return _calls(prefix, "evaluate_odds", cases)


def _hit_rate_003():
    r6, r18 = _RACE6, _race(_ODDS18)
    hr = dict(_LOOSE, priority_mode="hit_rate")
    cases = [
        {"odds": r6, "kwargs": dict(hr)},                                                        # フィルタなし（EV 閾値のみ）
        {"odds": r6, "kwargs": dict(hr, win_odds_max=12.0)},                                      # 12.0 ちょうどは除外しない（odds > 上限）
        {"odds": r6, "kwargs": dict(hr, win_odds_max=11.999)},
        {"odds": r6, "kwargs": dict(hr, win_prob_min=0.1)},
        {"odds": r6, "kwargs": dict(hr, win_prob_min=0.25)},
        {"odds": r6, "kwargs": dict(hr, win_ratio_min=1.1)},
        {"odds": r6, "kwargs": dict(hr, win_ratio_min=1.2)},
        {"odds": r6, "kwargs": dict(hr, win_odds_max=20.0, win_prob_min=0.05, win_ratio_min=0.9)},  # 3 フィルタの組合せ
        {"odds": r6, "kwargs": dict(hr, hit_rate_min_ev=0.15)},                                   # hit_rate_min_ev が ev_threshold を上書き
        {"odds": r6, "kwargs": dict(hr, hit_rate_min_ev=0.15, ev_threshold=-0.9)},
        {"odds": r6, "kwargs": dict(hr, ev_threshold=0.15)},                                      # hit_rate_min_ev なし → ev_threshold
        {"odds": r6, "kwargs": dict(hr, hit_rate_bet_type_priority=["単勝", "ワイド"], max_signals=3)},
        {"odds": r6, "kwargs": dict(hr, hit_rate_bet_type_priority=[], max_signals=3)},
        {"odds": r6, "kwargs": dict(_LOOSE, win_odds_max=5.0, win_prob_min=0.9, win_ratio_min=9.0)},  # edge ではフィルタは無効
        {"odds": r6, "kwargs": dict(_LOOSE, priority_mode="unknown_mode", win_odds_max=5.0)},       # hit_rate 以外は edge 扱い
        {"odds": r18, "kwargs": dict(hr, bankroll=2000.0, max_bet=50.0, max_signals=5, win_prob_min=0.01)},
        {"odds": r18, "kwargs": dict(hr, bankroll=2000.0, max_bet=50.0, max_signals=18, notify_skips=True)},   # 見送りシグナルを含む並び
        {"odds": r18, "kwargs": dict(hr, max_signals=18, notify_skips=True, hit_rate_min_ev=-0.9)},
        {"odds": r18, "kwargs": dict(hr, bankroll=2000.0, max_bet=50.0, max_signals=4, win_odds_max=30.0, win_ratio_min=1.0)},
    ]
    return _eval_calls("hr", cases)


def _snapshots_002():
    r6 = _RACE6
    sn = lambda *rows: [{"odds": o, "popularity": p} for o, p in rows]  # noqa: E731  新しい順
    snapshots = {
        "1": sn((3.0, 1), (3.2, 1)),            # 急落なし（3.2 → 3.0）
        "2": sn((4.5, 2), (5.625, 4)),          # 最古 5.625 → 4.5: 下落率 0.2（境界: 急落ではない）
        "3": sn((7.0, 3), (9.0, 3)),            # 9.0 → 7.0: 0.222… > 0.2 → 急落
        "4": sn((12.0, 4), (11.0, 7)),          # 人気 7 → 4: 差 3（境界: 急上昇）
        "5": sn((25.0, 5), (26.0, 7)),          # 人気 7 → 5: 差 2（急上昇でない）
        "6": sn((40.0, 6)),                      # 1 件だけ → 判定しない
    }
    cases = [
        {"odds": r6, "kwargs": dict(_LOOSE), "snapshots": snapshots},
        {"odds": r6, "kwargs": dict(_LOOSE, drop_threshold=0.1), "snapshots": snapshots},
        {"odds": r6, "kwargs": dict(_LOOSE, drop_threshold=0.3, surge_threshold=2), "snapshots": snapshots},
        {"odds": r6, "kwargs": dict(_LOOSE, surge_threshold=4), "snapshots": snapshots},
        {"odds": r6, "kwargs": dict(_LOOSE), "snapshots": {}},                                    # 全馬スナップショットなし
        {"odds": r6, "kwargs": dict(_LOOSE), "snapshots": {"1": sn((3.0, 1), (0.0, 1)), "2": sn((4.5, 2), (-1.0, 2))}},  # 最古のオッズ 0/負
        {"odds": r6, "kwargs": dict(_LOOSE, priority_mode="hit_rate", win_prob_min=0.1), "snapshots": snapshots},  # hit_rate フィルタが先
        {"odds": r6, "kwargs": dict(_LOOSE, notify_skips=True), "snapshots": snapshots},
        {"odds": r6, "kwargs": dict(_BIG, max_signals=6), "snapshots": snapshots},
        {"odds": _race(_ODDS18), "kwargs": dict(_BIG, max_signals=18, drop_threshold=0.05),
         "snapshots": {str(i + 1): [{"odds": o, "popularity": i + 1}, {"odds": o * 1.1, "popularity": i + 3}] for i, o in enumerate(_ODDS18)}},
    ]
    return _eval_calls("sn", cases)


def _select_003():
    r6, r18 = _RACE6, _race(_ODDS18)
    same18 = _race([20.0] * 18)  # 逆数の和 0.9 < 1: 全馬が同じ正の EV
    cases = [
        {"odds": same18, "kwargs": dict(_BIG, max_signals=2)},                                      # 全馬同オッズ（同点は入力順）
        {"odds": same18, "kwargs": dict(_BIG, max_signals=5, priority_mode="hit_rate")},
        {"odds": same18, "kwargs": dict(_BIG, max_signals=0)},
        {"odds": same18, "kwargs": dict(_BIG, max_signals=100)},
        {"odds": same18, "kwargs": dict(_LOOSE, max_signals=3)},                                    # 賭け額が min_bet 未満 → 信号なし
        {"odds": _race([8.0, 8.0, 8.0, 5.0, 5.0], [1, 2, 3, 4, 5]), "kwargs": dict(_BIG, max_signals=5)},   # 同点のグループ 2 つ
        {"odds": _race([8.0, 8.0, 8.0, 5.0, 5.0], [1, 2, 3, 4, 5]), "kwargs": dict(_BIG, max_signals=5, priority_mode="hit_rate")},
        {"odds": _race([8.0, 8.0, 8.0, 5.0, 5.0], [1, 2, 3, 4, 5]), "kwargs": dict(_BIG, max_signals=5, priority_mode="hit_rate",
                                                                                   hit_rate_bet_type_priority=["単勝"])},
        {"odds": r18, "kwargs": dict(_BIG, max_signals=3)},
        {"odds": r18, "kwargs": dict(_BIG, max_signals=18)},
        {"odds": r18, "kwargs": dict(_BIG, max_signals=18, priority_mode="hit_rate")},
        {"odds": r18, "kwargs": dict(ev_threshold=0.08, ev_shrink_factor=0.7)},                     # 既定どおり
        {"odds": r6, "kwargs": dict(_LOOSE, include_bet_strategy=False)},
        {"odds": r6, "kwargs": dict(_LOOSE, bet_type_mode="best_only")},
        {"odds": r6, "kwargs": dict(_LOOSE, bet_type_mode="BEST_ONLY", max_signals=3)},
        {"odds": r6, "kwargs": dict(_LOOSE, bet_types=["単勝"])},
        {"odds": r6, "kwargs": dict(_LOOSE, bet_types=["ワイド"], notify_skips=True)},               # 単勝を含まない → 見送りエントリが最推奨
        {"odds": r6, "kwargs": dict(_LOOSE, bet_types=["三連複", "単勝"])},
        {"odds": r6, "kwargs": dict(_LOOSE, bet_types=[])},                                         # 対象の賭式なし
        {"odds": r6, "kwargs": dict(_LOOSE, notify_skips=True, max_signals=6)},
        {"odds": r6, "kwargs": dict(_LOOSE, notify_skips=True, max_signals=6, race_id="synthetic-race-1",
                                    win_payout_rate=0.81, show_adjusted_mkt=True)},
        {"odds": r6, "kwargs": dict(_LOOSE, bet_types=["ワイド", "三連複"], notify_skips=True, ev_threshold=-1.5)},
        # win_max_over_market（p/市場確率 > 上限で除外。無効値は 0 以下）
        {"odds": r6, "kwargs": dict(_LOOSE, win_max_over_market=1.0)},
        {"odds": r6, "kwargs": dict(_LOOSE, win_max_over_market=1.1)},
        {"odds": r6, "kwargs": dict(_LOOSE, win_max_over_market=2.0)},
        {"odds": r6, "kwargs": dict(_LOOSE, win_max_over_market=-1.0)},
        {"odds": r6, "kwargs": dict(_LOOSE, win_max_over_market=None)},
        # max_total_bet_per_race（E6: 比例縮小・min_bet を見ない。最推奨の賭け額は scale² になる旧挙動）
        {"odds": r6, "kwargs": dict(_LOOSE, kelly_fraction=1.0, max_bet=10.0, max_signals=3, max_total_bet_per_race=12.0)},
        {"odds": r6, "kwargs": dict(_LOOSE, kelly_fraction=1.0, max_bet=10.0, max_signals=3, max_total_bet_per_race=1000.0)},
        {"odds": r6, "kwargs": dict(_LOOSE, kelly_fraction=1.0, max_bet=10.0, max_signals=3, max_total_bet_per_race=0.0)},
        {"odds": r6, "kwargs": dict(_LOOSE, kelly_fraction=1.0, max_bet=10.0, max_signals=3, max_total_bet_per_race=-5.0)},
        {"odds": r6, "kwargs": dict(_LOOSE, kelly_fraction=1.0, max_bet=10.0, max_signals=6, max_total_bet_per_race=2.0, notify_skips=True)},
        {"odds": r6, "kwargs": dict(_LOOSE, kelly_fraction=1.0, max_signals=3, max_total_bet_per_race=3.0, bet_type_mode="best_only")},
    ]
    return _eval_calls("sel", cases)


def _sizing_004():
    r6, r18 = _RACE6, _race(_ODDS18)
    cases = []
    # ev_shrink_factor（[0,1] に収める。0.0 はメタ情報では 1.0 になる旧挙動）
    for f in (0.0, 0.5, 0.7, 1.0, 1.5, -0.2):
        cases.append({"odds": r6, "kwargs": {"ev_threshold": -0.5, "ev_shrink_factor": f}})
    # Kelly 分数・資金・min/max bet（bet_before_clip == min_bet の境界を含む）
    for kf in (0.0, 0.1, 0.25, 0.5, 1.0):
        cases.append({"odds": r6, "kwargs": dict(_LOOSE, kelly_fraction=kf, notify_skips=True, max_signals=6)})
    for bankroll in (10.0, 50.0, 100.0, 1000.0):
        cases.append({"odds": r6, "kwargs": dict(_LOOSE, bankroll=bankroll, notify_skips=True, max_signals=6)})
    for mb, xb in ((0.0, 10.0), (1.0, 10.0), (2.0, 3.0), (5.0, 5.0), (0.5, 0.5)):
        cases.append({"odds": r6, "kwargs": dict(_LOOSE, min_bet=mb, max_bet=xb, notify_skips=True, max_signals=6)})
    # 市場ブレンド・確率クリップ
    for a in (0.0, 0.0005, 0.001, 0.0011, 0.25, 0.5, 0.9, 0.998, 0.999, 1.0, 1.5, -0.5):
        cases.append({"odds": r6, "kwargs": dict(_LOOSE, ml_market_blend_alpha=a, max_signals=6, notify_skips=True)})
    for c in (0.05, 0.2, 0.35, 1.0, 0.0, -1.0):
        cases.append({"odds": r6, "kwargs": dict(_LOOSE, win_prob_clip_max=c, max_signals=6, notify_skips=True)})
    cases.append({"odds": r6, "kwargs": dict(_LOOSE, enable_prob_clip=False, max_signals=6, notify_skips=True)})
    cases.append({"odds": r6, "kwargs": dict(_LOOSE, ml_market_blend_alpha=1.0, win_prob_clip_max=0.1, max_signals=6, notify_skips=True)})
    # 頭数・極端な人気
    for odds in ([1.2], [1.5, 3.0], [1.1, 30.0, 40.0, 60.0, 80.0, 100.0], [1.5, 1.5], [999.0, 999.0, 999.0],
                 [2.0, 0.0, 5.0], [0.0, 0.0], [0.5, 4.0], [1.01, 2.0]):
        cases.append({"odds": _race(odds), "kwargs": dict(_LOOSE, max_signals=10, notify_skips=True)})
    cases.append({"odds": _race([2.5, 4.0, 6.0, 9.0], [0, 0, 0, 0]), "kwargs": dict(_LOOSE, max_signals=10)})  # 人気 0（欠損）
    cases.append({"odds": r18, "kwargs": dict(_BIG, max_signals=18, notify_skips=True)})
    cases.append({"odds": r18, "kwargs": dict(_LOOSE, max_signals=18, notify_skips=True)})
    cases.append({"odds": r18, "kwargs": dict(ev_threshold=0.0, ev_shrink_factor=0.7, max_signals=18, notify_skips=True)})
    return _eval_calls("sz", cases)



# ---- T3.2（Stage 3-B）で追加: ワイド/三連複の確率（Plackett–Luce）・流し/BOX・実オッズ・フィルタ・賭け額・配分・判定への統合。既存ケースは変更しない ----
# 実オッズの辞書は JSON ではタプルのキーを表せないので配列で与える（adapter._decode_odds_maps が旧実装の辞書に直す。キーは昇順）。
def _mkt(odds):
    """市場の暗黙確率（1/オッズ）の正規化。合成入力を作るための補助で、期待値の生成には使わない。"""
    inv = [1.0 / max(o, 0.1) for o in odds]
    t = sum(inv)
    return [x / t for x in inv]


def _pm(probs):
    return {str(i + 1): p for i, p in enumerate(probs)}


def _wmap(n, fn):
    return [[a, b, *fn(a, b)] for a in range(1, n + 1) for b in range(a + 1, n + 1) if fn(a, b) is not None]


def _tmap(n, fn):
    return [[a, b, c, fn(a, b, c)] for a in range(1, n + 1) for b in range(a + 1, n + 1) for c in range(b + 1, n + 1) if fn(a, b, c) is not None]


_WHI = lambda a, b: (200.0, 260.0)  # noqa: E731  実オッズ（高め: ほぼ全ての買い目が EV > 0 になり、確率が見える）
_THI = lambda a, b, c: 2000.0  # noqa: E731


def _sg(umaban, probs, odds, pops=None, **kw):
    """suggest_multiple_bet_strategies の直接呼び出し。probs は全馬の最終確率（probs_by_umaban）。"""
    return {"umaban": umaban, "win_prob": probs[umaban - 1], "win_odds": float(odds[umaban - 1]),
            "odds_list": _race(odds, pops), "kwargs": dict(probs_by_umaban=_pm(probs), **kw)}


def _merge(*ds):
    out = {}
    for d in ds:
        out.update(d)
    return out


_RAW = dict(ev_shrink_factor=1.0, wide_max_prob=0.0, sanrenpuku_max_prob=0.0)  # 縮小・上限なし（Plackett–Luce の生の確率が prob / prob_used に出る）


def _pl_005():
    cases = []

    def add(probs, odds, axes, pops=None, wide=_WHI, tri=_THI, **kw):
        n = len(probs)
        for u in axes:
            cases.append(_sg(u, probs, odds, pops, **_merge(_RAW, dict(max_tickets_per_type=200, top_k_partners=6, box_size=5,
                                                                      wide_odds_map=_wmap(n, wide), sanrenpuku_odds_map=_tmap(n, tri)), kw)))
    p3 = [0.5, 0.3, 0.2]
    add(p3, [2.0, 3.3, 5.0], [1, 2, 3])                                                   # 3 頭（流しと BOX が同じ組合せになる）
    p5 = [0.35, 0.25, 0.2, 0.12, 0.08]
    add(p5, [1 / p for p in p5], [1, 2, 3, 4, 5])                                          # 5 頭
    for a in (0.0, 0.05, 0.1, 0.5, 1.0, 2.0, 5.0, 5.5, 100.0, -1.0):                       # α の端（0.0 は falsy で 1.0、0.1 未満/5 超は丸め）
        add(p5, [1 / p for p in p5], [2], plackett_luce_alpha=a)
    o18 = _ODDS18
    p18 = _mkt(o18)
    add(p18, o18, [1, 9, 18], max_tickets_per_type=14)                                      # 18 頭（軸が BOX 候補の内/外）
    add(p18, o18, [1], plackett_luce_alpha=0.7, max_tickets_per_type=14)
    add(p18, o18, [1], plackett_luce_alpha=1.8, max_tickets_per_type=14)
    add([1 / 6] * 6, [6.0] * 6, [1, 4])                                                     # 全馬同確率
    conc = [0.9, 0.04, 0.02, 0.01, 0.01, 0.01, 0.005, 0.005]
    add(conc, [1 / p for p in conc], [1, 2, 8])                                             # 人気集中（合計は 1 でない）
    low = [0.97, 0.02, 0.009, 1e-6, 1e-9, 1e-12, 1e-15]
    add(low, [1.05, 50.0, 111.0, 1e6, 1e9, 1e12, 1e15], [1, 2, 6, 7],
        wide=lambda a, b: (1e4, 1.2e4), tri=lambda a, b, c: 1e5)                            # 極端に低い確率（重みは 1e-12 で下限）
    weird = [0.5, 0.0, -0.2, 1.5, 0.3, 0.2]
    add(weird, [2.0, 9.0, 9.0, 1.1, 3.3, 5.0], [1, 2, 3, 4, 5])                            # 確率 0・負・1 超（重みは [1e-12, 0.99] に収める）
    add([0.3, 0.2, 0.1, 0.05, 0.05, 0.05], [3.3, 5.0, 10.0, 20.0, 20.0, 20.0], [1, 4])    # 合計 0.75（正規化しない）
    add([0.6, 0.4], [1.6, 2.5], [1, 2])                                                     # 2 頭（三連複は成立しない）
    add([1.0], [1.2], [1])                                                                  # 1 頭
    return cases


_RACE8 = [8.0, 3.0, 12.0, 5.0, 20.0, 2.5, 30.0, 15.0]


def _flow_006():
    cases = []

    def add(axis, odds, pops, **kw):
        n = len(odds)
        probs = _mkt(odds)
        cases.append(_sg(axis, probs, odds, pops, **_merge(_RAW, dict(max_tickets_per_type=200, box_size=3, wide_odds_map=_wmap(n, _WHI),
                                                                     sanrenpuku_odds_map=_tmap(n, _THI)), kw)))
    pops = [5, 2, 6, 3, 7, 1, 8, 4]                                                         # 出走順と人気順が違う
    for k in (0, 1, 2, 3, 4, 6, 100):                                                       # 相手の頭数（ワイドは max(1, k)、三連複は max(3, k)）
        add(1, _RACE8, pops, top_k_partners=k)
    for axis in (2, 6, 8):
        add(axis, _RACE8, pops, top_k_partners=3)
    add(4, _RACE8, [3, 0, 1, 0, 2, 4, 0, 5], top_k_partners=6)                              # 人気 0 の馬は相手にならない
    add(2, _RACE8, [3, 0, 1, 0, 2, 4, 0, 5], top_k_partners=6)                              # 軸の人気が 0（BOX 候補に入らない）
    add(1, _RACE8, [1, 1, 2, 2, 3, 3, 4, 4], top_k_partners=3)                              # 人気の同順位（出走順を保つ）
    add(1, _RACE8, [1, 1, 1, 1, 1, 1, 1, 1], top_k_partners=2)
    add(1, _RACE8, [0] * 8, top_k_partners=6, box_size=4)                                   # 人気が全て 0: 流しは無し、BOX 候補だけオッズ順
    add(6, _RACE8, [0] * 8, top_k_partners=6, box_size=4)
    add(1, [8.0, 0.0, 12.0, 0.0, 20.0, 2.5, 30.0, 15.0], [0] * 8, top_k_partners=6, box_size=4)  # オッズ 0 の馬は BOX 候補に入らない
    return cases


def _box_007():
    cases = []
    odds = [3.0, 4.5, 7.0, 12.0, 25.0, 40.0, 80.0]
    n = len(odds)
    probs = _mkt(odds)

    def add(axis, odds_=odds, pops=None, **kw):
        probs_ = _mkt(odds_)
        m = len(odds_)
        cases.append(_sg(axis, probs_, odds_, pops, **_merge(_RAW, dict(top_k_partners=3, wide_odds_map=_wmap(m, _WHI),
                                                                       sanrenpuku_odds_map=_tmap(m, _THI)), kw)))
    for bs in (0, 1, 3, 4, 5, 7, 10):                                                       # BOX の頭数 max(3, box_size)
        add(1, box_size=bs, max_tickets_per_type=200)
    for axis in (3, 5, 7):                                                                   # 軸が BOX 候補の外なら BOX なし
        add(axis, box_size=4, max_tickets_per_type=200)
    for mt in (1, 2, 3, 6, 0, -3, 100):                                                      # 点数上限（重複した買い目も数に入る）
        add(2, box_size=4, max_tickets_per_type=mt)
    add(1, [3.0, 4.5], box_size=4, max_tickets_per_type=200)                                 # 頭数不足
    add(1, [3.0], box_size=4, max_tickets_per_type=200)
    add(1, [3.0, 4.5, 7.0], box_size=4, max_tickets_per_type=200)
    add(2, [3.0, 4.5, 7.0], pops=[0, 0, 0], box_size=4, max_tickets_per_type=200)            # 人気なし → オッズ順
    add(1, [0.0, 0.0, 0.0, 0.0], pops=[0, 0, 0, 0], box_size=4, max_tickets_per_type=200)    # オッズも 0 → BOX 候補なし
    return cases


def _real_008():
    cases = []
    race = [3.0, 4.5, 7.0, 12.0, 25.0, 40.0]
    probs = _mkt(race)
    n = len(race)
    wl = lambda a, b: (round(0.12 / (probs[a - 1] * probs[b - 1]) + 1.0, 1), round(0.2 / (probs[a - 1] * probs[b - 1]) + 1.0, 1))  # noqa: E731
    tl = lambda a, b, c: round(0.06 / (probs[a - 1] * probs[b - 1] * probs[c - 1]) + 1.0, 1)  # noqa: E731
    full_w, full_t = _wmap(n, wl), _tmap(n, tl)
    part_w = [r for r in full_w if r[0] == 1 or r[1] == 1][:2]                                # (1,2),(1,3) だけ
    part_t = [r for r in full_t if r[:3] == [1, 2, 3] or r[:3] == [1, 2, 4]]

    def add(axis, **kw):
        cases.append(_sg(axis, probs, race, **dict(dict(ev_shrink_factor=1.0, max_tickets_per_type=50, box_size=4), **kw)))
    for req in (True, False):
        for axis in (1, 3, 6):
            add(axis, require_real_odds_for_combo=req)                                       # 実オッズなし（True: 見送りエントリ / False: 推定オッズ）
            add(axis, require_real_odds_for_combo=req, wide_odds_map=full_w, sanrenpuku_odds_map=full_t)
            add(axis, require_real_odds_for_combo=req, wide_odds_map=part_w, sanrenpuku_odds_map=part_t)  # 一部の組合せだけ
    for axis in (4, 5):
        add(axis, require_real_odds_for_combo=False)
        add(axis, require_real_odds_for_combo=False, wide_max_prob=0.0, sanrenpuku_max_prob=0.0)
    add(1, require_real_odds_for_combo=False, wide_odds_map=[], sanrenpuku_odds_map=[])    # 空の辞書は『無い』扱い（メモの文言）
    add(1, require_real_odds_for_combo=True, wide_odds_map=[], sanrenpuku_odds_map=[])
    zero_t = [[a, b, c, 0.0] for a, b, c in ((1, 2, 3), (1, 2, 4), (1, 2, 5), (1, 3, 4))]  # 三連複のオッズ 0.0 は『無い』（推定または見送り）
    add(1, require_real_odds_for_combo=True, sanrenpuku_odds_map=zero_t)
    add(1, require_real_odds_for_combo=False, sanrenpuku_odds_map=zero_t)
    zero_w = [[1, 2, 0.0, 0.0], [1, 3, 0.0, 5.0]]                                             # ワイドの下限 0.0 は実オッズとして使われる（EV −1 で除外。推定には戻らない）
    add(1, require_real_odds_for_combo=True, wide_odds_map=zero_w)
    add(1, require_real_odds_for_combo=False, wide_odds_map=zero_w)
    add(1, require_real_odds_for_combo=False, wide_odds_map=[[1, 2, 1.0, 1.0], [1, 3, 1.0, 1.0]])  # オッズ 1.0（元返し）
    for bt in (["ワイド"], ["三連複"], ["単勝", "ワイド"], ["単勝", "三連複"], ["ワイド", "三連複"]):  # 空（[]）は evaluate_odds 経由（GD08-SELECT-003）で検証済み
        add(1, require_real_odds_for_combo=True, wide_odds_map=full_w, sanrenpuku_odds_map=full_t, allowed_bet_types=bt)
    add(1, require_real_odds_for_combo=True, wide_odds_map=full_w, sanrenpuku_odds_map=full_t, wide_payout_rate=0.5, sanrenpuku_payout_rate=0.5)
    return cases


def _filter_009():
    cases = []
    race = [3.0, 4.5, 7.0, 12.0, 25.0, 40.0]
    probs = _mkt(race)
    n = len(race)
    wl = lambda a, b: (round(0.12 / (probs[a - 1] * probs[b - 1]) + 1.0, 1), round(0.2 / (probs[a - 1] * probs[b - 1]) + 1.0, 1))  # noqa: E731
    tl = lambda a, b, c: round(0.06 / (probs[a - 1] * probs[b - 1] * probs[c - 1]) + 1.0, 1)  # noqa: E731
    mw, mt = _wmap(n, wl), _tmap(n, tl)

    def add(axis=1, **kw):
        cases.append(_sg(axis, probs, race, **dict(dict(wide_odds_map=mw, sanrenpuku_odds_map=mt, max_tickets_per_type=100, box_size=4), **kw)))
    for f in (0.0, 0.3, 0.5, 0.7, 1.0, 1.5, -1.0):                                          # 縮小 → 上限 の順序
        add(ev_shrink_factor=f)
        add(ev_shrink_factor=f, wide_max_prob=0.3, sanrenpuku_max_prob=0.1)
    for mx in (0.0, 0.05, 0.1, 0.2, 0.35, 0.99, 1.0, -0.1):
        add(ev_shrink_factor=1.0, wide_max_prob=mx, sanrenpuku_max_prob=mx)
    for mn in (0.0, 0.05, 0.1, 0.2, 0.5, 0.99, -0.1):                                        # 下限（< で足切り）
        add(ev_shrink_factor=1.0, wide_max_prob=0.0, sanrenpuku_max_prob=0.0, wide_min_prob=mn, sanrenpuku_min_prob=mn)
    for mm in (0.0, 0.5, 1.0, 1.5, 2.0, 5.0, -2.0):                                          # 市場比上限（p ≤ k·R/odds）
        add(ev_shrink_factor=1.0, wide_max_over_market=mm, sanrenpuku_max_over_market=mm)
    for pr in (0.0, 0.5, 0.775, 1.0, 1.5, -0.2):                                             # 払戻率は [0, 1] に収める
        add(ev_shrink_factor=1.0, wide_max_over_market=1.5, sanrenpuku_max_over_market=1.5, wide_payout_rate=pr, sanrenpuku_payout_rate=pr)
    # 境界: 上限 = 下限（ちょうどは残る）、EV がちょうど 0（除外）
    add(ev_shrink_factor=1.0, wide_max_prob=0.2, wide_min_prob=0.2, sanrenpuku_max_prob=0.05, sanrenpuku_min_prob=0.05)
    add(ev_shrink_factor=1.0, wide_max_prob=0.2, wide_min_prob=0.2000001, sanrenpuku_max_prob=0.05, sanrenpuku_min_prob=0.0500001)
    for low, tri in ((4.0, 10.0), (4.01, 10.01), (3.99, 9.99)):
        add(ev_shrink_factor=1.0, wide_max_prob=0.25, sanrenpuku_max_prob=0.1,
            wide_odds_map=_wmap(n, lambda a, b, low=low: (low, low + 1.0)), sanrenpuku_odds_map=_tmap(n, lambda a, b, c, tri=tri: tri))
    add(ev_shrink_factor=1.0, wide_max_prob=0.0, sanrenpuku_max_prob=0.0, wide_odds_map=_wmap(n, lambda a, b: (0.05, 0.05)),
        sanrenpuku_odds_map=_tmap(n, lambda a, b, c: 0.05), wide_max_over_market=3.0, sanrenpuku_max_over_market=3.0)  # オッズ < 0.1 / < 1
    return cases


def _stake_010():
    cases = []
    race = [3.0, 4.5, 7.0, 12.0, 25.0, 40.0]
    probs = _mkt(race)
    n = len(race)
    wl = lambda a, b: (round(0.5 / (probs[a - 1] * probs[b - 1]) + 1.0, 1), round(0.7 / (probs[a - 1] * probs[b - 1]) + 1.0, 1))  # noqa: E731
    tl = lambda a, b, c: round(0.3 / (probs[a - 1] * probs[b - 1] * probs[c - 1]) + 1.0, 1)  # noqa: E731
    mw, mt = _wmap(n, wl), _tmap(n, tl)

    def add(axis=1, **kw):
        cases.append(_sg(axis, probs, race, **dict(dict(wide_odds_map=mw, sanrenpuku_odds_map=mt, max_tickets_per_type=6, box_size=4,
                                                          ev_shrink_factor=0.7), **kw)))
    for alloc in ("best_only", "proportional_ev", "PROPORTIONAL_EV", "Proportional_Ev", " proportional_ev", "proportional", "", "unknown", None):
        for axis in (1, 4):
            add(axis, ticket_allocation=alloc)
    for mt_ in (1, 2, 3, 12):
        add(1, ticket_allocation="proportional_ev", max_tickets_per_type=mt_)
    for kf in (0.0, 0.05, 0.25, 0.5, 1.0, 1.5):
        add(1, kelly_fraction=kf, ticket_allocation="proportional_ev")
    for br in (10.0, 100.0, 1000.0, 1e6):
        add(1, bankroll=br, ticket_allocation="proportional_ev")
    for mn, mx in ((0.0, 10.0), (1.0, 10.0), (5.0, 50.0), (2.0, 3.0), (50.0, 100.0), (0.5, 0.5), (1000.0, 2000.0)):               # min_bet 未満 → 0（見送り）、max_bet でクリップ
        add(1, min_bet=mn, max_bet=mx, bankroll=100.0, ticket_allocation="proportional_ev")
    for ps in ("ml_features", "ml_model", "market", "unknown", "ML_FEATURES", "ml:alpha", "market:fallback(ml_disabled)", ""):     # Kelly 係数 m（H10）
        add(1, prob_source=ps, kelly_fraction=0.5)
    for sh in (0.3, 0.7, 1.0):
        add(1, ev_shrink_factor=sh, ticket_allocation="proportional_ev", bankroll=1000.0)
    return cases


_WIDE_E = lambda probs: (lambda a, b: (round(0.3 / (probs[a - 1] * probs[b - 1]) + 1.0, 1), round(0.5 / (probs[a - 1] * probs[b - 1]) + 1.0, 1)))  # noqa: E731
_TRI_E = lambda probs: (lambda a, b, c: round(0.2 / (probs[a - 1] * probs[b - 1] * probs[c - 1]) + 1.0, 1))  # noqa: E731


def _eval_011():
    r6, r18 = _RACE6, _race(_ODDS18)
    odds6 = [x["odds"] for x in r6]
    probs6 = _mkt(odds6)
    probs18 = _mkt(_ODDS18)
    m6 = dict(wide_odds_map=_wmap(6, _WIDE_E(probs6)), sanrenpuku_odds_map=_tmap(6, _TRI_E(probs6)))
    m18 = dict(wide_odds_map=_wmap(18, _WIDE_E(probs18)), sanrenpuku_odds_map=_tmap(18, _TRI_E(probs18)))
    base = dict(_BIG, max_signals=3, box_size=4, max_tickets_per_type=4)
    cases = [
        {"odds": r6, "kwargs": dict(base, **m6)},                                            # 実オッズあり・既定の配分
        {"odds": r6, "kwargs": dict(base, ev_shrink_factor=0.7, **m6)},
        {"odds": r6, "kwargs": dict(base, ev_shrink_factor=0.7, ticket_allocation="proportional_ev", **m6)},
        {"odds": r6, "kwargs": dict(base, ev_shrink_factor=0.7, bet_type_mode="best_only", **m6)},
        {"odds": r6, "kwargs": dict(base, ev_shrink_factor=0.7, bet_type_mode="best_only", ticket_allocation="proportional_ev", max_signals=2, **m6)},
        {"odds": r6, "kwargs": dict(base, include_bet_strategy=False, **m6)},
        {"odds": r6, "kwargs": dict(base, bet_types=["ワイド"], **m6)},
        {"odds": r6, "kwargs": dict(base, bet_types=["三連複"], **m6)},
        {"odds": r6, "kwargs": dict(base, bet_types=["単勝", "三連複"], **m6)},
        {"odds": r6, "kwargs": dict(base, ev_threshold=0.5, **m6)},                          # 閾値を上げる
        {"odds": r6, "kwargs": dict(base, ev_threshold=100.0, notify_skips=True, **m6)},
        {"odds": r6, "kwargs": dict(base, notify_skips=True, min_bet=50.0, **m6)},           # 賭け額 0 → 見送りシグナル（ワイド/三連複が最推奨）
        {"odds": r6, "kwargs": dict(base, notify_skips=True, min_bet=50.0, bet_types=["ワイド"], **m6)},
        # 実オッズなし
        {"odds": r6, "kwargs": dict(base)},                                                  # 見送りエントリが最推奨になる（単勝が除外されたとき）
        {"odds": r6, "kwargs": dict(base, require_real_odds_for_combo=False)},               # 推定オッズ
        {"odds": r6, "kwargs": dict(base, require_real_odds_for_combo=False, ev_shrink_factor=1.0, wide_max_prob=0.0, sanrenpuku_max_prob=0.0)},
        {"odds": r6, "kwargs": dict(base, bet_types=["ワイド", "三連複"], notify_skips=True)},
        {"odds": r6, "kwargs": dict(base, bet_types=["ワイド", "三連複"], notify_skips=True, require_real_odds_for_combo=False)},
        # 総投資上限（tickets の stake も縮小。最推奨の賭け額は scale² になる旧挙動）
        {"odds": r6, "kwargs": dict(base, kelly_fraction=1.0, max_bet=10.0, max_signals=3, max_total_bet_per_race=12.0, **m6)},
        {"odds": r6, "kwargs": dict(base, kelly_fraction=1.0, max_bet=10.0, max_signals=3, max_total_bet_per_race=12.0, ticket_allocation="proportional_ev", **m6)},
        {"odds": r6, "kwargs": dict(base, kelly_fraction=1.0, max_bet=10.0, max_signals=3, max_total_bet_per_race=12.0, bet_type_mode="best_only",
                                    ticket_allocation="proportional_ev", **m6)},
        {"odds": r6, "kwargs": dict(base, kelly_fraction=1.0, max_bet=10.0, max_signals=6, max_total_bet_per_race=2.0, notify_skips=True, **m6)},
        {"odds": r6, "kwargs": dict(base, kelly_fraction=1.0, max_signals=3, max_total_bet_per_race=1000.0, **m6)},
        # hit_rate（確率 → EV → 賭式優先の順）
        {"odds": r6, "kwargs": dict(base, priority_mode="hit_rate", **m6)},
        {"odds": r6, "kwargs": dict(base, priority_mode="hit_rate", hit_rate_bet_type_priority=["三連複", "ワイド"], **m6)},
        {"odds": r6, "kwargs": dict(base, priority_mode="hit_rate", hit_rate_bet_type_priority=["単勝"], **m6)},
        {"odds": r6, "kwargs": dict(base, priority_mode="hit_rate", hit_rate_min_ev=0.2, bet_types=["ワイド", "三連複"], **m6)},
        # 18 頭
        {"odds": r18, "kwargs": dict(_BIG, max_signals=4, box_size=4, max_tickets_per_type=3, ev_shrink_factor=0.7, **m18)},
        {"odds": r18, "kwargs": dict(_BIG, max_signals=4, box_size=5, max_tickets_per_type=4, top_k_partners=6, ev_shrink_factor=0.7,
                                     ticket_allocation="proportional_ev", bet_type_mode="best_only", **m18)},
        {"odds": r18, "kwargs": dict(_BIG, max_signals=4, box_size=4, max_tickets_per_type=3, ev_shrink_factor=0.7, priority_mode="hit_rate", **m18)},
        {"odds": r18, "kwargs": dict(_BIG, max_signals=4, require_real_odds_for_combo=False, max_tickets_per_type=3)},
        # 3 頭・2 頭・1 頭
        {"odds": _race([2.0, 3.3, 5.0]), "kwargs": dict(_LOOSE, max_signals=3, wide_odds_map=_wmap(3, _WHI), sanrenpuku_odds_map=_tmap(3, _THI))},
        {"odds": _race([2.0, 3.3]), "kwargs": dict(_LOOSE, max_signals=3, wide_odds_map=_wmap(2, _WHI), notify_skips=True)},
        {"odds": _race([2.0]), "kwargs": dict(_LOOSE, max_signals=3, notify_skips=True)},
    ]
    return _eval_calls("cm", cases)


_EXC_MSG = {"path": "results.*.outcome.message", "mode": "ignore"}  # 例外メッセージは記録するが比較しない（型は比較する）

CASES = [
    Case("GD01-EV-001", ("GD01",), "calc_ev / calc_expected_return / calc_expected_profit の境界グリッド",
         ("jraev.ev.ev_calc.calc_ev", "jraev.ev.ev_calc.calc_expected_return", "jraev.ev.ev_calc.calc_expected_profit"),
         _ev_001()),
    Case("GD01-KELLY-002", ("GD01", "GD09"), "calc_kelly_fraction グリッドと suggest_bet_amount の min/max_bet 境界。odds=1.0 の ZeroDivisionError を含む",
         ("jraev.ev.ev_calc.calc_kelly_fraction", "jraev.ev.ev_calc.suggest_bet_amount"), _kelly_002(),
         overrides=(_EXC_MSG,), known_defects=("E5",)),
    Case("GD02-PROB-001", ("GD02",), "estimate_prob_raw / estimate_prob（0.99 上限）",
         ("jraev.ev.probability.estimate_prob_raw", "jraev.ev.probability.estimate_prob"), _prob_001()),
    Case("GD02-NORM-002", ("GD02",), "estimate_prob_normalized（use_ml=False の市場確率のみ。頭数 0/1/2/18、全 0、負値、補正制限）",
         ("jraev.ev.probability.estimate_prob_normalized",), _norm_002(), default_abs_tol=C.TOL_NORMALIZED,
         known_defects=("E13",)),
    Case("G2-HASH-001", ("G2",), "ハッシュ特徴（_hash_bucket / _hash_sign）。全角・結合文字・空・重複・不正サロゲート",
         ("jraev.ml.features._hash_bucket", "jraev.ml.features._hash_sign"), _hash_001(), default_abs_tol=0.0,
         overrides=(_EXC_MSG,)),
    Case("G1-MODEL-001", ("G1",), "WinProbabilityModel を合成重みで推論（Platt・市場ブレンド・次元の切詰め/ゼロ埋め・logit 極端値・未学習）",
         ("jraev.ml.model.WinProbabilityModel.predict", "jraev.ml.model.WinProbabilityModel.predict_batch",
          "jraev.ml.model.WinProbabilityModel.extract_features"), _model_001()),
    Case("GD03-FILTER-001", ("GD03",), "check_odds_drop / check_popularity_surge の閾値境界・スナップショット件数",
         ("jraev.ev.decision.check_odds_drop", "jraev.ev.decision.check_popularity_surge"), _filter_001(),
         known_defects=("E14",)),
    Case("GD05-COMBO-001", ("GD05", "GD06"), "bet_strategy の単純推定関数（estimate_wide/trifecta_odds、calculate_wide/trifecta_prob）。Plackett–Luce 経路は対象外",
         ("jraev.ev.bet_strategy.estimate_wide_odds", "jraev.ev.bet_strategy.estimate_trifecta_odds",
          "jraev.ev.bet_strategy.calculate_wide_prob", "jraev.ev.bet_strategy.calculate_trifecta_prob"), _combo_001()),
    Case("GD09-EVAL-001", ("GD04", "GD07", "GD08", "GD09"), "evaluate_odds 全体（ML 無効=市場フォールバック、DB なし、合成 6 頭。閾値・max_signals・odds=1.0・空・1 頭）",
         ("jraev.ev.decision.evaluate_odds",), _eval_001(), known_defects=("E1", "E3", "E5")),
    Case("GD04-WIN-HITRATE-003", ("GD03", "GD04", "GD08"), "evaluate_odds の hit_rate モード（win_odds_max / win_prob_min / win_ratio_min / hit_rate_min_ev / 同率の賭式優先 / edge との違い）",
         ("jraev.ev.decision.evaluate_odds",), _hit_rate_003(), known_defects=("E3",)),
    Case("GD03-SNAPSHOT-002", ("GD03",), "evaluate_odds の急落・人気急上昇フィルタ（recent_snapshots_func 経由。最古との比較・閾値の境界・件数不足・最古のオッズ 0/負）",
         ("jraev.ev.decision.evaluate_odds",), _snapshots_002(), known_defects=("E14",)),
    Case("GD08-SELECT-003", ("GD07", "GD08", "GD09"), "evaluate_odds の選別・見送り・賭式絞り込み・win_max_over_market・max_total_bet_per_race 縮小（同点の順序、全馬同オッズ、best_only、notify_skips）",
         ("jraev.ev.decision.evaluate_odds",), _select_003(), known_defects=("E3", "E6", "E7")),
    Case("GD07-SIZING-004", ("GD02", "GD07", "GD09"), "evaluate_odds の賭け額・確率経路（ev_shrink_factor・Kelly 分数・資金・min/max_bet・市場ブレンド α・確率クリップ・頭数 1/2/18・全馬同オッズ・odds 0/1.01/極端な人気）",
         ("jraev.ev.decision.evaluate_odds",), _sizing_004(), known_defects=("E4", "E5", "E12")),
    Case("GD05-PL-005", ("GD05", "GD06"), "suggest_multiple_bet_strategies（直接）: Plackett–Luce のワイド/三連複の確率（3/5/18 頭、全馬同確率、人気集中、極端に低い確率、確率 0/負/1 超、α の端、2 頭・1 頭）",
         ("jraev.ev.bet_strategy.suggest_multiple_bet_strategies",), _calls("pl", "suggest_multiple_bet_strategies", _pl_005()), known_defects=("E8", "E10")),
    Case("GD05-FLOW-006", ("GD05", "GD06"), "suggest_multiple_bet_strategies（直接）: 流しの相手選び（top_k_partners、人気 0、同順位、人気なし、軸の人気 0）",
         ("jraev.ev.bet_strategy.suggest_multiple_bet_strategies",), _calls("fl", "suggest_multiple_bet_strategies", _flow_006()), known_defects=("E9",)),
    Case("GD05-BOX-007", ("GD05", "GD06"), "suggest_multiple_bet_strategies（直接）: BOX 候補（box_size、軸が候補の内/外、頭数不足、点数上限と重複した買い目、人気なしのオッズ順）",
         ("jraev.ev.bet_strategy.suggest_multiple_bet_strategies",), _calls("bx", "suggest_multiple_bet_strategies", _box_007()), known_defects=("E9",)),
    Case("GD05-REAL-008", ("GD05", "GD06", "GD09"), "suggest_multiple_bet_strategies（直接）: 実オッズ（あり/一部/なし/空/0.0）、require_real_odds_for_combo の true/false、見送りエントリ、賭式の絞り込み",
         ("jraev.ev.bet_strategy.suggest_multiple_bet_strategies",), _calls("ro", "suggest_multiple_bet_strategies", _real_008()), known_defects=("E8", "E11")),
    Case("GD05-FILTER-009", ("GD05", "GD06"), "suggest_multiple_bet_strategies（直接）: 確率の段階（縮小 → *_max_prob → 市場比上限 → *_min_prob → EV > 0）と各境界",
         ("jraev.ev.bet_strategy.suggest_multiple_bet_strategies",), _calls("fi", "suggest_multiple_bet_strategies", _filter_009()), known_defects=("E8",)),
    Case("GD07-COMBO-STAKE-010", ("GD07",), "suggest_multiple_bet_strategies（直接）: ワイド/三連複の賭け額と ticket_allocation、min/max_bet、Kelly 分数、確率源の係数（H10）",
         ("jraev.ev.bet_strategy.suggest_multiple_bet_strategies",), _calls("st", "suggest_multiple_bet_strategies", _stake_010()), known_defects=("E6", "E12", "H10")),
    Case("GD06-EVAL-011", ("GD05", "GD06", "GD07", "GD08"), "evaluate_odds 全体のワイド/三連複（実オッズ・推定オッズ・best_only・配分・総投資上限での tickets の縮小・hit_rate の賭式優先・見送りシグナル・18/3/2/1 頭）",
         ("jraev.ev.decision.evaluate_odds",), _eval_011(), known_defects=("E6", "E7", "E8", "E9", "E11")),
    Case("GD10-DEFAULTS-001", ("GD10",), "関数シグネチャの既定値（D-016: 関数既定と example config の食い違い確認用）",
         ("inspect.signature(jraev.ev.decision.evaluate_odds ほか)",), _defaults_001(), default_abs_tol=0.0),
]

# ---- T3.6-A: ML 経路（G3〜G7）。既存フィクスチャは変えず `generate --add-new` で追加する ------------------------------------------------
_REASON_IGNORE = {"path": "results.*.outcome.value.signals.*.reason", "mode": "ignore"}  # 通知文（馬名・近走の要約を含む）は T3.7 で別に検証する

CASES += [
    Case("G6-FEATURES-001", ("G6", "G2"),
         "特徴量ベクトル（build_feature_vector 40 次元 / build_feature_vector_alpha 35 次元 / 血統ハッシュ 16 次元の集計）。合成 SQLite・固定時刻（age_years は now 基準: H8）。"
         "対象レース当日・未来の過去走を含む（H3）、脚質・着回数・生年月日・祖先名の癖、db=None",
         ("jraev.ml.features.build_feature_vector", "jraev.ml.features.build_feature_vector_alpha", "jraev.ml.features.get_horse_feature_summary"),
         M.g6_calls(), default_abs_tol=C.TOL_NORMALIZED, known_defects=("H3", "H8"), ml=True),
    Case("G3-ML-NORM-002", ("G3", "G1"),
         "evaluate_odds の ML 経路（単体モデル: legacy / calibration / alpha）。モデル出力 → レース内正規化と ±10% 補正 → 判定側ブレンド・クリップ・再正規化。"
         "重みの次元の切詰め/ゼロ埋め（H2）・z の飽和・頭数 1/2・オッズ 0/負を含む",
         ("jraev.ev.decision.evaluate_odds", "jraev.ml.predict.get_model", "jraev.ml.model.WinProbabilityModel.predict_proba_from_features"),
         M.g3_calls(), overrides=(_REASON_IGNORE,), known_defects=("H2", "H4"), ml=True),
    Case("G4-ENSEMBLE-002", ("G4",),
         "evaluate_odds の ensemble（calibration + alpha + β × カバレッジ）。オッズ無し・片方無し・両方無し・β の端と範囲外・最後の馬のオッズ 0 によるラベル・カバレッジ 0",
         ("jraev.ev.decision.evaluate_odds",), M.g4_calls(), overrides=(_REASON_IGNORE,), known_defects=("E2", "H4", "H10"), ml=True),
    Case("G5-BLEND-002", ("G5",),
         "evaluate_odds の判定側ブレンド（α の端・境界、モデルに保存された α の実行時上書き）・確率上限クリップ・再正規化・引数と ml_cfg の優先順位・hit_rate の確率フィルタ",
         ("jraev.ev.decision.evaluate_odds",), M.g5_calls(), overrides=(_REASON_IGNORE,), known_defects=("H4",), ml=True),
    Case("G7-LEGACY-FAIL-001", ("G7", "GD09"),
         "旧実装の ML 失敗時の挙動（新しいロード契約の仕様テストではない）: ML 無効・モデル無し・feature_names 不一致（旧 H1: 例外を握り潰して市場確率）・feature_names が空・"
         "未知の model_type・legacy でパス未指定。確率は市場確率に落ち、prob_source は旧ラベルのまま",
         ("jraev.ev.decision.evaluate_odds",), M.g7_legacy_calls(), overrides=(_REASON_IGNORE,), known_defects=("E1", "H1", "H10"), ml=True),
]

CASES_BY_ID = {c.case_id: c for c in CASES}
