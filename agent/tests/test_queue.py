"""SQLite WAL キュー: 永続化・クラッシュ復旧・順序・上限・チェックポイント。DB ファイルを実際に開き直して再起動を模擬する。"""

from __future__ import annotations

import sqlite3
from datetime import timedelta
from pathlib import Path

import pytest

from jra_agent.clock import FakeClock
from jra_agent.config import QueueConfig
from jra_agent.errors import QueueError, QueueFullError
from jra_agent.queue import AckInfo, OutboxQueue
from tests.helpers import TS, enqueue_realtime, enqueue_stored, start_session


def reopen(path: Path, clock: FakeClock, cfg: QueueConfig | None = None, free: int = 10**12) -> OutboxQueue:
    return OutboxQueue(path, clock, cfg or QueueConfig(min_free_bytes=0), disk_free=lambda _p: free)


def final_ack(fid: str, cursor_ts: str | None = TS, key: str = "RACE:2", status: str = "SUCCEEDED") -> AckInfo:
    return AckInfo(accepted=1, fetch_id=fid, fetch_status=status, cursor_type=key if cursor_ts else None, cursor_ts=cursor_ts)


def test_wal_mode_is_enabled_and_file_is_real(queue: OutboxQueue, qpath: Path) -> None:
    mode = sqlite3.connect(qpath).execute("PRAGMA journal_mode").fetchone()[0]
    assert mode.lower() == "wal"
    assert queue.verify() == []


def test_batches_survive_reopen_and_keep_identical_bytes(queue: OutboxQueue, qpath: Path, clock: FakeClock) -> None:
    fid = start_session(queue)
    b0 = enqueue_stored(queue, fid)
    b1 = enqueue_stored(queue, fid, eof=True)
    queue.close()
    q2 = reopen(qpath, clock)
    got = q2.batches()
    assert [b.batch_id for b in got] == [b0.batch_id, b1.batch_id]
    assert got[0].payload == b0.payload and got[1].payload == b1.payload  # 再起動しても本文（= batchId と内容）が同じ
    assert [b.fetch_seq for b in got] == [0, 1]
    assert q2.verify() == []
    q2.close()


def test_crash_while_sending_requeues_same_batch_without_loss(queue: OutboxQueue, qpath: Path, clock: FakeClock) -> None:
    enqueue_realtime(queue)
    sending = queue.claim_next()
    assert sending is not None and sending.status == "SENDING"
    queue.close()  # 送信中にプロセスが落ちた
    q2 = reopen(qpath, clock)
    assert q2.recover() == {"sending_requeued": 1, "sessions_interrupted": 0}
    again = q2.claim_next()
    assert again is not None and again.batch_id == sending.batch_id and again.payload == sending.payload
    assert again.attempts == 2  # 試行は数える（同じ batchId で再送）
    q2.close()


def test_only_one_batch_is_sending_at_a_time_and_order_is_preserved(queue: OutboxQueue) -> None:
    ids = [enqueue_realtime(queue).batch_id for _ in range(3)]
    first = queue.claim_next()
    assert first is not None and first.batch_id == ids[0]
    assert queue.claim_next() is None  # 送信中がある間は次を出さない
    queue.mark_acked(first.batch_id, AckInfo(accepted=1))
    second = queue.claim_next()
    assert second is not None and second.batch_id == ids[1]


def test_realtime_goes_before_stored_and_retry_order_is_kept(queue: OutboxQueue) -> None:
    fid = start_session(queue)
    stored = enqueue_stored(queue, fid, eof=True)
    rt = enqueue_realtime(queue)
    first = queue.claim_next()
    assert first is not None and first.batch_id == rt.batch_id
    queue.retry_later(first.batch_id, 30.0, "TEST", 10)
    # 速報は再試行待ち（30 秒後）。その間に蓄積系が送られる
    nxt = queue.claim_next()
    assert nxt is not None and nxt.batch_id == stored.batch_id


