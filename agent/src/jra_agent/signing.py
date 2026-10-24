"""リクエスト署名（CONTRACTS §3.3 / 提案 P-1。サーバの `AgentSigning`・`AgentAuthenticator` と同じ定義）。

- 署名鍵 `K = HMAC-SHA256(token, "jra-bot/agent-sign/v1")`
- `X-Signature = hex(HMAC-SHA256(K, "method|requestUri|timestamp|nonce|sha256hex(送信した本文のバイト列)"))`
  - `requestUri` はクエリを含む（サーバは `requestURI + "?" + queryString`）。
  - 本文は**送信するバイト列そのもの**（gzip で送るなら圧縮後）。サーバは認証の前に展開しない。
  - 本文が無い要求は空のバイト列の SHA-256。
- ヘッダ: `X-Agent-Id` / `X-Timestamp`（Unix 秒、サーバは ±300 秒）/ `X-Nonce`（`[A-Za-z0-9_-]{16,64}`、毎回新規）/ `X-Signature`（小文字 16 進 64 桁）。
トークン自体は送らない。アルゴリズムとヘッダは推測で変えない（既知のテストベクトル: tests/vectors/signing_vectors.json）。
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import secrets
from collections.abc import Callable
from dataclasses import dataclass

DERIVE_LABEL = b"jra-bot/agent-sign/v1"
NONCE_RE_LEN = (16, 64)


def derive_signing_key(token: str) -> bytes:
    return hmac.new(token.encode("utf-8"), DERIVE_LABEL, hashlib.sha256).digest()


def canonical(method: str, request_uri: str, timestamp: int, nonce: str, body_sha256_hex: str) -> str:
    return f"{method}|{request_uri}|{timestamp}|{nonce}|{body_sha256_hex}"


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sign(signing_key: bytes, canonical_text: str) -> str:
    return hmac.new(signing_key, canonical_text.encode("utf-8"), hashlib.sha256).hexdigest()


def new_nonce(token_bytes: Callable[[int], bytes] = secrets.token_bytes) -> str:
    """24 バイトの乱数を URL セーフ Base64（パディングなし）にした 32 文字。毎回新しく作る（再利用すると 401）。"""
    return base64.urlsafe_b64encode(token_bytes(24)).decode("ascii").rstrip("=")


@dataclass(frozen=True)
class SignedHeaders:
    agent_id: str
    timestamp: int
    nonce: str
    signature: str

    def as_dict(self) -> dict[str, str]:
        return {
            "X-Agent-Id": self.agent_id,
            "X-Timestamp": str(self.timestamp),
            "X-Nonce": self.nonce,
            "X-Signature": self.signature,
        }

    def __repr__(self) -> str:  # 署名・nonce をログに出さない
        return f"SignedHeaders(agent_id={self.agent_id!r}, timestamp={self.timestamp})"


class RequestSigner:
    """トークンから導いた署名鍵を保持して要求に署名する。トークン自体は保持しない。"""

    def __init__(self, agent_id: str, token: str, nonce_factory: Callable[[], str] = new_nonce) -> None:
        self.agent_id = agent_id
        self._key = derive_signing_key(token)
        self._nonce_factory = nonce_factory

    def sign_request(self, method: str, request_uri: str, body: bytes, timestamp: int) -> SignedHeaders:
        nonce = self._nonce_factory()
        text = canonical(method, request_uri, timestamp, nonce, sha256_hex(body))
        return SignedHeaders(self.agent_id, timestamp, nonce, sign(self._key, text))

    def __repr__(self) -> str:
        return f"RequestSigner(agent_id={self.agent_id!r})"
