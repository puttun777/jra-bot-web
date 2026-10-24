"""SQLite WAL の outbox・チェックポイント・取得セッション。

不変条件（tests/test_queue_*.py と `verify()` で検査する）:
 1. バッチは **commit された時点で永続**。プロセスが落ちても、起動時に SENDING を QUEUED へ戻して**同じ batchId・同じ内容**で再送する。
 2. `acked_ts` は **EOF バッチの ACK にサーバの `cursor` が付いたとき**にだけ前進し、後戻りしない（LIVE の取得だけ。BACKFILL は動かさない）。
 3. 取得セッションの連番（`fetch_seq`）は 0 から連続し、再送でも変えない。
 4. 同時に SENDING なのは 1 つだけ（順序保持）。取得セッション内は、手前が ACKED になるまで次を送らない（サーバは先走りを 409 で断る）。
 5. 容量の上限・ディスク不足・期限切れ速報は DEAD / 取り込み拒否になり、**成功には数えない**。ログに本文を出さない。
"""

from __future__ import annotations

import hashlib
import shutil
import sqlite3
import threading
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from ..clock import Clock
from ..config import QueueConfig
from ..errors import QueueError, QueueFullError
from .schema import DDL, SCHEMA_VERSION

PRIORITY_REALTIME = 0  # 速報（二度と取れない）を先に送る
PRIORITY_STORED = 1
ACTIVE = ("QUEUED", "SENDING", "RETRY_WAIT")


@dataclass(frozen=True)
class BatchRow:
    id: int
    batch_id: str
    cls: str
    dataspec: str
    fetch_id: str | None
    fetch_seq: int | None
    eof: bool
    priority: int
    observed_at: float
    expires_at: float | None
    payload: bytes
    payload_sha256: str
    record_count: int
    status: str
    attempts: int
    next_try_at: float
    last_outcome: str | None
    dead_reason: str | None

    def __repr__(self) -> str:  # 本文を出さない
        return f"BatchRow(id={self.id}, batch_id={self.batch_id}, {self.cls}/{self.dataspec}, status={self.status}, attempts={self.attempts}, bytes={len(self.payload)})"


@dataclass(frozen=True)
class SessionRow:
    fetch_id: str
    cursor_key: str
    dataspec: str
    option: int | None
    origin: str
    backfill_id: int | None
    fromtime: str | None
    last_file_timestamp: str | None
    target_from: str | None
    target_to: str | None
    state: str
    next_seq: int
    cursor_confirmed: bool
    reason: str | None


@dataclass(frozen=True)
class Checkpoint:
    cursor_key: str
    fetched_ts: str | None
    queued_ts: str | None
    queued_seq: int | None
    acked_ts: str | None


@dataclass(frozen=True)
class AckInfo:
    """200 応答から取り出した ACK（サーバの `IngestAck`）。`cursor_*` はサーバのカーソルが進んだ（確認できた）ときだけ。"""

    accepted: int = 0
    duplicates: int = 0
    quarantined: int = 0
    unsupported: int = 0
    duplicate: bool = False
    fetch_id: str | None = None
    fetch_status: str | None = None  # IN_PROGRESS / SUCCEEDED / FAILED
    fetch_next_seq: int | None = None
    cursor_type: str | None = None
    cursor_ts: str | None = None


@dataclass(frozen=True)
class QueueStats:
    by_status: dict[str, int]
    pending_bytes: int
    oldest_queued_at: float | None
    realtime_dropped: int
    dead: int
    disk_free_bytes: int | None


def _is_full_error(e: sqlite3.Error) -> bool:
    return isinstance(e, sqlite3.OperationalError) and ("full" in str(e).lower() or "disk i/o" in str(e).lower())