def test_fetch_batches_are_sent_strictly_in_seq_order_head_of_line(queue: OutboxQueue, clock: FakeClock) -> None:
    fid = start_session(queue)
    b0, b1 = enqueue_stored(queue, fid), enqueue_stored(queue, fid, eof=True)
    c0 = queue.claim_next()
    assert c0 is not None and c0.batch_id == b0.batch_id
    queue.retry_later(c0.batch_id, 10.0, "409", 10)
    assert queue.claim_next() is None  # b0 が ACKED になるまで b1 は送らない（先走りは 409 になる）
    clock.advance(11)
    again = queue.claim_next()
    assert again is not None and again.batch_id == b0.batch_id  # 同じ batchId で再送
    queue.mark_acked(b0.batch_id, AckInfo(accepted=1, fetch_status="IN_PROGRESS", fetch_next_seq=1))
    nxt = queue.claim_next()
    assert nxt is not None and nxt.batch_id == b1.batch_id and b1.fetch_seq == 1


def test_resend_keeps_batch_id_seq_and_payload(queue: OutboxQueue, clock: FakeClock) -> None:
    fid = start_session(queue)
    b0 = enqueue_stored(queue, fid, eof=True)
    for _ in range(3):
        c = queue.claim_next()
        assert c is not None
        assert (c.batch_id, c.fetch_seq, c.payload) == (b0.batch_id, 0, b0.payload)
        queue.retry_later(c.batch_id, 1.0, "NET", 10)
        clock.advance(2)


def test_retry_limit_makes_dead_and_aborts_session(queue: OutboxQueue, clock: FakeClock) -> None:
    fid = start_session(queue)
    b0, b1 = enqueue_stored(queue, fid), enqueue_stored(queue, fid, eof=True)
    for i in range(3):
        c = queue.claim_next()
        assert c is not None
        state = queue.retry_later(c.batch_id, 1.0, "NET", 3)
        clock.advance(2)
        assert state == ("DEAD" if i == 2 else "RETRY_WAIT")
    assert queue.batch(b0.batch_id).dead_reason == "RETRY_EXHAUSTED"  # type: ignore[union-attr]
    assert queue.batch(b1.batch_id).dead_reason == "SESSION_ABORTED"  # type: ignore[union-attr]
    assert queue.session(fid).state == "ABORTED"  # type: ignore[union-attr]
    assert queue.checkpoint("RACE:2").acked_ts is None
    assert queue.claim_next() is None


def test_dead_is_not_success_and_does_not_advance_checkpoints(queue: OutboxQueue) -> None:
    fid = start_session(queue)
    enqueue_stored(queue, fid)
    last = enqueue_stored(queue, fid, eof=True)
    c = queue.claim_next()
    assert c is not None
    queue.mark_dead(c.batch_id, "INVALID_BASE64")
    assert queue.session(fid).state == "ABORTED"  # type: ignore[union-attr]
    assert queue.batch(last.batch_id).status == "DEAD"  # type: ignore[union-attr]
    assert queue.stats().dead == 2
    cp = queue.checkpoint("RACE:2")
    assert cp.acked_ts is None
    assert queue.claim_next() is None


# ---------------------------------------------------------------- チェックポイント
def test_checkpoints_fetched_queued_acked_have_distinct_meanings(queue: OutboxQueue) -> None:
    fid = start_session(queue)
    enqueue_stored(queue, fid)  # EOF 未到達
    cp = queue.checkpoint("RACE:2")
    assert (cp.fetched_ts, cp.queued_ts, cp.acked_ts) == (
        None,
        None,
        None,
    ) and cp.queued_seq is not None  # EOF 前は fetched も queued も動かない
    enqueue_stored(queue, fid, eof=True)
    cp = queue.checkpoint("RACE:2")
    assert (cp.fetched_ts, cp.queued_ts, cp.acked_ts) == (TS, TS, None)  # 取得済み・投入済み。ACK はまだ
    assert queue.session(fid).state == "FETCHED"  # type: ignore[union-attr]


