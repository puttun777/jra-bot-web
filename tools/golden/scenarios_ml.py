"""ML 経路の合成入力（T3.6-A: G3〜G7）。旧実装の呼び出しや正規化は含まない。実データ・実モデルは使わない。

- DB は旧 SQLite のスキーマ（文字列の日付・JSON 列も文字列）の合成行。馬名・祖先名は作り物。
- モデルは重みの配列だけ（`adapter.py` が旧実装の pickle としてサンドボックスの一時ディレクトリに書き、旧実装に読ませる）。
"""
import json
import math

NOW = "2026-01-04T12:00:00"  # 旧 features.py の datetime.now() の代わり（H8: age_years の基準）


def _j(v):
    return json.dumps(v, ensure_ascii=False)


# ---- 合成 DB ---------------------------------------------------------------------------------------------------

RACE_TURF = "202601040511"   # 2026-01-04 中山 11R 芝 1600m
RACE_DIRT = "202601040611"   # ダート 1200m（天候が空）
RACE_OBST = "202601050511"   # 障害 3000m
RACE_NONE = "202601060511"   # races に行が無い

_PED8 = [{"name": n} for n in ["シンセティックサイアー001", "ＡＢＣ", "ABC", "dup", "dup", "漢字名称", "ひらがなのなまえ", "a"]]


def _races():
    return [
        dict(race_id=RACE_TURF, date="20260104", track="合成", race_no=11, post_time="15:25", name="合成記念", track_cd="06", distance_m=1600,
             track_type_cd="11", course_kubun_cd="00", tenko_cd="1", shiba_baba_cd="2", dirt_baba_cd="3"),
        dict(race_id=RACE_DIRT, date="20260104", track="合成", race_no=6, post_time="12:50", name="合成特別", track_cd="06", distance_m=1200,
             track_type_cd="12", course_kubun_cd=" 2 ", tenko_cd="", shiba_baba_cd="1", dirt_baba_cd="4"),
        dict(race_id=RACE_OBST, date="20260105", track="合成", race_no=11, post_time="15:30", name="合成障害", track_cd="06", distance_m=3000,
             track_type_cd="13", course_kubun_cd="x", tenko_cd="3", shiba_baba_cd="2", dirt_baba_cd="1"),
    ]


def _horses():
    k = "2020100"
    return [
        # 1: 全部入り（脚質 2・距離/馬場/状態の着回数・祖先 8 名・重複/全角）
        dict(ketto_num=k + "001", bamei="合成一号", birth_date="20220401", pedigree_json=_j(_PED8), style_tendency_json=_j(["2", "1"]),
             chaku_by_distance_json=_j([[2, 1, 1, 0, 0, 1], [0, 0, 0, 3]]), chaku_by_surface_json=_j([[1, 1, 0]]),
             chaku_by_condition_json=_j([[3, 2, 1, 4, 5, 6, 7]])),
        # 2: 馬マスタのみ（過去走なし）。着回数なし・祖先 10 名（9 件目以降は対象外）・先頭が空名
        dict(ketto_num=k + "002", bamei="合成二号", birth_date="20210915", pedigree_json=_j([{"name": ""}, {"name": " 前後空白 "}] + [{"name": f"祖先{i}"} for i in range(10)]),
             style_tendency_json=_j([" 3 "]), chaku_by_distance_json=_j([]), chaku_by_surface_json=_j([]), chaku_by_condition_json=_j([])),
        # 4: 不正な生年月日・脚質が数字でない・着回数に文字列/小数/空行/短い行・祖先に非辞書/名前なし
        dict(ketto_num=k + "004", bamei="合成四号", birth_date="20211345", pedigree_json=_j(["x", {"name": None}, {"nm": "z"}, {"name": 12345}, {"name": "ok"}]),
             style_tendency_json=_j(["x"]), chaku_by_distance_json=_j([[1, "2", 3.9, " 4 ", "x", None], [], [5], "bad", [0, 0]]),
             chaku_by_surface_json=_j([[0, 0, 0]]), chaku_by_condition_json=_j([[1, 0]])),
        # 5: 生年月日が空・JSON が壊れている列
        dict(ketto_num=k + "005", bamei="合成五号", birth_date="", pedigree_json="{broken", style_tendency_json="", chaku_by_distance_json="null",
             chaku_by_surface_json=_j({"a": 1}), chaku_by_condition_json=None),
        # 7: 馬マスタあり（過去走はすべて着順 0/NULL）
        dict(ketto_num=k + "007", bamei="合成七号", birth_date="20190301", pedigree_json=_j([{"name": "七の父"}, {"name": "七の母"}]), style_tendency_json=_j(["4"]),
             chaku_by_distance_json=_j([[1, 1, 1]]), chaku_by_surface_json=_j([[2, 2, 2]]), chaku_by_condition_json=_j([[3, 3, 3]])),
        # 8: 若い馬（生年月日が now より後 → age 0）
        dict(ketto_num=k + "008", bamei="合成八号", birth_date="20270101", pedigree_json=_j([{"name": "八の父"}]), style_tendency_json=_j(["1"]),
             chaku_by_distance_json=_j([]), chaku_by_surface_json=_j([]), chaku_by_condition_json=_j([])),
    ]


