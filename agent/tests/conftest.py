from __future__ import annotations

import json
from collections.abc import Callable, Iterator
from dataclasses import dataclass, field
from pathlib import Path

import pytest

from jra_agent.clock import FakeClock
from jra_agent.config import QueueConfig
from jra_agent.errors import TransportError
from jra_agent.queue import OutboxQueue
from jra_agent.transport import TransportResponse


@dataclass
class Call:
    method: str
    url: str
    headers: dict[str, str]
    body: bytes | None


@dataclass
class ScriptedTransport:
    """決めた応答（または通信エラー）を順に返す。呼び出しを記録する。"""

    script: list[TransportResponse | TransportError | Callable[[Call], TransportResponse | TransportError]] = field(default_factory=list)
    calls: list[Call] = field(default_factory=list)
    default: TransportResponse | None = None

    def push(self, *items: TransportResponse | TransportError | Callable[[Call], TransportResponse | TransportError]) -> None:
        self.script.extend(items)

    def request(self, method: str, url: str, headers: dict[str, str], body: bytes | None, timeout: float) -> TransportResponse:
        call = Call(method, url, dict(headers), body)
        self.calls.append(call)
        item = self.script.pop(0) if self.script else self.default
        if item is None:
            raise AssertionError("scripted transport: 想定外の呼び出し")
        if callable(item):
            item = item(call)
        if isinstance(item, TransportError):
            raise item
        return item


def resp(status: int = 200, body: object | bytes = b"", **headers: str) -> TransportResponse:
    raw = body if isinstance(body, bytes) else json.dumps(body).encode()
    return TransportResponse(status, {k.lower().replace("_", "-"): v for k, v in headers.items()}, raw)


def ack_body(
    *, duplicate: bool = False, fetch: dict[str, object] | None = None, cursor: dict[str, str] | None = None, accepted: int = 1
) -> dict[str, object]:
    return {
        "accepted": accepted, "duplicates": 0, "quarantined": 0, "unsupported": 0, "duplicate": duplicate,
        "ackTs": "2026-11-01T00:00:00Z", "serverTime": "2026-11-01T00:00:00Z", "fetch": fetch, "cursor": cursor,
    }  # fmt: skip


@pytest.fixture
def clock() -> FakeClock:
    return FakeClock()


@pytest.fixture
def qpath(tmp_path: Path) -> Path:
    return tmp_path / "q" / "queue.sqlite3"


@pytest.fixture
def queue(qpath: Path, clock: FakeClock) -> Iterator[OutboxQueue]:
    q = OutboxQueue(qpath, clock, QueueConfig(min_free_bytes=0), disk_free=lambda _p: 10**12)
    yield q  # type: ignore[misc]
    q.close()
