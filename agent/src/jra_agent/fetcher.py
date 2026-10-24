"""取得の実行（JV-Link ワーカーを通して読み、バッチにしてキューへ入れる）。送信はしない（sender の責務）。

安全策:
 - **仕様ゲート**: 公式仕様で未確認の項目に依存する取得は開始しない（`SpecRegistry.require`）。
 - 同じ種別の取得が実行中（`READING` のセッションがある）なら、新しく始めない（重複起動の防止）。
 - 取得の途中で NORMAL でなくなったら、**現在の安全な区切り（ファイル切替/バッチ境界）で止める**。読み込んだ分はキューに残す。取得は完了にしない。
 - ワーカーのタイムアウト・クラッシュ・キュー満杯・不正なレコードは、セッションを中止（`acked_ts`・`fetched_ts` は動かさない）。
 - ログに生データを出さない（件数・バイト長・コードだけ）。
"""

from __future__ import annotations

import logging
import re
from collections.abc import Callable
from dataclasses import dataclass
from datetime import timedelta
from enum import Enum

from .clock import Clock
from .config import QueueConfig
from .envelope import FetchInfo, RawRecord, batch_class_of, build_envelope, uuid7
from .errors import QueueFullError
from .jvlink.spec import FETCH_REALTIME_ITEMS, FETCH_STORED_ITEMS, SpecRegistry, SpecUnconfirmedError
from .jvlink.supervisor import WorkerError, WorkerSupervisor, decode_records
from .plan import PlanItem
from .queue import OutboxQueue

log = logging.getLogger(__name__)
REC_ID_RE = re.compile(r"[A-Z0-9]{2}")
FROMTIME_MARGIN = timedelta(hours=6)  # acked_ts から重なりを持たせて読み直す（JVLINK_DESIGN §5.2。重複はサーバが吸収）


class FetchResult(Enum):
    COMPLETED = "COMPLETED"  # EOF まで読み、全バッチをキューに入れた（取得済み。完了の確認はサーバの cursor）
    STOPPED_SAFE = "STOPPED_SAFE"  # モードが変わったため安全な区切りで止めた
    ABORTED = "ABORTED"
    SKIPPED = "SKIPPED"  # ゲート・重複・NORMAL でない
    NO_DATA = "NO_DATA"


@dataclass(frozen=True)
class FetchReport:
    result: FetchResult
    reason: str | None = None
    records: int = 0
    batches: int = 0
    open_code: int | None = None


def cursor_key(dataspec: str, option: int | None) -> str:
    return dataspec if option is None else f"{dataspec}:{option}"  # サーバの IngestCursors.dataType と同じ


def fromtime_from(acked_ts: str | None, seed: str | None) -> str:
    """次回の fromtime。acked_ts があれば `acked_ts - margin`、無ければ計画の key（14 桁）または全期間を表す 0 埋め（参照元実装の使用実態）。"""
    from datetime import datetime

    if acked_ts:
        t = datetime.strptime(acked_ts, "%Y%m%d%H%M%S") - FROMTIME_MARGIN
        return t.strftime("%Y%m%d%H%M%S")
    if seed and re.fullmatch(r"[0-9]{14}", seed):
        return seed
    return "00000000000000"


def target_range(item: PlanItem) -> tuple[str | None, str | None]:
    """YSCH（開催日程）の対象範囲。計画の key が 16 桁（`yyyyMMddyyyyMMdd` = targetFrom + targetTo）のときだけ申告する。
    申告が無い取得は、サーバが『予定なしを確認した』事実にしない（fail closed。CONTRACTS §3.4）。"""
    if item.dataspec == "YSCH" and item.key and re.fullmatch(r"[0-9]{16}", item.key):
        return item.key[:8], item.key[8:]
    return None, None


