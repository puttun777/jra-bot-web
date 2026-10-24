"""設定（TOML）。秘密（トークン・利用キー）は**置けない**: それらしいキーがあれば拒否する。
値の誤りは項目名だけを報告し、値は例外に含めない。本番（`environment = "production"`）は HTTPS 以外・ループバックを拒否する。
署名検証を省略する設定は存在しない（ビーコン・更新 manifest とも）。
"""

from __future__ import annotations

import base64
import os
import tomllib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

from .errors import ConfigError

_FORBIDDEN_KEYS = {"token", "password", "secret", "api_key", "apikey", "sid", "jvlink_key", "service_key", "private_key"}
_LOOPBACK = {"127.0.0.1", "::1", "localhost"}


@dataclass(frozen=True)
class PublicKey:
    kid: str
    raw: bytes  # Ed25519 公開鍵 32 バイト


@dataclass(frozen=True)
class QueueConfig:
    max_bytes: int = 2 * 1024**3
    min_free_bytes: int = 512 * 1024**2
    realtime_ttl_seconds: int = 6 * 24 * 3600  # サーバは速報を 7 日で拒否する（OBSERVED_AT_TOO_OLD）ので余裕を持たせる
    max_batch_records: int = 200
    max_batch_raw_bytes: int = 600_000
    gzip_min_bytes: int = 512


@dataclass(frozen=True)
class SenderConfig:
    max_attempts: int = 50
    max_conflict_wait_seconds: float = 120.0
    sends_per_second: float = 2.0  # 復帰時の再送は最大 2 バッチ/秒（CONTRACTS §7.4）
    request_timeout_seconds: float = 30.0


@dataclass(frozen=True)
class WorkerConfig:
    call_timeout_seconds: float = 120.0
    startup_timeout_seconds: float = 30.0
    max_restarts: int = 5
    restart_window_seconds: float = 3600.0
    restart_cooldown_seconds: float = 300.0


@dataclass(frozen=True)
class BeaconConfig:
    url: str
    keys: tuple[PublicKey, ...]
    max_age_seconds: int = 6 * 3600


@dataclass(frozen=True)
class UpdateConfig:
    keys: tuple[PublicKey, ...] = ()
    enabled: bool = False  # 既定は無効。本人の許可（`agent update --apply`）なしに適用しない


@dataclass(frozen=True)
class AgentConfig:
    environment: str
    base_url: str
    agent_id: str
    data_dir: Path
    beacon: BeaconConfig
    queue: QueueConfig = field(default_factory=QueueConfig)
    sender: SenderConfig = field(default_factory=SenderConfig)
    worker: WorkerConfig = field(default_factory=WorkerConfig)
    update: UpdateConfig = field(default_factory=UpdateConfig)
    jvlink_mode: str = "fake"  # fake | com（com は Windows 実機で、利用者が明示的に有効にしたときだけ）
    log_retention_days: int = 30

    @property
    def production(self) -> bool:
        return self.environment == "production"

    @property
    def queue_path(self) -> Path:
        return self.data_dir / "queue.sqlite3"

    @property
    def log_dir(self) -> Path:
        return self.data_dir / "logs"

    @property
    def secret_path(self) -> Path:
        return self.data_dir / "secrets"


def parse_public_key(entry: object, where: str) -> PublicKey:
    if not isinstance(entry, dict) or set(entry) != {"kid", "public_key"}:
        raise ConfigError(f"{where}: kid と public_key が必要")
    kid, key = entry["kid"], entry["public_key"]
    if not isinstance(kid, str) or not (1 <= len(kid) <= 32) or not kid.replace("-", "").replace("_", "").isalnum():
        raise ConfigError(f"{where}.kid の形式が不正")
    if not isinstance(key, str):
        raise ConfigError(f"{where}.public_key は文字列")
    try:
        raw = base64.urlsafe_b64decode(key + "=" * (-len(key) % 4))
    except ValueError as e:
        raise ConfigError(f"{where}.public_key の Base64 が不正") from e
    if len(raw) != 32:
        raise ConfigError(f"{where}.public_key は Ed25519 の 32 バイト")
    return PublicKey(kid, raw)