def _entries():
    k = "2020100"
    rows = []
    for race in (RACE_TURF, RACE_DIRT, RACE_OBST):
        rows += [
            dict(race_id=race, umaban=1, ketto_num=k + "001"),
            dict(race_id=race, umaban=2, ketto_num=k + "002"),
            dict(race_id=race, umaban=3, ketto_num=k + "003"),           # horses に行なし、過去走あり
            dict(race_id=race, umaban=4, ketto_num=k + "004"),
            dict(race_id=race, umaban=5, ketto_num="   "),                # ketto_num が空白
            dict(race_id=race, umaban=6, ketto_num=" " + k + "001 "),     # 前後に空白（旧実装は strip して引く）
            dict(race_id=race, umaban=7, ketto_num=k + "007"),
            dict(race_id=race, umaban=8, ketto_num=k + "008"),
        ]
    # umaban 9 は race_horses に行が無い
    return rows


def _runs():
    k = "2020100"

    def r(ketto, rid, date, finish):
        return dict(ketto_num=k + ketto, race_id=rid, date=date, finish=finish)

    runs = [
        # 001: 7 走 + 対象レース当日・未来の行（H3: 旧実装は日付で絞らない）
        r("001", "202512280611", "20251228", 1), r("001", "202512070711", "20251207", 4), r("001", "202511160811", "20251116", 2),
        r("001", "202510260511", "20251026", 5), r("001", "202510050611", "20251005", 3), r("001", "202509140511", "20250914", 9),
        r("001", "202508100111", "20250810", 0),
        r("001", RACE_TURF, "20260104", 1),                 # 対象レース自身（結果が漏れる）
        r("001", "202601110511", "20260111", 2),            # 未来
        # 003: 馬マスタなし
        r("003", "202512200611", "20251220", 3), r("003", "202512010611", "20251201", 3), r("003", "202511100611", "20251110", 12),
        # 004: 日付が不正な行・同日 2 走（race_id の降順で並ぶ）
        r("004", "202512270511", "20251227", 2), r("004", "202512270611", "20251227", 6), r("004", "202512270111", "20251227", 1),
        # 007: 着順 0 のみ
        r("007", "202512010511", "20251201", 0), r("007", "202511010511", "20251101", 0),
        # 008: 1 走
        r("008", "202512150511", "20251215", 8),
    ]
    runs.append(dict(ketto_num=k + "004", race_id="202511150511", date=None, finish=4))      # 日付なし（SQLite の降順で最後）
    runs.append(dict(ketto_num=k + "004", race_id="202511100511", date="", finish=7))        # 日付が空文字
    runs.append(dict(ketto_num=k + "004", race_id="202511050511", date="2025-11-05", finish=3))  # 形式が違う日付
    runs.append(dict(ketto_num=k + "004", race_id="202511040511", date="20251104", finish=None))  # 着順なし
    return runs


