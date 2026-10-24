from __future__ import annotations

from jra_agent.envelope import FetchInfo, RawRecord, build_envelope, uuid7
from jra_agent.queue import OutboxQueue
from jra_agent.queue.store import BatchRow

TS = "20261031200000"


def rec(i: int = 0, rid: str = "RA") -> RawRecord:
    return RawRecord(rid, f"{rid}{i:04d}-synthetic".encode("cp932"))


def start_session(q: OutboxQueue, key: str = "RACE:2", ts: str | None = TS, origin: str = "LIVE", fetch_id: str | None = None) -> str:
    fid = fetch_id or uuid7()
    dataspec, _, opt = key.partition(":")
    q.begin_session(
        fid,
        key,
        dataspec,
        int(opt) if opt else None,
        "20261030000000",
        ts,
        "20261101",
        "20261130",
        origin,
        7 if origin == "BACKFILL" else None,
    )
    return fid


def enqueue_stored(q: OutboxQueue, fid: str, n_records: int = 2, eof: bool = False, key: str = "RACE:2", ts: str | None = TS) -> BatchRow:
    s = q.session(fid)
    assert s is not None
    dataspec, _, opt = key.partition(":")
    batch_id = uuid7()
    payload = build_envelope(
        agent_id="ag_test", batch_id=batch_id, dataspec=dataspec, observed_at=q._clock.now(), records=[rec(i) for i in range(n_records)], eof=eof,
        option=int(opt) if opt else None, fromtime="20261030000000", last_file_timestamp=ts,
        fetch=FetchInfo(fid, s.next_seq, "20261101", "20261130", s.origin, s.backfill_id),
    )  # fmt: skip
    return q.enqueue(
        batch_id=batch_id,
        cls="S",
        dataspec=dataspec,
        payload=payload,
        record_count=n_records,
        eof=eof,
        fetch_id=fid,
        last_file_timestamp=ts,
    )


def enqueue_realtime(q: OutboxQueue, dataspec: str = "0B12", observed_at=None) -> BatchRow:  # type: ignore[no-untyped-def]
    batch_id = uuid7()
    when = observed_at or q._clock.now()
    payload = build_envelope(agent_id="ag_test", batch_id=batch_id, dataspec=dataspec, observed_at=when, records=[rec(1, "O1")], eof=True)
    return q.enqueue(batch_id=batch_id, cls="R", dataspec=dataspec, payload=payload, record_count=1, eof=True, observed_at=when.timestamp())