def validate_url(url: object, where: str, environment: str) -> str:
    if not isinstance(url, str):
        raise ConfigError(f"{where} は文字列")
    parts = urlsplit(url)
    if parts.username or parts.password:
        raise ConfigError(f"{where} に認証情報を含めない")
    if parts.scheme == "https" and parts.hostname:
        if environment == "production" and parts.hostname in _LOOPBACK:
            raise ConfigError(f"{where}: 本番でループバックは使えない")
        return url
    if parts.scheme == "http" and parts.hostname in _LOOPBACK and environment == "development":
        return url  # 開発・結合テスト専用。本番では不可
    raise ConfigError(f"{where} は HTTPS（本番）である必要がある")


def _reject_secrets(node: object, path: str = "") -> None:
    if isinstance(node, dict):
        for k, v in node.items():
            if str(k).lower() in _FORBIDDEN_KEYS:
                raise ConfigError(f"{path}{k}: 秘密は設定ファイルに置かない（DPAPI などの SecretStore に保存する）")
            _reject_secrets(v, f"{path}{k}.")
    elif isinstance(node, list):
        for i, v in enumerate(node):
            _reject_secrets(v, f"{path}[{i}].")


def _only(table: dict[str, Any], allowed: set[str], where: str) -> None:
    unknown = sorted(set(table) - allowed)
    if unknown:
        raise ConfigError(f"{where}: 未知の項目 {unknown[:3]}")


def _int(table: dict[str, Any], key: str, default: int, lo: int, hi: int, where: str) -> int:
    v = table.get(key, default)
    if isinstance(v, bool) or not isinstance(v, int) or not lo <= v <= hi:
        raise ConfigError(f"{where}.{key} は {lo}〜{hi} の整数")
    return v


def _float(table: dict[str, Any], key: str, default: float, lo: float, hi: float, where: str) -> float:
    v = table.get(key, default)
    if isinstance(v, bool) or not isinstance(v, (int, float)) or not lo <= v <= hi:
        raise ConfigError(f"{where}.{key} は {lo}〜{hi}")
    return float(v)


def expand_path(text: str) -> Path:
    return Path(os.path.expandvars(os.path.expanduser(text)))


