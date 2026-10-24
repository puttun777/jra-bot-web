"""エージェントの 1 周（tick）と、常駐ループ。

方針: **NORMAL_OK のときだけ** plan 取得・取得・送信・heartbeat を行う。STOPPED_CONFIRMED / UNKNOWN では、モード確認（ビーコン + /mode）だけ。
STOPPED/UNKNOWN からの復帰は CONTRACTS §7.4 の順序: ① ジッタ（0〜120 秒）待機 → ② plan 取得 → ③ outbox を古い順に最大 2 バッチ/秒で再送 → ④ 空になったら通常取得。
起動直後は必ず UNKNOWN（前回の状態を信用しない）。決定的に動くよう、時計・乱数・待機は注入できる。
"""

from __future__ import annotations

import logging
import random
from collections.abc import Callable
from dataclasses import dataclass, field

from . import __version__
from .api import AgentApi
from .backoff import STOPPED_POLL_SECONDS, UNKNOWN_POLL_STEPS, stepped_delay
from .beacon import BeaconClient, BeaconStatus
from .client import SignedClient
from .clock import Clock, iso_z
from .fetcher import Fetcher, FetchResult
from .modes import Mode, ModeMachine
from .plan import IDLE, Plan, PlanScheduler, parse_plan
from .queue import OutboxQueue
from .sender import Sender, Step

log = logging.getLogger(__name__)

HEARTBEAT_SECONDS = 60.0
PLAN_REFRESH_SECONDS = 300.0
BEACON_NORMAL_SECONDS = 300.0
RESUME_JITTER_MAX = 120.0
SEND_BUDGET_PER_TICK = 20


@dataclass
class TickReport:
    mode: Mode
    reason: str
    sent: int = 0
    fetched: int = 0
    actions: list[str] = field(default_factory=list)
    next_wake: float = 5.0


