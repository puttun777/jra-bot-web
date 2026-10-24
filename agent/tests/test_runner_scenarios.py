"""通し（Fake サーバ + Fake ビーコン + Fake JV-Link の子プロセス）: 通常取得 → STOPPING → STOPPED → PC 再起動 → NORMAL 復帰 → 再送 → 取得再開。"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

from jra_agent.api import AgentApi
from jra_agent.beacon import BeaconClient, BeaconGuard
from jra_agent.client import SignedClient
from jra_agent.clock import FakeClock
from jra_agent.config import BeaconConfig, QueueConfig, SenderConfig, WorkerConfig
from jra_agent.fetcher import Fetcher
from jra_agent.jvlink.fake import FakeScript
from jra_agent.jvlink.spec import SpecRegistry
from jra_agent.jvlink.supervisor import WorkerSupervisor
from jra_agent.modes import Mode, ModeMachine
from jra_agent.queue import OutboxQueue
from jra_agent.runner import AgentRunner, TickReport
from jra_agent.sender import Sender
from jra_agent.signing import RequestSigner
from tests.beacon_support import BeaconSigner
from tests.fake_server import BASE, BEACON_URL, FakeCloud
from tests.helpers import enqueue_realtime

TOKEN = "jrat1_scenario-token-0123456789"
RECS = [f"RA{i:04d}-synthetic".encode("cp932") for i in range(4)]
PLAN: dict[str, object] = {
    "planId": "p1",
    "validUntil": None,
    "idle": False,
    "items": [{"dataspec": "RACE", "option": 2, "interval": 3600}],
}


class Agent:
    """1 回の起動（= 1 プロセス）に相当。`new_agent()` を呼び直すと、PC 再起動後の起動を模擬する（キューの DB ファイルは同じ）。"""

    def __init__(self, tmp: Path, cloud: FakeCloud, clock: FakeClock, script: FakeScript, spec: SpecRegistry | None = None) -> None:
        self.queue = OutboxQueue(tmp / "q.sqlite3", clock, QueueConfig(min_free_bytes=0, max_batch_records=3), disk_free=lambda _p: 10**12)
        self.recovered = self.queue.recover()
        sp = tmp / "script.json"
        sp.write_text(script.to_json(), encoding="utf-8")
        self.sup = WorkerSupervisor(
            [sys.executable, "-m", "jra_agent.jvlink.worker_main", "--backend", "fake", "--script", str(sp)],
            WorkerConfig(call_timeout_seconds=5.0),
            clock,
        )
        self.modes = ModeMachine(clock)  # 起動直後は UNKNOWN
        client = SignedClient(BASE, RequestSigner("ag_test", TOKEN), cloud, clock)  # type: ignore[arg-type]
        api = AgentApi(client, self.modes)
        guard = BeaconGuard(self.queue.get_state, self.queue.set_state)
        beacon = BeaconClient(BeaconConfig(BEACON_URL, (cloud.signer.public,)), cloud, clock, guard)  # type: ignore[arg-type]
        sender = Sender(self.queue, client, self.modes, SenderConfig(), clock, "fp", rand=lambda: 0.5)
        fetcher = Fetcher(
            "ag_test",
            self.queue,
            self.sup,
            clock,
            spec or SpecRegistry.all_confirmed_for_tests(),
            QueueConfig(min_free_bytes=0, max_batch_records=3),
            self.modes.may_fetch,
        )
        self.runner = AgentRunner(self.queue, self.modes, beacon, api, client, sender, fetcher, clock, rand=lambda: 0.0)

    def run(self, ticks: int, step: float = 6.0, clock: FakeClock | None = None) -> list[TickReport]:
        out: list[TickReport] = []
        for _ in range(ticks):
            out.append(self.runner.tick())
            (clock or self.runner._clock).advance(step)  # type: ignore[attr-defined]
        return out

    def close(self) -> None:
        self.sup.stop()
        self.queue.close()


@pytest.fixture
def world(tmp_path: Path):  # type: ignore[no-untyped-def]
    clock = FakeClock()
    cloud = FakeCloud(clock, TOKEN, BeaconSigner())
    cloud.plan = PLAN
    agents: list[Agent] = []

    def new_agent(script: FakeScript | None = None, **kw: object) -> Agent:
        a = Agent(tmp_path, cloud, clock, script or FakeScript(files=[RECS]), **kw)  # type: ignore[arg-type]
        agents.append(a)
        return a

    yield clock, cloud, new_agent
    for a in agents:
        a.close()


def test_normal_flow_fetch_send_ack_and_cursor(world) -> None:  # type: ignore[no-untyped-def]
    clock, cloud, new_agent = world
    a = new_agent()
    assert a.modes.evaluate().mode is Mode.UNKNOWN and a.runner.tick().mode in (Mode.UNKNOWN, Mode.NORMAL_OK)  # 起動直後は UNKNOWN から
    a.run(8)
    assert a.modes.evaluate().mode is Mode.NORMAL_OK
    assert cloud.heartbeats >= 1 and any(r == ("GET", "/v1/agent/plan") for r in cloud.requests)
    assert len(cloud.batches) == 2 and sum(len(b["records"]) for b in cloud.batches.values()) == 4  # type: ignore[arg-type]
    cp = a.queue.checkpoint("RACE:2")
    assert cp.acked_ts == "20261031200000" == cloud.cursors["RACE:2"]  # サーバの cursor で前進
    assert a.queue.verify() == [] and a.queue.stats().by_status.get("ACKED") == 2


def test_idle_plan_fetches_nothing(world) -> None:  # type: ignore[no-untyped-def]
    clock, cloud, new_agent = world
    cloud.plan = {"planId": "p0", "validUntil": None, "items": [], "idle": True}
    a = new_agent()
    a.run(8)
    assert cloud.batches == {} and a.sup._h is None  # ワーカーも起動しない


def test_stopping_detected_stop_at_boundary_keep_queue_then_reboot_and_resume(world) -> None:  # type: ignore[no-untyped-def]
    clock, cloud, new_agent = world
    a = new_agent()
    a.run(8)
    assert a.modes.evaluate().mode is Mode.NORMAL_OK
    # 速報が溜まった状態で STOPPING → STOPPED
    for _ in range(3):
        enqueue_realtime(a.queue)
    cloud.set_mode("STOPPING")
    sent_before = len(cloud.batches)
    rep = a.runner.tick()  # 次の周期で ingest/ビーコンが STOPPING を知らせる
    a.run(3)
    cloud.set_mode("STOPPED")
    a.run(3)
    assert a.modes.evaluate().mode is Mode.STOPPED_CONFIRMED and not a.modes.may_fetch() and not a.modes.may_send()
    assert len(cloud.batches) in (sent_before, sent_before + 1)  # STOPPED になってからは 1 つも増えない
    frozen = len(cloud.batches)
    pending = a.queue.stats().by_status.get("QUEUED", 0)
    a.run(5)
    assert len(cloud.batches) == frozen and rep.mode is not None
    a.close()

    # ---- PC 再起動: STOPPED のまま自動起動。送信せず待機（古い NORMAL を引きずらない）
    b = new_agent()
    assert b.modes.evaluate().mode is Mode.UNKNOWN  # 起動直後は UNKNOWN
    assert b.recovered["sending_requeued"] == 0
    b.run(6)
    assert b.modes.evaluate().mode is Mode.STOPPED_CONFIRMED
    assert len(cloud.batches) == frozen and b.queue.stats().by_status.get("QUEUED", 0) >= pending  # キューは保持
    n_requests_stopped = len([r for r in cloud.requests if r[1] == "/v1/agent/ingest"])
    b.run(10)
    assert len([r for r in cloud.requests if r[1] == "/v1/agent/ingest"]) == n_requests_stopped  # 送信を試みない

    # ---- NORMAL に戻る → 状態再判定 → 未送信キューを再送 → 新規取得再開
    cloud.set_mode("NORMAL")
    clock.advance(3600)  # STOPPED の確認間隔（60 分）
    b.run(30)
    assert b.modes.evaluate().mode is Mode.NORMAL_OK
    assert b.queue.stats().by_status.get("QUEUED", 0) == 0 and b.queue.stats().by_status.get("RETRY_WAIT", 0) == 0
    assert any(x["class"] == "R" for x in cloud.batches.values())  # 溜めた速報が届いた
    assert b.queue.verify() == []


def test_stopped_beacon_blocks_even_if_server_would_accept(world) -> None:  # type: ignore[no-untyped-def]
    clock, cloud, new_agent = world
    cloud.set_mode("STOPPED")
    cloud.mode = "STOPPED"
    a = new_agent()
    enqueue_realtime(a.queue)
    a.run(6)
    assert a.modes.evaluate().mode is Mode.STOPPED_CONFIRMED
    assert [r for r in cloud.requests if r[1] == "/v1/agent/ingest"] == []
    assert not a.modes.may_fetch() and a.sup._h is None  # JVOpen を呼ばない（ワーカーも起動しない）


def test_unknown_when_nothing_reachable_then_recovers(world) -> None:  # type: ignore[no-untyped-def]
    clock, cloud, new_agent = world
    cloud.plan = {"planId": "p0", "validUntil": None, "items": [], "idle": True}
    cloud.beacon_reachable = False
    cloud.ingest_reachable = False
    a = new_agent()
    enqueue_realtime(a.queue)
    a.run(5, step=60.0)
    assert a.modes.evaluate().mode is Mode.UNKNOWN and a.queue.stats().by_status.get("QUEUED") == 1 and not a.modes.may_send()
    cloud.beacon_reachable = cloud.ingest_reachable = True
    clock.advance(4000)
    a.run(30)
    assert a.modes.evaluate().mode is Mode.NORMAL_OK and a.queue.stats().by_status.get("ACKED") == 1


def test_replayed_old_normal_beacon_after_stop_is_rejected_and_agent_stays_stopped_or_unknown(world) -> None:  # type: ignore[no-untyped-def]
    clock, cloud, new_agent = world
    a = new_agent()
    a.run(6)
    old = cloud.signer.doc(mode="NORMAL", seq=cloud.beacon_seq, updated_at=clock.now().strftime("%Y-%m-%dT%H:%M:%SZ"))
    cloud.set_mode("STOPPED")
    a.run(2, step=3700.0)
    assert a.modes.evaluate().mode is Mode.STOPPED_CONFIRMED
    cloud.stale_beacon_doc = old  # 攻撃者/キャッシュが古い NORMAL（署名は有効）を返す
    a.run(2, step=3700.0)
    assert a.modes.evaluate().mode is not Mode.NORMAL_OK and "BEACON_INVALID" in a.queue.alerts()


def test_disconnect_right_before_ack_resends_same_batch_and_cursor_via_duplicate(world) -> None:  # type: ignore[no-untyped-def]
    clock, cloud, new_agent = world
    a = new_agent(FakeScript(files=[RECS[:2]]))
    a.run(4)
    cloud.lose_next_ack = True  # 最初のバッチは保存されるが ACK が届かない
    a.run(12)
    ids = list(cloud.batches)
    assert len(ids) == len(set(ids))  # 同じ batchId の再送は二重登録にならない（サーバが重複で吸収）
    sent = [b for b in a.queue.batches()]
    assert all(b.status == "ACKED" for b in sent)


def test_worker_hang_during_scenario_recovers_next_cycle(world) -> None:  # type: ignore[no-untyped-def]
    clock, cloud, new_agent = world
    a = new_agent(FakeScript(files=[RECS], hang_after=2))
    a.sup._cfg = WorkerConfig(call_timeout_seconds=1.0, max_restarts=10)
    a.run(6)
    assert a.sup.timeouts >= 1
    assert a.queue.checkpoint("RACE:2").acked_ts is None and a.queue.checkpoint("RACE:2").fetched_ts is None
    assert a.modes.evaluate().mode is Mode.NORMAL_OK  # ワーカーの問題でモードや送信は止まらない
