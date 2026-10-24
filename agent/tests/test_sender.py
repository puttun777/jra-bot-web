"""応答ごとの送信動作（CONTRACTS §3.6）。リトライで新しい batchId を作らない・無限リトライしない・401/403 で止まる・STOPPED/UNKNOWN で送らない。"""

from __future__ import annotations

import json
from datetime import timedelta

import pytest

from jra_agent.backoff import Backoff
from jra_agent.beacon import Beacon, BeaconResult, BeaconStatus
from jra_agent.client import SignedClient
from jra_agent.clock import FakeClock
from jra_agent.config import SenderConfig
from jra_agent.errors import TransportError
from jra_agent.modes import Mode, ModeMachine
from jra_agent.queue import OutboxQueue
from jra_agent.responses import Classified, Outcome
from jra_agent.sender import Sender, Step, parse_ack
from jra_agent.signing import RequestSigner
from tests.conftest import ScriptedTransport, ack_body, resp
from tests.helpers import TS, enqueue_realtime, enqueue_stored, start_session


def normal_machine(clock: FakeClock) -> ModeMachine:
    m = ModeMachine(clock)
    now = clock.now()
    m.on_beacon(BeaconResult(BeaconStatus.VALID, Beacon("NORMAL", 5, "k", now, None)))
    m.on_server(Classified(Outcome.OK, 200))
    assert m.evaluate().mode is Mode.NORMAL_OK
    return m


class Env:
    def __init__(self, queue: OutboxQueue, clock: FakeClock, cfg: SenderConfig | None = None, token: str = "jrat1_tok") -> None:
        self.queue, self.clock = queue, clock
        self.transport = ScriptedTransport()
        self.modes = normal_machine(clock)
        self.client = SignedClient("https://ingest.example", RequestSigner("ag_test", token), self.transport, clock)
        self.sender = Sender(queue, self.client, self.modes, cfg or SenderConfig(max_attempts=4), clock, "fp1", Backoff(), rand=lambda: 0.5)


@pytest.fixture
def env(queue: OutboxQueue, clock: FakeClock) -> Env:
    return Env(queue, clock)


def test_200_acks_batch_and_resend_never_changes_batch_id(env: Env) -> None:
    b = enqueue_realtime(env.queue)
    env.transport.push(TransportError("TIMEOUT"), resp(503, {"error": "UNAVAILABLE"}, retry_after="2"), resp(200, ack_body()))
    results = []
    for _ in range(3):
        results.append(env.sender.send_next())
        env.clock.advance(1000)
        env.modes.on_server(Classified(Outcome.OK, 200))  # runner の mode 確認（200）で送信再開が許される
    assert [r.step for r in results] == [Step.RETRY, Step.RETRY, Step.ACKED]
    sent = [
        json.loads(__import__("gzip").decompress(c.body) if c.headers.get("Content-Encoding") == "gzip" else c.body or b"")
        for c in env.transport.calls
    ]
    assert {s["batchId"] for s in sent} == {b.batch_id}  # 3 回とも同じ batchId・同じ内容
    assert len({c.body for c in env.transport.calls}) == 1
    assert env.queue.batch(b.batch_id).status == "ACKED"  # type: ignore[union-attr]


def test_400_413_422_make_dead_and_stop_auto_resend(env: Env) -> None:
    for status in (400, 413, 422):
        b = enqueue_realtime(env.queue)
        env.transport.push(resp(status, {"error": "INVALID_BASE64"}))
        r = env.sender.send_next()
        assert r.step is Step.DEAD and r.detail == "INVALID_BASE64"
        assert env.queue.batch(b.batch_id).status == "DEAD"  # type: ignore[union-attr]
    assert env.sender.send_next().step is Step.IDLE  # DEAD は再送されない
    assert "DEAD_BATCH" in env.queue.alerts()
    assert len(env.transport.calls) == 3


def test_dead_in_fetch_session_aborts_session_and_never_advances_cursor(env: Env) -> None:
    fid = start_session(env.queue)
    enqueue_stored(env.queue, fid)
    last = enqueue_stored(env.queue, fid, eof=True)
    env.transport.push(resp(422, {"error": "FETCH_MISMATCH"}))
    assert env.sender.send_next().step is Step.DEAD
    assert env.queue.session(fid).state == "ABORTED"  # type: ignore[union-attr]
    assert env.queue.batch(last.batch_id).dead_reason == "SESSION_ABORTED"  # type: ignore[union-attr]
    assert env.queue.checkpoint("RACE:2").acked_ts is None
    assert env.sender.send_next().step is Step.IDLE


