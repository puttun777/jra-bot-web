"""取得計画の解釈、署名付き更新 manifest、多重起動の防止（別プロセスで排他を確認）。"""

from __future__ import annotations

import base64
import hashlib
import io
import json
import subprocess
import sys
import zipfile
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import pytest

from jra_agent.errors import VerificationError
from jra_agent.lock import AlreadyRunningError, InstanceLock
from jra_agent.plan import IDLE, PlanScheduler, parse_plan
from jra_agent.update import Installer, parse_version, verify_artifact, verify_manifest
from tests.beacon_support import BeaconSigner, b64u

NOW = datetime(2026, 11, 1, 0, 0, tzinfo=UTC)
GOOD = {
    "planId": "p-1", "validUntil": "2026-11-01T06:00:00Z", "idle": False,
    "items": [
        {"dataspec": "RACE", "option": 2, "interval": 300, "notBefore": "2026-11-01T00:00:00Z"},
        {"dataspec": "0B12", "key": "20261101", "interval": 60},
    ],
}  # fmt: skip


def test_good_plan_parses_and_is_usable_until_valid_until() -> None:
    p = parse_plan(GOOD)
    assert p.plan_id == "p-1" and len(p.items) == 2 and p.items[1].realtime and not p.items[0].realtime
    assert p.usable(NOW) and not p.usable(NOW + timedelta(hours=7))


@pytest.mark.parametrize(
    "bad",
    [
        None, [], "x", {"planId": "p", "items": [], "idle": False, "extra": 1},
        {"planId": "", "items": [], "idle": False},
        {"planId": "p", "items": [{"dataspec": "NOPE", "option": 1}], "idle": False},
        {"planId": "p", "items": [{"dataspec": "RACE"}], "idle": False},  # 蓄積系は option 必須
        {"planId": "p", "items": [{"dataspec": "0B12"}], "idle": False},  # 速報は key 必須
        {"planId": "p", "items": [{"dataspec": "0B12", "key": "k", "option": 1}], "idle": False},
        {"planId": "p", "items": [{"dataspec": "RACE", "option": 99}], "idle": False},
        {"planId": "p", "items": [{"dataspec": "RACE", "option": 1, "interval": 1}], "idle": False},
        {"planId": "p", "items": [{"dataspec": "RACE", "option": 1, "key": "../x"}], "idle": False},
        {"planId": "p", "items": [{"dataspec": "RACE", "option": 1, "notBefore": "2026-11-01T00:00:00"}], "idle": False},
        {"planId": "p", "items": [{"dataspec": "RACE", "option": 1, "bogus": 1}], "idle": False},
        {"planId": "p", "items": [{"dataspec": "RACE", "option": 1}] * 33, "idle": False},
    ],
)  # fmt: skip
def test_invalid_plan_becomes_idle_entirely(bad: object) -> None:
    assert parse_plan(bad) is IDLE


def test_idle_flag_empty_and_expired_plans_do_nothing() -> None:
    s = PlanScheduler()
    assert s.due(IDLE, NOW, 0.0) == []
    assert s.due(parse_plan({**GOOD, "idle": True}), NOW, 0.0) == []
    assert s.due(parse_plan({"planId": "p", "items": [], "idle": False}), NOW, 0.0) == []
    assert s.due(parse_plan(GOOD), NOW + timedelta(days=1), 0.0) == []


def test_not_before_interval_and_duplicate_start() -> None:
    s, p = PlanScheduler(), parse_plan(GOOD)
    early = NOW - timedelta(minutes=1)
    assert [i.dataspec for i in s.due(p, early, 0.0)] == ["0B12"]  # RACE はまだ notBefore 前
    due = s.due(p, NOW, 0.0)
    assert [i.dataspec for i in due] == ["RACE", "0B12"]
    assert s.start(due[0], 0.0) and not s.start(due[0], 1.0)  # 同じ項目の重複起動は拒否
    assert [i.dataspec for i in s.due(p, NOW, 1.0)] == ["0B12"]  # 実行中の RACE は出ない
    s.finish(due[0])
    assert s.due(p, NOW, 10.0)[0].dataspec == "0B12"  # RACE は interval(300) の間は出ない
    assert "RACE" in [i.dataspec for i in s.due(p, NOW, 301.0)]