def synthetic_db():
    return {"races": _races(), "race_horses": _entries(), "horses": _horses(), "horse_past_runs": _runs()}


# ---- G6: 特徴量ベクトル --------------------------------------------------------------------------------------------

def g6_calls():
    db = synthetic_db()
    odds_pop = [(3.5, 2), (0.0, 0), (-1.0, 5), (100.0, 18), (1.0, 1)]
    calls = []
    # 1) 全レース × 全馬番（+ race_horses に無い 9）× calibration/alpha（odds は馬番で変える）
    reqs = []
    for race in (RACE_TURF, RACE_DIRT, RACE_OBST, RACE_NONE):
        for umaban in range(1, 10):
            o, p = odds_pop[umaban % len(odds_pop)]
            reqs.append(dict(kind="calibration", race_id=race, umaban=umaban, odds=o, popularity=p))
            reqs.append(dict(kind="alpha", race_id=race, umaban=umaban))
    calls.append(dict(call_id="fb-000", function="build_features_batch", args=dict(db=db, now=NOW, requests=reqs)))
    # 2) odds/人気の境界（同じ馬で）
    reqs2 = [dict(kind="calibration", race_id=RACE_TURF, umaban=1, odds=o, popularity=p)
             for o in (-5.0, 0.0, 0.05, 0.1, 0.11, 1.0, 2.5, 999.0, 1e6) for p in (0, 1, 2, 18)]
    calls.append(dict(call_id="fb-001", function="build_features_batch", args=dict(db=db, now=NOW, requests=reqs2)))
    # 3) db=None / race_id が空
    reqs3 = [dict(kind="calibration", race_id=RACE_TURF, umaban=1, odds=3.5, popularity=2, with_db=False),
             dict(kind="alpha", race_id=RACE_TURF, umaban=1, with_db=False),
             dict(kind="calibration", race_id="", umaban=1, odds=0.0, popularity=0),
             dict(kind="alpha", race_id="", umaban=1)]
    calls.append(dict(call_id="fb-002", function="build_features_batch", args=dict(db=db, now=NOW, requests=reqs3)))
    # 4) now を変える（age_years は now 基準: H8）
    for i, now in enumerate(("2026-04-01T00:00:00", "2030-04-01T00:00:00", "2021-01-01T00:00:00", "2026-01-04T23:59:59")):
        reqs4 = [dict(kind="alpha", race_id=RACE_TURF, umaban=u) for u in (1, 2, 4, 8)]
        calls.append(dict(call_id=f"fb-{3 + i:03d}", function="build_features_batch", args=dict(db=db, now=now, requests=reqs4)))
    return calls


# ---- モデル（重みの配列）---------------------------------------------------------------------------------------------

# 特徴量ごとの重みの尺度（odds 系・日数・着順は桁が大きいので小さく。確率が 0/1 に張り付かないように）
_SCALE = {"odds": 0.01, "popularity": 0.05, "log_odds": 0.3, "inv_popularity": 1.2, "odds_popularity_interaction": 0.0002,
          "past_avg_finish": 0.02, "days_since_last_run": 0.002, "age_years": 0.05, "past_n": 0.05, "tenko_cd": 0.03, "baba_cd": 0.03,
          "course_kubun_cd": 0.02, "style_hint_code": 0.05, "distance_km": 0.2}
_NAMES_ALPHA = ["has_horse_master", "has_past_runs", "age_years", "past_n", "past_win_rate", "past_place_rate", "past_avg_finish", "style_hint_code",
                "distance_place_rate", "surface_place_rate", "condition_place_rate", "days_since_last_run", "distance_km", "is_turf", "is_dirt",
                "is_obstacle", "tenko_cd", "baba_cd", "course_kubun_cd"] + [f"pedigree_hash_{i}" for i in range(16)]
