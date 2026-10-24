"""Mock による G-AGENT-1〜6 の事前検証（実 JV-Link を一切使わない。どの OS でも動く）。実機の合否の代わりにはならない（`MOCK_PASS`）。

  python -m poc.poc_mock --out result-mock.json

G-AGENT-1 は『COM を作れるか』なので Mock では検証できない（NOT_RUN）。G-AGENT-6 のタスクスケジューラ/スリープは実機だけ（`poc_sleep.py` と POC.md）。
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import ClassVar

from jra_agent.clock import SystemClock
from jra_agent.config import QueueConfig, WorkerConfig
from jra_agent.envelope import RawRecord, build_envelope, uuid7
from jra_agent.jvlink.fake import FakeScript
from jra_agent.jvlink.supervisor import WorkerSupervisor, WorkerTimeout, decode_records
from jra_agent.queue import AckInfo, OutboxQueue

from .poc_common import Aggregate, GateResult, Timer, write_report

# 全バイト値を含む合成レコード（cp932 の欠損・変換の検査用）。実データではない
SYNTHETIC = [bytes([0x52, 0x41]) + bytes(range(1, 256)) * 3 + "日本語".encode("cp932") for _ in range(20)]


def _worker(script: FakeScript, tmp: Path, call_timeout: float = 5.0) -> WorkerSupervisor:
    p = tmp / "script.json"
    p.write_text(script.to_json(), encoding="utf-8")
    argv = [sys.executable, "-m", "jra_agent.jvlink.worker_main", "--backend", "fake", "--script", str(p)]
    return WorkerSupervisor(argv, WorkerConfig(call_timeout_seconds=call_timeout, max_restarts=10), SystemClock())


def g2_bytes(tmp: Path) -> GateResult:
    sup = _worker(FakeScript(files=[SYNTHETIC[:10], SYNTHETIC[10:]]), tmp)
    agg = Aggregate()
    try:
        sup.call("open_stored", {"dataspec": "RACE", "fromtime": "00000000000000", "option": 1})
        got: list[bytes] = []
        while True:
            with Timer() as t:
                r = sup.call("read_batch", {"max_records": 7, "max_bytes": 100_000, "max_polls": 3, "poll_sleep": 0.0})
            agg.add_duration(t.seconds)
            agg.add_code(r["status"])
            got += decode_records(r)
            if r["status"] == "EOF":
                break
        for g in got:
            agg.add_record(g)
    finally:
        sup.stop()
    ok = got == SYNTHETIC
    return GateResult("G-AGENT-2", "MOCK_PASS" if ok else "FAIL", "" if ok else "バイト列が一致しない", {**agg.summary(), "byte_exact": ok})


def g3_kinds(tmp: Path) -> GateResult:
    agg = Aggregate()
    sup = _worker(FakeScript(files=[SYNTHETIC[:3]], realtime_records=SYNTHETIC[3:5]), tmp)
    try:
        sup.call("open_stored", {"dataspec": "RACE", "fromtime": "00000000000000", "option": 2})
        stored = decode_records(sup.call("read_batch", {"max_records": 99, "max_bytes": 10**6}))
        sup.call("close")
        sup.call("open_realtime", {"dataspec": "0B12", "key": "20261101"})
        rt = decode_records(sup.call("read_batch", {"max_records": 99, "max_bytes": 10**6}))
        for d in stored + rt:
            agg.add_record(d)
    finally:
        sup.stop()
    ok = len(stored) == 3 and len(rt) == 2
    return GateResult("G-AGENT-3", "MOCK_PASS" if ok else "FAIL", "", {**agg.summary(), "stored": len(stored), "realtime": len(rt)})


def g4_hang(tmp: Path) -> GateResult:
    sup = _worker(FakeScript(files=[SYNTHETIC], hang_after=3), tmp, call_timeout=1.5)
    try:
        sup.call("open_stored", {"dataspec": "RACE", "fromtime": "00000000000000", "option": 1})
        t0 = time.perf_counter()
        hung = False
        try:
            sup.call("read_batch", {"max_records": 99, "max_bytes": 10**6})
        except WorkerTimeout:
            hung = True
        detect = time.perf_counter() - t0
        t1 = time.perf_counter()
        recovered = sup.call("open_stored", {"dataspec": "RACE", "fromtime": "00000000000000", "option": 1})["code"] == 0
        recover = time.perf_counter() - t1
    finally:
        sup.stop()
    ok = hung and recovered
    return GateResult(
        "G-AGENT-4",
        "MOCK_PASS" if ok else "FAIL",
        "",
        {
            "hang_detected": hung,
            "detect_seconds": round(detect, 2),
            "recovered": recovered,
            "recover_seconds": round(recover, 2),
            "timeouts": sup.timeouts,
        },
    )


class _Mock(BaseHTTPRequestHandler):
    seen: ClassVar[set[str]] = set()

    def do_POST(self) -> None:
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        bid = json.loads(__import__("gzip").decompress(body) if self.headers.get("Content-Encoding") == "gzip" else body)["batchId"]
        dup = bid in _Mock.seen
        _Mock.seen.add(bid)
        out = json.dumps(
            {
                "accepted": 0 if dup else 1,
                "duplicates": int(dup),
                "quarantined": 0,
                "unsupported": 0,
                "duplicate": dup,
                "ackTs": "t",
                "serverTime": "t",
                "fetch": None,
                "cursor": None,
            }
        ).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def log_message(self, *_a: object) -> None:
        pass


def g5_queue(tmp: Path) -> GateResult:
    """SQLite WAL キュー: 送信中に強制終了 → 再起動 → 同じ batchId で再送され、重複登録されない。"""
    _Mock.seen = set()
    srv = ThreadingHTTPServer(("127.0.0.1", 0), _Mock)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    db = tmp / "poc-queue.sqlite3"
    clock = SystemClock()
    q = OutboxQueue(db, clock, QueueConfig(min_free_bytes=0))
    ids = []
    for i in range(5):
        bid = uuid7()
        payload = build_envelope(
            agent_id="ag_poc",
            batch_id=bid,
            dataspec="0B12",
            observed_at=clock.now(),
            records=[RawRecord("O1", b"O1" + bytes([i]) * 20)],
            eof=True,
        )
        q.enqueue(batch_id=bid, cls="R", dataspec="0B12", payload=payload, record_count=1, eof=True)
        ids.append(bid)
    q.close()
    port = srv.server_address[1]
    child = (
        "import sys,urllib.request;from jra_agent.clock import SystemClock;from jra_agent.queue import OutboxQueue,AckInfo;"
        "q=OutboxQueue(__import__('pathlib').Path(sys.argv[1]),SystemClock());b=q.claim_next();"
        f"urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:{port}/',data=b.payload,method='POST')).read();"
        "import os;os._exit(9)"  # 送信は届いたが ACK を記録する前に強制終了
    )
    subprocess.run([sys.executable, "-c", child, str(db)], check=False, env={**os.environ}, timeout=60)
    q2 = OutboxQueue(db, clock, QueueConfig(min_free_bytes=0))
    rec = q2.recover()
    import urllib.request

    acked = 0
    while (b := q2.claim_next()) is not None:
        r = json.loads(urllib.request.urlopen(urllib.request.Request(f"http://127.0.0.1:{port}/", data=b.payload, method="POST")).read())
        q2.mark_acked(b.batch_id, AckInfo(accepted=r["accepted"], duplicate=r["duplicate"]))
        acked += 1
    problems = q2.verify()
    wal = str(q2._db.execute("PRAGMA journal_mode").fetchone()[0])
    q2.close()
    srv.shutdown()
    ok = rec["sending_requeued"] == 1 and acked == 5 and len(_Mock.seen) == 5 and not problems and wal == "wal"
    return GateResult(
        "G-AGENT-5",
        "MOCK_PASS" if ok else "FAIL",
        "",
        {
            "requeued_after_crash": rec["sending_requeued"],
            "acked": acked,
            "unique_batches_on_server": len(_Mock.seen),
            "problems": problems,
            "journal_mode": wal,
        },
    )


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=Path)
    ns = ap.parse_args(argv)
    results = [GateResult("G-AGENT-1", "NOT_RUN", "COM の生成は実機でだけ確認できる（poc_real.py g1）")]
    with tempfile.TemporaryDirectory(prefix="jra-poc-") as d:
        tmp = Path(d)
        for fn in (g2_bytes, g3_kinds, g4_hang, g5_queue):
            try:
                results.append(fn(tmp))
            except Exception as e:
                results.append(GateResult(fn.__name__, "FAIL", f"例外 {type(e).__name__}"))
    results.append(GateResult("G-AGENT-6", "NOT_RUN", "タスクスケジューラ/スリープ復帰は実機だけ（poc_sleep.py と POC.md）"))
    doc = write_report(ns.out, "mock", results)
    return 0 if all(g["status"] in ("MOCK_PASS", "NOT_RUN") for g in doc["gates"]) else 1


if __name__ == "__main__":
    sys.exit(main())
