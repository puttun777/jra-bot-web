"""Ed25519 署名（RFC 8032, pure）。署名対象はモデル JSON ファイルのバイト列そのもの（正準 JSON）。`.sig` は生の 64 バイト。

**テスト専用の鍵**: `TEST_SEED_*` は決定的な派生で、テストの成果物（domain/src/test/resources/ml）を再生成可能にするためのもの。本番の鍵ではなく、秘密ではない。
本番の秘密鍵は ml-train の Secret Manager（`MODEL_SIGNING_KEY`）にあり、Git・ログ・CI に出さない。
"""
import base64
import hashlib

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey


def test_seed(label: str) -> bytes:
    """テスト専用の 32 バイトのシード（ラベルの SHA-256）。"""
    return hashlib.sha256(f"jra-bot-web test-only ed25519 seed: {label}".encode()).digest()


TEST_SEED_PRIMARY = test_seed("primary")
TEST_SEED_OTHER = test_seed("other")


def public_key_raw(seed: bytes) -> bytes:
    return Ed25519PrivateKey.from_private_bytes(seed).public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)


def public_key_b64(seed: bytes) -> str:
    return base64.b64encode(public_key_raw(seed)).decode("ascii")


def sign(seed: bytes, message: bytes) -> bytes:
    return Ed25519PrivateKey.from_private_bytes(seed).sign(message)


def verify(public_raw: bytes, signature: bytes, message: bytes) -> bool:
    if len(signature) != 64:
        return False
    try:
        Ed25519PublicKey.from_public_bytes(public_raw).verify(signature, message)
        return True
    except InvalidSignature:
        return False
