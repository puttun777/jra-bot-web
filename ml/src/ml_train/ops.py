"""運用モードの確認（CONTRACTS §5.2・§5.3）。ml-train は NORMAL のときだけ動く。**DB に接続する前に**判定する。

- NORMAL → 実行／STARTING・STOPPING・STOPPED → 何もせず exit 0（理由 `MODE_<mode>`）／読めない・不正・未知の版 → UNKNOWN = exit 75（NORMAL として扱わない）。
- `OPS_MODE_SOURCE` の未設定・不正は設定エラー（exit 2）。この版が読めるのは `file://` の絶対パスだけ（`gs://` の直接読取りは未対応: UNRESOLVED）。
  bare path は `APP_ENV=local` のときだけ（bot の LocalFileModeProvider と同じ）。
"""

from pathlib import Path
from urllib.parse import unquote, urlparse

from tools.ml import canonical

from .errors import UsageError

MODES = ("NORMAL", "STARTING", "STOPPING", "STOPPED")


def read_mode(source: str | None, app_env: str | None) -> str:
    """'NORMAL' | 'STARTING' | 'STOPPING' | 'STOPPED' | 'UNKNOWN'。"""
    if not source:
        raise UsageError("OPS_MODE_SOURCE_MISSING", "OPS_MODE_SOURCE が未設定")
    if app_env not in ("local", "prod"):
        raise UsageError("APP_ENV_INVALID", "APP_ENV は local または prod")
    p = urlparse(source)
    if p.scheme == "file" and not p.netloc and p.path.startswith("/"):
        path = Path(unquote(p.path))
    elif p.scheme == "" and app_env == "local" and source.startswith("/"):
        path = Path(source)
    elif p.scheme == "gs":
        raise UsageError("OPS_MODE_SOURCE_UNSUPPORTED", "gs:// の直接読取りは未対応（file:// を使う。UNRESOLVED）")
    else:
        raise UsageError("OPS_MODE_SOURCE_INVALID", "OPS_MODE_SOURCE の形式が不正")
    try:
        state = canonical.loads_strict(path.read_bytes())
        if not isinstance(state, dict) or state.get("schema_version") != 1 or isinstance(state.get("schema_version"), bool):
            return "UNKNOWN"
        seq = state.get("seq")
        if isinstance(seq, bool) or not isinstance(seq, int) or seq < 0:
            return "UNKNOWN"
        desired = state.get("desired")
        if not isinstance(desired, dict) or desired.get("mode") not in ("NORMAL", "STOPPED"):
            return "UNKNOWN"
        mode = state.get("mode")
        return mode if mode in MODES else "UNKNOWN"
    except (OSError, ValueError, UnicodeDecodeError):
        return "UNKNOWN"