def test_acked_ts_advances_only_by_server_cursor_on_eof_batch(queue: OutboxQueue) -> None:
    fid = start_session(queue)
    b0, b1 = enqueue_stored(queue, fid), enqueue_stored(queue, fid, eof=True)
    c0 = queue.claim_next()
    assert c0 is not None
    # 一部のバッチだけ ACK → 進まない（cursor が付いていても EOF バッチではないので進めない）
    queue.mark_acked(c0.batch_id, final_ack(fid, status="IN_PROGRESS"))
    assert queue.checkpoint("RACE:2").acked_ts is None
    c1 = queue.claim_next()
    assert c1 is not None and c1.batch_id == b1.batch_id
    queue.mark_acked(c1.batch_id, final_ack(fid))
    assert queue.checkpoint("RACE:2").acked_ts == TS
    assert queue.session(fid).state == "COMPLETED" and queue.session(fid).cursor_confirmed  # type: ignore[union-attr]
    assert b0.batch_id != b1.batch_id and queue.verify() == []


@pytest.mark.parametrize(
    ("ack", "why"),
    [
        (AckInfo(accepted=1, fetch_status="SUCCEEDED"), "cursor が無い（サーバが完了を確認していない）"),
        (AckInfo(accepted=1, fetch_status="IN_PROGRESS", cursor_type="RACE:2", cursor_ts=TS), "fetch がまだ進行中"),
        (AckInfo(accepted=1, fetch_status="SUCCEEDED", cursor_type="RACE:1", cursor_ts=TS), "別の種別の cursor"),
        (AckInfo(accepted=1, fetch_status="SUCCEEDED", cursor_type="RACE:2", cursor_ts="20261231000000"), "自分の取得と一致しない cursor"),
        (AckInfo(accepted=1, fetch_status="FAILED"), "サーバが取得を失敗にした"),
        (AckInfo(accepted=1, duplicate=True), "重複 ACK（cursor 情報なし）"),
    ],
)
def test_acked_ts_does_not_advance_without_a_matching_server_cursor(queue: OutboxQueue, ack: AckInfo, why: str) -> None:
    fid = start_session(queue)
    enqueue_stored(queue, fid, eof=True)
    c = queue.claim_next()
    assert c is not None
    queue.mark_acked(c.batch_id, ack)
    assert queue.checkpoint("RACE:2").acked_ts is None, why


def test_acked_ts_never_moves_backwards(queue: OutboxQueue) -> None:
    for ts in ("20261101120000", "20261031000000"):  # 新しい → 古い（古い ACK でカーソルを巻き戻さない）
        fid = start_session(queue, ts=ts)
        enqueue_stored(queue, fid, eof=True, ts=ts)
        c = queue.claim_next()
        assert c is not None
        queue.mark_acked(c.batch_id, final_ack(fid, cursor_ts=ts))
    assert queue.checkpoint("RACE:2").acked_ts == "20261101120000"


def test_backfill_never_moves_checkpoints(queue: OutboxQueue) -> None:
    fid = start_session(queue, origin="BACKFILL")
    enqueue_stored(queue, fid, eof=True)
    c = queue.claim_next()
    assert c is not None
    queue.mark_acked(c.batch_id, final_ack(fid))  # サーバが cursor を返しても（返さないはずだが）通常取得のカーソルは動かさない
    cp = queue.checkpoint("RACE:2")
    assert (cp.fetched_ts, cp.queued_ts, cp.acked_ts) == (None, None, None)


def test_ack_is_idempotent_and_requires_sending_state(queue: OutboxQueue) -> None:
    b = enqueue_realtime(queue)
    with pytest.raises(QueueError):
        queue.mark_acked(b.batch_id, AckInfo(accepted=1))  # claim 前の ACK は受け付けない（DB 保存前の ACK を誤認しない）
    c = queue.claim_next()
    assert c is not None
    queue.mark_acked(c.batch_id, AckInfo(accepted=1))
    queue.mark_acked(c.batch_id, AckInfo(accepted=1))  # 二重でも壊れない
    assert queue.batch(b.batch_id).status == "ACKED"  # type: ignore[union-attr]
    assert queue.batch(b.batch_id).payload == b""  # type: ignore[union-attr]


