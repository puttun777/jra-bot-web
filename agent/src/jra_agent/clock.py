"""時計の抽象。テストでは [`FakeClock`] で時間を進め、実際には待たない。"""

from __future__ import annotations

import time
from datetime import UTC, datetime
from typing import Protocol


class Clock(Protocol):
    def now(self) -> datetime:
        """UTC の現在時刻（tz 付き）。"""

    def monotonic(self) -> float:
        """単調増加の秒（経過時間の測定用）。"""

    def sleep(self, seconds: float) -> None: ...


class SystemClock:
    def now(self) -> datetime:
        return datetime.now(UTC)

    def monotonic(self) -> float:
        return time.monotonic()

    def sleep(self, seconds: float) -> None:
        if seconds > 0:
            time.sleep(seconds)


class FakeClock:
    """テスト用。`sleep` は時刻を進めるだけで待たない。"""

    def __init__(self, start: datetime | None = None) -> None:
        self._now = start or datetime(2026, 11, 1, 0, 0, 0, tzinfo=UTC)
        self._mono = 1000.0
        self.slept: list[float] = []

    def now(self) -> datetime:
        return self._now

    def monotonic(self) -> float:
        return self._mono

    def sleep(self, seconds: float) -> None:
        self.slept.append(seconds)
        self.advance(seconds)

    def advance(self, seconds: float) -> None:
        from datetime import timedelta

        self._now += timedelta(seconds=seconds)
        self._mono += seconds

    def set(self, when: datetime) -> None:
        self._now = when


def to_unix(dt: datetime) -> int:
    return int(dt.timestamp())


def iso_z(dt: datetime) -> str:
    """封筒の `observedAt` の形（UTC・Z・ミリ秒）。"""
    return dt.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%S.") + f"{dt.microsecond // 1000:03d}Z"
