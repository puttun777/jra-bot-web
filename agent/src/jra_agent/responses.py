"""ingest 応答の分類（CONTRACTS §3.6）。HTTP の状態コード・`X-Ops-Mode`・`Retry-After`・エラーコードから、エージェントの動作を決める。

| 分類 | 状態 | 動作 |
|---|---|---|
| OK | 200 | ACK（必要な場合だけカーソルを前進） |
| DEAD | 400/413/422 | 不正データ（バグ）。DEAD にして自動再送を止める |
| AUTH | 401/403 | 送信停止 + ローカルアラート。自動で再試行しない |
| RETRY_CONFLICT | 409 | Retry-After に従い**同じバッチ**を再送 |
| THROTTLED | 429 | Retry-After に従い減速（**サーバ側は未実装。契約上の分類のみ**: UNRESOLVED G-12） |
| OPS_NOT_NORMAL | 503 + X-Ops-Mode ≠ NORMAL | 送信・取得を止める（モード待機） |
| OPS_UNKNOWN | 503（ヘッダなし）で OPS_MODE_UNKNOWN / その他の判別不能な 502/503/504 | UNKNOWN（安全側） |
| TRANSIENT_DB | 503 + Retry-After + UNAVAILABLE | 同一バッチで再送（モードは NORMAL のまま） |
| UNREACHABLE | タイムアウト・接続不可 | UNKNOWN の判定材料。同一バッチを保持 |
| SERVER_ERROR | 500 ほか 5xx | 指数バックオフ（上限あり） |
| UNEXPECTED | 3xx・想定外の 4xx | 異常。DEAD にはせず再試行（上限あり）、アラート |
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from enum import Enum

from .transport import TransportResponse

NON_NORMAL_MODES = frozenset({"STARTING", "STOPPING", "STOPPED"})


class Outcome(Enum):
    OK = "OK"
    DEAD = "DEAD"
    AUTH = "AUTH"
    RETRY_CONFLICT = "RETRY_CONFLICT"
    THROTTLED = "THROTTLED"
    OPS_NOT_NORMAL = "OPS_NOT_NORMAL"
    OPS_UNKNOWN = "OPS_UNKNOWN"
    TRANSIENT_DB = "TRANSIENT_DB"
    UNREACHABLE = "UNREACHABLE"
    SERVER_ERROR = "SERVER_ERROR"
    UNEXPECTED = "UNEXPECTED"


@dataclass(frozen=True)
class Classified:
    outcome: Outcome
    status: int | None
    code: str | None = None  # サーバのエラーコード（固定語彙。本文の自由記述ではない）
    retry_after: float | None = None
    ops_mode: str | None = None

    @property
    def fetch_level_dead(self) -> bool:
        """取得セッション全体が無効になるコード（以降の連番も受け付けられない）。"""
        return self.outcome is Outcome.DEAD and (self.code or "").startswith("FETCH_")


def parse_error_code(body: bytes) -> str | None:
    try:
        doc = json.loads(body)
    except ValueError:
        return None
    code = doc.get("error") if isinstance(doc, dict) else None
    if isinstance(code, str) and 0 < len(code) <= 64 and code.replace("_", "").isalnum():
        return code
    return None


def parse_retry_after(value: str | None, cap: float = 900.0) -> float | None:
    if value is None:
        return None
    try:
        seconds = float(value.strip())
    except ValueError:
        return None  # HTTP-date 形式は使わない（サーバは秒数を返す）
    if seconds != seconds or seconds < 0:  # noqa: PLR0124 - NaN
        return None
    return min(seconds, cap)


def classify(resp: TransportResponse) -> Classified:
    status = resp.status
    code = parse_error_code(resp.body) if status >= 400 else None
    retry_after = parse_retry_after(resp.headers.get("retry-after"))
    ops_mode = resp.headers.get("x-ops-mode", "").strip().upper() or None
    if status == 200:
        return Classified(Outcome.OK, status)
    if status in (400, 413, 422):
        return Classified(Outcome.DEAD, status, code)
    if status in (401, 403):
        return Classified(Outcome.AUTH, status, code)
    if status == 409:
        return Classified(Outcome.RETRY_CONFLICT, status, code, retry_after)
    if status == 429:
        return Classified(Outcome.THROTTLED, status, code, retry_after)
    if status == 503:
        if ops_mode in NON_NORMAL_MODES:
            return Classified(Outcome.OPS_NOT_NORMAL, status, code, retry_after, ops_mode)
        if ops_mode == "NORMAL":
            # NORMAL なのに 503 → 一時障害（モードは確定していない扱いで保守的に）
            return Classified(Outcome.TRANSIENT_DB, status, code, retry_after, ops_mode)
        if code == "UNAVAILABLE" and retry_after is not None:
            return Classified(Outcome.TRANSIENT_DB, status, code, retry_after)
        return Classified(Outcome.OPS_UNKNOWN, status, code, retry_after)  # OPS_MODE_UNKNOWN・ヘッダなしは区別できない = 安全側
    if status in (502, 504):
        return Classified(Outcome.OPS_UNKNOWN, status, code, retry_after)
    if 500 <= status < 600:
        return Classified(Outcome.SERVER_ERROR, status, code, retry_after)
    return Classified(Outcome.UNEXPECTED, status, code)
