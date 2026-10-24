"""コマンドライン。`jra-agent <command> --config <path>`。

verify-config  設定の検証（秘密が無いこと・HTTPS・検証鍵）
verify-queue   キューの整合性（読み取り専用）
status         キュー・チェックポイント・アラートの要約（読み取り専用）
diagnose       環境・資格情報・COM 登録・ビーコン・仕様ゲートの診断（集計のみ。JV-Link は呼ばない）
run            常駐（取得計画に従う。NORMAL_OK のときだけ取得・送信）
set-credential 資格情報の保存（トークンは標準入力から。画面・ログ・引数に出さない）
clear-credential / clear-alert / beacon-reset   利用者の明示操作
update         署名付き更新 manifest の確認・ステージング・切替・ロールバック（自動適用はしない）
"""

from __future__ import annotations

import argparse
import getpass
import json
import logging
import logging.handlers
import signal
import sys
import threading
from datetime import datetime
from pathlib import Path
from typing import Any

from . import __version__
from .api import AgentApi
from .beacon import BeaconClient, BeaconGuard
from .client import SignedClient
from .clock import SystemClock
from .config import AgentConfig, load_config
from .diagnostics import full_report, queue_report
from .errors import AgentError
from .fetcher import Fetcher
from .jvlink.spec import DEFAULT_REGISTRY
from .jvlink.supervisor import WorkerSupervisor
from .lock import AlreadyRunningError, InstanceLock
from .modes import ModeMachine
from .queue import OutboxQueue
from .redact import RedactingFilter
from .runner import AgentRunner
from .secrets_store import Credential, CredentialStore, ProtectedFileSecretStore, SecretStore
from .sender import Sender
from .signing import RequestSigner
from .transport import UrllibTransport
from .winapi import IS_WINDOWS


def _secret_store(cfg: AgentConfig) -> SecretStore:
    if IS_WINDOWS:
        from .winapi import DpapiProtector

        return ProtectedFileSecretStore(cfg.secret_path, DpapiProtector())
    if cfg.production:
        raise AgentError("本番の秘密の保護は Windows の DPAPI だけ（Windows 以外では開発環境のみ）")
    from .secrets_store import FakeProtector

    # 開発用（Linux）: プロセス内の鍵で保護するので、プロセスをまたいで復号できない。永続させたい場合は Windows で実行する
    return ProtectedFileSecretStore(cfg.secret_path, FakeProtector())


def setup_logging(cfg: AgentConfig, verbose: bool = False) -> None:
    cfg.log_dir.mkdir(parents=True, exist_ok=True)
    handler = logging.handlers.TimedRotatingFileHandler(
        cfg.log_dir / "agent.log", when="midnight", backupCount=cfg.log_retention_days, encoding="utf-8"
    )
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s %(message)s"))
    handler.addFilter(RedactingFilter())
    root = logging.getLogger()
    root.addHandler(handler)
    root.setLevel(logging.DEBUG if verbose else logging.INFO)


def _print(obj: Any) -> None:
    sys.stdout.write(json.dumps(obj, ensure_ascii=False, indent=2, default=str) + "\n")


def cmd_verify_config(cfg: AgentConfig, _a: argparse.Namespace) -> int:
    _print(
        {
            "ok": True,
            "environment": cfg.environment,
            "https": cfg.base_url.startswith("https://"),
            "beacon_keys": [k.kid for k in cfg.beacon.keys],
            "jvlink_mode": cfg.jvlink_mode,
        }
    )
    return 0


def cmd_verify_queue(cfg: AgentConfig, _a: argparse.Namespace) -> int:
    rep = queue_report(cfg.queue_path, SystemClock())
    _print(rep)
    return 0 if rep.get("exists") and not rep.get("problems") else 1


def cmd_status(cfg: AgentConfig, _a: argparse.Namespace) -> int:
    rep = queue_report(cfg.queue_path, SystemClock())
    _print({"agent_version": __version__, "queue": rep})
    return 0


