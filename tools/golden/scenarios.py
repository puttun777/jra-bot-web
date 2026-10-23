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
    Case("GD10-DEFAULTS-001", ("GD10",), "関数シグネチャの既定値（D-016: 関数既定と example config の食い違い確認用）",
         ("inspect.signature(jraev.ev.decision.evaluate_odds ほか)",), _defaults_001(), default_abs_tol=0.0),
]

CASES_BY_ID = {c.case_id: c for c in CASES}
