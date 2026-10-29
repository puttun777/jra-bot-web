"""COM ワーカーの分離・ウォッチドッグ・回復（実際に子プロセスを起動する。COM の代わりに Fake。ハング・クラッシュを注入する）。"""

from __future__ import annotations

import os
import sys
import time
from pathlib import Path

import pytest

from jra_agent.clock import FakeClock
from jra_agent.config import WorkerConfig
from jra_agent.jvlink.fake import FakeScript
from jra_agent.jvlink.supervisor import WorkerCrashed, WorkerQuarantined, WorkerRemoteError, WorkerSupervisor, WorkerTimeout, decode_records

REC = [f"RA{i:03d}".encode("cp932") + b"x" * 20 for i in range(5)]


def make(tmp_path: Path, script: FakeScript, **cfg: float) -> WorkerSupervisor:
    p = tmp_path / "script.json"
    p.write_text(script.to_json(), encoding="utf-8")
    c = WorkerConfig(
        call_timeout_seconds=cfg.get("call", 2.0),
        startup_timeout_seconds=cfg.get("startup", 15.0),
        max_restarts=int(cfg.get("max_restarts", 5)),
        restart_window_seconds=3600.0,
        restart_cooldown_seconds=60.0,
    )
    argv = [sys.executable, "-m", "jra_agent.jvlink.worker_main", "--backend", "fake", "--script", str(p)]
    return WorkerSupervisor(argv, c, FakeClock())


def test_reads_records_in_child_process_and_ends_at_eof(tmp_path: Path) -> None:
    sup = make(tmp_path, FakeScript(files=[REC[:3], REC[3:]]))
    try:
        assert sup.call("init", {"sid": "UNKNOWN"})["code"] == 0
        o = sup.call("open_stored", {"dataspec": "RACE", "fromtime": "20261030000000", "option": 2})
        assert o["code"] == 0 and o["last_file_timestamp"] == "20261031200000" and o["read_count"] == 5
        got, statuses = [], []
        while True:
            r = sup.call("read_batch", {"max_records": 2, "max_bytes": 10_000, "max_polls": 3, "poll_sleep": 0.0})
            got += decode_records(r)
            statuses.append(r["status"])
            if r["status"] == "EOF":
                break
        assert got == REC and "FILE_SWITCH" in statuses  # バイト列が欠けずに順番どおり。ファイル切替で区切れる
        assert sup.call("close")["code"] == 0
    finally:
        sup.stop()


def test_child_process_is_a_different_process_and_stop_terminates_it(tmp_path: Path) -> None:
    sup = make(tmp_path, FakeScript())
    sup.call("ping")
    h = sup._h
    assert h is not None and h.proc.pid != os.getpid()
    sup.stop()
    assert h.proc.poll() is not None


def test_hung_worker_is_killed_and_replaced_without_hanging_the_caller(tmp_path: Path) -> None:
    sup = make(tmp_path, FakeScript(files=[REC], hang_after=2), call=1.0)
    try:
        sup.call("init", {"sid": "x"})
        sup.call("open_stored", {"dataspec": "RACE", "fromtime": "0" * 14, "option": 1})
        first = sup._h.proc  # type: ignore[union-attr]
        t0 = time.monotonic()
        with pytest.raises(WorkerTimeout):
            sup.call("read_batch", {"max_records": 100, "max_bytes": 10_000})  # 3 件目の read で COM が戻らない
        assert time.monotonic() - t0 < 6.0
        assert first.poll() is not None  # ハングしたワーカーは強制終了された
        # 回復: 新しいワーカーで、最初から開き直せる（直前の open 状態は引き継がない）
        r = sup.call("open_stored", {"dataspec": "RACE", "fromtime": "0" * 14, "option": 1})
        assert r["code"] == 0 and sup.timeouts == 1 and sup.restarts == 1
    finally:
        sup.stop()


