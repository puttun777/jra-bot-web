"""ビーコンの Ed25519 検証（署名不正・kid・seq 巻き戻り・鮮度）と、状態機械 UNKNOWN / NORMAL_OK / STOPPED_CONFIRMED（CONTRACTS §7）。"""

from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta

import pytest

from jra_agent.beacon import BeaconClient, BeaconGuard, BeaconStatus, canonical_bytes, verify_beacon
from jra_agent.clock import FakeClock
from jra_agent.config import BeaconConfig
from jra_agent.errors import TransportError, VerificationError
from jra_agent.modes import Mode, ModeMachine
from jra_agent.queue import OutboxQueue
from jra_agent.responses import Classified, Outcome
from tests.beacon_support import BeaconSigner
from tests.conftest import ScriptedTransport, resp

NOW = datetime(2026, 11, 1, 0, 30, tzinfo=UTC)


def test_valid_beacon_is_accepted_and_canonical_is_sorted_compact() -> None:
    s = BeaconSigner()
    b = verify_beacon(s.doc(), (s.public,), NOW)
    assert (b.mode, b.seq, b.kid) == ("NORMAL", 1, s.kid)
    assert (
        canonical_bytes(s.doc())
        == b'{"kid":"beacon-test-a","mode":"NORMAL","schema_version":1,"seq":1,"updatedAt":"2026-11-01T00:00:00Z","validUntil":null}'
    )


@pytest.mark.parametrize(
    ("mutate", "reason"),
    [
        (lambda d: d.update(mode="STOPPED"), "BAD_SIGNATURE"),  # 署名後の改ざん
        (lambda d: d.update(seq=999), "BAD_SIGNATURE"),
        (lambda d: d.update(sig="A" * 86), "BAD_SIGNATURE"),
        (lambda d: d.update(sig="!!!"), "BAD_SIGNATURE"),
        (lambda d: d.update(kid="unknown"), "UNKNOWN_KID"),
        (lambda d: d.update(extra=1), "BAD_SHAPE"),
        (lambda d: d.pop("sig"), "BAD_SHAPE"),
        (lambda d: d.update(schema_version=2), "BAD_SCHEMA_VERSION"),
    ],
)
def test_tampered_or_malformed_beacon_is_rejected(mutate, reason: str) -> None:  # type: ignore[no-untyped-def]
    s = BeaconSigner()
    d = s.doc()
    mutate(d)
    with pytest.raises(VerificationError) as e:
        verify_beacon(d, (s.public,), NOW)
    assert e.value.reason == reason


def test_beacon_signed_by_other_key_is_rejected() -> None:
    attacker = BeaconSigner(kid="beacon-test-a")
    legit = BeaconSigner(kid="beacon-test-a")
    with pytest.raises(VerificationError, match="BAD_SIGNATURE"):
        verify_beacon(attacker.doc(), (legit.public,), NOW)


def test_kid_selects_public_key_two_keys_rotation() -> None:
    a, b = BeaconSigner(kid="k-a"), BeaconSigner(kid="k-b")
    keys = (a.public, b.public)
    assert verify_beacon(a.doc(), keys, NOW).kid == "k-a" and verify_beacon(b.doc(), keys, NOW).kid == "k-b"
    with pytest.raises(VerificationError, match="BAD_SIGNATURE"):
        verify_beacon(b.doc(kid="k-a"), keys, NOW)  # kid を偽っても、その鍵では検証に通らない


def test_expired_normal_and_future_beacon() -> None:
    s = BeaconSigner()
    with pytest.raises(VerificationError, match="EXPIRED"):
        verify_beacon(s.doc(valid_until="2026-11-01T00:10:00Z"), (s.public,), NOW)
    assert (
        verify_beacon(s.doc(mode="STOPPED", valid_until="2026-11-01T00:10:00Z"), (s.public,), NOW).mode == "STOPPED"
    )  # 止める方向は期限切れでも受ける
    with pytest.raises(VerificationError, match="UPDATED_IN_FUTURE"):
        verify_beacon(s.doc(updated_at="2026-11-01T01:00:00Z"), (s.public,), NOW)


def test_seq_must_not_roll_back_even_after_restart(queue: OutboxQueue) -> None:
    s = BeaconSigner()
    g = BeaconGuard(queue.get_state, queue.set_state)
    g.check_and_accept(verify_beacon(s.doc(seq=10), (s.public,), NOW))
    g.check_and_accept(verify_beacon(s.doc(seq=10), (s.public,), NOW))  # 同じ seq・同じ mode は再取得として受ける
    g.check_and_accept(verify_beacon(s.doc(seq=11, mode="STOPPED"), (s.public,), NOW))
    g2 = BeaconGuard(queue.get_state, queue.set_state)  # 再起動後（永続した値）
    with pytest.raises(VerificationError, match="SEQ_ROLLBACK"):
        g2.check_and_accept(verify_beacon(s.doc(seq=10), (s.public,), NOW))  # 古い NORMAL（署名は有効）を再生しても拒否
    with pytest.raises(VerificationError, match="SEQ_CONFLICT"):
        g2.check_and_accept(verify_beacon(s.doc(seq=11, mode="NORMAL"), (s.public,), NOW))
    g2.reset()
    g2.check_and_accept(verify_beacon(s.doc(seq=1), (s.public,), NOW))  # 利用者の明示操作だけが巻き戻せる


