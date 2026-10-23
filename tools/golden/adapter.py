"""旧実装の関数呼び出し（入力 JSON → 旧関数 → 結果）。Kotlin 側ランナーも同じ call 形式の入力を解釈する。"""
from . import jsonutil


def _odds_list(L, items):
    return [L.models.OddsData(**d) for d in items]


def _model(L, spec):
    import numpy as np
    w = spec.get("weights")
    return L.model.WinProbabilityModel(
        None if w is None else np.array(w, dtype=np.float64),
        spec.get("bias", 0.0),
        calib_a=spec.get("calib_a"),
        calib_b=spec.get("calib_b"),
        market_blend_alpha=spec.get("market_blend_alpha", 1.0),
    )


def _sig_defaults(L, mod, name):
    sig = L.inspect.signature(getattr(mod, name))
    return {p.name: (None if p.default is p.empty else p.default) for p in sig.parameters.values()
            if p.default is not p.empty}


def _eval(L, a):
    res = L.decision.evaluate_odds(
        _odds_list(L, a["odds"]), ml_cfg={"enabled": False}, return_debug=True, **a.get("kwargs", {}))
    if isinstance(res, tuple):  # 入力が空のときは return_debug=True でもリストだけが返る（旧実装の挙動をそのまま記録）
        signals, dbg = res
        return {"return_shape": "tuple", "signals": signals, "reject_counts": dbg.get("reject_counts")}
    return {"return_shape": "list", "signals": res, "reject_counts": None}


# call 名 → (L, args) -> 結果。ここに無い関数は呼べない（意図しない旧コード実行を防ぐ）。
CALLS = {
    "calc_ev": lambda L, a: L.ev_calc.calc_ev(a["prob"], a["odds"]),
    "calc_expected_return": lambda L, a: L.ev_calc.calc_expected_return(a["prob"], a["odds"]),
    "calc_expected_profit": lambda L, a: L.ev_calc.calc_expected_profit(a["stake"], a["prob"], a["odds"]),
    "calc_kelly_fraction": lambda L, a: L.ev_calc.calc_kelly_fraction(a["prob"], a["odds"], **a.get("kwargs", {})),
    "suggest_bet_amount": lambda L, a: L.ev_calc.suggest_bet_amount(a["prob"], a["odds"], **a.get("kwargs", {})),
    "estimate_prob_raw": lambda L, a: L.probability.estimate_prob_raw(a["odds"]),
    "estimate_prob": lambda L, a: L.probability.estimate_prob(a["odds"]),
    "estimate_prob_normalized": lambda L, a: L.probability.estimate_prob_normalized(
        a["odds_list"], correction_limit=a.get("correction_limit", 0.10), use_ml=False),
    "hash_bucket": lambda L, a: L.features._hash_bucket(a["s"], a["dim"]),
    "hash_sign": lambda L, a: L.features._hash_sign(a["s"]),
    "model_extract_features": lambda L, a: _model(L, a["model"]).extract_features(
        a["odds"], a["popularity"], a.get("num_horses", 18)),
    "model_predict": lambda L, a: _model(L, a["model"]).predict(a["odds"], a["popularity"], a.get("num_horses", 18)),
    "model_predict_batch": lambda L, a: _model(L, a["model"]).predict_batch(
        a["odds_list"], a["popularity_list"], a.get("num_horses", 18)),
    "check_odds_drop": lambda L, a: L.decision.check_odds_drop(
        a["recent_snapshots"], a["current_odds"], **a.get("kwargs", {})),
    "check_popularity_surge": lambda L, a: L.decision.check_popularity_surge(
        a["recent_snapshots"], a["current_popularity"], **a.get("kwargs", {})),
    "estimate_wide_odds": lambda L, a: L.bet_strategy.estimate_wide_odds(a["win_odds"], **a.get("kwargs", {})),
    "estimate_trifecta_odds": lambda L, a: L.bet_strategy.estimate_trifecta_odds(a["win_odds"], **a.get("kwargs", {})),
    "calculate_wide_prob": lambda L, a: L.bet_strategy.calculate_wide_prob(
        a["win_prob"], _odds_list(L, a["odds_list"]), a["target_umaban"]),
    "calculate_trifecta_prob": lambda L, a: L.bet_strategy.calculate_trifecta_prob(
        a["win_prob"], _odds_list(L, a["odds_list"]), a["target_umaban"]),
    "evaluate_odds": _eval,
    "signature_defaults": lambda L, a: _sig_defaults(L, getattr(L, a["module"]), a["function"]),
}


def run_call(L, call):
    """1 回の呼び出しを実行し、値または例外（型とメッセージ）を JSON 形式で返す。例外は握り潰さず記録する。"""
    try:
        value = CALLS[call["function"]](L, call["args"])
        return {"status": "ok", "value": jsonutil.normalize(value)}
    except Exception as e:  # 旧実装が投げる例外もゴールデンの一部（E5 等）
        return {"status": "exception", "exception_type": type(e).__name__, "message": str(e)}


def run_calls(L, calls):
    return {"results": [{"call_id": c["call_id"], "outcome": run_call(L, c)} for c in calls]}
