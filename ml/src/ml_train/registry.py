"""モデルレジストリ（`ml_model`）への書込み。**ml-train の DB ユーザー（mltrain）は `ml_model` の SELECT / INSERT だけ**（CONTRACTS §5.1・§8.2）。

- 登録できる状態は CANDIDATE と REJECTED だけ（型で ACTIVE を作れない）。`active_flag` / `activated_*` は常に NULL。UPDATE / DELETE は書かない。
- すべての SQL に `tenant_id` を入れる。他テナントの行は読まない・書かない。
- 既存 ACTIVE は読む（直前モデルとの比較）だけで、変更しない。
"""

import json
import re
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any, Protocol
from urllib.parse import urlparse

from .errors import MlTrainError, TransientError, UsageError


class DuplicateModel(MlTrainError):
    def __init__(self) -> None:
        super().__init__("MODEL_ALREADY_REGISTERED", "同じ tenant_id・model_id の行が既にある")


class RegistryUnavailable(TransientError):
    def __init__(self) -> None:
        super().__init__("DB_UNAVAILABLE", "DB に接続できない（一時的な失敗）")


class RegistryError(MlTrainError):
    def __init__(self, reason: str) -> None:
        super().__init__(reason, "レジストリの操作に失敗")


@dataclass(frozen=True)
class RegistryRow:
    tenant_id: int
    model_id: str
    kind: str
    schema_version: int
    feature_schema_hash: str
    artifact_uri: str
    sha256: str
    signature_sha256: str
    status: str
    metrics: dict[str, Any] | None = None


@dataclass(frozen=True)
class NewModel:
    """INSERT する行。status は CANDIDATE か REJECTED だけ。"""

    tenant_id: int
    model_id: str
    kind: str
    schema_version: int
    feature_schema_hash: str
    artifact_uri: str
    sha256: str
    signature_sha256: str
    status: str
    trained_from: str | None
    trained_to: str | None
    n_train: int | None
    metrics: dict[str, Any]
    created_at: datetime

    def __post_init__(self) -> None:
        if self.status not in ("CANDIDATE", "REJECTED"):
            raise ValueError("ml-train が登録できる状態は CANDIDATE / REJECTED だけ（ACTIVE への昇格は管理者操作）")
        if self.tenant_id < 1 or not re.fullmatch(r"sha256:[0-9a-f]{64}", self.feature_schema_hash):
            raise ValueError("tenant_id / feature_schema_hash が不正")
        if self.created_at.tzinfo is None:
            raise ValueError("created_at に時刻帯が必要")


class Registry(Protocol):
    def find(self, tenant_id: int, model_id: str) -> RegistryRow | None: ...

    def active(self, tenant_id: int, kind: str) -> RegistryRow | None: ...

    def insert(self, row: NewModel) -> None: ...

    def close(self) -> None: ...


class InMemoryRegistry:
    """テスト・ドライラン用。実 DB と同じ規則（テナント境界・重複・状態）。失敗の注入ができる。"""

    def __init__(self) -> None:
        self.rows: dict[tuple[int, str], RegistryRow] = {}
        self.actives: dict[tuple[int, str], RegistryRow] = {}
        self.fail_insert_with: Exception | None = None
        self.fail_find_with: Exception | None = None

    def find(self, tenant_id: int, model_id: str) -> RegistryRow | None:
        if self.fail_find_with:
            raise self.fail_find_with
        return self.rows.get((tenant_id, model_id))

    def active(self, tenant_id: int, kind: str) -> RegistryRow | None:
        return self.actives.get((tenant_id, kind))

    def insert(self, row: NewModel) -> None:
        if self.fail_insert_with:
            raise self.fail_insert_with
        key = (row.tenant_id, row.model_id)
        if key in self.rows:
            raise DuplicateModel
        self.rows[key] = RegistryRow(
            row.tenant_id, row.model_id, row.kind, row.schema_version, row.feature_schema_hash, row.artifact_uri,
            row.sha256, row.signature_sha256, row.status, row.metrics,
        )  # fmt: skip

    def close(self) -> None:
        pass


