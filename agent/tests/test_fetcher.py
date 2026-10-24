"""取得 → キュー（Fake JV-Link を別プロセスのワーカー経由で）。EOF・中断・ゲート・重複起動・取得セッションの seq。"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

from jra_agent.clock import FakeClock
from jra_agent.config import QueueConfig, WorkerConfig
from jra_agent.fetcher import Fetcher, FetchResult, cursor_key, fromtime_from
from jra_agent.jvlink.fake import FakeScript
from jra_agent.jvlink.spec import DEFAULT_REGISTRY, SpecRegistry
from jra_agent.jvlink.supervisor import WorkerSupervisor
from jra_agent.plan import PlanItem
from jra_agent.queue import OutboxQueue

RECS = [f"RA{i:04d}".encode("cp932") + b"\x93\xfa" * 5 for i in range(7)]  # cp932 の 2 バイト文字を含む合成データ
ITEM = PlanItem("RACE", None, 2, None, 300.0)


class Rig:
    def __init__(
        self, tmp: Path, queue: OutboxQueue, script: FakeScript, qcfg: QueueConfig | None = None, call_timeout: float = 5.0
    ) -> None:
        p = tmp / "s.json"
        p.write_text(script.to_json(), encoding="utf-8")
        argv = [sys.executable, "-m", "jra_agent.jvlink.worker_main", "--backend", "fake", "--script", str(p)]
        self.clock = FakeClock()
        self.sup = WorkerSupervisor(argv, WorkerConfig(call_timeout_seconds=call_timeout, max_restarts=10), self.clock)
        self.mode_ok = True
        self.queue = queue
        self.fetcher = Fetcher(
            "ag_test",
            queue,
            self.sup,
            self.clock,
            SpecRegistry.all_confirmed_for_tests(),
            qcfg or QueueConfig(max_batch_records=3, min_free_bytes=0),
            lambda: self.mode_ok,
        )

    def close(self) -> None:
        self.sup.stop()


@pytest.fixture
def rig(tmp_path: Path, queue: OutboxQueue):  # type: ignore[no-untyped-def]
    made: list[Rig] = []

    def make(script: FakeScript, **kw: object) -> Rig:
        r = Rig(tmp_path, queue, script, **kw)  # type: ignore[arg-type]
        made.append(r)
        return r

    yield make
    for r in made:
        r.close()


def test_fetch_reads_all_records_into_contiguous_fetch_batches(rig, queue: OutboxQueue) -> None:  # type: ignore[no-untyped-def]
    r = rig(FakeScript(files=[RECS[:4], RECS[4:]]))
    rep = r.fetcher.fetch_stored(ITEM)
    assert rep.result is FetchResult.COMPLETED and rep.records == 7
    batches = queue.batches()
    assert [b.fetch_seq for b in batches] == list(range(len(batches)))  # fetch.seq は 0 から連続
    docs = [json.loads(b.payload) for b in batches]
    assert [d["eof"] for d in docs] == [False] * (len(batches) - 1) + [True]
    assert sum(len(d["records"]) for d in docs) == 7 and all(len(d["records"]) <= 3 for d in docs)
    assert {d["fetch"]["id"] for d in docs} == {batches[0].fetch_id} and all(d["lastFileTimestamp"] == "20261031200000" for d in docs)
    assert all("tenantId" not in d for d in docs) and docs[0]["option"] == 2 and docs[0]["dataspec"] == "RACE"
    import base64

    assert [base64.b64decode(rec["bytesB64"]) for d in docs for rec in d["records"]] == RECS  # cp932 のバイト列が欠けずに順番どおり
    cp = queue.checkpoint("RACE:2")
    assert (cp.fetched_ts, cp.queued_ts, cp.acked_ts) == ("20261031200000",) * 2 + (None,)
    assert queue.verify() == []


def test_unconfirmed_spec_blocks_real_fetch_by_default(tmp_path: Path, queue: OutboxQueue) -> None:
    r = Rig(tmp_path, queue, FakeScript(files=[RECS]))
    r.fetcher._spec = DEFAULT_REGISTRY  # 本番の既定: 公式仕様の確認項目が 0 件
    try:
        rep = r.fetcher.fetch_stored(ITEM)
        assert rep.result is FetchResult.SKIPPED and rep.reason is not None and rep.reason.startswith("SPEC_UNCONFIRMED")
        assert r.fetcher.fetch_realtime(PlanItem("0B12", "20261101", None, None, 60.0)).result is FetchResult.SKIPPED
        assert queue.batches() == [] and "SPEC_UNCONFIRMED" in queue.alerts() and r.sup._h is None  # ワーカーも起動しない
    finally:
        r.close()


def test_not_normal_mode_does_not_start_fetch(rig, queue: OutboxQueue) -> None:  # type: ignore[no-untyped-def]
    r = rig(FakeScript(files=[RECS]))
    r.mode_ok = False
    assert r.fetcher.fetch_stored(ITEM).reason == "MODE_NOT_NORMAL" and queue.batches() == [] and r.sup._h is None


def test_duplicate_fetch_is_prevented(rig, queue: OutboxQueue) -> None:  # type: ignore[no-untyped-def]
    r = rig(FakeScript(files=[RECS]))
    queue.begin_session("019a0000-0000-7000-8000-0000000000aa", "RACE:2", "RACE", 2, None, None)
    assert r.fetcher.fetch_stored(ITEM).reason == "ALREADY_FETCHING"


def test_stop_mid_fetch_at_safe_boundary_keeps_queue_and_does_not_complete(rig, queue: OutboxQueue) -> None:  # type: ignore[no-untyped-def]
    r = rig(FakeScript(files=[RECS[:3], RECS[3:]]))
    calls = {"n": 0}

    def may() -> bool:
        calls["n"] += 1
        return calls["n"] <= 2  # 開始時と 1 回目の読み込み後は NORMAL、その後 STOPPING を検知

    r.fetcher._may_fetch = may
    rep = r.fetcher.fetch_stored(ITEM)
    assert rep.result is FetchResult.STOPPED_SAFE
    cp = queue.checkpoint("RACE:2")
    assert cp.fetched_ts is None and cp.acked_ts is None  # 取得済みにしない
    s = queue.sessions()[0]
    assert s.state == "INTERRUPTED" and queue.batches()  # 読み込んだ分は失わない
    assert not queue.has_open_session("RACE:2")


def test_hang_aborts_session_without_advancing_checkpoints_and_keeps_queued_data(rig, queue: OutboxQueue) -> None:  # type: ignore[no-untyped-def]
    r = rig(FakeScript(files=[RECS], hang_after=5), call_timeout=1.0)
    rep = r.fetcher.fetch_stored(ITEM)
    assert rep.result is FetchResult.ABORTED and rep.reason == "WORKER_read_batch"
    cp = queue.checkpoint("RACE:2")
    assert (cp.fetched_ts, cp.queued_ts, cp.acked_ts) == (None, None, None)  # 強制終了を取得完了と扱わない
    assert queue.sessions()[0].state == "ABORTED"
    # 次の周期で新しいセッションを開始できる（ワーカーは作り直される）。Fake はまた 5 件目でハングするので再び中止になるが、再起動は成功する
    rep2 = r.fetcher.fetch_stored(ITEM)
    assert rep2.result is FetchResult.ABORTED and r.sup.timeouts == 2 and r.sup.restarts >= 1


def test_read_error_aborts(rig, queue: OutboxQueue) -> None:  # type: ignore[no-untyped-def]
    r = rig(FakeScript(files=[RECS], read_error_after=2, read_error_code=-402))
    rep = r.fetcher.fetch_stored(ITEM)
    assert rep.result is FetchResult.ABORTED and rep.reason == "READ_-402" and queue.checkpoint("RACE:2").fetched_ts is None


def test_open_failure_creates_no_session(rig, queue: OutboxQueue) -> None:  # type: ignore[no-untyped-def]
    r = rig(FakeScript(open_code=-111))
    rep = r.fetcher.fetch_stored(ITEM)
    assert rep.result is FetchResult.ABORTED and rep.open_code == -111 and queue.sessions() == []


def test_empty_result_still_sends_eof_batch_so_completion_is_confirmed_by_server(rig, queue: OutboxQueue) -> None:  # type: ignore[no-untyped-def]
    r = rig(FakeScript(files=[]))
    assert r.fetcher.fetch_stored(ITEM).result is FetchResult.COMPLETED
    docs = [json.loads(b.payload) for b in queue.batches()]
    assert len(docs) == 1 and docs[0]["records"] == [] and docs[0]["eof"] is True


def test_queue_full_aborts_without_dropping_stored(rig, queue: OutboxQueue) -> None:  # type: ignore[no-untyped-def]
    r = rig(FakeScript(files=[RECS]))
    queue._cfg = QueueConfig(max_bytes=1500, min_free_bytes=0, max_batch_records=3)  # type: ignore[misc]
    rep = r.fetcher.fetch_stored(ITEM)
    assert rep.result is FetchResult.ABORTED and rep.reason is not None and rep.reason.startswith("QUEUE_")
    assert "QUEUE_FULL" in queue.alerts() and queue.sessions()[0].state == "ABORTED"
    assert all(b.status in ("QUEUED", "DEAD") for b in queue.batches())


def test_bad_record_id_aborts_instead_of_dropping(rig, queue: OutboxQueue) -> None:  # type: ignore[no-untyped-def]
    r = rig(FakeScript(files=[[b"RA0001", b"\x00\x01bad"]]))
    rep = r.fetcher.fetch_stored(ITEM)
    assert rep.result is FetchResult.ABORTED and rep.reason == "BAD_RECORD_ID" and "BAD_RECORD" in queue.alerts()


def test_realtime_batches_are_class_r_without_fetch_and_with_observed_at(rig, queue: OutboxQueue) -> None:  # type: ignore[no-untyped-def]
    r = rig(FakeScript(realtime_records=[b"O10001", b"O10002"]))
    rep = r.fetcher.fetch_realtime(PlanItem("0B12", "20261101", None, None, 60.0))
    assert rep.result is FetchResult.COMPLETED and rep.records == 2
    (b,) = queue.batches()
    d = json.loads(b.payload)
    assert b.cls == "R" and b.fetch_id is None and "fetch" not in d and d["class"] == "R" and d["observedAt"].endswith("Z")
    empty = rig(FakeScript(realtime_records=[]))
    assert empty.fetcher.fetch_realtime(PlanItem("0B12", "20261101", None, None, 60.0)).result is FetchResult.NO_DATA


def test_fromtime_uses_acked_ts_minus_margin() -> None:
    assert fromtime_from("20261031200000", None) == "20261031140000"
    assert fromtime_from(None, "20261001000000") == "20261001000000"
    assert fromtime_from(None, "bad") == "00000000000000"
    assert cursor_key("RACE", 2) == "RACE:2" and cursor_key("0B12", None) == "0B12"