# ------------------------------------------------------------------ 更新
def make_manifest(signer: BeaconSigner, data: bytes, version: str = "0.2.0", **over: Any) -> dict[str, object]:
    from jra_agent.beacon import canonical_bytes

    body: dict[str, object] = {
        "schema_version": 1, "version": version, "platform": "win-x64", "sha256": hashlib.sha256(data).hexdigest(), "size": len(data),
        "url": "https://releases.example/agent.zip", "issuedAt": "2026-11-01T00:00:00Z", "kid": signer.kid,
    }  # fmt: skip
    body.update(over)
    return {**body, "sig": b64u(signer.key.sign(canonical_bytes(body)))}


def zip_bytes(files: dict[str, bytes]) -> bytes:
    b = io.BytesIO()
    with zipfile.ZipFile(b, "w") as z:
        for n, d in files.items():
            z.writestr(n, d)
    return b.getvalue()


def test_valid_manifest_and_artifact() -> None:
    s, data = BeaconSigner(kid="upd-a"), zip_bytes({"agent.exe": b"x"})
    m = verify_manifest(make_manifest(s, data), (s.public,), "0.1.0")
    assert m.version == "0.2.0"
    verify_artifact(data, m)
    with pytest.raises(VerificationError, match="HASH_MISMATCH"):
        verify_artifact(bytes([data[0] ^ 1]) + data[1:], m)
    with pytest.raises(VerificationError, match="SIZE_MISMATCH"):
        verify_artifact(data + b"x", m)


@pytest.mark.parametrize(
    ("over", "reason"),
    [
        ({"version": "0.1.0"}, "NOT_NEWER"),  # 同一
        ({"version": "0.0.9"}, "NOT_NEWER"),  # 巻き戻り
        ({"platform": "linux"}, "WRONG_PLATFORM"),
        ({"url": "http://x.example/a.zip"}, "BAD_URL"),
        ({"sha256": "zz"}, "BAD_SHA256"),
        ({"size": 0}, "BAD_SIZE"),
        ({"size": 10**10}, "BAD_SIZE"),
        ({"version": "1.0"}, "BAD_VERSION"),
    ],
)
def test_manifest_rules(over: dict[str, Any], reason: str) -> None:
    s, data = BeaconSigner(), b"x" * 10
    with pytest.raises(VerificationError) as e:
        verify_manifest(make_manifest(s, data, **over), (s.public,), "0.1.0")
    assert e.value.reason == reason


def test_forged_manifest_is_rejected() -> None:
    s, other, data = BeaconSigner(kid="upd-a"), BeaconSigner(kid="upd-a"), b"x" * 10
    with pytest.raises(VerificationError, match="BAD_SIGNATURE"):
        verify_manifest(make_manifest(other, data), (s.public,), "0.1.0")  # 別の鍵で署名
    tampered = make_manifest(s, data)
    tampered["sha256"] = "0" * 64  # 署名後に差し替え
    with pytest.raises(VerificationError, match="BAD_SIGNATURE"):
        verify_manifest(tampered, (s.public,), "0.1.0")
    unsigned = make_manifest(s, data)
    unsigned.pop("sig")
    with pytest.raises(VerificationError, match="BAD_SHAPE"):
        verify_manifest(unsigned, (s.public,), "0.1.0")


