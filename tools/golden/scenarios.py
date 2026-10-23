"""合成入力のシナリオ定義（旧実装の呼び出しや正規化は含まない）。実データは一切使わない。"""
from dataclasses import dataclass, field
from itertools import product

from . import constants as C

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
    Case("GD10-DEFAULTS-001", ("GD10",), "関数シグネチャの既定値（D-016: 関数既定と example config の食い違い確認用）",
         ("inspect.signature(jraev.ev.decision.evaluate_odds ほか)",), _defaults_001(), default_abs_tol=0.0),
]

CASES_BY_ID = {c.case_id: c for c in CASES}
