"""診断（`agent diagnose`）。**集計だけ**を出す（件数・版数・可否・コード）。トークン・利用キー・レコードの内容・パスの中のユーザー名を出さない。
JV-Link の API は一切呼ばない（COM の登録の有無をレジストリで調べるだけ。実機 PoC は poc/）。"""

from __future__ import annotations

import platform
import shutil
import struct
import sys
from pathlib import Path
from typing import Any

from . import __version__
from .config import AgentConfig
from .jvlink.spec import DEFAULT_REGISTRY, SpecStatus
from .queue import OutboxQueue
from .secrets_store import CredentialStore


def environment_info() -> dict[str, Any]:
    return {
        "agent_version": __version__,
        "python": platform.python_version(),
        "python_bits": struct.calcsize("P") * 8,
        "os": platform.system(),
        "os_release": platform.release(),
        "machine": platform.machine(),
        "is_windows": sys.platform == "win32",
    }


def com_registration() -> dict[str, Any]:
    """JV-Link の COM 登録の有無（レジストリの読み取りだけ。Windows 以外は not_applicable）。ProgID/CLSID は参照元実装の使用実態（公式未確認）。"""
    if sys.platform != "win32":
        return {"applicable": False}
    import winreg  # type: ignore[import-not-found,unused-ignore]

    from .jvlink.com_adapter import CLSID_CANDIDATE, PROGID_CANDIDATES

    out: dict[str, Any] = {"applicable": True, "progids": {}, "clsid_registered": False, "pywin32": False}
    for progid in PROGID_CANDIDATES:
        try:
            winreg.CloseKey(winreg.OpenKey(winreg.HKEY_CLASSES_ROOT, progid))
            out["progids"][progid] = True
        except OSError:
            out["progids"][progid] = False
    try:
        winreg.CloseKey(winreg.OpenKey(winreg.HKEY_CLASSES_ROOT, rf"CLSID\{CLSID_CANDIDATE}"))
        out["clsid_registered"] = True
    except OSError:
        pass
    try:
        import win32com  # type: ignore[import-not-found,unused-ignore]  # noqa: F401

        out["pywin32"] = True
    except ImportError:
        pass
    return out


def queue_report(path: Path, clock: Any) -> dict[str, Any]:
    if not path.exists():
        return {"exists": False}
    q = OutboxQueue(path, clock, read_only=True)
    try:
        st = q.stats()
        return {
            "exists": True, "problems": q.verify(), "by_status": st.by_status, "pending_bytes": st.pending_bytes,
            "dead": st.dead, "realtime_dropped": st.realtime_dropped, "alerts": sorted(q.alerts()),
            "checkpoints": q.snapshot_checkpoints(),
        }  # fmt: skip
    finally:
        q.close()


def full_report(cfg: AgentConfig, creds: CredentialStore, clock: Any, beacon_result: Any | None = None) -> dict[str, Any]:
    cred = None
    try:
        cred = creds.load()
    except Exception as e:
        cred_state: dict[str, Any] = {"readable": False, "error": type(e).__name__}
    else:
        cred_state = {"present": cred is not None}
        if cred is not None:
            cred_state.update(
                agent_id_matches_config=cred.agent_id == cfg.agent_id,
                expires_at=cred.expires_at.isoformat() if cred.expires_at else None,
                expired=cred.expired(clock.now()),
            )
    try:
        free = shutil.disk_usage(cfg.data_dir if cfg.data_dir.exists() else cfg.data_dir.parent).free
    except OSError:
        free = None
    report: dict[str, Any] = {
        "environment": environment_info(),
        "config": {
            "environment": cfg.environment,
            "jvlink_mode": cfg.jvlink_mode,
            "https": cfg.base_url.startswith("https://"),
            "beacon_keys": [k.kid for k in cfg.beacon.keys],
        },
        "credential": cred_state,
        "disk_free_mb": None if free is None else free // (1024 * 1024),
        "queue": queue_report(cfg.queue_path, clock),
        "com": com_registration(),
        "jvlink_spec": {
            "confirmed": sum(1 for v in DEFAULT_REGISTRY.summary().values() if v == SpecStatus.CONFIRMED.value),
            "total": len(DEFAULT_REGISTRY.summary()),
            "items": DEFAULT_REGISTRY.summary(),
        },
    }
    if beacon_result is not None:
        report["beacon"] = {"status": beacon_result.status.value, "reason": beacon_result.reason}
        if beacon_result.beacon is not None:
            report["beacon"].update(mode=beacon_result.beacon.mode, seq=beacon_result.beacon.seq)
    return report
