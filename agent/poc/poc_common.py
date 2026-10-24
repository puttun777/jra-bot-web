"""PoC の共通部品: 集計（件数・バイト長・エラーコード・所要時間）と、結果 JSON の書出し。**レコードの内容は一切持たない**。"""

from __future__ import annotations

import json
import platform
import statistics
import struct
import sys
import time
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from jra_agent import __version__

GATES = ("G-AGENT-1", "G-AGENT-2", "G-AGENT-3", "G-AGENT-4", "G-AGENT-5", "G-AGENT-6")


@dataclass
class Aggregate:
    """長さ・コード・時間の集計。内容（バイト列そのもの）は受け取っても保持しない。"""

    count: int = 0
    total_bytes: int = 0
    lengths: list[int] = field(default_factory=list)
    by_code: Counter[str] = field(default_factory=Counter)
    by_record_id: Counter[str] = field(default_factory=Counter)  # 先頭 2 文字の種別 ID（RA・SE など）だけ。内容ではない
    durations_ms: list[float] = field(default_factory=list)

    def add_record(self, data: bytes) -> None:
        self.count += 1
        self.total_bytes += len(data)
        self.lengths.append(len(data))
        self.by_record_id[data[:2].decode("ascii", errors="replace")] += 1

    def add_code(self, code: int | str) -> None:
        self.by_code[str(code)] += 1

    def add_duration(self, seconds: float) -> None:
        self.durations_ms.append(seconds * 1000)

    def summary(self) -> dict[str, Any]:
        out: dict[str, Any] = {
            "records": self.count,
            "total_bytes": self.total_bytes,
            "codes": dict(self.by_code),
            "record_ids": dict(self.by_record_id),
        }
        if self.lengths:
            out["length"] = {"min": min(self.lengths), "max": max(self.lengths), "mean": round(statistics.fmean(self.lengths), 1)}
            buckets: Counter[str] = Counter()
            for n in self.lengths:
                buckets[f"<= {next(b for b in (64, 256, 1024, 4096, 16384, 65536, 10**9) if n <= b)}"] += 1
            out["length_buckets"] = dict(buckets)
        if self.durations_ms:
            s = sorted(self.durations_ms)
            out["duration_ms"] = {"count": len(s), "p50": round(s[len(s) // 2], 1), "max": round(s[-1], 1), "total": round(sum(s), 1)}
        return out


@dataclass
class GateResult:
    gate: str
    status: str  # PASS / FAIL / NOT_RUN / MOCK_PASS（Mock の合格 = 実機の合格ではない）
    reason: str = ""
    metrics: dict[str, Any] = field(default_factory=dict)

    def as_dict(self) -> dict[str, Any]:
        return {"gate": self.gate, "status": self.status, "reason": self.reason, "metrics": self.metrics}


def environment() -> dict[str, Any]:
    return {
        "agent_version": __version__, "python": platform.python_version(), "python_bits": struct.calcsize("P") * 8, "os": platform.system(),
        "os_release": platform.release(), "machine": platform.machine(),
    }  # fmt: skip


class Timer:
    def __enter__(self) -> Timer:
        self.t0 = time.perf_counter()
        return self

    def __exit__(self, *_: object) -> None:
        self.seconds = time.perf_counter() - self.t0


def write_report(path: Path | None, mode: str, results: list[GateResult], extra: dict[str, Any] | None = None) -> dict[str, Any]:
    doc = {
        "poc": "jra-agent T3.0", "mode": mode, "environment": environment(), "gates": [r.as_dict() for r in results],
        "note": "集計のみ。レコードの内容・利用キー・トークンを含まない。Mock の合格は実機の合格ではない。", **(extra or {}),
    }  # fmt: skip
    text = json.dumps(doc, ensure_ascii=False, indent=2)
    if path is not None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text + "\n", encoding="utf-8")
    sys.stdout.write(text + "\n")
    return doc