_NAMES_CAL = ["odds", "popularity", "log_odds", "inv_popularity", "odds_popularity_interaction"] + _NAMES_ALPHA


def weights(names, salt):
    out = []
    for i, n in enumerate(names):
        sign = -1.0 if n in ("odds", "popularity", "log_odds", "odds_popularity_interaction", "past_avg_finish") else 1.0
        base = _SCALE.get(n, 0.15) * (0.6 + 0.4 * math.sin(i * 1.3 + salt))
        out.append(round(sign * base, 6))
    return out


def w40(salt=1):
    return weights(_NAMES_CAL, salt)


def w35(salt=2):
    return weights(_NAMES_ALPHA, salt)


def model(w, bias, *, a=None, b=None, alpha=1.0, names="calibration40"):
    spec = dict(weights=w, bias=bias, market_blend_alpha=alpha, feature_names=names)
    if a is not None:
        spec["calib_a"], spec["calib_b"] = a, b
    return spec


# ---- ML 経路の evaluate_odds（G3: 正規化と補正 / G4: ensemble / G5: 判定側ブレンド / G7: 旧実装側の失敗時の挙動）--------------------------------------------

_HORSES6 = [dict(umaban=1, odds=2.5, popularity=1), dict(umaban=2, odds=4.0, popularity=2), dict(umaban=3, odds=8.0, popularity=3),
            dict(umaban=4, odds=15.0, popularity=4), dict(umaban=5, odds=30.0, popularity=5), dict(umaban=6, odds=60.0, popularity=7)]
_HORSES_ZERO = [dict(umaban=1, odds=2.5, popularity=1), dict(umaban=2, odds=0.0, popularity=0), dict(umaban=3, odds=8.0, popularity=3),
                dict(umaban=4, odds=15.0, popularity=4), dict(umaban=7, odds=-1.0, popularity=0), dict(umaban=8, odds=120.0, popularity=11)]

# 全馬のシグナル（BetSignal か SkipSignal）が出る設定: EV 閾値なし・単勝のみ・Kelly 全額・notify_skips
_KW = dict(ev_threshold=-1000.0, max_signals=50, kelly_fraction=1.0, bankroll=100.0, min_bet=0.01, max_bet=100.0, notify_skips=True,
           bet_types=["単勝"], ev_shrink_factor=1.0, include_bet_strategy=False, win_max_over_market=0.0)


def ml_call(call_id, models, *, model_type, horses=None, race_id=RACE_TURF, cfg=None, kwargs=None, pass_legacy_path=True, ml_enabled=True):
    ml_cfg = dict(enabled=ml_enabled, model_type=model_type, market_blend_alpha=0.25, ensemble_beta=0.35, win_prob_clip_max=0.35)
    ml_cfg.update(cfg or {})
    kw = dict(_KW)
    kw.update(kwargs or {})
    args = dict(db=synthetic_db(), now=NOW, race_id=race_id, odds=horses or _HORSES6, models=models, ml_cfg=ml_cfg, kwargs=kw)
    if not pass_legacy_path:
        args["pass_legacy_path"] = False
    return dict(call_id=call_id, function="evaluate_odds_ml", args=args)