def test_hang_during_open_is_detected(tmp_path: Path) -> None:
    sup = make(tmp_path, FakeScript(hang_in_open=True), call=1.0)
    try:
        sup.call("init", {"sid": "x"})
        with pytest.raises(WorkerTimeout):
            sup.call("open_stored", {"dataspec": "RACE", "fromtime": "0" * 14, "option": 1})
    finally:
        sup.stop()


def test_crashed_worker_is_reported_and_restarted(tmp_path: Path) -> None:
    sup = make(tmp_path, FakeScript(files=[REC], crash_after=1))
    try:
        sup.call("open_stored", {"dataspec": "RACE", "fromtime": "0" * 14, "option": 1})
        with pytest.raises(WorkerCrashed):
            sup.call("read_batch", {"max_records": 100, "max_bytes": 10_000})
        assert sup.call("ping") == {"pong": True} and sup.crashes == 1
    finally:
        sup.stop()


def test_no_double_open_in_worker(tmp_path: Path) -> None:
    sup = make(tmp_path, FakeScript(files=[REC]))
    try:
        sup.call("open_stored", {"dataspec": "RACE", "fromtime": "0" * 14, "option": 1})
        with pytest.raises(WorkerRemoteError) as e:
            sup.call("open_stored", {"dataspec": "RACE", "fromtime": "0" * 14, "option": 1})
        assert e.value.kind == "RuntimeError"  # 同時に複数の JVOpen を実行しない
        sup.call("close")
        assert sup.call("open_stored", {"dataspec": "RACE", "fromtime": "0" * 14, "option": 1})["code"] == 0
    finally:
        sup.stop()


def test_restart_loop_is_limited_then_quarantined(tmp_path: Path) -> None:
    sup = make(tmp_path, FakeScript(files=[REC], crash_after=0), max_restarts=3)
    try:
        outcomes = []
        for _ in range(6):
            try:
                sup.call("open_stored", {"dataspec": "RACE", "fromtime": "0" * 14, "option": 1})
                sup.call("read_batch", {"max_records": 10, "max_bytes": 10_000})
            except WorkerQuarantined:
                outcomes.append("Q")
            except WorkerCrashed:
                outcomes.append("C")
        assert outcomes[:3] == ["C", "C", "C"] and "Q" in outcomes  # 3 回で再起動を止める（無限再起動ループにしない）
        starts_before = len(sup._starts)
        with pytest.raises(WorkerQuarantined):
            sup.call("ping")
        assert len(sup._starts) == starts_before
        sup._clock.advance(61)  # type: ignore[attr-defined]  # クールダウン後は再び起動を試みる
        sup._starts.clear()
        assert sup.call("ping") == {"pong": True}
    finally:
        sup.stop()


def test_downloading_is_reported_so_watchdog_sees_progress(tmp_path: Path) -> None:
    sup = make(tmp_path, FakeScript(files=[REC], downloading_polls=5))
    try:
        sup.call("open_stored", {"dataspec": "RACE", "fromtime": "0" * 14, "option": 1})
        r = sup.call("read_batch", {"max_records": 10, "max_bytes": 10_000, "max_polls": 2, "poll_sleep": 0.0})
        assert r["status"] == "DOWNLOADING" and r["records"] == []
    finally:
        sup.stop()


def test_non_object_json_line_from_worker_is_a_protocol_error_not_an_unexpected_exception() -> None:
    """監査: ワーカーが JSON でも辞書でない行（配列など）を返しても、WorkerCrashed（BAD_PROTOCOL）として扱い、ワーカーを捨てる。"""
    code = "import sys\nfor _ in sys.stdin:\n    print('[]', flush=True)\n"
    c = WorkerConfig(call_timeout_seconds=2.0, startup_timeout_seconds=5.0, max_restarts=5, restart_window_seconds=3600.0, restart_cooldown_seconds=60.0)
    sup = WorkerSupervisor([sys.executable, "-c", code], c, FakeClock())
    try:
        with pytest.raises(WorkerCrashed, match="BAD_PROTOCOL"):
            sup.call("ping")
    finally:
        sup.stop()
