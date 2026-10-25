"""学習設定と品質ゲート設定の読込み・検証。未知のキー・型違い（bool を数値にする等）・範囲外は拒否（UsageError。終了コード 2）。

学習設定（JSON）:
  {"kind": "calibration|alpha", "mode": "v2", "seed": 42, "l2_c": 1.0, "max_iter": 500, "tol": 1e-6,
   "recency_halflife_days": 90|180|240, "calibration_fraction": 0.5, "market_blend_alpha_default": 0.25}

品質ゲート設定（JSON）— **数値の閾値は未承認（UNRESOLVED）。既定値を持たない**:
  {"purpose": "synthetic_test|production", "checks": {<check 名>: 数値 ...}}
  check が 1 つも無い／ファイルが無い → 品質ゲート未設定 = fail closed（REJECTED。理由 QUALITY_GATE_NOT_CONFIGURED）。
  設定した check が評価できなければ（例: 市場ベースライン無し）不合格（UNAVAILABLE も REJECTED）。
"""

import json
import math
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from tools.ml import canonical

from .errors import UsageError

KINDS = ("calibration", "alpha")
HALFLIVES = (90, 180, 240)
_TRAIN_KEYS = {
    "kind",
    "mode",
    "seed",
    "l2_c",
    "max_iter",
    "tol",
    "recency_halflife_days",
    "calibration_fraction",
    "market_blend_alpha_default",
}


def _num(d: dict[str, Any], key: str, lo: float, hi: float, *, integer: bool = False, default: Any = None) -> Any:
    if key not in d:
        if default is None:
            raise UsageError("CONFIG_INVALID", f"{key} が必須")
        return default
    v = d[key]
    if isinstance(v, bool) or not isinstance(v, int | float) or (integer and not isinstance(v, int)):
        raise UsageError("CONFIG_INVALID", f"{key} は{'整数' if integer else '数値'}")
    if not math.isfinite(v) or not lo <= v <= hi:
        raise UsageError("CONFIG_INVALID", f"{key} は [{lo}, {hi}] の有限値")
    return v


@dataclass(frozen=True)
class TrainConfig:
    kind: str
    seed: int = 42
    l2_c: float = 1.0
    max_iter: int = 500
    tol: float = 1e-6
    recency_halflife_days: int = 180
    calibration_fraction: float = 0.5
    market_blend_alpha_default: float = 0.25
    mode: str = "v2"

    def to_json(self) -> dict[str, Any]:
        return {
            "kind": self.kind,
            "mode": self.mode,
            "seed": self.seed,
            "l2_c": float(self.l2_c),
            "max_iter": self.max_iter,
            "tol": float(self.tol),
            "recency_halflife_days": self.recency_halflife_days,
            "calibration_fraction": float(self.calibration_fraction),
            "market_blend_alpha_default": float(self.market_blend_alpha_default),
        }

    @staticmethod
    def from_json(d: Any) -> "TrainConfig":
        if not isinstance(d, dict):
            raise UsageError("CONFIG_INVALID", "学習設定がオブジェクトでない")
        unknown = set(d) - _TRAIN_KEYS
        if unknown:
            raise UsageError("CONFIG_INVALID", f"学習設定に未知のキー: {sorted(unknown)}")
        kind = d.get("kind")
        if kind not in KINDS:
            raise UsageError("CONFIG_INVALID", f"kind は {KINDS}")
        mode = d.get("mode", "v2")
        if mode != "v2":
            # 旧学習方式（legacy_compat の再学習: GD・class_weight=balanced）は実装しない。legacy_compat のモデルは pkl 変換でだけ作る（D-165）
            raise UsageError(
                "LEGACY_COMPAT_TRAINING_NOT_IMPLEMENTED",
                "mode は v2 のみ（legacy_compat の再学習は未実装。旧モデルは convert-pkl で JSON 化する）",
            )
        halflife = _num(d, "recency_halflife_days", 1, 100000, integer=True, default=180)
        if halflife not in HALFLIVES:
            raise UsageError("CONFIG_INVALID", f"recency_halflife_days は {HALFLIVES}（ML_DESIGN §11.7）")
        return TrainConfig(
            kind=kind,
            seed=_num(d, "seed", 0, 2**31 - 1, integer=True, default=42),
            l2_c=float(_num(d, "l2_c", 1e-6, 1e6, default=1.0)),
            max_iter=_num(d, "max_iter", 10, 100000, integer=True, default=500),
            tol=float(_num(d, "tol", 1e-12, 1e-1, default=1e-6)),
            recency_halflife_days=halflife,
            calibration_fraction=float(_num(d, "calibration_fraction", 0.1, 0.9, default=0.5)),
            market_blend_alpha_default=float(_num(d, "market_blend_alpha_default", 0.0, 1.0, default=0.25)),
        )


