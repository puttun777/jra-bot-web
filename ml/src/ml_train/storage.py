"""成果物の保管（Kotlin の `ModelStore` / `DatasetStore` と同じ配置: `{root}/{tenant_id}/{datasets|models|reports}/…`）。

- 置き場は `MODEL_STORE_URI`。この版が扱えるのは `file://` の絶対パス（Cloud Run の GCS ボリュームマウントを想定。ローカルの検証も同じ）。
  `gs://` を直接読み書きするクライアントは入れていない（依存を増やさない。Stage 5 の実環境で方式を決める: UNRESOLVED）。`gs://` は『未対応』として設定エラーにする。
- 作成のみで上書きしない（成果物は不変）。同一内容の再書込みは UNCHANGED、異なる内容は CONFLICT。
- モデルは **`.sig` を先、`.json` を最後**に置く（JSON があれば署名もある）。失敗時は、この実行が作ったものだけを消す（cleanup）。
"""

import errno
import os
import re
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import unquote, urlparse

from .errors import MlTrainError, TransientError, UsageError

_SEGMENT = re.compile(r"[A-Za-z0-9_.-]{1,100}")


class StoreError(TransientError):
    def __init__(self, message: str) -> None:
        super().__init__("STORAGE_ERROR", message)


class ArtifactConflict(MlTrainError):
    def __init__(self, key: str) -> None:
        super().__init__("ARTIFACT_CONFLICT", f"同じキーに内容の異なる成果物が既にある: {key}")


def _safe_key(key: str) -> list[str]:
    parts = key.split("/")
    for p in parts:
        if not _SEGMENT.fullmatch(p) or p in (".", ".."):
            raise StoreError(f"保管キーが不正: {key!r}")
    return parts


class LocalBlobStore:
    """ローカルのディレクトリ（またはそこにマウントされたバケット）に置く。"""

    def __init__(self, root: Path) -> None:
        self.root = root

    def _path(self, key: str) -> Path:
        return self.root.joinpath(*_safe_key(key))

    def get(self, key: str) -> bytes | None:
        p = self._path(key)
        try:
            if not p.is_file():
                return None
            return p.read_bytes()
        except OSError as e:
            raise StoreError("読込みに失敗") from e

    def exists(self, key: str) -> bool:
        try:
            return self._path(key).is_file()
        except OSError as e:
            raise StoreError("存在確認に失敗") from e

    def put_if_absent(self, key: str, data: bytes) -> str:
        """'CREATED' | 'UNCHANGED' | 'CONFLICT'。ファイルは一時ファイルに書き、`link` で原子的に確定する（無ければ排他作成にフォールバック）。"""
        p = self._path(key)
        try:
            p.parent.mkdir(parents=True, exist_ok=True)
            if p.exists():
                return "UNCHANGED" if p.read_bytes() == data else "CONFLICT"
            fd, tmp_name = tempfile.mkstemp(prefix=".tmp-", dir=p.parent)
            tmp = Path(tmp_name)
            try:
                with os.fdopen(fd, "wb") as f:
                    f.write(data)
                    f.flush()
                    os.fsync(f.fileno())
                try:
                    os.link(tmp, p)
                except FileExistsError:
                    return "UNCHANGED" if p.read_bytes() == data else "CONFLICT"
                except OSError as e:
                    if e.errno not in (errno.EPERM, errno.ENOTSUP, errno.EOPNOTSUPP, errno.EXDEV, errno.ENOSYS):
                        raise
                    # ハードリンクが無いファイルシステム（FUSE など）: 排他作成（O_EXCL）で直接書く
                    try:
                        with p.open("xb") as f:
                            f.write(data)
                    except FileExistsError:
                        return "UNCHANGED" if p.read_bytes() == data else "CONFLICT"
                return "CREATED"
            finally:
                tmp.unlink(missing_ok=True)
        except OSError as e:
            raise StoreError("書込みに失敗") from e

    def delete(self, key: str) -> None:
        try:
            self._path(key).unlink(missing_ok=True)
        except OSError as e:
            raise StoreError("削除に失敗") from e