def make_client(queue: OutboxQueue, clock: FakeClock, signer: BeaconSigner) -> tuple[BeaconClient, ScriptedTransport]:
    t = ScriptedTransport()
    cfg = BeaconConfig("https://beacon.example/mode.json", (signer.public,))
    return BeaconClient(cfg, t, clock, BeaconGuard(queue.get_state, queue.set_state)), t


def test_beacon_client_statuses(queue: OutboxQueue, clock: FakeClock) -> None:
    s = BeaconSigner()
    c, t = make_client(queue, clock, s)
    clock.set(NOW)
    t.push(
        resp(200, s.bytes(seq=3)),
        TransportError("TIMEOUT"),
        resp(503),
        resp(200, b"not json"),
        resp(200, s.bytes(seq=2)),
        resp(200, b"x" * 5000),
    )
    assert c.fetch().status is BeaconStatus.VALID
    assert c.fetch().status is BeaconStatus.UNREACHABLE
    assert c.fetch().status is BeaconStatus.UNREACHABLE
    assert (r := c.fetch()).status is BeaconStatus.INVALID and r.reason == "BAD_JSON"
    assert (r := c.fetch()).status is BeaconStatus.INVALID and r.reason == "SEQ_ROLLBACK"
    assert c.fetch().status is BeaconStatus.INVALID


# ------------------------------------------------------------------ 状態機械
def beacon_result(s: BeaconSigner, clock: FakeClock, **kw: object) -> object:
    from jra_agent.beacon import Beacon, BeaconResult

    d = s.doc(**kw)  # type: ignore[arg-type]
    return BeaconResult(BeaconStatus.VALID, Beacon(str(d["mode"]), int(d["seq"]), s.kid, clock.now(), None))  # type: ignore[call-overload]


OK = Classified(Outcome.OK, 200)


def test_starts_unknown_and_needs_both_beacon_and_server(clock: FakeClock) -> None:
    s, m = BeaconSigner(), ModeMachine(clock)
    assert m.evaluate().mode is Mode.UNKNOWN and not m.may_fetch() and not m.may_send()
    m.on_beacon(beacon_result(s, clock))  # type: ignore[arg-type]
    assert m.evaluate().mode is Mode.UNKNOWN  # ビーコン NORMAL だけでは足りない（ingest/mode/plan の 200 も要る）
    m.on_server(OK)
    assert m.evaluate().mode is Mode.NORMAL_OK and m.may_fetch() and m.may_send()


def test_server_200_alone_is_not_enough(clock: FakeClock) -> None:
    m = ModeMachine(clock)
    m.on_server(OK)
    assert m.evaluate().mode is Mode.UNKNOWN


def test_stopped_beacon_stops_everything_and_requires_fresh_server_ok_to_resume(clock: FakeClock) -> None:
    s, m = BeaconSigner(), ModeMachine(clock)
    m.on_beacon(beacon_result(s, clock))  # type: ignore[arg-type]
    m.on_server(OK)
    assert m.may_fetch()
    m.on_beacon(beacon_result(s, clock, mode="STOPPING", seq=2))  # type: ignore[arg-type]
    assert m.evaluate().mode is Mode.STOPPED_CONFIRMED and not m.may_fetch() and not m.may_send()
    m.on_beacon(beacon_result(s, clock, mode="STOPPED", seq=3))  # type: ignore[arg-type]
    clock.advance(3600)
    assert m.evaluate().mode is Mode.STOPPED_CONFIRMED
    m.on_beacon(beacon_result(s, clock, seq=4))  # type: ignore[arg-type]  # NORMAL に戻った
    assert m.evaluate().mode is Mode.UNKNOWN  # 止まる前の 200 を引きずらない。新しい 200 を待つ
    m.on_server(OK)
    assert m.evaluate().mode is Mode.NORMAL_OK


def test_503_with_ops_mode_overrides_stale_normal_beacon(clock: FakeClock) -> None:
    s, m = BeaconSigner(), ModeMachine(clock)
    m.on_beacon(beacon_result(s, clock))  # type: ignore[arg-type]
    m.on_server(OK)
    m.on_server(Classified(Outcome.OPS_NOT_NORMAL, 503, None, None, "STOPPING"))
    assert m.evaluate().mode is Mode.STOPPED_CONFIRMED  # ビーコンが NORMAL のままでも、確定した 503 を優先（古い NORMAL で動き続けない）
    clock.advance(60)
    m.on_server(OK)  # 200 が来ても、新しいビーコンの NORMAL が無ければ復帰しない
    assert m.evaluate().mode is Mode.NORMAL_OK  # サーバが再び 200 を返し、ビーコンも NORMAL なら復帰してよい
    m2 = ModeMachine(clock)
    m2.on_beacon(beacon_result(s, clock))  # type: ignore[arg-type]
    m2.on_server(Classified(Outcome.OPS_NOT_NORMAL, 503, None, None, "STOPPED"))
    m2.on_beacon(beacon_result(s, clock, seq=2))  # type: ignore[arg-type]  # 新しい NORMAL ビーコン
    assert m2.evaluate().mode is Mode.UNKNOWN
    m2.on_server(OK)
    assert m2.evaluate().mode is Mode.NORMAL_OK