# check 名 → (種類, 下限, 上限, 整数か)。値の方向は gate.py の評価で決まる
GATE_CHECKS: dict[str, tuple[float, float, bool]] = {
    "min_train_rows": (1, 1e12, True),
    "min_train_positives": (1, 1e12, True),
    "min_valid_rows": (1, 1e12, True),
    "min_valid_positives": (1, 1e12, True),
    "max_valid_logloss": (0.0, 100.0, False),
    "max_valid_brier": (0.0, 1.0, False),
    "min_valid_auc": (0.0, 1.0, False),
    "max_logloss_minus_market": (-100.0, 100.0, False),
    "calibration_slope_min": (-100.0, 100.0, False),
    "calibration_slope_max": (-100.0, 100.0, False),
    "max_abs_calibration_intercept": (0.0, 100.0, False),
    "max_constant_feature_ratio": (0.0, 1.0, False),
    "min_horse_master_coverage": (0.0, 1.0, False),
    "max_logloss_regression_vs_active": (-100.0, 100.0, False),
}


@dataclass(frozen=True)
class GateConfig:
    purpose: str
    checks: dict[str, float] = field(default_factory=dict)

    @property
    def configured(self) -> bool:
        return bool(self.checks)

    def to_json(self) -> dict[str, Any]:
        return {"purpose": self.purpose, "checks": {k: self.checks[k] for k in sorted(self.checks)}}

    def digest(self) -> str:
        return str(canonical.sha256_hex(self.to_json()))

    @staticmethod
    def unconfigured() -> "GateConfig":
        return GateConfig(purpose="unconfigured", checks={})

    @staticmethod
    def from_json(d: Any) -> "GateConfig":
        if not isinstance(d, dict) or set(d) != {"purpose", "checks"}:
            raise UsageError("GATE_CONFIG_INVALID", "品質ゲート設定は {purpose, checks} のオブジェクト")
        if d["purpose"] not in ("synthetic_test", "production"):
            raise UsageError("GATE_CONFIG_INVALID", "purpose は synthetic_test または production")
        raw = d["checks"]
        if not isinstance(raw, dict):
            raise UsageError("GATE_CONFIG_INVALID", "checks がオブジェクトでない")
        checks: dict[str, float] = {}
        for name, v in raw.items():
            spec = GATE_CHECKS.get(name)
            if spec is None:
                raise UsageError("GATE_CONFIG_INVALID", f"未知の check: {name}")
            lo, hi, integer = spec
            if (
                isinstance(v, bool)
                or not isinstance(v, int | float)
                or (integer and not isinstance(v, int))
                or not math.isfinite(v)
                or not lo <= v <= hi
            ):
                raise UsageError("GATE_CONFIG_INVALID", f"{name} の値が不正")
            checks[name] = v
        if ("calibration_slope_min" in checks) != ("calibration_slope_max" in checks):
            raise UsageError("GATE_CONFIG_INVALID", "calibration_slope_min と calibration_slope_max は両方指定する")
        if "calibration_slope_min" in checks and checks["calibration_slope_min"] > checks["calibration_slope_max"]:
            raise UsageError("GATE_CONFIG_INVALID", "calibration_slope_min > calibration_slope_max")
        return GateConfig(purpose=d["purpose"], checks=checks)


def load_json_file(path: str | Path, what: str) -> Any:
    try:
        raw = Path(path).read_bytes()
    except OSError as e:
        raise UsageError("CONFIG_UNREADABLE", f"{what}を読めない") from e
    try:
        return canonical.loads_strict(raw)
    except (ValueError, UnicodeDecodeError, json.JSONDecodeError) as e:
        raise UsageError("CONFIG_INVALID", f"{what}が JSON として不正（重複キー・NaN 等を含む）") from e