class AgentRunner:
    def __init__(
        self,
        queue: OutboxQueue,
        modes: ModeMachine,
        beacon: BeaconClient,
        api: AgentApi,
        client: SignedClient,
        sender: Sender,
        fetcher: Fetcher | None,
        clock: Clock,
        rand: Callable[[], float] = random.random,
        disk_free_mb: Callable[[], int | None] = lambda: None,
    ) -> None:
        self._q, self._modes, self._beacon, self._api, self._client, self._sender, self._fetcher, self._clock = (
            queue,
            modes,
            beacon,
            api,
            client,
            sender,
            fetcher,
            clock,
        )
        self._rand = rand
        self._disk_free_mb = disk_free_mb
        self._plan: Plan = IDLE
        self._sched = PlanScheduler()
        self._t_beacon = self._t_probe = self._t_hb = self._t_plan = -1e18
        self._unknown_polls = 0
        self._was_normal = False
        self._resume_at: float | None = None
        self._draining = False
        self.last_error: str | None = None
        self.last_jv: dict[str, object] = {}

    # ------------------------------------------------------------ 補助
    def _due(self, last: float, every: float) -> bool:
        return self._clock.monotonic() - last >= every

    def _poll_beacon(self) -> None:
        r = self._beacon.fetch()
        self._t_beacon = self._clock.monotonic()
        if r.server_date:
            self._client.skew.observe({"date": r.server_date})
        self._modes.on_beacon(r)
        if r.status is BeaconStatus.INVALID:
            self._q.raise_alert("BEACON_INVALID", f"ビーコンを検証できない（{r.reason}）。取得も送信も止めている")
        elif r.status is BeaconStatus.VALID:
            self._q.clear_alert("BEACON_INVALID")

    def _heartbeat_body(self, view_mode: Mode) -> dict[str, object]:
        st = self._q.stats()
        body: dict[str, object] = {
            "agentVersion": __version__,
            "queueDepth": st.by_status.get("QUEUED", 0) + st.by_status.get("RETRY_WAIT", 0) + st.by_status.get("SENDING", 0),
            "mode": {Mode.NORMAL_OK: "NORMAL", Mode.STOPPED_CONFIRMED: "STOPPED", Mode.UNKNOWN: "UNKNOWN"}[view_mode],
        }
        if self._client.skew.last_skew_ms is not None:
            body["clockSkewMs"] = max(-2_000_000_000, min(2_000_000_000, self._client.skew.last_skew_ms))
        if st.oldest_queued_at is not None:
            from datetime import UTC, datetime

            body["oldestQueuedAt"] = iso_z(datetime.fromtimestamp(st.oldest_queued_at, UTC))
        acked = {c.cursor_key.replace(":", "."): c.acked_ts for c in self._q.checkpoints() if c.acked_ts}
        if acked:
            body["ackedTs"] = acked
        jv = {
            k: v for k, v in {**self.last_jv, **(self._fetcher.last_jv if self._fetcher else {})}.items() if isinstance(v, (int, str, bool))
        }
        if jv:
            body["lastJvResult"] = jv
        free = self._disk_free_mb()
        if free is not None:
            body["diskFreeMb"] = free
        alerts = self._q.alerts()
        if alerts:
            body["lastError"] = ",".join(sorted(alerts))[:200]  # 固定語彙の名前だけ
        return body

    # ------------------------------------------------------------ 1 周
    def tick(self) -> TickReport:
        view = self._modes.evaluate()
        rep = TickReport(view.mode, view.reason)

        # ---- ビーコン（モードごとの間隔）
        interval = (
            BEACON_NORMAL_SECONDS
            if view.mode is Mode.NORMAL_OK
            else (
                STOPPED_POLL_SECONDS
                if view.mode is Mode.STOPPED_CONFIRMED
                else stepped_delay(UNKNOWN_POLL_STEPS, self._unknown_polls + 1, rand=self._rand)
            )
        )
        if self._due(self._t_beacon, interval) or self._t_beacon < 0:
            self._poll_beacon()
            rep.actions.append("beacon")
            self._unknown_polls = self._unknown_polls + 1 if self._modes.evaluate().mode is Mode.UNKNOWN else 0
            view = self._modes.evaluate()

        # ---- UNKNOWN で NORMAL ビーコンが有効なら、サーバ（/mode）で確認
        if view.mode is Mode.UNKNOWN and view.reason in ("AWAITING_SERVER_CONFIRMATION",) and self._due(self._t_probe, 5.0):
            self._api.probe_mode()
            self._t_probe = self._clock.monotonic()
            rep.actions.append("probe")
            view = self._modes.evaluate()
        if view.send_paused and self._due(self._t_probe, 30.0):
            self._api.probe_mode()  # 一時障害: 送信を止めたまま、軽い確認だけをバックオフ的に（30 秒おき）
            self._t_probe = self._clock.monotonic()
            view = self._modes.evaluate()

        rep.mode, rep.reason = view.mode, view.reason
        if view.mode is not Mode.NORMAL_OK:
            if self._was_normal:
                self._resume_at = None
                self._draining = False
            self._was_normal = False
            self._plan = IDLE if view.mode is Mode.STOPPED_CONFIRMED else self._plan
            rep.next_wake = 30.0 if view.mode is Mode.UNKNOWN else 300.0
            return rep

        # ---- NORMAL_OK
        if not self._was_normal:
            self._was_normal = True
            self._resume_at = self._clock.monotonic() + self._rand() * RESUME_JITTER_MAX  # ① ジッタ
            self._draining = True
            self._t_plan = -1e18
            rep.actions.append("resume")
        if self._resume_at is not None:
            if self._clock.monotonic() < self._resume_at:
                rep.next_wake = min(5.0, self._resume_at - self._clock.monotonic())
                return rep
            self._resume_at = None

        if self._due(self._t_hb, HEARTBEAT_SECONDS) and not view.send_paused:
            self._api.post_heartbeat(self._heartbeat_body(view.mode))
            self._t_hb = self._clock.monotonic()
            rep.actions.append("heartbeat")
        if self._due(self._t_plan, PLAN_REFRESH_SECONDS) and not view.send_paused:  # ② plan
            r = self._api.get_plan()
            self._t_plan = self._clock.monotonic()
            self._plan = parse_plan(r.json()) if r.ok else IDLE  # 取得できない・不正 = idle（安全側）
            rep.actions.append(f"plan:{'idle' if not self._plan.usable(self._clock.now()) else len(self._plan.items)}")

        # ---- ③ 再送（古い順・最大 2 バッチ/秒）
        for _ in range(SEND_BUDGET_PER_TICK):
            step = self._sender.send_next()
            if step.step is Step.ACKED:
                rep.sent += 1
                continue
            if step.step in (Step.RETRY, Step.DEAD):
                continue
            break
        pending = self._q.stats().by_status
        if self._draining and not (pending.get("QUEUED") or pending.get("RETRY_WAIT") or pending.get("SENDING")):
            self._draining = False  # ④ 空になった = 通常取得へ

        # ---- 取得（再送が空になってから。送信できる状態のときだけ）
        if self._fetcher is not None and not self._draining and self._modes.may_send():
            for item in self._sched.due(self._plan, self._clock.now(), self._clock.monotonic()):
                if not self._sched.start(item, self._clock.monotonic()):
                    continue
                try:
                    report = self._fetcher.fetch_realtime(item) if item.realtime else self._fetcher.fetch_stored(item)
                finally:
                    self._sched.finish(item)
                rep.actions.append(f"fetch:{item.ident}:{report.result.value}")
                if report.result is FetchResult.COMPLETED:
                    rep.fetched += report.records
                if not self._modes.may_fetch():
                    break
        rep.next_wake = 1.0 if (rep.sent or rep.fetched) else 5.0
        self.last_jv = dict(self._fetcher.last_jv) if self._fetcher else {}
        return rep

    def run_forever(self, should_stop: Callable[[], bool]) -> None:
        self._q.recover()
        while not should_stop():
            try:
                rep = self.tick()
            except Exception as e:
                self.last_error = type(e).__name__
                log.error("tick が失敗: %s", type(e).__name__)
                self._clock.sleep(5.0)
                continue
            self._clock.sleep(min(rep.next_wake, 5.0))
