"""Windows 固有部分（SDK・JV-Link 不要）。Windows ランナーで実行する。Linux では skip（CI は Windows ジョブで『実行された件数』を検査する）。"""

from __future__ import annotations

import subprocess
import sys
import time
from pathlib import Path

import pytest

from jra_agent.errors import SecretError
from jra_agent.secrets_store import Credential, CredentialStore, ProtectedFileSecretStore

pytestmark = pytest.mark.windows
win_only = pytest.mark.skipif(sys.platform != "win32", reason="Windows only")


@win_only
def test_dpapi_roundtrip_and_entropy_binding(tmp_path: Path) -> None:
    from jra_agent.winapi import DpapiProtector

    p = DpapiProtector()
    blob = p.protect(b"jrat1_dpapi-secret-value", b"e1")
    assert b"dpapi-secret-value" not in blob and p.unprotect(blob, b"e1") == b"jrat1_dpapi-secret-value"
    with pytest.raises(SecretError):
        p.unprotect(blob, b"other-entropy")
    store = CredentialStore(ProtectedFileSecretStore(tmp_path, p))
    store.save(Credential("ag_main", "jrat1_dpapi-secret-value", None))
    assert all(b"dpapi-secret-value" not in f.read_bytes() for f in tmp_path.iterdir())
    assert store.load().token == "jrat1_dpapi-secret-value"  # type: ignore[union-attr]


@win_only
def test_job_object_kills_child_tree_and_terminate_tree_releases() -> None:
    from jra_agent.jvlink import procutil

    proc = procutil.spawn([sys.executable, "-c", "import time;time.sleep(60)"])
    t0 = time.time()
    procutil.terminate_tree(proc)
    assert proc.poll() is not None and time.time() - t0 < 30


@win_only
def test_instance_lock_on_windows(tmp_path: Path) -> None:
    from jra_agent.lock import AlreadyRunningError, InstanceLock

    with InstanceLock(tmp_path / "l"):
        with pytest.raises(AlreadyRunningError):
            InstanceLock(tmp_path / "l").acquire()
    subprocess.run([sys.executable, "-c", "print(1)"], check=True, capture_output=True)


@win_only
def test_worker_hang_recovery_on_windows(tmp_path: Path) -> None:
    from jra_agent.clock import FakeClock
    from jra_agent.config import WorkerConfig
    from jra_agent.jvlink.fake import FakeScript
    from jra_agent.jvlink.supervisor import WorkerSupervisor, WorkerTimeout

    p = tmp_path / "s.json"
    p.write_text(FakeScript(files=[[b"RA0001"]], hang_after=0).to_json(), encoding="utf-8")
    sup = WorkerSupervisor([sys.executable, "-m", "jra_agent.jvlink.worker_main", "--backend", "fake", "--script", str(p)], WorkerConfig(call_timeout_seconds=2.0), FakeClock())
    try:
        sup.call("open_stored", {"dataspec": "RACE", "fromtime": "0" * 14, "option": 1})
        with pytest.raises(WorkerTimeout):
            sup.call("read_batch", {"max_records": 5, "max_bytes": 1000})
        assert sup.call("ping") == {"pong": True}
    finally:
        sup.stop()