def g3_calls():
    """単体モデル（legacy / calibration / alpha）の確率 → レース内正規化と ±10% 補正 → 判定側ブレンド。Platt・モデル内ブレンドを含む。"""
    calls = []
    cal = model(w40(1), -1.5, a=1.1, b=-0.2)
    cal_plain = model(w40(3), -1.0)
    alpha_ok = model(w40(5), -2.0, names="calibration40")          # 40 次元の重み（アルファの学習列が 40 の構成）
    alpha35 = model(w35(2), -2.0, names="calibration40")           # 35 次元の重み + 40 名の feature_names（旧 H2: 先頭 35 列へ切り詰めて推論）
    for i, (mt, models, key) in enumerate([("calibration", cal, "calibration"), ("calibration", cal_plain, "calibration"), ("alpha", alpha_ok, "alpha"),
                                           ("alpha", alpha35, "alpha"), ("legacy", cal, "legacy"), ("legacy", cal_plain, "legacy")]):
        for j, horses in enumerate((_HORSES6, _HORSES_ZERO)):
            calls.append(ml_call(f"ml-{i}{j}", {key: models}, model_type=mt, horses=horses))
    # 全馬の確率が 0 近傍（正規化の合計 ≤ 0 に近い極端値）・z の極端値（±50 の飽和）
    ext_hi = model([5.0] * 40, 100.0, names="calibration40")
    ext_lo = model([-5.0] * 40, -100.0, a=2.0, b=1.0, names="calibration40")
    calls.append(ml_call("ml-ext0", {"calibration": ext_hi}, model_type="calibration"))
    calls.append(ml_call("ml-ext1", {"calibration": ext_lo}, model_type="calibration"))
    # 重みが 5 次元（旧市場特徴量モデル）・44 次元（ゼロ埋め）— 旧 _align_feature_matrix の切詰め/ゼロ埋め（互換検証用。新しいロード契約では拒否される）
    calls.append(ml_call("ml-w5", {"legacy": model([0.01, -0.05, -0.3, 1.2, -0.0002], -1.5, a=1.1, b=-0.2, names="market5")}, model_type="legacy"))
    calls.append(ml_call("ml-w44", {"calibration": model(w40(7) + [0.1, -0.1, 0.2, 0.3], -1.0)}, model_type="calibration"))
    # 馬 1 頭・2 頭
    calls.append(ml_call("ml-n1", {"calibration": cal}, model_type="calibration", horses=_HORSES6[:1]))
    calls.append(ml_call("ml-n2", {"calibration": cal}, model_type="calibration", horses=_HORSES6[:2]))
    return calls


def g4_calls():
    """ensemble（calibration + alpha + β + coverage）。オッズ無し・片方無し・β の端・カバレッジの差。"""
    calls = []
    cal = model(w40(1), -1.5, a=1.1, b=-0.2)
    alp = model(w40(5), -2.0, names="calibration40")
    alp35 = model(w35(2), -2.0, names="calibration40")
    both = {"calibration": cal, "alpha": alp}
    both35 = {"calibration": cal, "alpha": alp35}
    for k, beta in enumerate((0.0, 0.05, 0.35, 0.8, 1.0)):
        calls.append(ml_call(f"en-b{k}", both, model_type="ensemble", cfg=dict(ensemble_beta=beta)))
    calls.append(ml_call("en-zero", both, model_type="ensemble", horses=_HORSES_ZERO))
    calls.append(ml_call("en-35", both35, model_type="ensemble", horses=_HORSES_ZERO))
    calls.append(ml_call("en-calib-only", {"calibration": cal}, model_type="ensemble", horses=_HORSES_ZERO))
    calls.append(ml_call("en-alpha-only", {"alpha": alp}, model_type="ensemble", horses=_HORSES_ZERO))
    calls.append(ml_call("en-none", {}, model_type="ensemble", horses=_HORSES_ZERO))
    # 最後の馬のオッズが 0（旧ラベルは最後の馬の変数で決まる: E2）
    last0 = _HORSES6[:5] + [dict(umaban=6, odds=0.0, popularity=0)]
    calls.append(ml_call("en-last0", both, model_type="ensemble", horses=last0))
    # カバレッジが全馬 0（馬マスタ・過去走なし）の race_id / レース条件なし
    calls.append(ml_call("en-nocov", both, model_type="ensemble", race_id=RACE_NONE))
    calls.append(ml_call("en-dirt", both, model_type="ensemble", race_id=RACE_DIRT))
    # 設定の β の範囲外（0 未満・1 超は [0, 1] に収める）
    calls.append(ml_call("en-bneg", both, model_type="ensemble", cfg=dict(ensemble_beta=-0.5)))
    calls.append(ml_call("en-bbig", both, model_type="ensemble", cfg=dict(ensemble_beta=2.5)))
    return calls


