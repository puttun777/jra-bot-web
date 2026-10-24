"""旧実装の関数呼び出し（入力 JSON → 旧関数 → 結果）。Kotlin 側ランナーも同じ call 形式の入力を解釈する。"""
import contextlib
import datetime as _dt
import hashlib
import json
import os

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


def _decode_odds_maps(kwargs):
    """JSON はタプルのキーを表せないので、実オッズは配列で受け取り、旧実装の辞書に直す（T3.2）。
    wide_odds_map: [[馬番, 馬番, 下限, 上限], …] → {(a, b): (low, high)} / sanrenpuku_odds_map: [[a, b, c, オッズ], …] → {(a, b, c): odds}。
    キーは入力のまま（旧実装は昇順のキーで検索するので、シナリオは昇順で与える）。"""
    w = kwargs.get("wide_odds_map")
    if w is not None:
        kwargs["wide_odds_map"] = {(int(r[0]), int(r[1])): (r[2], r[3]) for r in w}
    t = kwargs.get("sanrenpuku_odds_map")
    if t is not None:
        kwargs["sanrenpuku_odds_map"] = {(int(r[0]), int(r[1]), int(r[2])): r[3] for r in t}
    return kwargs


def _suggest(L, a):
    """suggest_multiple_bet_strategies の直接呼び出し。evaluate_odds と同じく賭けの方式の引数は None（旧実装の隠れ変数 bet_strategy=None を再現）。
    確率は probs_by_umaban（{"馬番": 確率}）で与える。"""
    kwargs = _decode_odds_maps(dict(a.get("kwargs", {})))
    pm = kwargs.get("probs_by_umaban")
    if pm is not None:
        kwargs["probs_by_umaban"] = {int(k): v for k, v in pm.items()}
    return L.bet_strategy.suggest_multiple_bet_strategies(
        a["umaban"], a["win_prob"], a["win_odds"], _odds_list(L, a["odds_list"]), bet_strategy=None, **kwargs)


def _eval(L, a):
    kwargs = _decode_odds_maps(dict(a.get("kwargs", {})))
    snaps = a.get("snapshots")  # {"<umaban>": [{"odds","popularity"}…]}（新しい順）。recent_snapshots_func の代わりにデータで渡す
    if snaps is not None:
        kwargs["recent_snapshots_func"] = lambda umaban: snaps.get(str(umaban), [])
    res = L.decision.evaluate_odds(
        _odds_list(L, a["odds"]), ml_cfg={"enabled": False}, return_debug=True, **kwargs)
    if isinstance(res, tuple):  # 入力が空のときは return_debug=True でもリストだけが返る（旧実装の挙動をそのまま記録）
        signals, dbg = res
        return {"return_shape": "tuple", "signals": signals, "reject_counts": dbg.get("reject_counts")}
    return {"return_shape": "list", "signals": res, "reject_counts": None}


# ---- ML 経路（T3.6-A: G3〜G7）。合成 SQLite と、合成モデルをサンドボックスの一時ディレクトリへ旧実装の pickle で書いて旧実装に読ませる -------------------
# pickle は **この生成プロセス自身が直前に書いた合成モデル**だけを旧実装の get_model が読む（外部の pickle は読まない）。リポジトリには入らない。

_DB_INSERTS = {
    "races": ("race_id", "date", "track", "race_no", "post_time", "name", "track_cd", "distance_m", "track_type_cd", "course_kubun_cd",
              "tenko_cd", "shiba_baba_cd", "dirt_baba_cd"),
    "race_horses": ("race_id", "umaban", "ketto_num", "bamei"),
    "horses": ("ketto_num", "bamei", "birth_date", "pedigree_json", "style_tendency_json", "chaku_by_distance_json", "chaku_by_surface_json",
               "chaku_by_condition_json"),
    "horse_past_runs": ("ketto_num", "race_id", "date", "finish"),
}


def _make_db(L, rows, directory):
    """旧スキーマの SQLite（一時ディレクトリ）に合成行を入れて、旧実装の Database を返す。"""
    db = L.db.Database(os.path.join(directory, "synthetic-" + hashlib.sha256(json.dumps(rows, sort_keys=True).encode()).hexdigest()[:12] + ".sqlite"))
    conn = db.get_connection()
    try:
        for table, cols in _DB_INSERTS.items():
            for r in rows.get(table, []):
                conn.execute(f"INSERT INTO {table} ({', '.join(cols)}) VALUES ({', '.join('?' for _ in cols)})", tuple(r.get(c) for c in cols))
        conn.commit()
    finally:
        conn.close()
    return db


