"""旧 `WinProbabilityModel` の pickle を、**許可リスト式の Unpickler** で読む（docs/ML_DESIGN.md §11.9）。

重要: **これで任意の pickle が安全になるわけではない**。リスクを下げるだけの多層の対策で、前提は『利用者本人が自分の PC で作った自分のファイルを、
ネットワークの無いローカル環境で読む』こと（クラウド・他人のファイルには使わない）。
- `pickle.load` は使わない。`find_class` が許可リストの (module, name) 以外を **すべて拒否**する（`os.system`・`builtins.eval`・`subprocess` などの呼び出しを作れない）。
- 許可するのは、旧クラスの『空の入れ物』と、numpy の配列/スカラの復元に必要な数個だけ。旧 `jraev` は import しない（同名の空クラスを渡す）。
- 事前に opcode を走査する（許可しない種類: 拡張レジストリ `EXT*`、永続 ID `PERSID`、巨大な opcode 数）。ファイルの大きさにも上限。
- 読んだ結果を検査する（配列は 1 次元の float32/float64 だけ。object 配列は拒否）。
- 残るリスク: 許可した numpy の内部関数自体の不具合。これは排除できない（numpy 2.5.3 固定のローカル環境で、自分のファイルだけを読む運用で受容する）。
"""

import io
import pickle
import pickletools
from typing import Any

import numpy as np

MAX_BYTES = 8 * 1024 * 1024
MAX_OPCODES = 200_000
_FORBIDDEN_OPCODES = {"EXT1", "EXT2", "EXT4", "PERSID", "BINPERSID"}

# 旧クラスの置き場所として受け入れるモジュール名（参照元の `src/jraev/ml/model.py`）。他の場所のクラスは未知として拒否
LEGACY_MODULES = ("jraev.ml.model", "src.jraev.ml.model")
LEGACY_CLASS = "WinProbabilityModel"
_NUMPY_MODULES = ("numpy.core.multiarray", "numpy._core.multiarray", "numpy.core.numeric", "numpy._core.numeric")
_ALLOWED_NUMPY = {
    ("multiarray", "_reconstruct"), ("multiarray", "scalar"), ("numeric", "_frombuffer"),
}  # fmt: skip


class UnpickleRefused(Exception):
    """許可リスト外・形式不正・大きさ超過など。理由コードは `reason`（値や内容は含めない）。"""

    def __init__(self, reason: str, message: str) -> None:
        super().__init__(message)
        self.reason = reason


class LegacyModelShell:
    """旧 `WinProbabilityModel` の空の入れ物（属性は pickle の状態でそのまま入る。メソッドは持たない）。"""


class _AllowlistUnpickler(pickle.Unpickler):
    def find_class(self, module: str, name: str) -> Any:
        if module in LEGACY_MODULES and name == LEGACY_CLASS:
            return LegacyModelShell
        if module in _NUMPY_MODULES and (module.rsplit(".", 1)[1], name) in _ALLOWED_NUMPY:
            import importlib

            return getattr(importlib.import_module(module), name)
        if module == "numpy" and name in ("ndarray", "dtype"):
            return getattr(np, name)
        raise UnpickleRefused("UNKNOWN_CLASS", f"許可リスト外のクラス/関数: {module}.{name}")


def _scan(data: bytes) -> None:
    n = 0
    try:
        for op, _arg, _pos in pickletools.genops(data):
            n += 1
            if n > MAX_OPCODES:
                raise UnpickleRefused("TOO_MANY_OPCODES", "opcode が多すぎる")
            if op.name in _FORBIDDEN_OPCODES:
                raise UnpickleRefused("FORBIDDEN_OPCODE", f"許可しない opcode: {op.name}")
    except UnpickleRefused:
        raise
    except Exception as e:
        raise UnpickleRefused("MALFORMED", "pickle として不正") from e


def load_legacy_model(data: bytes) -> LegacyModelShell:
    if not data or len(data) > MAX_BYTES:
        raise UnpickleRefused("SIZE", "ファイルが空、または大きすぎる")
    _scan(data)
    try:
        obj = _AllowlistUnpickler(io.BytesIO(data)).load()
    except UnpickleRefused:
        raise
    except Exception as e:
        raise UnpickleRefused("MALFORMED", f"読めない: {type(e).__name__}") from e
    if not isinstance(obj, LegacyModelShell):
        raise UnpickleRefused("NOT_A_MODEL", "WinProbabilityModel ではない")
    return obj