_TRANSIENT = {1040, 1205, 1213, 2002, 2003, 2006, 2013, 2055}  # 接続数超過・ロック待ち・デッドロック・接続不能/断
_AUTH = {1044, 1045, 1142, 1143, 1049}  # 認証・権限・DB なし（再試行しても直らない）


def parse_jdbc_url(url: str) -> tuple[str, int, str]:
    """`jdbc:mysql://host:port/db?params` → (host, port, db)。"""
    if not url.startswith("jdbc:mysql://"):
        raise UsageError("DB_URL_INVALID", "DB_URL は jdbc:mysql:// で始まる")
    p = urlparse(url[len("jdbc:") :])
    if not p.hostname or not p.path.strip("/"):
        raise UsageError("DB_URL_INVALID", "DB_URL にホストと DB 名が必要")
    return p.hostname, p.port or 3306, p.path.strip("/")


class MySqlRegistry:
    def __init__(self, url: str, user: str, password: str, *, connect_timeout: int = 10) -> None:
        import pymysql

        self._pymysql = pymysql
        host, port, db = parse_jdbc_url(url)
        try:
            self._conn = pymysql.connect(
                host=host, port=port, user=user, password=password, database=db, charset="utf8mb4",
                connect_timeout=connect_timeout, read_timeout=30, write_timeout=30, autocommit=False,
            )  # fmt: skip
        except pymysql.err.Error as e:
            raise self._map(e) from None

    def _map(self, e: Exception) -> MlTrainError:
        code = e.args[0] if e.args and isinstance(e.args[0], int) else None
        if code in _TRANSIENT:
            return RegistryUnavailable()
        if code == 1062:
            return DuplicateModel()
        if code in _AUTH:
            return RegistryError("DB_AUTH_FAILED")
        return RegistryError("DB_ERROR")

    def _row(self, r: tuple[Any, ...]) -> RegistryRow:
        metrics = json.loads(r[9]) if r[9] else None
        return RegistryRow(r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8], metrics)

    _COLS = "tenant_id, model_id, kind, schema_version, feature_schema_hash, artifact_uri, sha256, signature_sha256, status, metrics_json"

    def find(self, tenant_id: int, model_id: str) -> RegistryRow | None:
        try:
            with self._conn.cursor() as c:
                c.execute(f"SELECT {self._COLS} FROM ml_model WHERE tenant_id = %s AND model_id = %s", (tenant_id, model_id))  # noqa: S608
                r = c.fetchone()
            self._conn.rollback()
        except self._pymysql.err.Error as e:
            raise self._map(e) from None
        return self._row(r) if r else None

    def active(self, tenant_id: int, kind: str) -> RegistryRow | None:
        try:
            with self._conn.cursor() as c:
                c.execute(f"SELECT {self._COLS} FROM ml_model WHERE tenant_id = %s AND kind = %s AND status = 'ACTIVE'", (tenant_id, kind))  # noqa: S608
                r = c.fetchone()
            self._conn.rollback()
        except self._pymysql.err.Error as e:
            raise self._map(e) from None
        return self._row(r) if r else None

    def insert(self, row: NewModel) -> None:
        created = row.created_at.astimezone(UTC).strftime("%Y-%m-%d %H:%M:%S.%f")
        try:
            with self._conn.cursor() as c:
                c.execute(
                    "INSERT INTO ml_model (tenant_id, model_id, kind, schema_version, feature_schema_hash, artifact_uri, sha256, signature_sha256, "
                    "status, trained_from, trained_to, n_train, metrics_json, created_at) "
                    "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)",
                    (
                        row.tenant_id, row.model_id, row.kind, row.schema_version, row.feature_schema_hash, row.artifact_uri,
                        row.sha256, row.signature_sha256, row.status, row.trained_from, row.trained_to, row.n_train,
                        json.dumps(row.metrics, sort_keys=True, ensure_ascii=False, allow_nan=False), created,
                    ),
                )  # fmt: skip
            self._conn.commit()
        except self._pymysql.err.Error as e:
            try:
                self._conn.rollback()
            except self._pymysql.err.Error:
                pass
            raise self._map(e) from None

    def close(self) -> None:
        try:
            self._conn.close()
        except self._pymysql.err.Error:
            pass