def test_invalid_beacon_is_never_ignored_unknown_until_valid_again(clock: FakeClock) -> None:
    from jra_agent.beacon import BeaconResult

    s, m = BeaconSigner(), ModeMachine(clock)
    m.on_beacon(beacon_result(s, clock))  # type: ignore[arg-type]
    m.on_server(OK)
    m.on_beacon(BeaconResult(BeaconStatus.INVALID, reason="BAD_SIGNATURE"))
    v = m.evaluate()
    assert v.mode is Mode.UNKNOWN and v.reason == "BEACON_INVALID:BAD_SIGNATURE" and not m.may_fetch() and not m.may_send()
    m.on_server(OK)
    assert m.evaluate().mode is Mode.UNKNOWN  # ingest が 200 でも、署名不正のビーコンを無視して稼働しない
    m.on_beacon(beacon_result(s, clock, seq=2))  # type: ignore[arg-type]
    m.on_server(OK)
    assert m.evaluate().mode is Mode.NORMAL_OK


def test_beacon_unreachable_and_ingest_failures_become_unknown(clock: FakeClock) -> None:
    from jra_agent.beacon import BeaconResult

    s, m = BeaconSigner(), ModeMachine(clock)
    m.on_beacon(beacon_result(s, clock))  # type: ignore[arg-type]
    m.on_server(OK)
    m.on_beacon(BeaconResult(BeaconStatus.UNREACHABLE, reason="TIMEOUT"))
    for _ in range(2):
        m.on_server(Classified(Outcome.UNREACHABLE, None, "TIMEOUT"))
    assert m.evaluate().mode is Mode.NORMAL_OK and m.evaluate().send_paused
    m.on_server(Classified(Outcome.OPS_UNKNOWN, 503))
    assert m.evaluate().mode is Mode.UNKNOWN and m.evaluate().reason == "BEACON_UNREACHABLE_AND_INGEST_FAILING"


def test_normal_beacon_with_ingest_trouble_pauses_sending_then_unknown_after_30min(clock: FakeClock) -> None:
    s, m = BeaconSigner(), ModeMachine(clock)
    m.on_beacon(beacon_result(s, clock))  # type: ignore[arg-type]
    m.on_server(OK)
    m.on_server(Classified(Outcome.TRANSIENT_DB, 503, "UNAVAILABLE", 5.0))
    v = m.evaluate()
    assert v.mode is Mode.NORMAL_OK and v.send_paused and m.may_fetch() and not m.may_send()
    clock.advance(31 * 60)
    m.on_beacon(beacon_result(s, clock))  # type: ignore[arg-type]  # ビーコンは取れていて NORMAL のまま
    assert m.evaluate().mode is Mode.UNKNOWN and m.evaluate().reason == "INGEST_FAILING_30M"


def test_stale_normal_beacon_and_stale_stopped_beacon_become_unknown(clock: FakeClock) -> None:
    s, m = BeaconSigner(), ModeMachine(clock, beacon_max_age_seconds=3600)
    m.on_beacon(beacon_result(s, clock))  # type: ignore[arg-type]
    m.on_server(OK)
    clock.advance(3601)
    assert m.evaluate().reason == "BEACON_STALE" and m.evaluate().mode is Mode.UNKNOWN
    m3 = ModeMachine(clock)
    m3.on_beacon(beacon_result(s, clock, mode="STOPPED", seq=9))  # type: ignore[arg-type]
    clock.advance(6 * 3600 + 1)
    assert m3.evaluate().mode is Mode.UNKNOWN and m3.evaluate().reason == "STOPPED_BEACON_STALE"


def test_conflict_and_auth_responses_do_not_change_mode(clock: FakeClock) -> None:
    s, m = BeaconSigner(), ModeMachine(clock)
    m.on_beacon(beacon_result(s, clock))  # type: ignore[arg-type]
    m.on_server(OK)
    for o in (Outcome.RETRY_CONFLICT, Outcome.THROTTLED, Outcome.AUTH, Outcome.DEAD):
        m.on_server(Classified(o, 400))
    assert m.evaluate().mode is Mode.NORMAL_OK and not m.evaluate().send_paused


def test_beacon_document_roundtrip_is_json_serialisable() -> None:
    assert json.loads(BeaconSigner().bytes())["schema_version"] == 1
    assert timedelta(hours=6).total_seconds() == 21600
