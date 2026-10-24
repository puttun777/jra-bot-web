"""モードビーコン（CONTRACTS §7.2）の取得と検証。

- HTTPS GET の静的 JSON `{schema_version, mode, seq, updatedAt, validUntil, kid, sig}`。
- `sig` は Ed25519。署名対象は `sig` を除いたフィールドを**キーの辞書順・空白なし**で直列化した JSON の UTF-8。公開鍵は `kid` で選ぶ（設定に最大 2 本）。
- `seq` は単調増加。**巻き戻りは拒否**（再起動後も、永続化した最後の seq と比較する）。同じ seq で mode が違うものも拒否。
- 検証を省略する設定は存在しない。検証に失敗したビーコンは**無視して稼働するのではなく**、UNKNOWN（取得も送信も止める）として扱う。
"""

from __future__ import annotations

import base64
import json
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from enum import Enum

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

from .clock import Clock
from .config import BeaconConfig, PublicKey
from .errors import TransportError, VerificationError
from .transport import Transport

MODES = frozenset({"NORMAL", "STARTING", "STOPPING", "STOPPED"})
_KEYS = {"schema_version", "mode", "seq", "updatedAt", "validUntil", "kid", "sig"}
FUTURE_TOLERANCE = timedelta(minutes=10)
MAX_BEACON_BYTES = 4096


def canonical_bytes(doc: dict[str, object]) -> bytes:
    """`sig` を除き、キーの辞書順・空白なしで直列化（値は文字列・整数・null のみ。RFC 8785 と同じ結果になる）。"""
    body = {k: v for k, v in doc.items() if k != "sig"}
    return json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def b64url_decode(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


@dataclass(frozen=True)
class Beacon:
    mode: str
    seq: int
    kid: str
    updated_at: datetime
    valid_until: datetime | None

    @property
    def normal(self) -> bool:
        return self.mode == "NORMAL"


def _parse_time(value: object, reason: str) -> datetime:
    if not isinstance(value, str):
        raise VerificationError(reason)
    try:
        dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as e:
        raise VerificationError(reason) from e
    if dt.tzinfo is None:
        raise VerificationError(reason)
    return dt.astimezone(UTC)


def verify_beacon(doc: object, keys: tuple[PublicKey, ...], now: datetime) -> Beacon:
    """署名・形式・時刻を検証する（seq の単調性は呼び出し側 [`BeaconGuard`] が永続した値と比較する）。失敗は [`VerificationError`]（理由は固定語彙）。"""
    if not isinstance(doc, dict) or set(doc) != _KEYS:
        raise VerificationError("BAD_SHAPE")
    if doc["schema_version"] != 1 or isinstance(doc["schema_version"], bool):
        raise VerificationError("BAD_SCHEMA_VERSION")
    kid, sig = doc["kid"], doc["sig"]
    if not isinstance(kid, str) or not isinstance(sig, str):
        raise VerificationError("BAD_SHAPE")
    key = next((k for k in keys if k.kid == kid), None)
    if key is None:
        raise VerificationError("UNKNOWN_KID")
    try:
        raw_sig = b64url_decode(sig)
        Ed25519PublicKey.from_public_bytes(key.raw).verify(raw_sig, canonical_bytes(doc))
    except (InvalidSignature, ValueError) as e:
        raise VerificationError("BAD_SIGNATURE") from e
    # ここから先は署名済みの内容
    mode, seq = doc["mode"], doc["seq"]
    if mode not in MODES:
        raise VerificationError("BAD_MODE")
    if isinstance(seq, bool) or not isinstance(seq, int) or seq < 0:
        raise VerificationError("BAD_SEQ")
    updated = _parse_time(doc["updatedAt"], "BAD_UPDATED_AT")
    until = None if doc["validUntil"] is None else _parse_time(doc["validUntil"], "BAD_VALID_UNTIL")
    if updated > now + FUTURE_TOLERANCE:
        raise VerificationError("UPDATED_IN_FUTURE")  # 署名済みなのに未来 = ローカル時計の異常 or 異常な発行。いずれも安全側
    if until is not None and mode == "NORMAL" and now > until:
        raise VerificationError("EXPIRED")
    return Beacon(str(mode), int(seq), kid, updated, until)


class BeaconGuard:
    """seq の巻き戻り拒否。最後に受け入れた (seq, mode) を永続し、再起動後も比較する。"""

    SEQ_KEY, MODE_KEY = "beacon_seq", "beacon_mode"

    def __init__(self, get_state, set_state) -> None:  # type: ignore[no-untyped-def]
        self._get, self._set = get_state, set_state

    def last(self) -> tuple[int, str] | None:
        s, m = self._get(self.SEQ_KEY), self._get(self.MODE_KEY)
        return None if not s or not m else (int(s), m)

    def check_and_accept(self, beacon: Beacon) -> None:
        last = self.last()
        if last is not None:
            if beacon.seq < last[0]:
                raise VerificationError("SEQ_ROLLBACK")
            if beacon.seq == last[0] and beacon.mode != last[1]:
                raise VerificationError("SEQ_CONFLICT")
        self._set(self.SEQ_KEY, str(beacon.seq))
        self._set(self.MODE_KEY, beacon.mode)

    def reset(self) -> None:
        """利用者の明示操作だけ（ops-state を作り直したときなど）。"""
        self._set(self.SEQ_KEY, "")
        self._set(self.MODE_KEY, "")


class BeaconStatus(Enum):
    VALID = "VALID"
    UNREACHABLE = "UNREACHABLE"
    INVALID = "INVALID"


@dataclass(frozen=True)
class BeaconResult:
    status: BeaconStatus
    beacon: Beacon | None = None
    reason: str | None = None
    server_date: str | None = None  # 時計ずれの測定用に Date を持ち帰る


class BeaconClient:
    def __init__(self, cfg: BeaconConfig, transport: Transport, clock: Clock, guard: BeaconGuard, timeout: float = 15.0) -> None:
        self._cfg, self._transport, self._clock, self._guard, self._timeout = cfg, transport, clock, guard, timeout

    def fetch(self) -> BeaconResult:
        try:
            resp = self._transport.request(
                "GET", self._cfg.url, {"Accept": "application/json", "Cache-Control": "no-cache"}, None, self._timeout
            )
        except TransportError as e:
            return BeaconResult(BeaconStatus.UNREACHABLE, reason=e.kind)
        date = resp.headers.get("date")
        if resp.status != 200:
            return BeaconResult(BeaconStatus.UNREACHABLE, reason=f"HTTP_{resp.status}", server_date=date)
        if len(resp.body) > MAX_BEACON_BYTES:
            return BeaconResult(BeaconStatus.INVALID, reason="TOO_LARGE", server_date=date)
        try:
            doc = json.loads(resp.body)
        except ValueError:
            return BeaconResult(BeaconStatus.INVALID, reason="BAD_JSON", server_date=date)
        try:
            beacon = verify_beacon(doc, self._cfg.keys, self._clock.now())
            self._guard.check_and_accept(beacon)
        except VerificationError as e:
            return BeaconResult(BeaconStatus.INVALID, reason=e.reason, server_date=date)
        return BeaconResult(BeaconStatus.VALID, beacon, server_date=date)
