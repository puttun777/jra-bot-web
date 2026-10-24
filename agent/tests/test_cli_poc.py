"""CLI（verify-config / verify-queue / status / diagnose / set-credential）と PoC の Mock。"""

from __future__ import annotations

import base64
import json
from pathlib import Path

import pytest

from jra_agent.cli import main
from jra_agent.clock import SystemClock
from jra_agent.config import QueueConfig
from jra_agent.queue import OutboxQueue
from tests.helpers import enqueue_realtime

KEY = base64.urlsafe_b64encode(bytes(range(32))).decode().rstrip("=")


@pytest.fixture
def cfg_path(tmp_path: Path) -> Path:
    p = tmp_path / "agent.toml"
    d = (tmp_path / "data").as_posix()
    p.write_text(
        f'environment = "development"\nagent_id = "ag_dev"\ndata_dir = "{d}"\n[server]\nbase_url = "http://127.0.0.1:18080"\n'
        f'[beacon]\nurl = "http://127.0.0.1:18081/b.json"\nkeys = [{{kid = "dev-a", public_key = "{KEY}"}}]\n[jvlink]\nmode = "fake"\n',
        encoding="utf-8",
    )
    return p


def run(capsys: pytest.CaptureFixture[str], *args: str) -> tuple[int, str, str]:
    rc = main(list(args))
    out = capsys.readouterr()
    return rc, out.out, out.err


def test_verify_config_ok_and_rejects_bad(cfg_path: Path, tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    rc, out, _ = run(capsys, "--config", str(cfg_path), "verify-config")
    assert rc == 0 and json.loads(out)["ok"] is True
    bad = tmp_path / "bad.toml"
    bad.write_text(cfg_path.read_text().replace("[server]", '[server]\ntoken = "jrat1_secret0123456789"'), encoding="utf-8")
    rc, out, err = run(capsys, "--config", str(bad), "verify-config")
    assert rc == 2 and "jrat1_secret" not in out + err


def test_verify_queue_and_status(cfg_path: Path, tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    rc, out, _ = run(capsys, "--config", str(cfg_path), "verify-queue")
    assert rc == 1 and json.loads(out) == {"exists": False}  # キューが無い
    q = OutboxQueue(tmp_path / "data" / "queue.sqlite3", SystemClock(), QueueConfig(min_free_bytes=0))
    enqueue_realtime(q)
    rc, out, _ = run(capsys, "--config", str(cfg_path), "verify-queue")
    doc = json.loads(out)
    assert rc == 0 and doc["by_status"] == {"QUEUED": 1} and doc["problems"] == []
    q.raise_alert("X", "detail")
    rc, out, _ = run(capsys, "--config", str(cfg_path), "status")
    assert rc == 0 and json.loads(out)["queue"]["alerts"] == ["X"]
    rc, out, _ = run(capsys, "--config", str(cfg_path), "clear-alert", "X")
    assert json.loads(out) == {"cleared": True}
    q.close()


def test_set_credential_never_echoes_token_and_diagnose_has_no_secrets(
    cfg_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    import io

    monkeypatch.setattr("sys.stdin", io.StringIO("jrat1_cli-secret-token-0123456789\n"))
    rc, out, err = run(capsys, "--config", str(cfg_path), "set-credential", "--stdin")
    assert rc == 0 and "cli-secret-token" not in out + err
    rc, out, err = run(capsys, "--config", str(cfg_path), "diagnose")
    doc = json.loads(out)
    assert rc == 0 and "cli-secret-token" not in out + err
    assert doc["jvlink_spec"]["confirmed"] == 0 and doc["jvlink_spec"]["total"] >= 10  # 公式仕様の確認項目は現在 0 件
    assert doc["environment"]["python_bits"] in (32, 64) and doc["com"]["applicable"] in (True, False)
    monkeypatch.setattr("sys.stdin", io.StringIO("bad\n"))
    assert run(capsys, "--config", str(cfg_path), "set-credential", "--stdin")[0] == 2


def test_beacon_reset_requires_confirm(cfg_path: Path, tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    (tmp_path / "data").mkdir(exist_ok=True)
    assert run(capsys, "--config", str(cfg_path), "beacon-reset")[0] == 2


def test_poc_mock_passes_all_runnable_gates(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    from poc.poc_mock import main as poc_main

    out = tmp_path / "mock.json"
    assert poc_main(["--out", str(out)]) == 0
    capsys.readouterr()
    doc = json.loads(out.read_text(encoding="utf-8"))
    st = {g["gate"]: g["status"] for g in doc["gates"]}
    assert st == {
        "G-AGENT-1": "NOT_RUN",
        "G-AGENT-2": "MOCK_PASS",
        "G-AGENT-3": "MOCK_PASS",
        "G-AGENT-4": "MOCK_PASS",
        "G-AGENT-5": "MOCK_PASS",
        "G-AGENT-6": "NOT_RUN",
    }
    text = out.read_text(encoding="utf-8")
    assert "bytes" not in json.dumps([g["metrics"].get("record") for g in doc["gates"]])  # 内容を持たない
    assert doc["gates"][1]["metrics"]["byte_exact"] is True and doc["gates"][4]["metrics"]["requeued_after_crash"] == 1
    assert "日本語" not in text.replace("集計のみ", "")  # 合成レコードの中身も出力しない


def test_poc_real_refuses_without_explicit_flag(capsys: pytest.CaptureFixture[str]) -> None:
    from poc.poc_real import main as real_main

    assert real_main(["g1"]) == 2  # --allow-real-jvlink が無い（Windows 以外でも 2）
    capsys.readouterr()
