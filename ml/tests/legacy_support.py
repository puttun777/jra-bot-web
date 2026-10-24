"""合成の『旧モデル』pickle を作る（実 pkl は使わない）。旧 jraev を import せず、同名のモジュール/クラスをその場で作って pickle する。"""

import pickle
import sys
import types
from typing import Any

import numpy as np

from tests.synth import schema


def _fake_module() -> type:
    for name in ("jraev", "jraev.ml", "jraev.ml.model"):
        if name not in sys.modules:
            sys.modules[name] = types.ModuleType(name)
    mod = sys.modules["jraev.ml.model"]
    if not hasattr(mod, "WinProbabilityModel"):

        class WinProbabilityModel:  # 旧クラスと同名・同モジュール名（pickle の参照だけを合わせる）
            pass

        WinProbabilityModel.__module__ = "jraev.ml.model"
        WinProbabilityModel.__qualname__ = "WinProbabilityModel"
        mod.WinProbabilityModel = WinProbabilityModel  # type: ignore[attr-defined]
    return mod.WinProbabilityModel  # type: ignore[attr-defined,no-any-return]


def legacy_pickle(*, kind: str = "calibration", dim: int | None = None, protocol: int = 5, **attrs: Any) -> bytes:
    """kind: market5 / calibration40 / alpha35。attrs で上書き（weights, bias, calib_a, calib_b, market_blend_alpha, feature_names）。"""
    names = {
        "market5": ["odds", "popularity", "log_odds", "inv_popularity", "odds_popularity_interaction"],
        "calibration": schema("calibration")["names"],
        "alpha": schema("alpha")["names"],
    }[{"market5": "market5", "calibration40": "calibration", "alpha35": "alpha"}.get(kind, kind)]
    n = dim if dim is not None else len(names)
    obj = _fake_module()()
    obj.__dict__.update(
        {
            "weights": np.array([((i * 7) % 11 - 5) / 20.0 for i in range(n)], dtype=np.float64),
            "bias": -1.25,
            "calib_a": 1.1,
            "calib_b": -0.2,
            "market_blend_alpha": 0.25,
            "feature_names": list(names),
        }
    )
    obj.__dict__.update(attrs)
    return pickle.dumps(obj, protocol=protocol)