def cmd_diagnose(cfg: AgentConfig, a: argparse.Namespace) -> int:
    clock = SystemClock()
    creds = CredentialStore(_secret_store(cfg))
    beacon = None
    if a.online:
        q = OutboxQueue(cfg.queue_path, clock, read_only=True) if cfg.queue_path.exists() else None
        guard = BeaconGuard(q.get_state if q else (lambda _k: None), lambda _k, _v: None)  # 診断では seq を永続しない
        beacon = BeaconClient(cfg.beacon, UrllibTransport(), clock, guard).fetch()
        if q:
            q.close()
    _print(full_report(cfg, creds, clock, beacon))
    return 0


def cmd_set_credential(cfg: AgentConfig, a: argparse.Namespace) -> int:
    token = sys.stdin.readline().strip() if a.stdin else getpass.getpass("トークン（表示されません）: ").strip()
    if not token.startswith("jrat1_") or len(token) < 20:
        sys.stderr.write("トークンの形式が不正\n")
        return 2
    expires = datetime.fromisoformat(a.expires_at) if a.expires_at else None
    CredentialStore(_secret_store(cfg)).save(Credential(cfg.agent_id, token, expires))
    _print({"ok": True, "agent_id": cfg.agent_id})
    return 0


def cmd_clear_credential(cfg: AgentConfig, _a: argparse.Namespace) -> int:
    _print({"removed": CredentialStore(_secret_store(cfg)).revoke_local()})
    return 0


def cmd_clear_alert(cfg: AgentConfig, a: argparse.Namespace) -> int:
    q = OutboxQueue(cfg.queue_path, SystemClock())
    _print({"cleared": q.clear_alert(a.name)})
    q.close()
    return 0


def cmd_beacon_reset(cfg: AgentConfig, a: argparse.Namespace) -> int:
    if not a.confirm:
        sys.stderr.write("ビーコンの seq の記録を消します。infra の ops-state を作り直したときだけ。--confirm を付けて再実行\n")
        return 2
    q = OutboxQueue(cfg.queue_path, SystemClock())
    BeaconGuard(q.get_state, q.set_state).reset()
    q.close()
    _print({"ok": True})
    return 0


def cmd_update(cfg: AgentConfig, a: argparse.Namespace) -> int:
    from .update import Installer, verify_manifest

    inst = Installer(Path(a.install_root))
    if a.action == "status":
        _print(inst.state())
        return 0
    if a.action == "rollback":
        _print({"current": inst.rollback()})
        return 0
    if a.action == "activate":
        inst.activate(a.version)
        _print({"current": a.version})
        return 0
    doc = json.loads(Path(a.manifest).read_text(encoding="utf-8"))
    m = verify_manifest(doc, cfg.update.keys, __version__)
    if a.action == "check":
        _print({"ok": True, "version": m.version, "size": m.size})
        return 0
    data = Path(a.artifact).read_bytes()  # ダウンロードは利用者が行う（自動で実行ファイルを取りに行かない）
    _print({"staged": str(inst.stage(data, m)), "version": m.version, "next": "agent update activate --version を実行して切り替える"})
    return 0


def build_runner(cfg: AgentConfig, queue: OutboxQueue, cred: Credential, clock: SystemClock) -> tuple[AgentRunner, WorkerSupervisor | None]:
    transport = UrllibTransport()
    modes = ModeMachine(clock, cfg.beacon.max_age_seconds)
    client = SignedClient(
        cfg.base_url,
        RequestSigner(cred.agent_id, cred.token),
        transport,
        clock,
        cfg.sender.request_timeout_seconds,
        cfg.queue.gzip_min_bytes,
    )
    api = AgentApi(client, modes)
    beacon = BeaconClient(cfg.beacon, transport, clock, BeaconGuard(queue.get_state, queue.set_state))
    sender = Sender(queue, client, modes, cfg.sender, clock, cred.fingerprint())
    sup = None
    fetcher = None
    if cfg.jvlink_mode == "com":
        argv = [sys.executable, "-m", "jra_agent.jvlink.worker_main", "--backend", "com"]
        sup = WorkerSupervisor(argv, cfg.worker, clock)
        fetcher = Fetcher(
            cred.agent_id, queue, sup, clock, DEFAULT_REGISTRY, cfg.queue, modes.may_fetch
        )  # 仕様ゲート: 公式仕様の確認が記録されるまで実取得はしない
    runner = AgentRunner(queue, modes, beacon, api, client, sender, fetcher, clock)
    return runner, sup


