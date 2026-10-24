"""モデル JSON の生成・署名・検証（Kotlin の `ModelLoader` と同じ契約。docs/CONTRACTS.md §5.1・ML_DESIGN §5.2・§11.3）。

- 正準 JSON（`tools/ml/canonical.py`。Kotlin の `CanonicalJson` とバイト一致）。浮動小数のフィールドは必ず float（`1` ではなく `1.0`）。NaN/Infinity は作れない。
- `sha256` = `sha256` を除いた正準 JSON の SHA-256。署名 = Ed25519（RFC 8032）で **JSON ファイルのバイト列そのもの**に付ける（`.sig` は生の 64 バイト）。
- 秘密鍵（`MODEL_SIGNING_KEY`: 生 32 バイトのシードの Base64）はログ・例外・成果物に出さない。
"""

import base64
import binascii
import math
import re
from dataclasses import dataclass
from typing import Any

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey

from tools.ml import canonical

from .errors import MlTrainError, UsageError

MODEL_ID_RE = re.compile(r"[A-Za-z0-9_-]{1,64}")
SHA256_RE = re.compile(r"[0-9a-f]{64}")
_TOP_KEYS = {
    "model_schema_version", "model_id", "tenant_id", "kind", "algorithm", "feature_schema", "preprocessing", "weights", "bias",
    "calibration", "market_blend", "ensemble", "training", "metrics", "created_at", "producer", "sha256",
}  # fmt: skip
ALLOWED_DIMS = {"calibration": {40}, "alpha": {35}, "legacy_compat": {5, 40}}  # Kotlin の ModelKind.allowedSchemas と同じ


class ModelVerificationError(MlTrainError):
    """作った（または読んだ）モデル成果物の検証に失敗した。reason は Kotlin の FallbackReason と同じ語彙。"""

    def __init__(self, reason: str, message: str) -> None:
        super().__init__(reason, message)


def _f(x: float) -> float:
    """float にして -0.0 を 0.0 に、非有限を拒否する。"""
    v = float(x)
    if not math.isfinite(v):
        raise ModelVerificationError("MODEL_INVALID", "非有限の数値は JSON にできない")
    return v + 0.0


def floats(xs: Any) -> list[float]:
    return [_f(x) for x in xs]


def clean(obj: Any) -> Any:
    """numpy のスカラ/配列・タプルを Python の素の型にし、float は有限だけにする（int/bool はそのまま）。"""
    if isinstance(obj, dict):
        return {str(k): clean(v) for k, v in obj.items()}
    if isinstance(obj, list | tuple):
        return [clean(v) for v in obj]
    if hasattr(obj, "tolist") and not isinstance(obj, str | bytes):
        return clean(obj.tolist())
    if isinstance(obj, bool) or obj is None or isinstance(obj, int | str):
        return obj
    if isinstance(obj, float):
        return _f(obj)
    raise ModelVerificationError("MODEL_INVALID", f"JSON にできない型: {type(obj).__name__}")


def build_model(
    *,
    model_id: str,
    tenant_id: int,
    kind: str,
    feature_schema: dict[str, Any],
    weights: list[float],
    bias: float,
    mean: list[float] | None,
    std: list[float] | None,
    platt: tuple[float, float] | None,
    market_blend_alpha_default: float,
    training: dict[str, Any],
    metrics: dict[str, Any],
    created_at: str,
    producer: str,
    algorithm: str = "logistic_regression",
) -> dict[str, Any]:
    m: dict[str, Any] = {
        "model_schema_version": 1,
        "model_id": model_id,
        "tenant_id": int(tenant_id),
        "kind": kind,
        "algorithm": algorithm,
        "feature_schema": feature_schema,
        "preprocessing": None if mean is None else {"type": "standardize", "mean": floats(mean), "std": floats(std or [])},
        "weights": floats(weights),
        "bias": _f(bias),
        "calibration": None if platt is None else {"type": "platt", "a": _f(platt[0]), "b": _f(platt[1])},
        "market_blend": {"alpha_default": _f(market_blend_alpha_default)},
        "ensemble": None,
        "training": clean(training),
        "metrics": clean(metrics),
        "created_at": created_at,
        "producer": producer,
        "sha256": "0" * 64,
    }
    m["sha256"] = canonical.model_sha256(m)
    return m


def load_signing_seed(value: str | None) -> bytes:
    """`MODEL_SIGNING_KEY`（生 32 バイトのシードの Base64）。鍵の値を例外に含めない。"""
    if not value:
        raise UsageError("MODEL_SIGNING_KEY_MISSING", "MODEL_SIGNING_KEY が未設定")
    try:
        seed = base64.b64decode(value.strip(), validate=True)
    except (binascii.Error, ValueError) as e:
        raise UsageError("MODEL_SIGNING_KEY_INVALID", "MODEL_SIGNING_KEY が Base64 でない") from e
    if len(seed) != 32:
        raise UsageError("MODEL_SIGNING_KEY_INVALID", "MODEL_SIGNING_KEY は生 32 バイトの Base64")
    return seed


def public_key_of(seed: bytes) -> bytes:
    return Ed25519PrivateKey.from_private_bytes(seed).public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)


def sign(seed: bytes, message: bytes) -> bytes:
    return Ed25519PrivateKey.from_private_bytes(seed).sign(message)


