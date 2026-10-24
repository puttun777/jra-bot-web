"""G-AGENT-6 補助: スリープ/休止からの復帰を、時刻の飛びと Mock ビーコンへの復帰で確認する（JV-Link・ingest を使わない）。

  python -m poc.poc_sleep --duration 1800 --out sleep.json
実行中に PC をスリープさせ、起こす。結果: スリープ回数・各スリープの長さ・復帰から NORMAL_OK までの秒数（合格は 300 秒以内）。
"""

from __future__ import annotations

import argparse
import sys
import threading
import time
from datetime import UTC, datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from jra_agent.beacon import BeaconClient, BeaconGuard, canonical_bytes
from jra_agent.clock import SystemClock
from jra_agent.config import BeaconConfig, PublicKey
from jra_agent.modes import Mode, ModeMachine
from jra_agent.responses import Classified, Outcome
from jra_agent.transport import UrllibTransport

from .poc_common import GateResult, write_report

KEY = Ed25519PrivateKey.generate()  # 実行時に生成する使い捨て鍵


def _doc(seq: int) -> bytes:
    import base64
    import json

    body = {
        "schema_version": 1,
        "mode": "NORMAL",
        "seq": seq,
        "updatedAt": datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "validUntil": None,
        "kid": "poc",
    }
    sig = base64.urlsafe_b64encode(KEY.sign(canonical_bytes(body))).decode().rstrip("=")
    return json.dumps({**body, "sig": sig}).encode()


class _H(BaseHTTPRequestHandler):
    seq = 1

    def do_GET(self) -> None:
        out = _doc(_H.seq)
        self.send_response(200)
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def log_message(self, *_a: object) -> None:
        pass


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--duration", type=int, default=1800)
    ap.add_argument("--out", type=Path)
    ns = ap.parse_args(argv)
    srv = ThreadingHTTPServer(("127.0.0.1", 0), _H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    pub = PublicKey("poc", KEY.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw))
    clock = SystemClock()
    # PoC 専用: 127.0.0.1 の HTTP。本番の設定検証（config.py）は HTTPS を要求する
    cfg = BeaconConfig(f"http://127.0.0.1:{srv.server_address[1]}/", (pub,))
    machine = ModeMachine(clock)
    client = BeaconClient(cfg, UrllibTransport(), clock, BeaconGuard(lambda _k: None, lambda _k, _v: None))
    sleeps: list[dict[str, float]] = []
    last = time.time()
    end = last + ns.duration
    pending_resume: float | None = None
    while time.time() < end:
        time.sleep(5)
        now = time.time()
        if now - last > 60:  # 5 秒のはずが 60 秒以上空いた = スリープした
            sleeps.append({"gap_seconds": round(now - last, 1)})
            machine = ModeMachine(clock)  # 起動直後と同じ UNKNOWN から（復帰直後に古い状態を使わない）
            pending_resume = now
        last = now
        _H.seq += 0
        machine.on_beacon(client.fetch())
        machine.on_server(Classified(Outcome.OK, 200))
        if pending_resume is not None and machine.evaluate().mode is Mode.NORMAL_OK:
            sleeps[-1]["normal_ok_after_seconds"] = round(time.time() - pending_resume, 1)
            pending_resume = None
    srv.shutdown()
    ok = bool(sleeps) and all(s.get("normal_ok_after_seconds", 1e9) <= 300 for s in sleeps)
    res = GateResult(
        "G-AGENT-6",
        "PASS" if ok else ("NOT_RUN" if not sleeps else "FAIL"),
        "" if ok else ("スリープが発生しなかった" if not sleeps else "復帰が 300 秒を超えた"),
        {"sleeps": sleeps},
    )
    write_report(ns.out, "real-sleep", [res])
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