def parse_config(doc: dict[str, Any]) -> AgentConfig:
    _reject_secrets(doc)
    _only(
        doc, {"environment", "agent_id", "data_dir", "server", "beacon", "queue", "sender", "worker", "update", "jvlink", "log"}, "config"
    )
    env = doc.get("environment", "production")
    if env not in ("production", "development"):
        raise ConfigError("environment は production または development")
    agent_id = doc.get("agent_id")
    if not isinstance(agent_id, str) or not (3 <= len(agent_id) <= 64) or not agent_id.replace("-", "").replace("_", "").isalnum():
        raise ConfigError("agent_id の形式が不正")
    data_dir = doc.get("data_dir")
    if not isinstance(data_dir, str) or not data_dir:
        raise ConfigError("data_dir が必要")

    server = doc.get("server", {})
    _only(server, {"base_url"}, "server")
    base_url = validate_url(server.get("base_url"), "server.base_url", env)

    beacon = doc.get("beacon", {})
    _only(beacon, {"url", "keys", "max_age_seconds"}, "beacon")
    keys_raw = beacon.get("keys", [])
    if not isinstance(keys_raw, list) or not 1 <= len(keys_raw) <= 2:
        raise ConfigError("beacon.keys は 1〜2 本（kid 付きの公開鍵）。検証を省略する設定はない")
    keys = tuple(parse_public_key(k, f"beacon.keys[{i}]") for i, k in enumerate(keys_raw))
    if len({k.kid for k in keys}) != len(keys):
        raise ConfigError("beacon.keys の kid が重複")
    beacon_cfg = BeaconConfig(
        validate_url(beacon.get("url"), "beacon.url", env), keys, _int(beacon, "max_age_seconds", 21600, 60, 7 * 24 * 3600, "beacon")
    )

    q = doc.get("queue", {})
    _only(q, set(QueueConfig.__dataclass_fields__), "queue")
    d = QueueConfig()
    queue = QueueConfig(
        _int(q, "max_bytes", d.max_bytes, 1024**2, 64 * 1024**3, "queue"),
        _int(q, "min_free_bytes", d.min_free_bytes, 0, 1024**4, "queue"),
        _int(q, "realtime_ttl_seconds", d.realtime_ttl_seconds, 60, 6 * 24 * 3600 + 12 * 3600, "queue"),
        _int(q, "max_batch_records", d.max_batch_records, 1, 200, "queue"),
        _int(q, "max_batch_raw_bytes", d.max_batch_raw_bytes, 1024, 700_000, "queue"),
        _int(q, "gzip_min_bytes", d.gzip_min_bytes, 0, 1024**2, "queue"),
    )
    s = doc.get("sender", {})
    _only(s, set(SenderConfig.__dataclass_fields__), "sender")
    sd = SenderConfig()
    sender = SenderConfig(
        _int(s, "max_attempts", sd.max_attempts, 1, 1000, "sender"),
        _float(s, "max_conflict_wait_seconds", sd.max_conflict_wait_seconds, 1, 900, "sender"),
        _float(s, "sends_per_second", sd.sends_per_second, 0.1, 10, "sender"),
        _float(s, "request_timeout_seconds", sd.request_timeout_seconds, 1, 300, "sender"),
    )
    w = doc.get("worker", {})
    _only(w, set(WorkerConfig.__dataclass_fields__), "worker")
    wd = WorkerConfig()
    worker = WorkerConfig(
        _float(w, "call_timeout_seconds", wd.call_timeout_seconds, 1, 3600, "worker"),
        _float(w, "startup_timeout_seconds", wd.startup_timeout_seconds, 1, 600, "worker"),
        _int(w, "max_restarts", wd.max_restarts, 1, 100, "worker"),
        _float(w, "restart_window_seconds", wd.restart_window_seconds, 10, 86400, "worker"),
        _float(w, "restart_cooldown_seconds", wd.restart_cooldown_seconds, 1, 86400, "worker"),
    )
    u = doc.get("update", {})
    _only(u, {"keys", "enabled"}, "update")
    update = UpdateConfig(
        tuple(parse_public_key(k, f"update.keys[{i}]") for i, k in enumerate(u.get("keys", []))), bool(u.get("enabled", False))
    )
    if update.enabled and not update.keys:
        raise ConfigError("update.enabled には update.keys（署名検証用の公開鍵）が必要")
    jv = doc.get("jvlink", {})
    _only(jv, {"mode"}, "jvlink")
    mode = jv.get("mode", "fake")
    if mode not in ("fake", "com"):
        raise ConfigError("jvlink.mode は fake または com")
    if env == "production" and mode == "fake":
        # 本番の設定で Fake を黙って使わない（取得したつもりで何も取らない事故を防ぐ）。com にするには利用者の明示が要る
        raise ConfigError('production では jvlink.mode = "com" を明示する（fake は development だけ）')
    log = doc.get("log", {})
    _only(log, {"retention_days"}, "log")
    return AgentConfig(
        env,
        base_url,
        agent_id,
        expand_path(data_dir),
        beacon_cfg,
        queue,
        sender,
        worker,
        update,
        mode,
        _int(log, "retention_days", 30, 1, 365, "log"),
    )


def load_config(path: Path) -> AgentConfig:
    try:
        with path.open("rb") as f:
            doc = tomllib.load(f)
    except OSError as e:
        raise ConfigError("設定ファイルを読めない") from e
    except tomllib.TOMLDecodeError as e:
        raise ConfigError("設定ファイルの TOML が不正") from e
    return parse_config(doc)