def test_disconnect_right_before_ack_then_resend_gets_duplicate_ack(queue: OutboxQueue, qpath: Path, clock: FakeClock) -> None:
    fid = start_session(queue)
    b = enqueue_stored(queue, fid, eof=True)
    c = queue.claim_next()
    assert c is not None  # サーバは保存済みだが ACK が届く前に切断・再起動
    queue.close()
    q2 = reopen(qpath, clock)
    q2.recover()
    again = q2.claim_next()
    assert again is not None and (again.batch_id, again.fetch_seq, again.payload) == (b.batch_id, 0, b.payload)
    q2.mark_acked(
        again.batch_id,
        AckInfo(accepted=0, duplicates=1, duplicate=True, fetch_id=fid, fetch_status="SUCCEEDED", cursor_type="RACE:2", cursor_ts=TS),
    )
    assert q2.checkpoint("RACE:2").acked_ts == TS  # サーバが重複 ACK に cursor を付けた場合（D-144）は確定できる
    q2.close()


def test_interrupted_session_is_restored_and_not_completed(queue: OutboxQueue, qpath: Path, clock: FakeClock) -> None:
    fid = start_session(queue)
    enqueue_stored(queue, fid)
    enqueue_stored(queue, fid)
    queue.close()  # 取得の途中で再起動（JV-Link の読み位置は復元できない）
    q2 = reopen(qpath, clock)
    assert q2.recover()["sessions_interrupted"] == 1
    s = q2.session(fid)
    assert s is not None and s.state == "INTERRUPTED" and s.next_seq == 2
    assert q2.checkpoint("RACE:2").fetched_ts is None  # EOF 未到達なので取得済みにしない
    assert not q2.has_open_session("RACE:2")
    c = q2.claim_next()  # すでにキューにある分は連番のまま送られる
    assert c is not None and c.fetch_seq == 0
    new_fid = start_session(q2)  # 新しい取得は新しいセッション
    assert new_fid != fid and q2.verify() == []
    q2.close()


def test_duplicate_open_session_is_detected(queue: OutboxQueue) -> None:
    start_session(queue)
    assert queue.has_open_session("RACE:2") and not queue.has_open_session("RACE:1")


# ---------------------------------------------------------------- 期限・上限・ディスク
def test_realtime_expires_but_stored_does_not(queue: OutboxQueue, clock: FakeClock) -> None:
    rt = enqueue_realtime(queue)
    fid = start_session(queue)
    st = enqueue_stored(queue, fid, eof=True)
    clock.advance(QueueConfig().realtime_ttl_seconds + 1)
    assert queue.expire_realtime() == 1
    assert queue.batch(rt.batch_id).dead_reason == "EXPIRED"  # type: ignore[union-attr]
    assert queue.batch(st.batch_id).status == "QUEUED"  # type: ignore[union-attr]


def test_size_cap_drops_oldest_realtime_but_never_stored(qpath: Path, clock: FakeClock) -> None:
    cap = 6000
    q = reopen(qpath, clock, QueueConfig(max_bytes=1024**2, min_free_bytes=0))
    q._cfg = QueueConfig(max_bytes=cap, min_free_bytes=0)  # type: ignore[misc]
    first = enqueue_realtime(q)
    while q.stats().pending_bytes + len(first.payload) <= cap:
        enqueue_realtime(q)
    before = q.stats().by_status.get("QUEUED", 0)
    enqueue_realtime(q)  # 溢れる → 一番古い速報が DEAD(OVERFLOW_DROPPED)
    assert q.batch(first.batch_id).dead_reason == "OVERFLOW_DROPPED"  # type: ignore[union-attr]
    assert q.stats().realtime_dropped >= 1 and q.stats().by_status.get("QUEUED", 0) <= before
    q.close()