class OutboxQueue:
    def __init__(
        self,
        path: Path,
        clock: Clock,
        cfg: QueueConfig | None = None,
        disk_free: Callable[[Path], int] | None = None,
        read_only: bool = False,
    ) -> None:
        self._path = path
        self._clock = clock
        self._cfg = cfg or QueueConfig()
        self._disk_free = disk_free or (lambda p: shutil.disk_usage(p).free)
        self._lock = threading.RLock()
        path.parent.mkdir(parents=True, exist_ok=True)
        uri = f"file:{path.as_posix()}?mode=ro" if read_only else f"file:{path.as_posix()}"
        self._db = sqlite3.connect(uri, uri=True, isolation_level=None, timeout=30.0, check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self._db.execute("PRAGMA busy_timeout = 30000")
        if not read_only:
            mode = self._db.execute("PRAGMA journal_mode = WAL").fetchone()[0]
            if str(mode).lower() != "wal":
                raise QueueError("WAL モードにできない（ネットワークドライブなどでは使えない）")
            self._db.execute("PRAGMA synchronous = FULL")  # 電源断でも commit 済みを失わない
            self._db.execute("PRAGMA foreign_keys = ON")
            self._migrate()

    def close(self) -> None:
        with self._lock:
            self._db.close()

    # ------------------------------------------------------------------ 基盤
    def _migrate(self) -> None:
        with self._tx():
            for stmt in [s.strip() for s in DDL.split(";\n") if s.strip()]:
                self._db.execute(stmt)
            row = self._db.execute("SELECT v FROM meta WHERE k = 'schema_version'").fetchone()
            if row is None:
                self._db.execute("INSERT INTO meta (k, v) VALUES ('schema_version', ?)", (str(SCHEMA_VERSION),))
            elif int(row["v"]) != SCHEMA_VERSION:
                raise QueueError("キューのスキーマ版が違う")

    @contextmanager
    def _tx(self) -> Iterator[sqlite3.Connection]:
        with self._lock:
            try:
                self._db.execute("BEGIN IMMEDIATE")
            except sqlite3.Error as e:
                raise QueueError("キューを開始できない") from e
            try:
                yield self._db
            except BaseException as e:
                try:
                    self._db.execute("ROLLBACK")
                except sqlite3.Error:
                    pass
                if isinstance(e, sqlite3.Error) and _is_full_error(e):
                    raise QueueFullError("DISK_FULL") from e
                raise
            else:
                try:
                    self._db.execute("COMMIT")
                except sqlite3.Error as e:
                    try:
                        self._db.execute("ROLLBACK")
                    except sqlite3.Error:
                        pass
                    if _is_full_error(e):
                        raise QueueFullError("DISK_FULL") from e
                    raise QueueError("commit に失敗") from e

    def _now(self) -> float:
        return self._clock.now().timestamp()

    @staticmethod
    def _batch(r: sqlite3.Row) -> BatchRow:
        return BatchRow(
            r["id"], r["batch_id"], r["cls"], r["dataspec"], r["fetch_id"], r["fetch_seq"], bool(r["eof"]), r["priority"], r["observed_at"],
            r["expires_at"], bytes(r["payload"]), r["payload_sha256"], r["record_count"], r["status"], r["attempts"], r["next_try_at"],
            r["last_outcome"], r["dead_reason"],
        )  # fmt: skip

    @staticmethod
    def _session(r: sqlite3.Row) -> SessionRow:
        return SessionRow(
            r["fetch_id"], r["cursor_key"], r["dataspec"], r["option"], r["origin"], r["backfill_id"], r["fromtime"], r["last_file_timestamp"],
            r["target_from"], r["target_to"], r["state"], r["next_seq"], bool(r["cursor_confirmed"]), r["reason"],
        )  # fmt: skip

    # ------------------------------------------------------------------ 取得セッション
    def begin_session(
        self,
        fetch_id: str,
        cursor_key: str,
        dataspec: str,
        option: int | None,
        fromtime: str | None,
        last_file_timestamp: str | None,
        target_from: str | None = None,
        target_to: str | None = None,
        origin: str = "LIVE",
        backfill_id: int | None = None,
    ) -> SessionRow:
        now = self._now()
        with self._tx() as db:
            db.execute(
                "INSERT INTO fetch_session (fetch_id, cursor_key, dataspec, option, origin, backfill_id, fromtime, last_file_timestamp, target_from, target_to,"
                " state, next_seq, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?, 'READING', 0, ?, ?)",
                (
                    fetch_id,
                    cursor_key,
                    dataspec,
                    option,
                    origin,
                    backfill_id,
                    fromtime,
                    last_file_timestamp,
                    target_from,
                    target_to,
                    now,
                    now,
                ),
            )
        return self.session(fetch_id)  # type: ignore[return-value]

    def session(self, fetch_id: str) -> SessionRow | None:
        with self._lock:
            r = self._db.execute("SELECT * FROM fetch_session WHERE fetch_id = ?", (fetch_id,)).fetchone()
        return self._session(r) if r else None

    def sessions(self, states: tuple[str, ...] | None = None) -> list[SessionRow]:
        with self._lock:
            rows = self._db.execute("SELECT * FROM fetch_session ORDER BY created_at, fetch_id").fetchall()
        return [self._session(r) for r in rows if states is None or r["state"] in states]

    def has_open_session(self, cursor_key: str) -> bool:
        """同じ種別の取得が重複して起動しない（READING のセッションがあれば新しく始めない）。"""
        with self._lock:
            r = self._db.execute("SELECT 1 FROM fetch_session WHERE cursor_key = ? AND state = 'READING' LIMIT 1", (cursor_key,)).fetchone()
        return r is not None

    def abort_session(self, fetch_id: str, reason: str, state: str = "ABORTED") -> None:
        """セッションを中止する。まだ送っていないバッチは DEAD にする（サーバは先走りも終了後の追加も受け付けない）。`acked_ts` は動かさない。"""
        now = self._now()
        with self._tx() as db:
            self._abort(db, fetch_id, reason, now, state)

    def _abort(self, db: sqlite3.Connection, fetch_id: str, reason: str, now: float, state: str = "ABORTED") -> None:
        db.execute(
            "UPDATE fetch_session SET state = ?, reason = ?, updated_at = ?, completed_at = ? WHERE fetch_id = ? AND state IN ('READING','FETCHED')",
            (state, reason[:100], now, now, fetch_id),
        )
        db.execute(
            "UPDATE batch SET status = 'DEAD', dead_reason = 'SESSION_ABORTED', payload = x'', updated_at = ? "
            "WHERE fetch_id = ? AND status IN ('QUEUED','RETRY_WAIT')",
            (now, fetch_id),
        )

    # ------------------------------------------------------------------ 投入
    def _pending_bytes(self, db: sqlite3.Connection) -> int:
        return int(
            db.execute("SELECT COALESCE(SUM(payload_len), 0) FROM batch WHERE status IN ('QUEUED','SENDING','RETRY_WAIT')").fetchone()[0]
        )

    def enqueue(
        self,
        *,
        batch_id: str,
        cls: str,
        dataspec: str,
        payload: bytes,
        record_count: int,
        eof: bool,
        fetch_id: str | None = None,
        observed_at: float | None = None,
        last_file_timestamp: str | None = None,
    ) -> BatchRow:
        """バッチを永続化する。1 つのトランザクションで、行の挿入・セッションの連番・チェックポイント（EOF のとき）を更新する。

        上限・ディスク不足のときは [`QueueFullError`]（何も書かない）。速報（R）は古いものを DEAD にして場所を空ける。蓄積系（S）は捨てない。
        """
        if cls not in ("S", "R") or (cls == "R" and fetch_id is not None):
            raise QueueError("クラスと取得セッションの組合せが不正")
        now = self._now()
        obs = observed_at if observed_at is not None else now
        priority = PRIORITY_REALTIME if cls == "R" else PRIORITY_STORED
        expires = obs + self._cfg.realtime_ttl_seconds if cls == "R" else None
        free = self._free_bytes()
        if free is not None and free - len(payload) < self._cfg.min_free_bytes:
            raise QueueFullError("DISK_LOW")  # ディスク容量が少ないときは安全に停止する（取得を止め、すでにある分は守る）
        with self._tx() as db:
            used = self._pending_bytes(db)
            if used + len(payload) > self._cfg.max_bytes:
                if cls == "R":
                    self._drop_oldest_realtime(db, used + len(payload) - self._cfg.max_bytes, now)
                    used = self._pending_bytes(db)
                if used + len(payload) > self._cfg.max_bytes:
                    raise QueueFullError("QUEUE_FULL")
            seq: int | None = None
            if fetch_id is not None:
                s = db.execute("SELECT * FROM fetch_session WHERE fetch_id = ?", (fetch_id,)).fetchone()
                if s is None or s["state"] != "READING":
                    raise QueueError("取得セッションが取得中ではない")
                seq = s["next_seq"]
            db.execute(
                "INSERT INTO batch (batch_id, cls, dataspec, fetch_id, fetch_seq, eof, priority, observed_at, expires_at, payload, payload_sha256,"
                " payload_len, record_count, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?, 'QUEUED', ?, ?)",
                (batch_id, cls, dataspec, fetch_id, seq, int(eof), priority, obs, expires, payload, hashlib.sha256(payload).hexdigest(), len(payload), record_count, now, now),
            )  # fmt: skip
            bid = db.execute("SELECT id FROM batch WHERE batch_id = ?", (batch_id,)).fetchone()["id"]
            if fetch_id is not None:
                db.execute("UPDATE fetch_session SET next_seq = next_seq + 1, last_file_timestamp = COALESCE(?, last_file_timestamp), updated_at = ? WHERE fetch_id = ?",
                           (last_file_timestamp, now, fetch_id))  # fmt: skip
                s = db.execute("SELECT * FROM fetch_session WHERE fetch_id = ?", (fetch_id,)).fetchone()
                self._bump_checkpoint(db, s["cursor_key"], queued_seq=bid, now=now)
                if eof:
                    # EOF までの全バッチがキューに入った = 取得済み。fetched_ts と queued_ts はこの 1 トランザクションで動く（acked_ts は動かない）
                    db.execute("UPDATE fetch_session SET state = 'FETCHED', updated_at = ? WHERE fetch_id = ?", (now, fetch_id))
                    ts = s["last_file_timestamp"]
                    if ts and s["origin"] == "LIVE":
                        self._bump_checkpoint(db, s["cursor_key"], fetched_ts=ts, queued_ts=ts, now=now)
            row = db.execute("SELECT * FROM batch WHERE id = ?", (bid,)).fetchone()
        return self._batch(row)

    def _free_bytes(self) -> int | None:
        try:
            return int(self._disk_free(self._path.parent))
        except OSError:
            return None

    def _drop_oldest_realtime(self, db: sqlite3.Connection, need: int, now: float) -> None:
        freed = 0
        for r in db.execute(
            "SELECT id, payload_len FROM batch WHERE cls = 'R' AND status IN ('QUEUED','RETRY_WAIT') ORDER BY observed_at, id"
        ).fetchall():
            if freed >= need:
                break
            db.execute(
                "UPDATE batch SET status = 'DEAD', dead_reason = 'OVERFLOW_DROPPED', payload = x'', updated_at = ? WHERE id = ?",
                (now, r["id"]),
            )
            freed += r["payload_len"]

    def _bump_checkpoint(
        self,
        db: sqlite3.Connection,
        key: str,
        *,
        fetched_ts: str | None = None,
        queued_ts: str | None = None,
        queued_seq: int | None = None,
        acked_ts: str | None = None,
        now: float,
    ) -> None:
        """チェックポイントは**前進だけ**（文字列比較。14 桁の数字なので辞書順 = 時刻順）。"""
        db.execute("INSERT OR IGNORE INTO checkpoint (cursor_key, updated_at) VALUES (?, ?)", (key, now))
        for col, val in (("fetched_ts", fetched_ts), ("queued_ts", queued_ts), ("queued_seq", queued_seq), ("acked_ts", acked_ts)):
            if val is None:
                continue
            sql = f"UPDATE checkpoint SET {col} = ?, updated_at = ? WHERE cursor_key = ? AND ({col} IS NULL OR {col} < ?)"  # noqa: S608 - 列名は固定
            db.execute(sql, (val, now, key, val))

    # ------------------------------------------------------------------ 送信側
    def recover(self) -> dict[str, int]:
        """起動時の復旧。SENDING（送信中に落ちた。サーバに届いたかは不明）→ QUEUED、取得中のまま残ったセッション → INTERRUPTED。
        バッチは捨てず、同じ batchId・同じ内容で再送する（サーバは重複 ACK で吸収）。"""
        now = self._now()
        with self._tx() as db:
            sending = db.execute(
                "UPDATE batch SET status = 'QUEUED', next_try_at = 0, updated_at = ? WHERE status = 'SENDING'", (now,)
            ).rowcount
            reading = db.execute(
                "UPDATE fetch_session SET state = 'INTERRUPTED', reason = 'RESTART', updated_at = ?, completed_at = ? WHERE state = 'READING'",
                (now, now),
            ).rowcount
        return {"sending_requeued": sending, "sessions_interrupted": reading}

    def expire_realtime(self) -> int:
        now = self._now()
        with self._tx() as db:
            return db.execute(
                "UPDATE batch SET status = 'DEAD', dead_reason = 'EXPIRED', payload = x'', updated_at = ? "
                "WHERE cls = 'R' AND expires_at IS NOT NULL AND expires_at <= ? AND status IN ('QUEUED','RETRY_WAIT')",
                (now, now),
            ).rowcount

    def claim_next(self) -> BatchRow | None:
        """次に送るバッチを 1 つだけ取り出して SENDING にする（原子的）。送信中のものがあれば None（順序を保つ）。"""
        now = self._now()
        with self._tx() as db:
            if db.execute("SELECT 1 FROM batch WHERE status = 'SENDING' LIMIT 1").fetchone():
                return None
            db.execute(
                "UPDATE batch SET status = 'DEAD', dead_reason = 'EXPIRED', payload = x'', updated_at = ? "
                "WHERE cls = 'R' AND expires_at IS NOT NULL AND expires_at <= ? AND status IN ('QUEUED','RETRY_WAIT')",
                (now, now),
            )
            row = db.execute(
                "SELECT b.* FROM batch b WHERE b.status IN ('QUEUED','RETRY_WAIT') AND b.next_try_at <= ? "
                " AND (b.fetch_id IS NULL OR NOT EXISTS (SELECT 1 FROM batch p WHERE p.fetch_id = b.fetch_id AND p.fetch_seq < b.fetch_seq AND p.status <> 'ACKED')) "
                "ORDER BY b.priority, b.id LIMIT 1",
                (now,),
            ).fetchone()
            if row is None:
                return None
            db.execute("UPDATE batch SET status = 'SENDING', attempts = attempts + 1, updated_at = ? WHERE id = ?", (now, row["id"]))
            return self._batch(db.execute("SELECT * FROM batch WHERE id = ?", (row["id"],)).fetchone())

    def next_eligible_at(self) -> float | None:
        """次に送れる時刻（待機中のものだけ）。None なら送るものが無い。"""
        with self._lock:
            r = self._db.execute("SELECT MIN(next_try_at) AS t FROM batch WHERE status IN ('QUEUED','RETRY_WAIT')").fetchone()
        return None if r["t"] is None else float(r["t"])

    def defer(self, batch_id: str, outcome: str) -> None:
        """送れなかった（モード待機など）。試行回数を戻し、そのまま QUEUED に戻す（待機は試行に数えない）。"""
        with self._tx() as db:
            db.execute("UPDATE batch SET status = 'QUEUED', attempts = MAX(attempts - 1, 0), last_outcome = ?, updated_at = ? WHERE batch_id = ? AND status = 'SENDING'",
                       (outcome[:40], self._now(), batch_id))  # fmt: skip

    def retry_later(self, batch_id: str, delay: float, outcome: str, max_attempts: int) -> str:
        """同じバッチを後で再送する。試行回数が上限なら DEAD（RETRY_EXHAUSTED）にして取得セッションも中止する。戻り値は新しい状態。"""
        now = self._now()
        with self._tx() as db:
            r = db.execute("SELECT * FROM batch WHERE batch_id = ?", (batch_id,)).fetchone()
            if r is None or r["status"] != "SENDING":
                raise QueueError("送信中でないバッチは再試行に回せない")
            if r["attempts"] >= max_attempts:
                db.execute("UPDATE batch SET status = 'DEAD', dead_reason = 'RETRY_EXHAUSTED', last_outcome = ?, payload = x'', updated_at = ? WHERE id = ?",
                           (outcome[:40], now, r["id"]))  # fmt: skip
                if r["fetch_id"]:
                    self._abort(db, r["fetch_id"], "RETRY_EXHAUSTED", now)
                return "DEAD"
            db.execute(
                "UPDATE batch SET status = 'RETRY_WAIT', next_try_at = ?, last_outcome = ?, updated_at = ? WHERE id = ?",
                (now + delay, outcome[:40], now, r["id"]),
            )
            return "RETRY_WAIT"

    def mark_dead(self, batch_id: str, reason: str) -> None:
        """不正データなど。自動再送を止める。取得セッションの一部なら、セッション全体を中止する（`acked_ts` は動かない）。"""
        now = self._now()
        with self._tx() as db:
            r = db.execute("SELECT * FROM batch WHERE batch_id = ?", (batch_id,)).fetchone()
            if r is None or r["status"] not in ("SENDING", "QUEUED", "RETRY_WAIT"):
                raise QueueError("DEAD にできない状態")
            db.execute(
                "UPDATE batch SET status = 'DEAD', dead_reason = ?, last_outcome = 'DEAD', updated_at = ? WHERE id = ?",
                (reason[:60], now, r["id"]),
            )
            if r["fetch_id"]:
                self._abort(db, r["fetch_id"], f"DEAD:{reason}"[:100], now)

    def mark_acked(self, batch_id: str, ack: AckInfo) -> None:
        """サーバの ACK を反映する。`acked_ts` は **EOF バッチの ACK に `cursor` が付き、種別が一致する LIVE のセッション**のときだけ前進する。"""
        now = self._now()
        with self._tx() as db:
            r = db.execute("SELECT * FROM batch WHERE batch_id = ?", (batch_id,)).fetchone()
            if r is None:
                raise QueueError("不明なバッチ")
            if r["status"] == "ACKED":
                return  # 二重の反映をしない
            if r["status"] != "SENDING":
                raise QueueError("送信中でないバッチの ACK")
            db.execute(
                "UPDATE batch SET status = 'ACKED', payload = x'', acked_at = ?, updated_at = ?, last_outcome = 'OK' WHERE id = ?",
                (now, now, r["id"]),
            )
            fid = r["fetch_id"]
            if not fid:
                return
            s = db.execute("SELECT * FROM fetch_session WHERE fetch_id = ?", (fid,)).fetchone()
            if s is None:
                return
            if ack.fetch_status == "FAILED":
                self._abort(db, fid, "SERVER_FETCH_FAILED", now)  # 取得の事実を作れなかった。完了にせず、次の取得でやり直す
                return
            if not r["eof"]:
                if ack.fetch_status in ("SUCCEEDED",):  # 途中のバッチで成功になるのは契約違反
                    self._abort(db, fid, "PROTOCOL_UNEXPECTED_SUCCESS", now)
                return
            if s["state"] not in ("FETCHED", "READING"):
                return
            confirmed = (
                ack.fetch_status == "SUCCEEDED"
                and ack.cursor_ts is not None
                and ack.cursor_type == s["cursor_key"]
                and s["origin"] == "LIVE"
                and s["last_file_timestamp"] is not None
                and ack.cursor_ts == s["last_file_timestamp"]  # 自分の取得が確定させた値と一致するときだけ（ずれていれば進めない）
            )
            db.execute(
                "UPDATE fetch_session SET state = 'COMPLETED', cursor_confirmed = ?, updated_at = ?, completed_at = ? WHERE fetch_id = ?",
                (int(confirmed), now, now, fid),
            )
            if confirmed:
                self._bump_checkpoint(db, s["cursor_key"], acked_ts=ack.cursor_ts, now=now)

    # ------------------------------------------------------------------ 参照
    def checkpoint(self, key: str) -> Checkpoint:
        with self._lock:
            r = self._db.execute("SELECT * FROM checkpoint WHERE cursor_key = ?", (key,)).fetchone()
        return (
            Checkpoint(key, None, None, None, None)
            if r is None
            else Checkpoint(key, r["fetched_ts"], r["queued_ts"], r["queued_seq"], r["acked_ts"])
        )

    def checkpoints(self) -> list[Checkpoint]:
        with self._lock:
            rows = self._db.execute("SELECT * FROM checkpoint ORDER BY cursor_key").fetchall()
        return [Checkpoint(r["cursor_key"], r["fetched_ts"], r["queued_ts"], r["queued_seq"], r["acked_ts"]) for r in rows]

    def batch(self, batch_id: str) -> BatchRow | None:
        with self._lock:
            r = self._db.execute("SELECT * FROM batch WHERE batch_id = ?", (batch_id,)).fetchone()
        return self._batch(r) if r else None

    def batches(self, status: str | None = None) -> list[BatchRow]:
        with self._lock:
            rows = self._db.execute("SELECT * FROM batch ORDER BY id").fetchall()
        return [self._batch(r) for r in rows if status is None or r["status"] == status]

    def stats(self) -> QueueStats:
        with self._lock:
            by = {r["status"]: r["n"] for r in self._db.execute("SELECT status, COUNT(*) AS n FROM batch GROUP BY status")}
            pending = self._pending_bytes(self._db)
            old = self._db.execute("SELECT MIN(created_at) AS t FROM batch WHERE status IN ('QUEUED','SENDING','RETRY_WAIT')").fetchone()[
                "t"
            ]
            dropped = self._db.execute("SELECT COUNT(*) FROM batch WHERE dead_reason = 'OVERFLOW_DROPPED'").fetchone()[0]
        return QueueStats(by, pending, old, int(dropped), by.get("DEAD", 0), self._free_bytes())

    def housekeeping(self, acked_keep_seconds: float = 86400.0, dead_keep_seconds: float = 7 * 86400.0) -> int:
        """古い ACKED / DEAD の行を消す（本文はすでに消してある）。取得セッションの行は参照されなくなったものだけ。"""
        now = self._now()
        with self._tx() as db:
            n = db.execute("DELETE FROM batch WHERE status = 'ACKED' AND acked_at < ?", (now - acked_keep_seconds,)).rowcount
            n += db.execute("DELETE FROM batch WHERE status = 'DEAD' AND updated_at < ?", (now - dead_keep_seconds,)).rowcount
            db.execute(
                "DELETE FROM fetch_session WHERE state IN ('COMPLETED','ABORTED','INTERRUPTED') AND completed_at < ? "
                "AND NOT EXISTS (SELECT 1 FROM batch b WHERE b.fetch_id = fetch_session.fetch_id)",
                (now - dead_keep_seconds,),
            )
        return n

    # ------------------------------------------------------------------ 状態・アラート
    def set_state(self, key: str, value: str) -> None:
        with self._tx() as db:
            db.execute(
                "INSERT INTO state (k, v, updated_at) VALUES (?,?,?) ON CONFLICT(k) DO UPDATE SET v = excluded.v, updated_at = excluded.updated_at",
                (key, value, self._now()),
            )

    def get_state(self, key: str) -> str | None:
        with self._lock:
            r = self._db.execute("SELECT v FROM state WHERE k = ?", (key,)).fetchone()
        return None if r is None else str(r["v"])

    def raise_alert(self, name: str, detail: str) -> None:
        with self._tx() as db:
            db.execute(
                "INSERT INTO alert (name, detail, raised_at) VALUES (?,?,?) ON CONFLICT(name) DO UPDATE SET detail = excluded.detail",
                (name, detail[:200], self._now()),
            )

    def clear_alert(self, name: str) -> bool:
        with self._tx() as db:
            return db.execute("DELETE FROM alert WHERE name = ?", (name,)).rowcount > 0

    def alerts(self) -> dict[str, str]:
        with self._lock:
            return {r["name"]: r["detail"] for r in self._db.execute("SELECT name, detail FROM alert ORDER BY name")}

    # ------------------------------------------------------------------ 検査（verify-queue）
    def verify(self) -> list[str]:
        """問題の一覧（空なら健全）。内容（本文）は出さない。"""
        problems: list[str] = []
        with self._lock:
            db = self._db
            ic = db.execute("PRAGMA integrity_check").fetchall()
            if [r[0] for r in ic] != ["ok"]:
                problems.append("SQLITE_INTEGRITY")
            if db.execute("PRAGMA foreign_key_check").fetchall():
                problems.append("FOREIGN_KEY")
            if db.execute("SELECT COUNT(*) FROM batch WHERE status = 'SENDING'").fetchone()[0] > 1:
                problems.append("MULTIPLE_SENDING")
            for s in db.execute("SELECT fetch_id, next_seq FROM fetch_session"):
                seqs = [r[0] for r in db.execute("SELECT fetch_seq FROM batch WHERE fetch_id = ? ORDER BY fetch_seq", (s["fetch_id"],))]
                if seqs != list(range(len(seqs))) or len(seqs) != s["next_seq"]:
                    problems.append("FETCH_SEQ_GAP")
                eofs = db.execute("SELECT COUNT(*) FROM batch WHERE fetch_id = ? AND eof = 1", (s["fetch_id"],)).fetchone()[0]
                if eofs > 1:
                    problems.append("MULTIPLE_EOF")
            for r in db.execute("SELECT * FROM checkpoint"):
                a, f = r["acked_ts"], r["fetched_ts"]
                if a is not None and (f is None or a > f):
                    # acked が fetched より先に進むのは、自分の取得がサーバのカーソルを確定させた場合に限る（fetched ≥ acked）
                    problems.append("ACKED_AHEAD_OF_FETCHED")
            for r in db.execute("SELECT fetch_id FROM fetch_session WHERE state = 'COMPLETED' AND cursor_confirmed = 1"):
                eof_acked = db.execute(
                    "SELECT COUNT(*) FROM batch WHERE fetch_id = ? AND eof = 1 AND status = 'ACKED'", (r["fetch_id"],)
                ).fetchone()[0]
                if eof_acked != 1:
                    problems.append("COMPLETED_WITHOUT_EOF_ACK")
        return sorted(set(problems))

    def snapshot_checkpoints(self) -> dict[str, Any]:
        return {c.cursor_key: {"fetched": c.fetched_ts, "queued": c.queued_ts, "acked": c.acked_ts} for c in self.checkpoints()}
