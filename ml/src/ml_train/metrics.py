"""評価指標。すべて numpy で自前計算（tests で scikit-learn と突合）。計算できない指標は値を作らず None にして `unavailable` に理由を残す。"""

from typing import Any

import numpy as np
from numpy.typing import NDArray
from scipy.stats import rankdata

from .trainer import P_EPS, fit_platt, logit

_EPS = 1e-15


def logloss(y: NDArray[np.int64], p: NDArray[np.float64]) -> float:
    q = np.clip(p, _EPS, 1.0 - _EPS)
    return float(-np.mean(y * np.log(q) + (1 - y) * np.log(1.0 - q)))


def brier(y: NDArray[np.int64], p: NDArray[np.float64]) -> float:
    return float(np.mean((p - y) ** 2))


def auc(y: NDArray[np.int64], p: NDArray[np.float64]) -> float | None:
    """Mann–Whitney U による AUC（同順位は平均順位）。片方のクラスしか無ければ None。"""
    n_pos = int(y.sum())
    n_neg = len(y) - n_pos
    if n_pos == 0 or n_neg == 0:
        return None
    ranks = rankdata(p)
    return float((ranks[y == 1].sum() - n_pos * (n_pos + 1) / 2.0) / (n_pos * n_neg))


def calibration_slope_intercept(y: NDArray[np.int64], p: NDArray[np.float64]) -> tuple[float, float] | None:
    """`y ~ sigmoid(slope · logit(p) + intercept)` の最尤。理想は slope=1・intercept=0。片方のクラスしか無ければ None。"""
    if int(y.sum()) in (0, len(y)):
        return None
    a, b = fit_platt(np.clip(p, P_EPS, 1.0 - P_EPS), y)
    return a, b


def race_normalize(race_id: NDArray[np.str_], p: NDArray[np.float64]) -> NDArray[np.float64]:
    """同じレースに含まれる行の合計が 1 になるよう正規化（データセットに含まれる行だけが対象。行が欠けたレースでは近似）。"""
    out = np.empty_like(p)
    _, inv = np.unique(race_id, return_inverse=True)
    sums = np.bincount(inv, weights=p)
    out[:] = p / sums[inv]
    return out


def market_probability(odds: NDArray[np.float64], race_id: NDArray[np.str_]) -> tuple[NDArray[np.float64], NDArray[np.float64]]:
    """市場確率 `1/max(odds, 0.1)`（生）と、同じレース内で正規化したもの（オーバーラウンドを除く）。"""
    raw = np.clip(1.0 / np.maximum(odds, 0.1), P_EPS, 1.0 - P_EPS)
    return raw, race_normalize(race_id, raw)


def top1_hit_rate(race_id: NDArray[np.str_], y: NDArray[np.int64], p: NDArray[np.float64]) -> float:
    """各レースで最も確率の高い馬が 1 着だった割合（同率は先の行）。"""
    order = np.lexsort((-p, race_id))
    sorted_r = race_id[order]
    first = np.ones(len(order), dtype=bool)
    first[1:] = sorted_r[1:] != sorted_r[:-1]
    return float(y[order][first].mean())


def distribution(p: NDArray[np.float64]) -> dict[str, Any]:
    qs = [0.0, 0.01, 0.05, 0.25, 0.5, 0.75, 0.95, 0.99, 1.0]
    return {
        "mean": float(p.mean()),
        "quantiles": {f"{q:g}": float(np.quantile(p, q)) for q in qs},
    }


def reliability_bins(y: NDArray[np.int64], p: NDArray[np.float64], bins: int = 10) -> list[dict[str, Any]]:
    """予測確率の分位でビンを作り、各ビンの平均予測と実測の勝率（信頼度曲線）。"""
    order = np.argsort(p, kind="stable")
    out = []
    for chunk in np.array_split(order, min(bins, len(order))):
        if len(chunk) == 0:
            continue
        out.append({"n": len(chunk), "mean_pred": float(p[chunk].mean()), "observed": float(y[chunk].mean())})
    return out


def evaluate(
    y: NDArray[np.int64], p: NDArray[np.float64], race_id: NDArray[np.str_], odds: NDArray[np.float64] | None
) -> tuple[dict[str, Any], dict[str, str]]:
    """評価用データでの指標一式と、計算できなかった指標の理由（`unavailable`）。"""
    unavailable: dict[str, str] = {}
    pn = np.clip(race_normalize(race_id, p), _EPS, 1.0 - _EPS)
    m: dict[str, Any] = {
        "n": len(y),
        "n_positive": int(y.sum()),
        "n_races": len(np.unique(race_id)),
        "positive_rate": float(y.mean()),
        "logloss": logloss(y, p),
        "brier": brier(y, p),
        "auc": auc(y, p),
        "accuracy_at_0_5": float(np.mean((p >= 0.5).astype(np.int64) == y)),
        "logloss_race_normalized": logloss(y, pn),
        "brier_race_normalized": brier(y, pn),
        "top1_hit_rate": top1_hit_rate(race_id, y, p),
        "distribution": distribution(p),
        "reliability": reliability_bins(y, p),
    }
    cal = calibration_slope_intercept(y, np.clip(p, P_EPS, 1.0 - P_EPS))
    m["calibration_slope"], m["calibration_intercept"] = cal if cal else (None, None)
    if cal is None:
        unavailable["calibration_slope"] = "評価データが片方のクラスだけ"
    if m["auc"] is None:
        unavailable["auc"] = "評価データが片方のクラスだけ"
    if odds is None:
        for k in ("baseline_market_logloss", "baseline_market_brier", "baseline_market_auc", "baseline_market_top1_hit_rate"):
            m[k] = None
            unavailable[k] = "データセットに odds 特徴量が無い（alpha）。市場ベースラインは作らない"
        m["market_baseline_available"] = False
    else:
        raw, norm = market_probability(odds, race_id)
        m["baseline_market_logloss"] = logloss(y, norm)
        m["baseline_market_brier"] = brier(y, norm)
        m["baseline_market_auc"] = auc(y, norm)
        m["baseline_market_top1_hit_rate"] = top1_hit_rate(race_id, y, norm)
        m["baseline_market_logloss_raw_overround"] = logloss(y, raw)
        m["market_baseline_available"] = True
    return m, unavailable


def constant_feature_ratio(x: NDArray[np.float64]) -> float:
    return float(np.mean(x.std(axis=0) <= 1e-12))


def logit_clip(p: NDArray[np.float64]) -> NDArray[np.float64]:
    return logit(np.clip(p, P_EPS, 1.0 - P_EPS))
