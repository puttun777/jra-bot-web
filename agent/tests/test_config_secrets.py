"""設定の検証（HTTPS 強制・秘密を置けない・署名検証を省略できない）と、秘密の保護（Fake の SecretStore で保存・復号・失効・更新）。"""

from __future__ import annotations

import base64
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import pytest

from jra_agent.config import parse_config
from jra_agent.errors import ConfigError, SecretError
from jra_agent.redact import RedactingFilter, redact
from jra_agent.secrets_store import Credential, CredentialStore, FakeProtector, MemorySecretStore, ProtectedFileSecretStore

KEY = base64.urlsafe_b64encode(bytes(range(32))).decode().rstrip("=")


def base(**over: Any) -> dict[str, Any]:
    doc: dict[str, Any] = {
        "environment": "production", "agent_id": "ag_main", "data_dir": "C:/jraagent",
        "server": {"base_url": "https://ingest.example.run.app"},
        "beacon": {"url": "https://storage.example/beacon.json", "keys": [{"kid": "beacon-2026a", "public_key": KEY}]},
        "jvlink": {"mode": "com"},
    }  # fmt: skip
    doc.update(over)
    return doc


def test_minimal_production_config_parses() -> None:
    c = parse_config(base())
    assert c.production and c.base_url.startswith("https://") and c.beacon.keys[0].kid == "beacon-2026a" and not c.update.enabled


@pytest.mark.parametrize(
    "bad",
    [
        {"server": {"base_url": "http://ingest.example"}},  # 本番の平文 HTTP
        {"server": {"base_url": "https://127.0.0.1:8080"}},  # 本番のループバック
        {"server": {"base_url": "ftp://x"}},
        {"server": {"base_url": "https://user:pw@ingest.example"}},
        {"beacon": {"url": "http://storage.example/b.json", "keys": [{"kid": "k", "public_key": KEY}]}},
        {"beacon": {"url": "https://storage.example/b.json", "keys": []}},  # 検証鍵が無い = 検証を省略する設定は作れない
        {"beacon": {"url": "https://s.example/b.json", "keys": [{"kid": "k", "public_key": KEY}], "verify": False}},
        {"beacon": {"url": "https://s.example/b.json", "keys": [{"kid": "k", "public_key": "AAAA"}]}},
        {"beacon": {"url": "https://s.example/b.json", "keys": [{"kid": "k", "public_key": KEY}, {"kid": "k", "public_key": KEY}]}},
        {"jvlink": {"mode": "fake"}},  # 本番で Fake を黙って使わない
        {"agent_id": "x"},
        {"environment": "staging"},
        {"queue": {"max_batch_records": 201}},
        {"update": {"enabled": True}},
        {"unknown_key": 1},
    ],
)
def test_invalid_configs_are_rejected(bad: dict[str, Any]) -> None:
    with pytest.raises(ConfigError):
        parse_config(base(**bad))


@pytest.mark.parametrize("key", ["token", "password", "secret", "api_key", "sid", "jvlink_key", "private_key"])
def test_secrets_cannot_be_put_in_config(key: str) -> None:
    doc = base()
    doc["server"][key] = "jrat1_abcdefabcdefabcdef"
    with pytest.raises(ConfigError) as e:
        parse_config(doc)
    assert "jrat1_abcdef" not in str(e.value)  # 値は例外に出さない


def test_development_allows_loopback_http_only() -> None:
    c = parse_config(base(environment="development", server={"base_url": "http://127.0.0.1:18080"}, jvlink={"mode": "fake"}))
    assert not c.production
    with pytest.raises(ConfigError):
        parse_config(base(environment="development", server={"base_url": "http://ingest.example"}))


def test_error_messages_do_not_contain_values() -> None:
    with pytest.raises(ConfigError) as e:
        parse_config(base(server={"base_url": "http://user:SECRETPW@evil.example"}))
    assert "SECRETPW" not in str(e.value)


# ------------------------------------------------------------------ 秘密
def test_credential_roundtrip_revoke_and_rotate(tmp_path: Path) -> None:
    store = CredentialStore(ProtectedFileSecretStore(tmp_path / "secrets", FakeProtector()))
    assert store.load() is None
    exp = datetime(2027, 1, 30, tzinfo=UTC)
    store.save(Credential("ag_main", "jrat1_first-token-value", exp))
    got = store.load()
    assert got is not None and got.token == "jrat1_first-token-value" and got.expires_at == exp
    fp1 = got.fingerprint()
    store.save(Credential("ag_main", "jrat1_second-token-value", exp))  # 更新（再発行されたトークンで上書き）
    assert store.load().fingerprint() != fp1  # type: ignore[union-attr]
    assert store.revoke_local() is True and store.load() is None and store.revoke_local() is False


def test_plain_token_is_never_written_to_disk(tmp_path: Path) -> None:
    d = tmp_path / "secrets"
    CredentialStore(ProtectedFileSecretStore(d, FakeProtector())).save(Credential("ag_main", "jrat1_PLAINTEXT-MARKER-0123456789", None))
    blobs = list(d.iterdir())
    assert blobs and all(b"PLAINTEXT-MARKER" not in b.read_bytes() for b in blobs)


def test_protected_blob_is_not_decryptable_by_another_protector_or_tampered(tmp_path: Path) -> None:
    d = tmp_path / "secrets"
    ProtectedFileSecretStore(d, FakeProtector()).save("credential", b"secret")
    with pytest.raises(SecretError):
        ProtectedFileSecretStore(d, FakeProtector()).load("credential")  # 別のユーザー/PC（別の鍵）では復号できない
    p = FakeProtector()
    s = ProtectedFileSecretStore(d, p)
    s.save("credential", b"secret")
    f = d / "credential.bin"
    f.write_bytes(f.read_bytes()[:-1] + b"\x00")
    with pytest.raises(SecretError):
        s.load("credential")


def test_secret_name_cannot_escape_directory(tmp_path: Path) -> None:
    s = ProtectedFileSecretStore(tmp_path, FakeProtector())
    with pytest.raises(SecretError):
        s.save("../evil", b"x")


def test_credential_repr_and_expiry() -> None:
    c = Credential("ag_main", "jrat1_very-secret-token", datetime(2026, 12, 1, tzinfo=UTC))
    assert "very-secret-token" not in repr(c) and "very-secret-token" not in str(c)
    assert not c.expired(datetime(2026, 11, 30, tzinfo=UTC)) and c.expired(datetime(2026, 12, 1, tzinfo=UTC))
    assert not Credential("a", "t", None).expired(datetime.now(UTC) + timedelta(days=9999))


def test_memory_store_basic() -> None:
    m = MemorySecretStore()
    m.save("a", b"1")
    assert m.load("a") == b"1" and m.delete("a") and m.load("a") is None


def test_redaction() -> None:
    text = "token=jrat1_abcdefghijklmnop X-Signature: " + "a" * 64 + " bytesB64='" + "QUJD" * 10 + "'"
    out = redact(text)
    assert "abcdefghijklmnop" not in out and "a" * 64 not in out and "QUJD" * 10 not in out


def test_logging_filter_redacts_and_drops_exception_text(caplog: pytest.LogCaptureFixture) -> None:
    import logging

    log = logging.getLogger("t.redact")
    log.addFilter(RedactingFilter())
    with caplog.at_level(logging.INFO, logger="t.redact"):
        try:
            raise ValueError("jrat1_leaky-token-in-exception-message")
        except ValueError:
            log.exception("failed with jrat1_inline-token-0123456789")
    text = caplog.text
    assert "leaky-token" not in text and "inline-token" not in text and "ValueError" in text