class Fetcher:
    def __init__(
        self,
        agent_id: str,
        queue: OutboxQueue,
        worker: WorkerSupervisor,
        clock: Clock,
        spec: SpecRegistry,
        qcfg: QueueConfig,
        may_fetch: Callable[[], bool],
        sid: str = "UNKNOWN",
    ) -> None:
        self._agent_id, self._q, self._w, self._clock, self._spec, self._cfg, self._may_fetch, self._sid = (
            agent_id,
            queue,
            worker,
            clock,
            spec,
            qcfg,
            may_fetch,
            sid,
        )
        self._inited = False
        self.last_jv: dict[str, int | str | bool] = {}

    # ------------------------------------------------------------ 共通
    def _init(self) -> None:
        if not self._inited:
            code = int(self._w.call("init", {"sid": self._sid})["code"])
            self.last_jv["init"] = code
            if code < 0:
                raise WorkerError(f"INIT_{code}")
            self._inited = True

    def _close_quietly(self) -> None:
        try:
            self.last_jv["close"] = int(self._w.call("close")["code"])
        except WorkerError:
            self._inited = False  # ワーカーが作り直されたら JVInit からやり直す

    # ------------------------------------------------------------ 蓄積系
    def fetch_stored(self, item: PlanItem, backfill_id: int | None = None) -> FetchReport:
        try:
            self._spec.require(*FETCH_STORED_ITEMS)
        except SpecUnconfirmedError as e:
            self._q.raise_alert("SPEC_UNCONFIRMED", "公式仕様未確認の項目があるため、実取得を開始しない")
            return FetchReport(FetchResult.SKIPPED, "SPEC_UNCONFIRMED:" + ",".join(e.items[:3]))
        if not self._may_fetch():
            return FetchReport(FetchResult.SKIPPED, "MODE_NOT_NORMAL")
        key = cursor_key(item.dataspec, item.option)
        if self._q.has_open_session(key):
            return FetchReport(FetchResult.SKIPPED, "ALREADY_FETCHING")
        assert item.option is not None
        origin = "BACKFILL" if backfill_id is not None else "LIVE"
        fromtime = fromtime_from(self._q.checkpoint(key).acked_ts if origin == "LIVE" else None, item.key)
        fid: str | None = None
        try:
            self._init()
            o = self._w.call("open_stored", {"dataspec": item.dataspec, "fromtime": fromtime, "option": item.option})
            self.last_jv.update(open=int(o["code"]), read_count=int(o.get("read_count", 0)))
            if int(o["code"]) < 0:
                return FetchReport(FetchResult.ABORTED, f"OPEN_{o['code']}", open_code=int(o["code"]))
            fid = uuid7()
            t_from, t_to = target_range(item)
            self._q.begin_session(
                fid,
                key,
                item.dataspec,
                item.option,
                fromtime,
                o.get("last_file_timestamp"),
                t_from,
                t_to,
                origin=origin,
                backfill_id=backfill_id,
            )
            return self._read_loop(item, fid, key, fromtime, o.get("last_file_timestamp"), int(o["code"]))
        except WorkerError as e:
            self._inited = False
            if fid:
                self._q.abort_session(fid, f"WORKER_{e.kind}")
            return FetchReport(FetchResult.ABORTED, f"WORKER_{e.kind}")
        except QueueFullError as e:
            if fid:
                self._q.abort_session(fid, f"QUEUE_{e}")
            self._close_quietly()
            self._q.raise_alert("QUEUE_FULL", f"キューに入れられない（{e}）。取得を停止")
            return FetchReport(FetchResult.ABORTED, f"QUEUE_{e}")

    def _read_loop(self, item: PlanItem, fid: str, key: str, fromtime: str, last_ts: str | None, open_code: int) -> FetchReport:
        pending: list[RawRecord] = []
        pending_bytes = 0
        total = batches = 0

        def flush(eof: bool) -> None:
            nonlocal pending, pending_bytes, batches
            if not pending and not eof:
                return
            s = self._q.session(fid)
            assert s is not None
            bid = uuid7()
            payload = build_envelope(
                agent_id=self._agent_id, batch_id=bid, dataspec=item.dataspec, observed_at=self._clock.now(), records=pending, eof=eof,
                option=item.option, fromtime=fromtime, last_file_timestamp=last_ts,
                fetch=FetchInfo(fid, s.next_seq, s.target_from, s.target_to, s.origin, s.backfill_id),
            )  # fmt: skip
            self._q.enqueue(
                batch_id=bid,
                cls="S",
                dataspec=item.dataspec,
                payload=payload,
                record_count=len(pending),
                eof=eof,
                fetch_id=fid,
                last_file_timestamp=last_ts,
            )
            batches += 1
            pending, pending_bytes = [], 0

        while True:
            r = self._w.call(
                "read_batch",
                {
                    "max_records": self._cfg.max_batch_records,
                    "max_bytes": self._cfg.max_batch_raw_bytes,
                    "max_polls": 10,
                    "poll_sleep": 0.5,
                },
            )
            self.last_jv["read"] = int(r.get("code", 0))
            for data in decode_records(r):
                rid = data[:2].decode("ascii", errors="replace")
                if not REC_ID_RE.fullmatch(rid):
                    self._q.abort_session(fid, "BAD_RECORD_ID")  # 勝手に捨てない。取得を中止して知らせる
                    self._q.raise_alert("BAD_RECORD", "レコード種別 ID が不正。取得を中止した（件数・内容は記録しない）")
                    self._close_quietly()
                    return FetchReport(FetchResult.ABORTED, "BAD_RECORD_ID", total, batches, open_code)
                if len(pending) >= self._cfg.max_batch_records or pending_bytes + len(data) > self._cfg.max_batch_raw_bytes:
                    flush(False)
                pending.append(RawRecord(rid, data))
                pending_bytes += len(data)
                total += 1
            status = r["status"]
            if status == "ERROR":
                self._q.abort_session(fid, f"READ_{r['code']}")
                self._close_quietly()
                return FetchReport(FetchResult.ABORTED, f"READ_{r['code']}", total, batches, open_code)
            if status == "EOF":
                flush(True)  # EOF のバッチ: ここで初めて fetched/queued が動く
                self._close_quietly()
                return FetchReport(FetchResult.COMPLETED, None, total, batches, open_code)
            if status in ("FILE_SWITCH", "MORE", "DOWNLOADING") and not self._may_fetch():
                flush(False)
                self._q.abort_session(fid, "MODE_CHANGED", state="INTERRUPTED")  # 現在の安全な区切りで止める。完了にしない
                self._close_quietly()
                return FetchReport(FetchResult.STOPPED_SAFE, "MODE_CHANGED", total, batches, open_code)
            if status == "FILE_SWITCH":
                flush(False)

    # ------------------------------------------------------------ 速報系
    def fetch_realtime(self, item: PlanItem) -> FetchReport:
        try:
            self._spec.require(*FETCH_REALTIME_ITEMS)
        except SpecUnconfirmedError as e:
            self._q.raise_alert("SPEC_UNCONFIRMED", "公式仕様未確認の項目があるため、実取得を開始しない")
            return FetchReport(FetchResult.SKIPPED, "SPEC_UNCONFIRMED:" + ",".join(e.items[:3]))
        if not self._may_fetch():
            return FetchReport(FetchResult.SKIPPED, "MODE_NOT_NORMAL")
        assert item.key is not None
        observed = self._clock.now()  # 観測時刻 = JVRTOpen を呼んだ時刻（UTC）
        total = batches = 0
        try:
            self._init()
            o = self._w.call("open_realtime", {"dataspec": item.dataspec, "key": item.key})
            self.last_jv.update(open=int(o["code"]))
            if int(o["code"]) < 0:
                return FetchReport(FetchResult.ABORTED, f"OPEN_{o['code']}", open_code=int(o["code"]))
            while True:
                r = self._w.call(
                    "read_batch",
                    {
                        "max_records": self._cfg.max_batch_records,
                        "max_bytes": self._cfg.max_batch_raw_bytes,
                        "max_polls": 10,
                        "poll_sleep": 0.5,
                    },
                )
                recs = []
                for data in decode_records(r):
                    rid = data[:2].decode("ascii", errors="replace")
                    if not REC_ID_RE.fullmatch(rid):
                        self._close_quietly()
                        self._q.raise_alert("BAD_RECORD", "レコード種別 ID が不正。速報の取得を中止した")
                        return FetchReport(FetchResult.ABORTED, "BAD_RECORD_ID", total, batches)
                    recs.append(RawRecord(rid, data))
                status = r["status"]
                if recs:
                    bid = uuid7()
                    payload = build_envelope(
                        agent_id=self._agent_id,
                        batch_id=bid,
                        dataspec=item.dataspec,
                        observed_at=observed,
                        records=recs,
                        eof=status == "EOF",
                    )
                    self._q.enqueue(
                        batch_id=bid,
                        cls=batch_class_of(item.dataspec),
                        dataspec=item.dataspec,
                        payload=payload,
                        record_count=len(recs),
                        eof=status == "EOF",
                        observed_at=observed.timestamp(),
                    )
                    total += len(recs)
                    batches += 1
                if status == "ERROR":
                    self._close_quietly()
                    return FetchReport(FetchResult.ABORTED, f"READ_{r['code']}", total, batches)
                if status == "EOF":
                    self._close_quietly()
                    return FetchReport(FetchResult.COMPLETED if total else FetchResult.NO_DATA, None, total, batches)
                if not self._may_fetch():
                    self._close_quietly()
                    return FetchReport(FetchResult.STOPPED_SAFE, "MODE_CHANGED", total, batches)
        except WorkerError as e:
            self._inited = False
            return FetchReport(FetchResult.ABORTED, f"WORKER_{e.kind}", total, batches)
        except QueueFullError as e:
            self._close_quietly()
            self._q.raise_alert("QUEUE_FULL", f"キューに入れられない（{e}）。取得を停止")
            return FetchReport(FetchResult.ABORTED, f"QUEUE_{e}", total, batches)