@pytest.mark.parametrize("status", [401, 403])
def test_auth_failure_stops_sending_and_never_retries_automatically(env: Env, status: int) -> None:
    enqueue_realtime(env.queue)
    enqueue_realtime(env.queue)
    env.transport.push(resp(status, {"error": "UNAUTHORIZED"}))
    r = env.sender.send_next()
    assert r.step is Step.HALTED and "AUTH_FAILED" in env.queue.alerts()
    for _ in range(5):
        env.clock.advance(3600)
        assert env.sender.send_next().step is Step.HALTED
    assert len(env.transport.calls) == 1  # 自動で再試行しない
    assert env.queue.stats().by_status.get("QUEUED") == 2  # バッチは失わない（試行にも数えない）


def test_auth_halt_clears_only_when_credential_is_replaced(env: Env, queue: OutboxQueue, clock: FakeClock) -> None:
    enqueue_realtime(queue)
    env.transport.push(resp(403, {"error": "UNAUTHORIZED"}))
    assert env.sender.send_next().step is Step.HALTED
    fresh = Sender(queue, env.client, env.modes, SenderConfig(), clock, "fp-new-token", Backoff(), rand=lambda: 0.5)
    env.transport.push(resp(200, ack_body()))
    assert fresh.send_next().step is Step.ACKED  # トークン再発行（指紋が変わる）で再開
    assert "AUTH_FAILED" not in queue.alerts()


def test_401_with_large_clock_skew_is_reported_as_clock_problem(env: Env) -> None:
    enqueue_realtime(env.queue)
    date = (env.clock.now() + timedelta(minutes=20)).strftime("%a, %d %b %Y %H:%M:%S GMT")  # サーバの時刻が 20 分進んでいる
    env.transport.push(resp(401, {"error": "UNAUTHORIZED"}, date=date))
    assert env.sender.send_next().detail == "CLOCK_SKEW" or "CLOCK_SKEW" in env.queue.alerts()
    assert "CLOCK_SKEW" in env.queue.alerts() and "AUTH_FAILED" not in env.queue.alerts()
    assert env.client.skew.warn and env.client.skew.suspicious


def test_409_waits_retry_after_and_resends_same_batch(env: Env) -> None:
    fid = start_session(env.queue)
    b0 = enqueue_stored(env.queue, fid, eof=True)
    env.transport.push(resp(409, {"error": "BATCH_IN_PROGRESS"}, retry_after="5"), resp(200, ack_body()))
    assert env.sender.send_next().step is Step.RETRY
    row = env.queue.batch(b0.batch_id)
    assert row is not None and row.status == "RETRY_WAIT" and row.next_try_at - env.clock.now().timestamp() == pytest.approx(5.0)
    assert env.sender.send_next().step is Step.IDLE  # まだ待つ
    env.clock.advance(5.1)
    assert env.sender.send_next().step is Step.ACKED
    assert len({c.body for c in env.transport.calls}) == 1


def test_409_wait_is_capped(queue: OutboxQueue, clock: FakeClock) -> None:
    e = Env(queue, clock, SenderConfig(max_conflict_wait_seconds=30.0, max_attempts=9))
    b = enqueue_realtime(queue)
    e.transport.push(resp(409, {"error": "FETCH_SEQ_AHEAD"}, retry_after="900"))
    e.sender.send_next()
    assert queue.batch(b.batch_id).next_try_at - clock.now().timestamp() == pytest.approx(30.0)  # type: ignore[union-attr]


def test_429_follows_retry_after_and_slows_everything(env: Env) -> None:
    a, b = enqueue_realtime(env.queue), enqueue_realtime(env.queue)
    env.transport.push(resp(429, {"error": "RATE_LIMITED"}, retry_after="60"), resp(200, ack_body()))
    assert env.sender.send_next().step is Step.RETRY
    t0 = env.clock.monotonic()
    nxt = env.sender.send_next()  # 全体が減速している（Retry-After の 60 秒待ってから送る）
    assert nxt.step is Step.ACKED and env.clock.monotonic() - t0 >= 59.0
    # 待っている間に a の再送時刻にもなったので、順序（古い順）どおり a が先に再送される
    assert nxt.batch_id == a.batch_id and env.queue.batch(b.batch_id).status == "QUEUED"  # type: ignore[union-attr]


@pytest.mark.parametrize("mode", ["STARTING", "STOPPING", "STOPPED"])
def test_503_with_ops_mode_stops_send_and_fetch_without_consuming_attempts(env: Env, mode: str) -> None:
    b = enqueue_realtime(env.queue)
    env.transport.push(resp(503, {"error": "OPS_MODE_NOT_NORMAL"}, x_ops_mode=mode))
    assert env.sender.send_next().step is Step.DEFERRED
    assert env.modes.evaluate().mode is Mode.STOPPED_CONFIRMED and not env.modes.may_fetch() and not env.modes.may_send()
    assert env.queue.batch(b.batch_id).attempts == 0  # type: ignore[union-attr]
    for _ in range(3):  # 以後は送らない（HTTP も呼ばない）
        assert env.sender.send_next().step is Step.HALTED
    assert len(env.transport.calls) == 1