@contextlib.contextmanager
def _fixed_now(L, now_text):
    """旧 features.py の `datetime.now()` を固定時刻にする（age_years の H8）。`datetime.strptime` などは本物のまま。"""
    fixed = _dt.datetime.fromisoformat(now_text)

    class _FixedDateTime(_dt.datetime):
        @classmethod
        def now(cls, tz=None):
            return fixed

    saved = L.features.datetime
    L.features.datetime = _FixedDateTime
    try:
        yield
    finally:
        L.features.datetime = saved


def _build_features_batch(L, a):
    """build_feature_vector / build_feature_vector_alpha（旧 G6）。requests[].with_db=false は db=None（追加特徴量・血統が 0 埋め）。"""
    import tempfile
    out = []
    with tempfile.TemporaryDirectory(prefix="golden-db-") as d, _fixed_now(L, a["now"]):
        db = _make_db(L, a["db"], d)
        for q in a["requests"]:
            use_db = db if q.get("with_db", True) else None
            if q["kind"] == "alpha":
                vec, meta = L.features.build_feature_vector_alpha(use_db, q["race_id"], q["umaban"], num_horses=q.get("num_horses", 18))
            else:
                vec, meta = L.features.build_feature_vector(use_db, q["race_id"], q["umaban"], odds=q["odds"], popularity=q["popularity"],
                                                            num_horses=q.get("num_horses", 18))
            out.append({"vector": vec.tolist(), "meta": meta})
    return {"names": {"calibration": L.features.get_feature_names(), "alpha": L.features.get_feature_names_alpha()}, "rows": out}


def _feature_names(L, kind):
    if kind == "calibration40":
        return list(L.features.get_feature_names())
    if kind == "alpha35":
        return list(L.features.get_feature_names_alpha())
    if kind == "none":
        return []
    return None  # 旧クラスの既定（5 市場特徴量）のまま


def _write_model(L, role, spec, directory):
    """合成モデル 1 つを旧実装の pickle で書き、パスを返す。spec が None ならファイルを作らない（存在しないパスを返す）。"""
    tag = hashlib.sha256(json.dumps([role, spec], sort_keys=True).encode()).hexdigest()[:12]
    path = os.path.join(directory, f"{role}_{tag}.model")
    if spec is None:
        return path
    m = _model(L, spec)
    names = _feature_names(L, spec.get("feature_names", "calibration40"))
    if names is not None:
        m.feature_names = names
    L.model.save_model(m, path)
    return path


def _evaluate_odds_ml(L, a):
    """evaluate_odds の ML 経路（db + race_id + モデル）。結果の形は `evaluate_odds` と同じ。"""
    import tempfile
    models = a["models"]
    L.probability._use_ml_model = None  # 旧 estimate_prob_normalized の『モデルの有無』のグローバルキャッシュ。呼び出しの順序に依存させない
    with tempfile.TemporaryDirectory(prefix="golden-ml-") as d, _fixed_now(L, a["now"]):
        db = _make_db(L, a["db"], d)
        cfg = dict(a["ml_cfg"])
        cfg["calibration_model_path"] = _write_model(L, "calibration", models.get("calibration"), d)
        cfg["alpha_model_path"] = _write_model(L, "alpha", models.get("alpha"), d)
        legacy_path = _write_model(L, "legacy", models.get("legacy"), d)
        kwargs = _decode_odds_maps(dict(a.get("kwargs", {})))
        res = L.decision.evaluate_odds(
            _odds_list(L, a["odds"]), ml_cfg=cfg, ml_model_path=legacy_path if a.get("pass_legacy_path", True) else None,
            db=db, race_id=a["race_id"], return_debug=True, **kwargs)
    if isinstance(res, tuple):
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
    "suggest_multiple_bet_strategies": _suggest,
    "evaluate_odds": _eval,
    "build_features_batch": _build_features_batch,
    "evaluate_odds_ml": _evaluate_odds_ml,
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