def open_store(uri: str | None) -> tuple[LocalBlobStore, str]:
    """`MODEL_STORE_URI` から保管先を開く。戻り値は (ストア, artifact_uri の接頭辞)。"""
    if not uri:
        raise UsageError("MODEL_STORE_URI_MISSING", "MODEL_STORE_URI が未設定")
    parsed = urlparse(uri)
    if parsed.scheme == "file" and not parsed.netloc and parsed.path.startswith("/"):
        root = Path(unquote(parsed.path))
        if not root.is_dir():
            raise UsageError("MODEL_STORE_URI_INVALID", "MODEL_STORE_URI の指すディレクトリが無い")
        return LocalBlobStore(root), uri.rstrip("/")
    if parsed.scheme == "gs":
        raise UsageError(
            "MODEL_STORE_URI_UNSUPPORTED", "gs:// の直接アクセスは未対応（GCS ボリュームのマウント先を file:// で指定する。UNRESOLVED）"
        )
    raise UsageError("MODEL_STORE_URI_INVALID", "MODEL_STORE_URI は file:///絶対パス")


@dataclass
class Written:
    """この実行が新しく作ったキー（失敗時の cleanup の対象）。"""

    created: list[str] = field(default_factory=list)


class ArtifactStore:
    def __init__(self, blob: LocalBlobStore, uri_prefix: str) -> None:
        self.blob = blob
        self.uri_prefix = uri_prefix

    @staticmethod
    def dataset_keys(tenant_id: int, dataset_id: str) -> tuple[str, str]:
        return f"{tenant_id}/datasets/{dataset_id}.manifest.json", f"{tenant_id}/datasets/{dataset_id}.csv.gz"

    def read_dataset(self, tenant_id: int, dataset_id: str) -> tuple[bytes, bytes] | None:
        """(manifest, csv.gz)。manifest が無ければ None（manifest が確定の印。CSV だけの途中状態は『まだ無い』）。"""
        mk, ck = self.dataset_keys(tenant_id, dataset_id)
        manifest = self.blob.get(mk)
        if manifest is None:
            return None
        csv_gz = self.blob.get(ck)
        if csv_gz is None:
            raise StoreError("manifest があるのにデータセット本体が無い")
        return manifest, csv_gz

    def model_keys(self, tenant_id: int, model_id: str) -> tuple[str, str]:
        return f"{tenant_id}/models/{model_id}.json", f"{tenant_id}/models/{model_id}.sig"

    def report_key(self, tenant_id: int, model_id: str) -> str:
        return f"{tenant_id}/reports/{model_id}.report.json"

    def artifact_uri(self, tenant_id: int, model_id: str) -> str:
        return f"{self.uri_prefix}/{tenant_id}/models/{model_id}.json"

    def read_model(self, tenant_id: int, model_id: str) -> tuple[bytes, bytes] | None:
        jk, sk = self.model_keys(tenant_id, model_id)
        raw = self.blob.get(jk)
        if raw is None:
            return None
        sig = self.blob.get(sk)
        if sig is None:
            raise StoreError("署名ファイルが無い")
        return raw, sig

    def write_model(self, tenant_id: int, model_id: str, raw: bytes, sig: bytes, report: bytes, written: Written) -> None:
        """sig → report → json の順（json が確定の印）。同じキーに別内容があれば ArtifactConflict（上書きしない）。"""
        jk, sk = self.model_keys(tenant_id, model_id)
        rk = self.report_key(tenant_id, model_id)
        for key, data in ((sk, sig), (rk, report), (jk, raw)):
            outcome = self.blob.put_if_absent(key, data)
            if outcome == "CONFLICT":
                raise ArtifactConflict(key)
            if outcome == "CREATED":
                written.created.append(key)

    def cleanup(self, written: Written) -> list[str]:
        """この実行が作ったものだけを消す（json を先に消して、確定の印を外す）。消せなかったキーを返す。"""
        failed: list[str] = []
        for key in sorted(written.created, key=lambda k: not k.endswith(".json")):
            try:
                self.blob.delete(key)
            except StoreError:
                failed.append(key)
        written.created.clear()
        return failed
