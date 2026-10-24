"""テスト用ビーコン: 実行時に生成した Ed25519 鍵で署名する（実 GCP のビーコン・実鍵は使わない）。"""

from __future__ import annotations

import base64
import json
from dataclasses import dataclass, field

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from jra_agent.beacon import canonical_bytes
from jra_agent.config import PublicKey


def b64u(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


@dataclass
class BeaconSigner:
    kid: str = "beacon-test-a"
    key: Ed25519PrivateKey = field(default_factory=Ed25519PrivateKey.generate)

    @property
    def public(self) -> PublicKey:
        raw = self.key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
        return PublicKey(self.kid, raw)

    def doc(
        self,
        mode: str = "NORMAL",
        seq: int = 1,
        updated_at: str = "2026-11-01T00:00:00Z",
        valid_until: str | None = None,
        kid: str | None = None,
    ) -> dict[str, object]:
        body: dict[str, object] = {
            "schema_version": 1,
            "mode": mode,
            "seq": seq,
            "updatedAt": updated_at,
            "validUntil": valid_until,
            "kid": kid or self.kid,
        }
        return {**body, "sig": b64u(self.key.sign(canonical_bytes(body)))}

    def bytes(self, **kw: object) -> bytes:
        return json.dumps(self.doc(**kw)).encode()  # type: ignore[arg-type]
