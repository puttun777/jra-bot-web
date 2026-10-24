"""テスト用の「サーバ + ビーコン」。サーバ契約（署名・冪等・取得セッションの連番・カーソル・503+X-Ops-Mode）を、エージェントの実装を使わずに再現する。
本物の Kotlin ingest との結合は :ingest の AgentInteropIntegrationTest が行う（こちらは Python 単体で STOPPED 復帰などの流れを検証するため）。"""

from __future__ import annotations

import gzip
import hashlib
import hmac
import json
import re
from dataclasses import dataclass, field

from jra_agent.clock import FakeClock, to_unix
from jra_agent.errors import TransportError
from jra_agent.transport import TransportResponse
from tests.beacon_support import BeaconSigner

BEACON_URL = "https://beacon.example/mode.json"
BASE = "https://ingest.example"


def j(status: int, body: object, **h: str) -> TransportResponse:
    return TransportResponse(status, {k.lower().replace("_", "-"): v for k, v in h.items()}, json.dumps(body).encode())


@dataclass
class FakeCloud:
    clock: FakeClock
    token: str
    signer: BeaconSigner
    mode: str = "NORMAL"
    beacon_seq: int = 1
    beacon_reachable: bool = True
    ingest_reachable: bool = True
    plan: dict[str, object] = field(default_factory=lambda: {"planId": "p1", "validUntil": None, "items": [], "idle": True})
    lose_next_ack: bool = False
    batches: dict[str, dict[str, object]] = field(default_factory=dict)
    requests: list[tuple[str, str]] = field(default_factory=list)
    fetches: dict[str, dict[str, object]] = field(default_factory=dict)
    cursors: dict[str, str] = field(default_factory=dict)
    nonces: set[str] = field(default_factory=set)
    heartbeats: int = 0
    stale_beacon_doc: dict[str, object] | None = None

    def set_mode(self, mode: str) -> None:
        self.mode = mode
        self.beacon_seq += 1

    def _auth(self, method: str, uri: str, headers: dict[str, str], body: bytes) -> bool:
        key = hmac.new(self.token.encode(), b"jra-bot/agent-sign/v1", hashlib.sha256).digest()
        ts, nonce, sig = int(headers["X-Timestamp"]), headers["X-Nonce"], headers["X-Signature"]
        if abs(to_unix(self.clock.now()) - ts) > 300 or nonce in self.nonces:
            return False
        expect = hmac.new(key, f"{method}|{uri}|{ts}|{nonce}|{hashlib.sha256(body).hexdigest()}".encode(), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(expect, sig):
            return False
        self.nonces.add(nonce)
        return True

    # Transport の実装
    def request(self, method: str, url: str, headers: dict[str, str], body: bytes | None, timeout: float) -> TransportResponse:
        date = self.clock.now().strftime("%a, %d %b %Y %H:%M:%S GMT")
        if url == BEACON_URL:
            if not self.beacon_reachable:
                raise TransportError("CONNECTION")
            if self.stale_beacon_doc is not None:
                return TransportResponse(200, {"date": date}, json.dumps(self.stale_beacon_doc).encode())
            return TransportResponse(
                200,
                {"date": date},
                self.signer.bytes(mode=self.mode, seq=self.beacon_seq, updated_at=self.clock.now().strftime("%Y-%m-%dT%H:%M:%SZ")),
            )
        assert url.startswith(BASE)
        uri = url[len(BASE) :]
        self.requests.append((method, uri.split("?")[0]))
        if not self.ingest_reachable:
            raise TransportError("TIMEOUT")
        if uri == "/v1/agent/mode":
            if self.mode != "NORMAL":
                return j(200, {"mode": self.mode, "seq": self.beacon_seq}, date=date)  # /mode は NORMAL 以外でも 200 で名乗る
            return j(200, {"mode": "NORMAL", "seq": self.beacon_seq}, date=date)
        if self.mode != "NORMAL":
            return j(503, {"error": "OPS_MODE_NOT_NORMAL"}, x_ops_mode=self.mode, date=date)
        raw = body or b""
        if not self._auth(method, uri, headers, raw):
            return j(401, {"error": "UNAUTHORIZED"}, date=date)
        if uri == "/v1/agent/plan":
            return j(200, self.plan, date=date)
        if uri == "/v1/agent/heartbeat":
            self.heartbeats += 1
            return j(200, {"serverTime": "x"}, date=date)
        if uri == "/v1/agent/ingest":
            return self._ingest(headers, raw, date)
        return j(404, {"error": "NOT_FOUND"}, date=date)

    def _ingest(self, headers: dict[str, str], raw: bytes, date: str) -> TransportResponse:
        data = gzip.decompress(raw) if headers.get("Content-Encoding") == "gzip" else raw
        env = json.loads(data)
        bid = env["batchId"]
        if bid in self.batches:
            ack = {
                "accepted": 0,
                "duplicates": len(env["records"]),
                "quarantined": 0,
                "unsupported": 0,
                "duplicate": True,
                "ackTs": "t",
                "serverTime": "t",
                "fetch": None,
                "cursor": None,
            }
            return j(200, ack, date=date)
        fetch = env.get("fetch")
        progress, cursor = None, None
        if fetch:
            f = self.fetches.setdefault(
                fetch["id"], {"next": 0, "status": "IN_PROGRESS", "last": None, "dataspec": env["dataspec"], "option": env.get("option")}
            )
            if fetch["seq"] > f["next"]:
                return j(409, {"error": "FETCH_SEQ_AHEAD"}, retry_after="5", date=date)
            f["next"] = fetch["seq"] + 1  # type: ignore[operator]
            f["last"] = env.get("lastFileTimestamp") or f["last"]
            if env["eof"]:
                f["status"] = "SUCCEEDED"
                key = env["dataspec"] + (f":{env['option']}" if env.get("option") is not None else "")
                if f["last"] and fetch.get("origin", "LIVE") == "LIVE" and str(f["last"]) > self.cursors.get(key, ""):
                    self.cursors[key] = str(f["last"])
                    cursor = {"dataType": key, "lastFileTimestamp": f["last"]}
            progress = {"id": fetch["id"], "status": f["status"], "nextSeq": f["next"]}
        self.batches[bid] = env
        if self.lose_next_ack:
            self.lose_next_ack = False
            raise TransportError("CONNECTION")  # サーバは保存済み。ACK 直前に切断
        ack = {
            "accepted": len(env["records"]),
            "duplicates": 0,
            "quarantined": 0,
            "unsupported": 0,
            "duplicate": False,
            "ackTs": "t",
            "serverTime": "t",
            "fetch": progress,
            "cursor": cursor,
        }
        return j(200, ack, date=date)


UUID7 = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