def test_503_without_mode_header_is_unknown_and_safe(env: Env) -> None:
    b = enqueue_realtime(env.queue)
    env.transport.push(resp(503, {"error": "OPS_MODE_UNKNOWN"}))
    assert env.sender.send_next().step is Step.RETRY
    assert env.modes.evaluate().send_paused  # 一時障害の扱い: 送信を止めてバックオフ
    assert env.sender.send_next().step is Step.HALTED
    assert env.queue.batch(b.batch_id).status == "RETRY_WAIT"  # type: ignore[union-attr]


@pytest.mark.parametrize("outcome", [TransportError("TIMEOUT"), TransportError("CONNECTION"), resp(502), resp(504)])
def test_timeouts_and_gateway_errors_keep_batch_and_retry_same(env: Env, outcome: object) -> None:
    b = enqueue_realtime(env.queue)
    env.transport.push(outcome)  # type: ignore[arg-type]
    assert env.sender.send_next().step is Step.RETRY
    row = env.queue.batch(b.batch_id)
    assert row is not None and row.status == "RETRY_WAIT" and row.payload


def test_retries_are_bounded_then_dead(env: Env) -> None:
    b = enqueue_realtime(env.queue)
    env.transport.default = resp(500, {"error": "INTERNAL"})
    steps = []
    for _ in range(10):
        env.modes.on_server(Classified(Outcome.OK, 200))  # 送信側の判定は別途: 個別バッチの 5xx を繰り返す状況
        steps.append(env.sender.send_next().step)
        env.clock.advance(1000)
        if steps[-1] is Step.DEAD:
            break
    assert steps[-1] is Step.DEAD and len(env.transport.calls) == 4  # max_attempts=4 で打ち切る（無限リトライしない）
    assert env.queue.batch(b.batch_id).dead_reason == "RETRY_EXHAUSTED"  # type: ignore[union-attr]
    assert "RETRY_EXHAUSTED" in env.queue.alerts()


def test_unknown_mode_blocks_send_and_fetch(queue: OutboxQueue, clock: FakeClock) -> None:
    e = Env(queue, clock)
    e.modes = ModeMachine(clock)  # 起動直後 = UNKNOWN
    e.sender = Sender(queue, e.client, e.modes, SenderConfig(), clock, "fp1")
    enqueue_realtime(queue)
    assert e.sender.send_next().step is Step.HALTED and not e.modes.may_fetch()
    assert e.transport.calls == []


def test_ack_with_bad_shape_is_not_treated_as_ack(env: Env) -> None:
    b = enqueue_realtime(env.queue)
    env.transport.push(resp(200, {"ok": True}))
    assert env.sender.send_next().step is Step.RETRY
    assert env.queue.batch(b.batch_id).status == "RETRY_WAIT"  # type: ignore[union-attr]


def test_cursor_in_final_ack_advances_acked_ts_and_partial_does_not(env: Env) -> None:
    fid = start_session(env.queue)
    enqueue_stored(env.queue, fid)
    enqueue_stored(env.queue, fid, eof=True)
    env.transport.push(
        resp(200, ack_body(fetch={"id": fid, "status": "IN_PROGRESS", "nextSeq": 1})),
        resp(200, ack_body(fetch={"id": fid, "status": "SUCCEEDED", "nextSeq": 2}, cursor={"dataType": "RACE:2", "lastFileTimestamp": TS})),
    )
    assert env.sender.send_next().step is Step.ACKED
    assert env.queue.checkpoint("RACE:2").acked_ts is None
    env.clock.advance(1)
    assert env.sender.send_next().step is Step.ACKED
    assert env.queue.checkpoint("RACE:2").acked_ts == TS


def test_pacing_limits_send_rate(env: Env) -> None:
    for _ in range(3):
        enqueue_realtime(env.queue)
    env.transport.default = resp(200, ack_body())
    t0 = env.clock.monotonic()
    for _ in range(3):
        assert env.sender.send_next().step is Step.ACKED
    assert env.clock.monotonic() - t0 >= 1.0  # 最大 2 バッチ/秒 → 3 バッチで 1 秒以上


def test_parse_ack_variants() -> None:
    assert parse_ack(None) is None and parse_ack({"accepted": "1"}) is None
    a = parse_ack(
        ack_body(
            duplicate=True, fetch={"id": "f", "status": "SUCCEEDED", "nextSeq": 3}, cursor={"dataType": "RACE:2", "lastFileTimestamp": TS}
        )
    )
    assert a is not None and a.duplicate and a.fetch_status == "SUCCEEDED" and a.cursor_ts == TS
