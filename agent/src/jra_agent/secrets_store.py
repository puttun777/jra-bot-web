"""エージェントの秘密（トークン）の保護。

- 平文のトークンを設定ファイルに置かない。Windows では DPAPI（ユーザー資格情報に結びつく OS 提供の保護。`winapi.DpapiProtector`）で
  保護した BLOB だけをディスクに書く。OS 依存部分は [`Protector`] に抽象化してあり、Linux の CI では [`FakeProtector`]（AES-GCM。
  テストごとに実行時に鍵を生成）で保存・復号・失効・更新を検査する。
- 本番にハードコードされた鍵は無い。[`FakeProtector`] は本番の構成（`jvlink.mode = "com"` + Windows）では選ばれない。
- 秘密を `repr`・例外・ログに出さない。
"""

from __future__ import annotations

import json
import os
import secrets as _secrets
import tempfile
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Protocol

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from .errors import SecretError


class Protector(Protocol):
    """OS 提供の保護（DPAPI など）の抽象。"""

    def protect(self, plain: bytes, entropy: bytes) -> bytes: ...

    def unprotect(self, blob: bytes, entropy: bytes) -> bytes: ...


class FakeProtector:
    """テスト用。実行時に生成した鍵で AES-GCM する（ディスクに平文が残らないことの検査用）。DPAPI の代わりに本番では使わない。"""

    def __init__(self) -> None:
        self._key = AESGCM.generate_key(bit_length=256)

    def protect(self, plain: bytes, entropy: bytes) -> bytes:
        nonce = _secrets.token_bytes(12)
        return nonce + AESGCM(self._key).encrypt(nonce, plain, entropy)

    def unprotect(self, blob: bytes, entropy: bytes) -> bytes:
        try:
            return AESGCM(self._key).decrypt(blob[:12], blob[12:], entropy)
        except Exception as e:
            raise SecretError("復号できない") from e


class SecretStore(Protocol):
    def save(self, name: str, data: bytes) -> None: ...

    def load(self, name: str) -> bytes | None: ...

    def delete(self, name: str) -> bool: ...


class MemorySecretStore:
    def __init__(self) -> None:
        self._items: dict[str, bytes] = {}

    def save(self, name: str, data: bytes) -> None:
        self._items[name] = bytes(data)

    def load(self, name: str) -> bytes | None:
        return self._items.get(name)

    def delete(self, name: str) -> bool:
        return self._items.pop(name, None) is not None


class ProtectedFileSecretStore:
    """[`Protector`] で保護した BLOB をファイルに置く。書込みは一時ファイル + 置換（途中で落ちても壊れない）。"""

    _ENTROPY = b"jra-agent/secret/v1|"

    def __init__(self, directory: Path, protector: Protector) -> None:
        self._dir = directory
        self._protector = protector

    def _path(self, name: str) -> Path:
        if not name.replace("-", "").replace("_", "").isalnum():
            raise SecretError("秘密の名前が不正")
        return self._dir / f"{name}.bin"

    def save(self, name: str, data: bytes) -> None:
        path = self._path(name)
        try:
            self._dir.mkdir(parents=True, exist_ok=True)
            blob = self._protector.protect(data, self._ENTROPY + name.encode())
            fd, tmp = tempfile.mkstemp(dir=self._dir, prefix=".tmp-")
            try:
                with os.fdopen(fd, "wb") as f:
                    f.write(blob)
                    f.flush()
                    os.fsync(f.fileno())
                os.replace(tmp, path)
            except BaseException:
                Path(tmp).unlink(missing_ok=True)
                raise
        except OSError as e:
            raise SecretError("秘密を保存できない") from e

    def load(self, name: str) -> bytes | None:
        path = self._path(name)
        try:
            blob = path.read_bytes()
        except FileNotFoundError:
            return None
        except OSError as e:
            raise SecretError("秘密を読めない") from e
        return self._protector.unprotect(blob, self._ENTROPY + name.encode())

    def delete(self, name: str) -> bool:
        try:
            self._path(name).unlink()
        except FileNotFoundError:
            return False
        except OSError as e:
            raise SecretError("秘密を削除できない") from e
        return True


@dataclass(frozen=True)
class Credential:
    agent_id: str
    token: str
    expires_at: datetime | None

    def __repr__(self) -> str:  # トークンを出さない
        return f"Credential(agent_id={self.agent_id!r}, expires_at={self.expires_at})"

    def fingerprint(self) -> str:
        """トークンが差し替えられたかの識別用（トークンから復元できない短い値）。"""
        import hashlib

        return hashlib.sha256(b"jra-agent/credential-fp/v1|" + self.token.encode()).hexdigest()[:16]

    def expired(self, now: datetime) -> bool:
        return self.expires_at is not None and now >= self.expires_at


class CredentialStore:
    """トークンの保存・読出し・更新・失効。更新は新しいトークンで上書き（古い署名鍵は即座に使われなくなる）。"""

    NAME = "credential"

    def __init__(self, store: SecretStore) -> None:
        self._store = store

    def save(self, credential: Credential) -> None:
        doc = {
            "v": 1,
            "agent_id": credential.agent_id,
            "token": credential.token,
            "expires_at": credential.expires_at.astimezone(UTC).isoformat() if credential.expires_at else None,
        }
        self._store.save(self.NAME, json.dumps(doc).encode("utf-8"))

    def load(self) -> Credential | None:
        raw = self._store.load(self.NAME)
        if raw is None:
            return None
        try:
            doc = json.loads(raw)
            exp = doc["expires_at"]
            return Credential(doc["agent_id"], doc["token"], datetime.fromisoformat(exp) if exp else None)
        except (ValueError, KeyError, TypeError) as e:
            raise SecretError("保存された資格情報の形式が不正") from e

    def revoke_local(self) -> bool:
        """端末から資格情報を消す（サーバ側の失効とは別。サーバでの失効は管理操作）。"""
        return self._store.delete(self.NAME)
