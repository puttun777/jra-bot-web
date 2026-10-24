"""旧 `WinProbabilityModel`（読み込み済みの入れ物）→ モデル JSON（kind=legacy_compat / alpha）。**パディング/スライスで次元を合わせない**（不一致は中止）。"""

import hashlib
import json
import math
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import numpy as np

from tools.ml import canonical

from .. import PRODUCER, modeljson
from ..errors import MlTrainError
from .unpickle import LegacyModelShell


class ConversionRefused(MlTrainError):
    """変換を中止した（理由コードは固定語彙。メッセージに数値・名前の中身を含めない）。"""

    def __init__(self, reason: str, message: str) -> None:
        super().__init__(reason, message)


@dataclass
class KnownSchemas:
    market5: dict[str, Any]
    calibration40: dict[str, Any]
    alpha35: dict[str, Any]

    @staticmethod
    def load(directory: Path) -> "KnownSchemas":
        """Kotlin が書いた `feature_schema.*.json`（唯一の定義）を読む。名前の一覧は Python に持たない。hash を再計算して検証する。"""
        out = {}
        for key, fname in (("market5", "market5"), ("calibration40", "calibration40"), ("alpha35", "alpha35")):
            path = directory / f"feature_schema.{fname}.json"
            try:
                s = canonical.loads_strict(path.read_bytes())
            except (OSError, ValueError) as e:
                raise ConversionRefused("SCHEMA_FILE_UNREADABLE", f"feature_schema.{fname}.json を読めない") from e
            if canonical.feature_schema_hash(s) != s.get("hash"):
                raise ConversionRefused("SCHEMA_FILE_INVALID", f"feature_schema.{fname}.json の hash が内容と一致しない")
            out[key] = s
        return KnownSchemas(**out)


@dataclass
class Conversion:
    model: dict[str, Any]
    warnings: list[str] = field(default_factory=list)


def _finite_float(v: Any, what: str) -> float:
    if isinstance(v, bool) or not isinstance(v, int | float | np.floating | np.integer):
        raise ConversionRefused("INVALID_NUMBER", f"{what} が数値でない")
    f = float(v)
    if not math.isfinite(f):
        raise ConversionRefused("INVALID_NUMBER", f"{what} が有限でない")
    return f


def _feature_names(v: Any) -> list[str]:
    if not isinstance(v, list) or not v or len(v) > 4096:
        raise ConversionRefused("INVALID_FEATURE_NAMES", "feature_names が空でないリストでない")
    if not all(isinstance(x, str) and 0 < len(x) <= 200 for x in v):
        raise ConversionRefused("INVALID_FEATURE_NAMES", "feature_names に文字列以外/空/長すぎる要素")
    if len(set(v)) != len(v):
        raise ConversionRefused("INVALID_FEATURE_NAMES", "feature_names が重複している")
    return list(v)


def convert(
    shell: LegacyModelShell, *, schemas: KnownSchemas, model_id: str, tenant_id: int, created_at: str, source_sha256: str
) -> Conversion:
    state = shell.__dict__
    allowed = {"weights", "bias", "calib_a", "calib_b", "market_blend_alpha", "feature_names"}
    extra = set(state) - allowed
    if extra:
        raise ConversionRefused("UNKNOWN_ATTRIBUTES", f"想定外の属性がある（{len(extra)} 個）。旧クラスの形ではない")
    missing = {"weights", "bias", "feature_names"} - set(state)
    if missing:
        raise ConversionRefused("MISSING_ATTRIBUTES", f"必須の属性が無い: {sorted(missing)}")

    w = state["weights"]
    if not isinstance(w, np.ndarray) or w.ndim != 1 or w.dtype.kind != "f" or not 1 <= w.shape[0] <= 4096:
        raise ConversionRefused("INVALID_WEIGHTS", "weights が 1 次元の浮動小数配列でない（学習前のモデル/object 配列を含む）")
    if not np.isfinite(w).all():
        raise ConversionRefused("INVALID_NUMBER", "weights に NaN / Infinity がある")
    names = _feature_names(state["feature_names"])
    bias = _finite_float(state["bias"], "bias")

    warnings: list[str] = []
    candidates = {
        "market5": ("legacy_compat", schemas.market5),
        "calibration40": ("legacy_compat", schemas.calibration40),
        "alpha35": ("alpha", schemas.alpha35),
    }
    matched = [(k, kind, s) for k, (kind, s) in candidates.items() if s["names"] == names]
    if not matched:
        raise ConversionRefused(
            "UNKNOWN_FEATURE_SCHEMA", f"feature_names（{len(names)} 個）が既知のスキーマ（5・40・35 次元）と一致しない。変換しない"
        )
    _key, kind, schema = matched[0]
    if w.shape[0] != len(schema["names"]):
        # H1/H2 の疑い（feature_names は 40 名だが重みは 35 など）。パディング/スライスで合わせない
        raise ConversionRefused(
            "DIMENSION_MISMATCH", f"重みの数 {w.shape[0]} と feature_names の数 {len(names)} が違う（H1/H2 の疑い）。変換しない"
        )

    a, b = state.get("calib_a"), state.get("calib_b")
    if (a is None) != (b is None):
        raise ConversionRefused("INVALID_CALIBRATION", "calib_a と calib_b の片方だけがある")
    platt = None if a is None else (_finite_float(a, "calib_a"), _finite_float(b, "calib_b"))
    blend = _finite_float(state.get("market_blend_alpha", 1.0), "market_blend_alpha")
    if not 0.0 <= blend <= 1.0:
        raise ConversionRefused("INVALID_NUMBER", "market_blend_alpha が [0, 1] の外")
    if kind == "alpha" and platt is not None:
        warnings.append("alpha(35 次元) のモデルに Platt が付いている（旧 train.py 経路では通常付かない）。そのまま変換する")
    if kind == "legacy_compat" and len(names) == 40:
        warnings.append("旧 40 次元モデルを legacy_compat として変換（v2 の calibration とは別物。学習方式は旧 GD・標準化なし）")

    model = modeljson.build_model(
        model_id=model_id, tenant_id=tenant_id, kind=kind, feature_schema=schema, weights=[float(x) for x in w], bias=bias,
        mean=None, std=None, platt=platt, market_blend_alpha_default=blend,
        training={"converted_from": "legacy_pkl", "source_sha256": source_sha256, "conversion": "convert-pkl", "lr": None, "synthetic": False},
        metrics={}, created_at=created_at, producer=f"{PRODUCER}/convert-pkl",
    )  # fmt: skip
    return Conversion(model, warnings)


def sha256_of(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def report_json(c: Conversion) -> str:
    return json.dumps(
        {
            "model_id": c.model["model_id"],
            "kind": c.model["kind"],
            "dims": len(c.model["weights"]),
            "sha256": c.model["sha256"],
            "warnings": c.warnings,
        },
        ensure_ascii=False,
        sort_keys=True,
    )
