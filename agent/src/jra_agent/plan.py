"""取得計画（`GET /v1/agent/plan`、CONTRACTS §3.2）の解釈。

`{planId, validUntil, items:[{dataspec, key, option, notBefore, interval}], idle}`。**計画が無い・期限切れ・不正・idle なら、安全に何もしない**。
未確認の JV-Link 仕様に依存する項目（dataspec・option・key の形式）は、サーバが配るかどうかに関わらず、エージェント側でも `SpecRegistry` のゲートで拒否する
（fetcher）。ここでは形式の検証と、いつ実行するか（notBefore・interval・重複起動の防止）だけを扱う。
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import UTC, datetime

from .envelope import REALTIME_DATASPECS, STORED_DATASPECS

MAX_ITEMS = 32
KEY_RE = re.compile(r"[0-9A-Za-z]{1,32}")


@dataclass(frozen=True)
class PlanItem:
    dataspec: str
    key: str | None
    option: int | None
    not_before: datetime | None
    interval: float  # 秒。同じ項目の実行間隔の下限

    @property
    def realtime(self) -> bool:
        return self.dataspec in REALTIME_DATASPECS

    @property
    def ident(self) -> str:
        return f"{self.dataspec}:{self.option if self.option is not None else '-'}:{self.key or '-'}"


@dataclass(frozen=True)
class Plan:
    plan_id: str
    valid_until: datetime | None
    items: tuple[PlanItem, ...]
    idle: bool

    def usable(self, now: datetime) -> bool:
        return not self.idle and bool(self.items) and (self.valid_until is None or now < self.valid_until)


IDLE = Plan("none", None, (), True)


def _time(v: object) -> datetime | None:
    if v is None:
        return None
    if not isinstance(v, str):
        raise ValueError
    dt = datetime.fromisoformat(v.replace("Z", "+00:00"))
    if dt.tzinfo is None:
        raise ValueError
    return dt.astimezone(UTC)


def parse_plan(doc: object) -> Plan:
    """不正な計画は丸ごと破棄して IDLE（一部だけ採用しない）。"""
    try:
        if not isinstance(doc, dict) or set(doc) - {"planId", "validUntil", "items", "idle"}:
            return IDLE
        pid = doc.get("planId")
        items_raw = doc.get("items", [])
        idle = doc.get("idle", False)
        if (
            not isinstance(pid, str)
            or not 1 <= len(pid) <= 64
            or not isinstance(items_raw, list)
            or len(items_raw) > MAX_ITEMS
            or not isinstance(idle, bool)
        ):
            return IDLE
        items: list[PlanItem] = []
        for it in items_raw:
            if not isinstance(it, dict) or set(it) - {"dataspec", "key", "option", "notBefore", "interval"}:
                return IDLE
            ds = it.get("dataspec")
            if ds not in STORED_DATASPECS | REALTIME_DATASPECS:
                return IDLE
            key, opt, interval = it.get("key"), it.get("option"), it.get("interval", 300)
            if key is not None and (not isinstance(key, str) or not KEY_RE.fullmatch(key)):
                return IDLE
            if opt is not None and (isinstance(opt, bool) or not isinstance(opt, int) or not 0 <= opt <= 9):
                return IDLE
            if isinstance(interval, bool) or not isinstance(interval, (int, float)) or not 30 <= interval <= 86400:
                return IDLE
            if ds in REALTIME_DATASPECS and (key is None or opt is not None):
                return IDLE  # 速報は key が要り、option は無い
            if ds in STORED_DATASPECS and opt is None:
                return IDLE
            items.append(PlanItem(str(ds), key, opt, _time(it.get("notBefore")), float(interval)))
        return Plan(pid, _time(doc.get("validUntil")), tuple(items), idle)
    except (ValueError, TypeError):
        return IDLE


@dataclass
class PlanScheduler:
    """いつ何を実行するか。同じ項目を重複して起動しない（実行中の印）。実行の記録はプロセス内（再起動後は `interval` を待たずに再開してよい: 取得は冪等）。"""

    _last_run: dict[str, float] = field(default_factory=dict)
    _running: set[str] = field(default_factory=set)

    def due(self, plan: Plan, now: datetime, mono: float) -> list[PlanItem]:
        if not plan.usable(now):
            return []
        out = []
        for it in plan.items:
            if it.ident in self._running:
                continue
            if it.not_before is not None and now < it.not_before:
                continue
            last = self._last_run.get(it.ident)
            if last is not None and mono - last < it.interval:
                continue
            out.append(it)
        return out

    def start(self, item: PlanItem, mono: float) -> bool:
        if item.ident in self._running:
            return False  # 重複起動を防ぐ
        self._running.add(item.ident)
        self._last_run[item.ident] = mono
        return True

    def finish(self, item: PlanItem) -> None:
        self._running.discard(item.ident)