def test_stage_activate_rollback_cycle_and_recovery_from_failed_update(tmp_path: Path) -> None:
    s = BeaconSigner()
    inst = Installer(tmp_path / "install")
    d1 = zip_bytes({"agent.exe": b"v1", "lib/a.dll": b"a"})
    m1 = verify_manifest(make_manifest(s, d1, "0.2.0"), (s.public,), "0.1.0")
    p = inst.stage(d1, m1)
    assert (p / "agent.exe").read_bytes() == b"v1" and inst.state()["current"] is None
    inst.activate("0.2.0")
    d2 = zip_bytes({"agent.exe": b"v2"})
    m2 = verify_manifest(make_manifest(s, d2, "0.3.0"), (s.public,), "0.2.0")
    inst.stage(d2, m2)
    inst.activate("0.3.0")
    assert inst.state() == {"current": "0.3.0", "previous": "0.2.0", "staged": None}
    assert inst.rollback() == "0.2.0" and inst.state()["current"] == "0.2.0"  # 更新後に失敗 → 直前の版へ
    with pytest.raises(VerificationError, match="NO_PREVIOUS"):
        inst.rollback()
    with pytest.raises(VerificationError, match="NOT_STAGED"):
        inst.activate("9.9.9")


def test_stage_rejects_unsafe_archives_and_hash_mismatch(tmp_path: Path) -> None:
    s = BeaconSigner()
    inst = Installer(tmp_path / "install")
    for name in ("../evil.exe", "/abs.exe", "a/../../evil"):
        data = zip_bytes({name: b"x"})
        m = verify_manifest(make_manifest(s, data), (s.public,), "0.1.0")
        with pytest.raises(VerificationError, match="UNSAFE_PATH"):
            inst.stage(data, m)
    data = zip_bytes({"a": b"x"})
    m = verify_manifest(make_manifest(s, data), (s.public,), "0.1.0")
    with pytest.raises(VerificationError, match="HASH_MISMATCH"):
        inst.stage(zip_bytes({"a": b"y"}), m)
    assert not (tmp_path / "evil.exe").exists() and not (tmp_path / "install" / "versions" / "0.2.0").exists()
    assert not list((tmp_path / "install" / "versions").glob(".*tmp"))  # 失敗した展開の残骸を残さない


def test_version_ordering() -> None:
    assert parse_version("1.10.0") > parse_version("1.9.9") and parse_version("0.2.0") > parse_version("0.1.9")
    json.dumps(base64.b64encode(b"x").decode())


# ------------------------------------------------------------------ 多重起動
def test_instance_lock_excludes_second_process(tmp_path: Path) -> None:
    path = tmp_path / "agent.lock"
    code = "import sys;from pathlib import Path;from jra_agent.lock import InstanceLock,AlreadyRunningError\ntry:\n  InstanceLock(Path(sys.argv[1])).acquire();print('ACQUIRED')\nexcept AlreadyRunningError:\n  print('BLOCKED')"
    with InstanceLock(path):
        out = subprocess.run(
            [sys.executable, "-c", code, str(path)], capture_output=True, text=True, timeout=60, check=False
        ).stdout.strip()
        assert out == "BLOCKED"
        with pytest.raises(AlreadyRunningError):
            InstanceLock(path).acquire()
    out = subprocess.run([sys.executable, "-c", code, str(path)], capture_output=True, text=True, timeout=60, check=False).stdout.strip()
    assert out == "ACQUIRED"  # 解放後は取れる


def test_lock_is_released_when_holder_process_dies(tmp_path: Path) -> None:
    path = tmp_path / "agent.lock"
    holder = subprocess.Popen(
        [sys.executable, "-c", "import sys,time;from pathlib import Path;from jra_agent.lock import InstanceLock;L=InstanceLock(Path(sys.argv[1]));L.acquire();print('READY',flush=True);time.sleep(60)", str(path)],
        stdout=subprocess.PIPE, text=True,
    )  # fmt: skip
    try:
        assert holder.stdout is not None and holder.stdout.readline().strip() == "READY"
        with pytest.raises(AlreadyRunningError):
            InstanceLock(path).acquire()
        holder.kill()
        holder.wait(timeout=10)
        with InstanceLock(path):  # 強制終了しても OS がロックを解放する（クラッシュ後に起動できなくならない）
            pass
    finally:
        holder.kill()
        holder.wait()
