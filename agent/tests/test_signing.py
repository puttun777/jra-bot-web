"""署名の既知テストベクトル（OpenSSL で独立に計算。Kotlin サーバの `AgentSigning` も同じファイルを検査する）と、サーバ側認証の独立な再現。"""

from __future__ import annotations

import hashlib
import hmac
import json
import re
from pathlib import Path

import pytest

from jra_agent.client import SignedClient, gzip_deterministic
from jra_agent.clock import FakeClock, to_unix
from jra_agent.signing import RequestSigner, canonical, derive_signing_key, new_nonce, sha256_hex, sign
from tests.conftest import ScriptedTransport, resp

VECTORS = json.loads((Path(__file__).parent / "vectors" / "signing_vectors.json").read_text(encoding="utf-8"))


@pytest.mark.parametrize("case", VECTORS["cases"], ids=lambda c: c["name"])
def test_known_vectors(case: dict[str, object]) -> None:
    body = bytes.fromhex(str(case["bodyHex"]))
    assert derive_signing_key(str(case["token"])).hex() == case["signingKeyHex"]
    assert sha256_hex(body) == case["bodySha256"]
    text = canonical(str(case["method"]), str(case["requestUri"]), int(str(case["timestamp"])), str(case["nonce"]), sha256_hex(body))
    assert text == case["canonical"]
    assert sign(derive_signing_key(str(case["token"])), text) == case["signature"]
    signer = RequestSigner("ag_test", str(case["token"]), nonce_factory=lambda: str(case["nonce"]))
    h = signer.sign_request(str(case["method"]), str(case["requestUri"]), body, int(str(case["timestamp"])))
    assert h.signature == case["signature"]


def test_signing_key_derivation_label_is_fixed() -> None:
    assert VECTORS["derive_label"] == "jra-bot/agent-sign/v1"
    assert derive_signing_key("t") == hmac.new(b"t", b"jra-bot/agent-sign/v1", hashlib.sha256).digest()


def test_nonce_is_fresh_and_valid_shape() -> None:
    nonces = {new_nonce() for _ in range(2000)}
    assert len(nonces) == 2000
    assert all(re.fullmatch(r"[A-Za-z0-9_-]{16,64}", n) for n in nonces)


def test_headers_do_not_leak_in_repr() -> None:
    signer = RequestSigner("ag_test", "jrat1_secret-token-value")
    h = signer.sign_request("GET", "/v1/agent/plan", b"", 1)
    assert h.signature not in repr(h) and h.nonce not in repr(h)
    assert "secret-token-value" not in repr(signer)


class IndependentServer:
    """サーバ側の認証を、エージェントの実装を使わずに書き直したもの（契約 §3.3 の文面から）。"""

    def __init__(self, token: str, now: float) -> None:
        self.key = hmac.new(token.encode(), b"jra-bot/agent-sign/v1", hashlib.sha256).digest()
        self.now = now
        self.seen: set[str] = set()

    def check(self, method: str, uri: str, headers: dict[str, str], body: bytes) -> bool:
        ts, nonce, sig = int(headers["X-Timestamp"]), headers["X-Nonce"], headers["X-Signature"]
        if abs(self.now - ts) > 300 or not re.fullmatch(r"[A-Za-z0-9_-]{16,64}", nonce) or not re.fullmatch(r"[0-9a-f]{64}", sig):
            return False
        expect = hmac.new(self.key, f"{method}|{uri}|{ts}|{nonce}|{hashlib.sha256(body).hexdigest()}".encode(), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(expect, sig) or nonce in self.seen:
            return False
        self.seen.add(nonce)
        return True


def _client(token: str = "jrat1_abc", gzip_min: int = 512) -> tuple[SignedClient, ScriptedTransport, FakeClock, IndependentServer]:
    clock = FakeClock()
    t = ScriptedTransport(default=resp(200, {}))
    srv = IndependentServer(token, to_unix(clock.now()))
    return SignedClient("https://ingest.example", RequestSigner("ag_test", token), t, clock, gzip_min_bytes=gzip_min), t, clock, srv


def test_client_signature_matches_independent_server_with_gzip_and_without() -> None:
    c, t, _clock, srv = _client()
    small, big = b'{"a":1}', json.dumps({"x": "y" * 5000}).encode()
    c.post_json("/v1/agent/heartbeat", small)
    c.post_json("/v1/agent/ingest", big)
    c.get("/v1/agent/plan?since=1")
    c1, c2, c3 = t.calls
    assert "content-encoding" not in {k.lower() for k in c1.headers} and c1.body == small
    assert c2.headers["Content-Encoding"] == "gzip" and c2.body == gzip_deterministic(big) and c2.body != big
    assert srv.check("POST", "/v1/agent/heartbeat", c1.headers, c1.body or b"")
    # gzip: 圧縮後のバイト列をハッシュしている（展開後の本文では検証に失敗する）
    assert srv.check("POST", "/v1/agent/ingest", c2.headers, c2.body or b"")
    assert c3.body is None and srv.check("GET", "/v1/agent/plan?since=1", c3.headers, b"")
    assert c3.url == "https://ingest.example/v1/agent/plan?since=1"


def test_signature_does_not_verify_for_uncompressed_body_of_gzip_request() -> None:
    c, t, _clock, srv = _client()
    big = json.dumps({"x": "y" * 5000}).encode()
    c.post_json("/v1/agent/ingest", big)
    assert not srv.check("POST", "/v1/agent/ingest", t.calls[0].headers, big)


def test_every_request_gets_a_new_nonce_and_resend_body_is_identical() -> None:
    c, t, clock, _srv = _client()
    payload = json.dumps({"x": "y" * 3000}).encode()
    for _ in range(3):
        c.post_json("/v1/agent/ingest", payload)
        clock.advance(1)
    assert len({call.headers["X-Nonce"] for call in t.calls}) == 3
    assert len({call.body for call in t.calls}) == 1  # 再送の本文（gzip 後）は毎回同じバイト列
    assert len({call.headers["X-Signature"] for call in t.calls}) == 3


@pytest.mark.parametrize(("offset", "ok"), [(0, True), (299, True), (300, True), (301, False), (-300, True), (-301, False)])
def test_timestamp_boundary_against_server_window(offset: int, ok: bool) -> None:
    c, t, clock, srv = _client()
    srv.now = to_unix(clock.now()) + offset
    c.get("/v1/agent/mode")
    assert srv.check("GET", "/v1/agent/mode", t.calls[0].headers, b"") is ok


def test_replayed_nonce_is_rejected_by_server_but_client_never_reuses() -> None:
    c, t, _clock, srv = _client()
    c.get("/v1/agent/mode")
    h, _ = t.calls[0].headers, None
    assert srv.check("GET", "/v1/agent/mode", h, b"")
    assert not srv.check("GET", "/v1/agent/mode", h, b"")  # 同じ nonce の再送は拒否される


def test_wrong_token_does_not_verify() -> None:
    c, t, _clock, _srv = _client("jrat1_right")
    c.get("/v1/agent/mode")
    other = IndependentServer("jrat1_wrong", to_unix(FakeClock().now()))
    assert not other.check("GET", "/v1/agent/mode", t.calls[0].headers, b"")
