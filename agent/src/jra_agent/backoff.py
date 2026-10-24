"""指数バックオフ + ジッタ。上限つき（CONTRACTS §7 / JVLINK_DESIGN §5.4: 1s→2s→…最大 15 分、ジッタ ±20%）。"""

from __future__ import annotations

import random
from collections.abc import Callable
from dataclasses import dataclass


@dataclass(frozen=True)
class Backoff:
    base: float = 1.0
    factor: float = 2.0
    cap: float = 900.0
    jitter: float = 0.2

    def delay(self, attempt: int, rand: Callable[[], float] = random.random) -> float:
        """`attempt` は 1 から。ジッタは ±jitter の一様乱数（`rand()` は 0〜1）。上限を超えない。"""
        if attempt < 1:
            raise ValueError("attempt は 1 以上")
        raw = min(self.cap, self.base * (self.factor ** (attempt - 1)))
        spread = (rand() * 2.0 - 1.0) * self.jitter
        return max(0.0, min(self.cap, raw * (1.0 + spread)))


# 状態確認（UNKNOWN 中のビーコン確認）: 5 分→15 分→60 分で頭打ち、ジッタ ±20%（CONTRACTS §7.3）
UNKNOWN_POLL_STEPS = (300.0, 900.0, 3600.0)
STOPPED_POLL_SECONDS = 3600.0


def stepped_delay(steps: tuple[float, ...], attempt: int, jitter: float = 0.2, rand: Callable[[], float] = random.random) -> float:
    base = steps[min(max(attempt, 1), len(steps)) - 1]
    return base * (1.0 + (rand() * 2.0 - 1.0) * jitter)