def parse_pubkeys(value: str | None) -> list[bytes]:
    """`MODEL_VERIFY_PUBKEY`（カンマ区切りの Base64 の生 32 バイト）。"""
    keys: list[bytes] = []
    for item in (value or "").split(","):
        part = item.strip()
        if not part:
            continue
        try:
            raw = base64.b64decode(part, validate=True)
        except (binascii.Error, ValueError) as e:
            raise UsageError("MODEL_VERIFY_PUBKEY_INVALID", "MODEL_VERIFY_PUBKEY が Base64 でない") from e
        if len(raw) != 32:
            raise UsageError("MODEL_VERIFY_PUBKEY_INVALID", "MODEL_VERIFY_PUBKEY は生 32 バイト")
        keys.append(raw)
    return keys


def _verify_sig(pubkeys: list[bytes], sig: bytes, message: bytes) -> bool:
    if len(sig) != 64:
        return False
    for k in pubkeys:
        try:
            Ed25519PublicKey.from_public_bytes(k).verify(sig, message)
            return True
        except InvalidSignature:
            continue
    return False


@dataclass(frozen=True)
class VerifiedModelJson:
    model: dict[str, Any]
    sha256: str
    signature_sha256: str


def verify_model_bytes(
    raw: bytes,
    sig: bytes,
    pubkeys: list[bytes],
    *,
    tenant_id: int,
    model_id: str,
    expected_kind: str | None = None,
    expected_schema_hash: str | None = None,
    expected_sha256: str | None = None,
) -> VerifiedModelJson:
    """Kotlin の `ModelLoader` と同じ順序で検査する: 署名 → 構文・正準形・キー → 版 → sha256 → feature_schema → 次元 → 取り違え。"""
    import hashlib

    if not pubkeys or not _verify_sig(pubkeys, sig, raw):
        raise ModelVerificationError("MODEL_SIGNATURE_INVALID", "署名が検証できない")
    try:
        m = canonical.loads_strict(raw)
    except (ValueError, UnicodeDecodeError) as e:
        raise ModelVerificationError("MODEL_INVALID", "JSON として不正") from e
    if not isinstance(m, dict) or set(m) != _TOP_KEYS:
        raise ModelVerificationError("MODEL_INVALID", "キーが契約と違う")
    if canonical.dumps(m) != raw:
        raise ModelVerificationError("MODEL_INVALID", "正準 JSON でない")
    if m["model_schema_version"] != 1:
        raise ModelVerificationError("MODEL_SCHEMA_MISMATCH", "未対応の model_schema_version")
    sha = m["sha256"]
    if (
        not isinstance(sha, str)
        or not SHA256_RE.fullmatch(sha)
        or canonical.model_sha256(m) != sha
        or (expected_sha256 and sha != expected_sha256)
    ):
        raise ModelVerificationError("MODEL_HASH_INVALID", "sha256 が内容（またはレジストリの記録）と一致しない")
    schema = m["feature_schema"]
    if not isinstance(schema, dict) or canonical.feature_schema_hash(schema) != schema.get("hash"):
        raise ModelVerificationError("FEATURE_SCHEMA_MISMATCH", "feature_schema.hash が内容と一致しない")
    if expected_schema_hash and schema["hash"] != expected_schema_hash:
        raise ModelVerificationError("FEATURE_SCHEMA_MISMATCH", "feature_schema.hash がレジストリの記録と違う")
    kind = m["kind"]
    if kind not in ALLOWED_DIMS or len(schema["names"]) not in ALLOWED_DIMS[kind] or m["algorithm"] != "logistic_regression":
        raise ModelVerificationError("FEATURE_SCHEMA_MISMATCH", "kind と feature_schema が合わない")
    n = len(schema["names"])
    w = m["weights"]
    if not isinstance(w, list) or len(w) != n or not all(isinstance(x, float) and math.isfinite(x) for x in w):
        raise ModelVerificationError("INVALID_MODEL_DIMENSION", "重みの数・型が不正")
    pre = m["preprocessing"]
    if pre is not None:
        ok = isinstance(pre, dict) and set(pre) == {"type", "mean", "std"} and pre["type"] == "standardize"
        ok = ok and len(pre["mean"]) == n and len(pre["std"]) == n
        ok = ok and all(isinstance(x, float) and math.isfinite(x) for x in pre["mean"])
        ok = ok and all(isinstance(x, float) and math.isfinite(x) and x > 0 for x in pre["std"])
        if not ok:
            raise ModelVerificationError("INVALID_MODEL_DIMENSION", "前処理が不正")
    cal = m["calibration"]
    if cal is not None and not (
        isinstance(cal, dict)
        and set(cal) == {"type", "a", "b"}
        and cal["type"] == "platt"
        and all(math.isfinite(cal[k]) for k in ("a", "b"))
    ):
        raise ModelVerificationError("MODEL_INVALID", "calibration が不正")
    if m["tenant_id"] != tenant_id or m["model_id"] != model_id or (expected_kind and kind != expected_kind):
        raise ModelVerificationError("MODEL_INVALID", "tenant_id・model_id・kind が期待と違う")
    return VerifiedModelJson(m, sha, hashlib.sha256(sig).hexdigest())
