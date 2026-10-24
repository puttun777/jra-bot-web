"""JSON 正規化・正準シリアライズ。NaN/Infinity は黙って通さず、タグ付きオブジェクトにして検出可能にする。"""
import dataclasses
import json
import math

NONFINITE_KEY = "$float"


def normalize(obj):
    """旧実装の戻り値を JSON 化可能な構造へ。辞書キーは str のみ許可（暗黙変換しない）。"""
    if obj is None or isinstance(obj, (bool, str)):
        return obj
    if isinstance(obj, int):
        return int(obj)
    # numpy のスカラ・配列。**配列も `.item` を持つ**ので、`.tolist()`（スカラなら Python のスカラ、配列ならネストしたリスト）を先に使う。
    # （以前は `.item()` を先に呼んでいたため、要素が 2 つ以上の配列が ValueError になり、G1-MODEL-001 の model_extract_features が例外として記録されていた: D-153）
    if hasattr(obj, "tolist") and hasattr(obj, "dtype"):
        return normalize(obj.tolist())
    if isinstance(obj, float):
        if math.isnan(obj):
            return {NONFINITE_KEY: "nan"}
        if math.isinf(obj):
            return {NONFINITE_KEY: "inf" if obj > 0 else "-inf"}
        return float(obj)
    if dataclasses.is_dataclass(obj) and not isinstance(obj, type):
        return normalize(dataclasses.asdict(obj))
    if isinstance(obj, dict):
        out = {}
        for k, v in obj.items():
            if not isinstance(k, str):
                raise TypeError(f"non-str dict key is not allowed: {k!r}")
            out[k] = normalize(v)
        return out
    if isinstance(obj, (list, tuple)):
        return [normalize(v) for v in obj]
    raise TypeError(f"cannot normalize {type(obj).__name__}")


def dumps(obj) -> str:
    """正準形式: キー昇順、indent=2、非 ASCII 保持、NaN 禁止、末尾改行。"""
    return json.dumps(obj, sort_keys=True, indent=2, ensure_ascii=False, allow_nan=False) + "\n"


def _reject_constant(name):
    raise ValueError(f"non-standard JSON constant: {name}")


def loads(text: str):
    return json.loads(text, parse_constant=_reject_constant)


def find_nonfinite(obj, path="$"):
    """タグ付き非有限値の場所を列挙。"""
    found = []
    if isinstance(obj, dict):
        if set(obj) == {NONFINITE_KEY}:
            found.append(path)
        else:
            for k, v in obj.items():
                found += find_nonfinite(v, f"{path}.{k}")
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            found += find_nonfinite(v, f"{path}[{i}]")
    return found