def g5_calls():
    """判定側の市場ブレンド（α）・上限クリップ・再正規化。モデル内ブレンドの実行時上書き（二重ブレンド H4）を含む。"""
    calls = []
    cal = model(w40(1), -1.5, a=1.1, b=-0.2, alpha=0.9)   # モデルに保存された α は実行時に config 値で上書きされる
    both = {"calibration": cal, "alpha": model(w40(5), -2.0)}
    for k, alpha in enumerate((0.0, 0.0005, 0.001, 0.0011, 0.25, 0.6, 0.998, 0.999, 1.0, 1.5, -0.5)):
        calls.append(ml_call(f"bl-a{k}", {"calibration": cal}, model_type="calibration", cfg=dict(market_blend_alpha=alpha)))
    for k, clip in enumerate((0.0, 0.05, 0.2, 0.35, 0.9)):
        calls.append(ml_call(f"bl-c{k}", {"calibration": cal}, model_type="calibration", cfg=dict(win_prob_clip_max=clip)))
    calls.append(ml_call("bl-noclip", {"calibration": cal}, model_type="calibration", kwargs=dict(enable_prob_clip=False)))
    # 引数（ml_market_blend_alpha / win_prob_clip_max）は ml_cfg より優先
    calls.append(ml_call("bl-arg", {"calibration": cal}, model_type="calibration", kwargs=dict(ml_market_blend_alpha=0.5, win_prob_clip_max=0.3)))
    calls.append(ml_call("bl-ens", both, model_type="ensemble", cfg=dict(market_blend_alpha=0.4)))
    # hit_rate モード・EV 閾値（ML 確率でのフィルタ）
    calls.append(ml_call("bl-hit", {"calibration": cal}, model_type="calibration",
                         kwargs=dict(priority_mode="hit_rate", win_prob_min=0.05, win_ratio_min=0.5, win_odds_max=40.0, hit_rate_min_ev=-1000.0)))
    return calls


def g7_legacy_calls():
    """旧実装側の失敗時の挙動（新しいロード契約の仕様テストとは別）: 数値は市場確率と同じになる。ラベルは旧のまま。"""
    calls = []
    cal = model(w40(1), -1.5, a=1.1, b=-0.2)
    bad_names = model(w35(2), -2.0, names="alpha35")                       # feature_names 不一致 → 旧実装は ValueError で握り潰し（H1）
    no_names = model(w40(3), -1.0, names="none")                           # feature_names が空 → ガードを通過する
    calls.append(ml_call("lf-disabled", {"calibration": cal}, model_type="calibration", ml_enabled=False))
    calls.append(ml_call("lf-nomodel-cal", {}, model_type="calibration"))
    calls.append(ml_call("lf-nomodel-alpha", {}, model_type="alpha"))
    calls.append(ml_call("lf-nomodel-legacy", {}, model_type="legacy"))
    calls.append(ml_call("lf-badnames-cal", {"calibration": bad_names}, model_type="calibration"))
    calls.append(ml_call("lf-badnames-alpha", {"alpha": bad_names}, model_type="alpha"))
    calls.append(ml_call("lf-badnames-ens", {"calibration": cal, "alpha": bad_names}, model_type="ensemble"))
    calls.append(ml_call("lf-nonames", {"calibration": no_names}, model_type="calibration"))
    calls.append(ml_call("lf-unknown-type", {"calibration": cal}, model_type="foo"))
    calls.append(ml_call("lf-no-legacy-path", {"legacy": cal}, model_type="legacy", pass_legacy_path=False))
    return calls