def test_stored_is_not_dropped_when_full_it_stops(qpath: Path, clock: FakeClock) -> None:
    q = reopen(qpath, clock, QueueConfig(max_bytes=1024**2, min_free_bytes=0))
    fid = start_session(q)
    first = enqueue_stored(q, fid)
    q._cfg = QueueConfig(max_bytes=len(first.payload) + 10, min_free_bytes=0)  # type: ignore[misc]
    with pytest.raises(QueueFullError):
        enqueue_stored(q, fid)
    assert q.batch(first.batch_id).status == "QUEUED"  # type: ignore[union-attr]
    assert q.session(fid).next_seq == 1  # type: ignore[union-attr]  # 失敗した投入は何も書かない
    q.close()


def test_low_disk_space_stops_safely_and_keeps_existing(qpath: Path, clock: FakeClock) -> None:
    free = {"v": 10**12}
    q = OutboxQueue(qpath, clock, QueueConfig(min_free_bytes=500 * 1024**2), disk_free=lambda _p: free["v"])
    keep = enqueue_realtime(q)
    free["v"] = 100 * 1024**2
    with pytest.raises(QueueFullError, match="DISK_LOW"):
        enqueue_realtime(q)
    assert q.batch(keep.batch_id).status == "QUEUED" and q.stats().disk_free_bytes == 100 * 1024**2  # type: ignore[union-attr]
    q.close()


def test_sqlite_disk_full_error_rolls_back_without_partial_rows(queue: OutboxQueue, qpath: Path, clock: FakeClock) -> None:
    fid = start_session(queue)
    enqueue_stored(queue, fid)
    limit = sqlite3.connect(qpath).execute("PRAGMA page_count").fetchone()[0]
    queue._db.execute(f"PRAGMA max_page_count = {limit}")  # これ以上ファイルを大きくできない = SQLITE_FULL
    big = b"x" * 200_000
    with pytest.raises(QueueFullError, match="DISK_FULL"):
        queue.enqueue(batch_id="019a0000-0000-7000-8000-000000000001", cls="R", dataspec="0B12", payload=big, record_count=1, eof=True)
    assert queue.batch("019a0000-0000-7000-8000-000000000001") is None  # 部分的な行を残さない
    assert queue.verify() == []


def test_unknown_class_or_session_combination_rejected(queue: OutboxQueue) -> None:
    with pytest.raises(QueueError):
        queue.enqueue(batch_id="x", cls="Z", dataspec="RACE", payload=b"{}", record_count=1, eof=True)
    fid = start_session(queue)
    with pytest.raises(QueueError):
        queue.enqueue(batch_id="y", cls="R", dataspec="0B12", payload=b"{}", record_count=1, eof=True, fetch_id=fid)


def test_enqueue_into_non_reading_session_is_rejected(queue: OutboxQueue) -> None:
    fid = start_session(queue)
    enqueue_stored(queue, fid, eof=True)
    with pytest.raises(QueueError):
        enqueue_stored(queue, fid)  # EOF 後に追加できない（サーバも FETCH_CLOSED で拒否する）


def test_housekeeping_removes_old_acked_and_keeps_active(queue: OutboxQueue, clock: FakeClock) -> None:
    done, keep = enqueue_realtime(queue), enqueue_realtime(queue)
    c = queue.claim_next()
    assert c is not None
    queue.mark_acked(c.batch_id, AckInfo(accepted=1))
    clock.advance(timedelta(days=2).total_seconds())
    assert queue.housekeeping() == 1
    assert queue.batch(done.batch_id) is None and queue.batch(keep.batch_id) is not None


def test_verify_detects_problems(queue: OutboxQueue) -> None:
    fid = start_session(queue)
    enqueue_stored(queue, fid)
    enqueue_stored(queue, fid, eof=True)
    queue._db.execute("UPDATE batch SET fetch_seq = 5 WHERE fetch_seq = 1")
    assert "FETCH_SEQ_GAP" in queue.verify()


def test_read_only_connection_can_inspect_while_writer_is_open(queue: OutboxQueue, qpath: Path, clock: FakeClock) -> None:
    enqueue_realtime(queue)
    ro = OutboxQueue(qpath, clock, read_only=True)
    assert ro.stats().by_status == {"QUEUED": 1} and ro.verify() == []
    ro.close()
