"""署名付き更新 manifest の検証と、更新物の段階的な導入（ステージング → 切替 → ロールバック）。

- manifest は Ed25519 署名（`update.keys` の公開鍵 = kid 付き最大 2 本。秘密鍵は CI/リリース作業者の手元にあり、このリポジトリにもサーバにも無い）。
- 検証: 署名 → 形式 → **バージョンが現在より新しい**（巻き戻り・同一は拒否）→ プラットフォーム → HTTPS の URL → 更新物の SHA-256 とサイズ。
- **未署名の実行ファイルを自動でインストールしない**。ダウンロードしたファイルは `stage()` でハッシュを確認して別ディレクトリへ置くだけ。
  切替（`activate()`）は利用者の明示操作（CLI の `update --activate`）。失敗時は `rollback()` で直前の版に戻る。
- 実際の公開サーバ・配布は未実装（UNRESOLVED）。
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import zipfile
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from urllib.parse import urlsplit

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

from .beacon import b64url_decode, canonical_bytes
from .config import PublicKey
from .errors import VerificationError

SEMVER = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")
MAX_ARTIFACT_BYTES = 200 * 1024 * 1024
_KEYS = {"schema_version", "version", "platform", "sha256", "size", "url", "issuedAt", "kid", "sig"}


def parse_version(text: str) -> tuple[int, int, int]:
    m = SEMVER.fullmatch(text)
    if not m:
        raise VerificationError("BAD_VERSION")
    return int(m[1]), int(m[2]), int(m[3])


@dataclass(frozen=True)
class Manifest:
    version: str
    platform: str
    sha256: str
    size: int
    url: str
    issued_at: datetime


def verify_manifest(doc: object, keys: tuple[PublicKey, ...], current_version: str, platform: str = "win-x64") -> Manifest:
    if not isinstance(doc, dict) or set(doc) != _KEYS:
        raise VerificationError("BAD_SHAPE")
    kid, sig = doc["kid"], doc["sig"]
    key = next((k for k in keys if isinstance(kid, str) and k.kid == kid), None)
    if key is None or not isinstance(sig, str):
        raise VerificationError("UNKNOWN_KID")
    try:
        Ed25519PublicKey.from_public_bytes(key.raw).verify(b64url_decode(sig), canonical_bytes(doc))
    except (InvalidSignature, ValueError) as e:
        raise VerificationError("BAD_SIGNATURE") from e  # 偽 manifest
    if doc["schema_version"] != 1:
        raise VerificationError("BAD_SCHEMA_VERSION")
    version, plat, sha, size, url, issued = doc["version"], doc["platform"], doc["sha256"], doc["size"], doc["url"], doc["issuedAt"]
    if not isinstance(version, str) or parse_version(version) <= parse_version(current_version):
        raise VerificationError("NOT_NEWER")  # バージョンの巻き戻り・同一は拒否
    if plat != platform:
        raise VerificationError("WRONG_PLATFORM")
    if not isinstance(sha, str) or not re.fullmatch(r"[0-9a-f]{64}", sha):
        raise VerificationError("BAD_SHA256")
    if isinstance(size, bool) or not isinstance(size, int) or not 0 < size <= MAX_ARTIFACT_BYTES:
        raise VerificationError("BAD_SIZE")
    if not isinstance(url, str) or urlsplit(url).scheme != "https" or urlsplit(url).username:
        raise VerificationError("BAD_URL")
    try:
        when = datetime.fromisoformat(str(issued).replace("Z", "+00:00")).astimezone(UTC)
    except ValueError as e:
        raise VerificationError("BAD_ISSUED_AT") from e
    return Manifest(version, str(plat), sha, size, url, when)


def verify_artifact(data: bytes, m: Manifest) -> None:
    if len(data) != m.size:
        raise VerificationError("SIZE_MISMATCH")
    if hashlib.sha256(data).hexdigest() != m.sha256:
        raise VerificationError("HASH_MISMATCH")


class Installer:
    """`<root>/versions/<ver>/`（展開先）と `<root>/current.json`（現在・直前の版）。起動スクリプトが current.json の指す版を使う。"""

    def __init__(self, root: Path) -> None:
        self.root = root

    @property
    def _state(self) -> Path:
        return self.root / "current.json"

    def state(self) -> dict[str, str | None]:
        try:
            return json.loads(self._state.read_text(encoding="utf-8"))  # type: ignore[no-any-return]
        except (OSError, ValueError):
            return {"current": None, "previous": None, "staged": None}

    def _write(self, st: dict[str, str | None]) -> None:
        self.root.mkdir(parents=True, exist_ok=True)
        tmp = self._state.with_suffix(".tmp")
        tmp.write_text(json.dumps(st), encoding="utf-8")
        os.replace(tmp, self._state)  # 原子的な置換（途中で落ちても壊れない）

    def stage(self, data: bytes, m: Manifest) -> Path:
        """ハッシュ・サイズを確認し、zip を別ディレクトリへ安全に展開する（パストラバーサル・絶対パス・リンクを拒否）。現在の版は触らない。"""
        verify_artifact(data, m)
        dest = self.root / "versions" / m.version
        if dest.exists():
            raise VerificationError("ALREADY_STAGED")
        tmp = self.root / "versions" / f".{m.version}.tmp"
        shutil.rmtree(tmp, ignore_errors=True)
        tmp.mkdir(parents=True)
        try:
            import io

            with zipfile.ZipFile(io.BytesIO(data)) as z:
                for info in z.infolist():
                    name = info.filename
                    target = (tmp / name).resolve()
                    if (
                        name.startswith(("/", "\\"))
                        or ".." in Path(name).parts
                        or (tmp.resolve() not in target.parents and target != tmp.resolve())
                    ):
                        raise VerificationError("UNSAFE_PATH")
                    if (info.external_attr >> 16) & 0o170000 == 0o120000:
                        raise VerificationError("SYMLINK")
                z.extractall(tmp)
        except zipfile.BadZipFile as e:
            shutil.rmtree(tmp, ignore_errors=True)
            raise VerificationError("BAD_ZIP") from e
        except VerificationError:
            shutil.rmtree(tmp, ignore_errors=True)
            raise
        os.replace(tmp, dest)
        st = self.state()
        st["staged"] = m.version
        self._write(st)
        return dest

    def activate(self, version: str) -> None:
        """利用者の明示操作。ステージ済みの版へ切り替える（直前の版を残す）。"""
        st = self.state()
        if st.get("staged") != version or not (self.root / "versions" / version).is_dir():
            raise VerificationError("NOT_STAGED")
        if st.get("current") and parse_version(version) <= parse_version(str(st["current"])):
            raise VerificationError("NOT_NEWER")
        self._write({"current": version, "previous": st.get("current"), "staged": None})

    def rollback(self) -> str:
        st = self.state()
        prev = st.get("previous")
        if not prev or not (self.root / "versions" / prev).is_dir():
            raise VerificationError("NO_PREVIOUS")
        self._write({"current": prev, "previous": None, "staged": None})
        return prev
