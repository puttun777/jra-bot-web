"""正準 JSON（canonical JSON）の Python 参照実装。Kotlin の `CanonicalJson`（jp.jrabot.domain.ml.json）と**バイト単位で同じ**になる。

  json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False).encode("utf-8")

規則（docs/CONTRACTS.md §5.1）: 空白なし・キーはコードポイント順・`ensure_ascii=False`・整数と浮動小数は別物（`1` と `1.0` は別の正準形）・NaN/Infinity 禁止。
"""
import hashlib
import json


def dumps(obj) -> bytes:
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False).encode("utf-8")


def sha256_hex(obj) -> str:
    return hashlib.sha256(dumps(obj)).hexdigest()


def feature_schema_hash(schema: dict) -> str:
    """`feature_schema.hash`: hash を除いたオブジェクトの正準 JSON の SHA-256 に `sha256:` を付ける。名前の一覧は再実装せず、渡された内容だけから計算する。"""
    body = {k: v for k, v in schema.items() if k != "hash"}
    return "sha256:" + sha256_hex(body)


def model_sha256(model: dict) -> str:
    """モデル JSON の `sha256`: sha256 フィールドを除いた正準 JSON の SHA-256。"""
    return sha256_hex({k: v for k, v in model.items() if k != "sha256"})


def loads_strict(raw: bytes):
    """重複キー・NaN/Infinity・BOM を拒否して読む（Python の json.loads は既定ではこれらを受け入れる）。"""
    if raw.startswith(b"\xef\xbb\xbf"):
        raise ValueError("BOM は許可しない")

    def no_dups(pairs):
        d = {}
        for k, v in pairs:
            if k in d:
                raise ValueError(f"キーが重複: {k}")
            d[k] = v
        return d

    def bad_const(name):
        raise ValueError(f"定数 {name} は許可しない")

    return json.loads(raw.decode("utf-8"), object_pairs_hook=no_dups, parse_constant=bad_const)