def cmd_run(cfg: AgentConfig, a: argparse.Namespace) -> int:
    setup_logging(cfg, a.verbose)
    log = logging.getLogger("jra_agent")
    clock = SystemClock()
    cred = CredentialStore(_secret_store(cfg)).load()
    if cred is None or cred.agent_id != cfg.agent_id:
        log.error("資格情報が無い、または agent_id が設定と違う（set-credential で保存する）")
        return 3
    if cred.expired(clock.now()):
        log.error("トークンの期限切れ。再発行して set-credential で差し替える")
        return 3
    try:
        lock = InstanceLock(cfg.data_dir / "agent.lock")
        lock.acquire()
    except AlreadyRunningError:
        log.error("多重起動を検出。終了する")
        return 4
    stop = threading.Event()
    for sig in (signal.SIGINT, signal.SIGTERM) + ((signal.SIGBREAK,) if IS_WINDOWS else ()):  # type: ignore[attr-defined,unused-ignore]
        signal.signal(sig, lambda *_: stop.set())
    queue = OutboxQueue(cfg.queue_path, clock, cfg.queue)
    runner, sup = build_runner(cfg, queue, cred, clock)
    log.info("agent %s 起動 (jvlink=%s)", __version__, cfg.jvlink_mode)
    try:
        runner.run_forever(stop.is_set)
    finally:
        if sup:
            sup.stop()
        queue.close()
        lock.release()
    log.info("agent 停止")
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="jra-agent", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--config", type=Path, required=True)
    sub = p.add_subparsers(dest="command", required=True)
    for name in ("verify-config", "verify-queue", "status", "clear-credential"):
        sub.add_parser(name)
    d = sub.add_parser("diagnose")
    d.add_argument("--online", action="store_true", help="ビーコンを取得して検証する（JV-Link・ingest は呼ばない）")
    r = sub.add_parser("run")
    r.add_argument("--verbose", action="store_true")
    s = sub.add_parser("set-credential")
    s.add_argument("--stdin", action="store_true", help="トークンを標準入力の 1 行目から読む（既定は非表示の対話入力）")
    s.add_argument("--expires-at", help="トークンの有効期限（ISO 8601、tz 付き）")
    ca = sub.add_parser("clear-alert")
    ca.add_argument("name")
    br = sub.add_parser("beacon-reset")
    br.add_argument("--confirm", action="store_true")
    u = sub.add_parser("update")
    u.add_argument("action", choices=["check", "stage", "activate", "rollback", "status"])
    u.add_argument("--install-root", required=True)
    u.add_argument("--manifest")
    u.add_argument("--artifact")
    u.add_argument("--version")
    a = p.parse_args(argv)
    try:
        cfg = load_config(a.config)
        handlers = {
            "verify-config": cmd_verify_config, "verify-queue": cmd_verify_queue, "status": cmd_status, "diagnose": cmd_diagnose, "run": cmd_run,
            "set-credential": cmd_set_credential, "clear-credential": cmd_clear_credential, "clear-alert": cmd_clear_alert, "beacon-reset": cmd_beacon_reset, "update": cmd_update,
        }  # fmt: skip
        return handlers[a.command](cfg, a)
    except AgentError as e:
        sys.stderr.write(f"error: {type(e).__name__}: {e}\n")
        return 2


if __name__ == "__main__":
    sys.exit(main())
