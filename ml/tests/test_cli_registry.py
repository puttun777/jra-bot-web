import base64
import io
import json
import logging

import pymysql
import pytest

from ml_train import cli, logsetup
from ml_train import modeljson as mj
from ml_train import registry as reg
from ml_train.errors import UsageError
from tests.synth import make_dataset

SEED = bytes(range(3, 35))
KEY = base64.b64encode(SEED).decode()
PUB = base64.b64encode(mj.public_key_of(SEED)).decode()


def _write_ops(path, mode="NORMAL", **over):
    body = {"schema_version": 1, "seq": 3, "mode": mode, "desired": {"mode": "NORMAL"}}
    body.update(over)
    path.write_text(json.dumps(body))
    return f"file://{path}"


@pytest.fixture
def env(tmp_path):
    store = tmp_path / "store"
    (store / "1" / "datasets").mkdir(parents=True)
    gz, mf = make_dataset()
    (store / "1" / "datasets" / "ds-synth-1.csv.gz").write_bytes(gz)
    (store / "1" / "datasets" / "ds-synth-1.manifest.json").write_bytes(mf)
    (tmp_path / "train.json").write_text(json.dumps({"kind": "calibration"}))
    (tmp_path / "gate.json").write_text(json.dumps({"purpose": "synthetic_test", "checks": {"min_valid_rows": 10}}))
    return {
        "APP_ENV": "local", "OPS_MODE_SOURCE": _write_ops(tmp_path / "ops.json"), "MODEL_STORE_URI": f"file://{store}", "MODEL_SIGNING_KEY": KEY,
        "DB_URL": "jdbc:mysql://127.0.0.1:3306/jra", "DB_USER": "mltrain", "DB_PASSWORD": "pw", "_tmp": str(tmp_path),
    }  # fmt: skip


def _argv(env, gate=True):
    t = env["_tmp"]
    a = ["train", "--tenant-id", "1", "--dataset-id", "ds-synth-1", "--config", f"{t}/train.json"]
    return [*a, "--gate", f"{t}/gate.json"] if gate else a


def _run(argv, env, monkeypatch, registry=None):
    out = io.StringIO()
    logsetup.setup(stream=out)
    monkeypatch.setattr(cli, "setup", lambda: logging.getLogger("ml_train"))
    if registry is not None:
        monkeypatch.setattr(cli, "MySqlRegistry", lambda *a, **k: registry)
    code = cli.main(argv, {k: v for k, v in env.items() if not k.startswith("_")})
    return code, [json.loads(line) for line in out.getvalue().splitlines()]


def test_train_success_exit_0_and_job_result(env, monkeypatch):
    r = reg.InMemoryRegistry()
    code, logs = _run(_argv(env), env, monkeypatch, r)
    assert code == 0
    res = logs[-1]
    assert res["event"] == "JOB_RESULT" and res["reason"] == "CANDIDATE_REGISTERED" and res["status"] == "CANDIDATE"
    assert len(r.rows) == 1 and all(row.status == "CANDIDATE" for row in r.rows.values())
    assert KEY not in json.dumps(logs) and "pw" not in json.dumps(logs)


def test_missing_gate_means_rejected_exit_0(env, monkeypatch):
    r = reg.InMemoryRegistry()
    code, logs = _run(_argv(env, gate=False), env, monkeypatch, r)
    assert code == 0 and logs[-1]["reason"] == "MODEL_REJECTED" and [x.status for x in r.rows.values()] == ["REJECTED"]


@pytest.mark.parametrize(
    ("drop", "reason"),
    [
        ("MODEL_SIGNING_KEY", "MODEL_SIGNING_KEY_MISSING"),
        ("MODEL_STORE_URI", "MODEL_STORE_URI_MISSING"),
        ("DB_URL", "DB_ENV_MISSING"),
        ("OPS_MODE_SOURCE", "OPS_MODE_SOURCE_MISSING"),
        ("APP_ENV", "APP_ENV_INVALID"),
    ],
)
def test_missing_env_is_usage_error_exit_2_before_touching_db(env, monkeypatch, drop, reason):
    e = {k: v for k, v in env.items() if k != drop}
    called = []
    monkeypatch.setattr(cli, "MySqlRegistry", lambda *a, **k: called.append(1))
    code, logs = _run(_argv(env), e, monkeypatch)
    assert code == 2 and logs[-1]["reason"] == reason and not called


def test_args_and_files_errors_are_exit_2(env, monkeypatch):
    for argv in (["train"], ["train", "--tenant-id", "0", "--dataset-id", "a", "--config", "x"], ["train", "--tenant-id", "1", "--dataset-id", "../x", "--config", "x"],
                 ["train", "--tenant-id", "1", "--dataset-id", "a", "--config", "/nonexistent"], ["nope"]):  # fmt: skip
        code, _ = _run(argv, env, monkeypatch)
        assert code == 2, argv


def test_prod_requires_least_privilege_db_user(env, monkeypatch):
    e = {**env, "APP_ENV": "prod", "DB_USER": "migrate"}
    code, logs = _run(_argv(env), e, monkeypatch)
    assert code == 2 and logs[-1]["reason"] == "DB_USER_INVALID"


def test_signing_key_must_be_trusted_by_verify_pubkey(env, monkeypatch):
    other = base64.b64encode(mj.public_key_of(bytes(32))).decode()
    code, logs = _run(_argv(env), {**env, "MODEL_VERIFY_PUBKEY": other}, monkeypatch, reg.InMemoryRegistry())
    assert code == 2 and logs[-1]["reason"] == "SIGNING_KEY_NOT_TRUSTED"
    code, _ = _run(_argv(env), {**env, "MODEL_VERIFY_PUBKEY": other + "," + PUB}, monkeypatch, reg.InMemoryRegistry())
    assert code == 0


@pytest.mark.parametrize(
    ("mode", "code", "reason"), [("STOPPED", 0, "MODE_STOPPED"), ("STARTING", 0, "MODE_STARTING"), ("STOPPING", 0, "MODE_STOPPING")]
)
def test_non_normal_mode_exits_before_db(env, monkeypatch, tmp_path, mode, code, reason):
    e = {**env, "OPS_MODE_SOURCE": _write_ops(tmp_path / "ops2.json", mode)}
    called = []
    monkeypatch.setattr(cli, "MySqlRegistry", lambda *a, **k: called.append(1))
    got, logs = _run(_argv(env), e, monkeypatch)
    assert (got, logs[-1]["reason"]) == (code, reason) and not called


def test_unknown_mode_is_75_not_normal(env, monkeypatch, tmp_path):
    for body in (
        "not json",
        json.dumps({"schema_version": 2, "seq": 1, "mode": "NORMAL", "desired": {"mode": "NORMAL"}}),
        json.dumps({"schema_version": 1, "seq": 1, "mode": "X", "desired": {"mode": "NORMAL"}}),
    ):
        p = tmp_path / "ops3.json"
        p.write_text(body)
        called = []
        monkeypatch.setattr(cli, "MySqlRegistry", lambda *a, _c=called, **k: _c.append(1))
        got, logs = _run(_argv(env), {**env, "OPS_MODE_SOURCE": f"file://{p}"}, monkeypatch)
        assert got == 75 and logs[-1]["reason"] == "MODE_UNKNOWN" and not called
    got, logs = _run(_argv(env), {**env, "OPS_MODE_SOURCE": f"file://{tmp_path}/missing.json"}, monkeypatch)
    assert got == 75
    got, _ = _run(_argv(env), {**env, "OPS_MODE_SOURCE": "gs://b/o"}, monkeypatch)
    assert got == 2


def test_dataset_problem_exits_1_and_registers_nothing(env, monkeypatch):
    r = reg.InMemoryRegistry()
    argv = _argv(env)
    argv[argv.index("--dataset-id") + 1] = "missing"
    code, logs = _run(argv, env, monkeypatch, r)
    assert code == 1 and logs[-1]["reason"] == "DATASET_INVALID" and not r.rows


def test_db_unavailable_is_75(env, monkeypatch):
    r = reg.InMemoryRegistry()
    r.fail_find_with = reg.RegistryUnavailable()
    code, logs = _run(_argv(env), env, monkeypatch, r)
    assert code == 75 and logs[-1]["reason"] == "DB_UNAVAILABLE"


def test_unexpected_error_is_1_and_not_leaked(env, monkeypatch):
    r = reg.InMemoryRegistry()
    r.fail_find_with = RuntimeError("secret-in-message")
    code, logs = _run(_argv(env), env, monkeypatch, r)
    assert code == 1 and logs[-1]["reason"] == "UNEXPECTED_ERROR" and "secret-in-message" not in json.dumps(logs)


def test_parse_jdbc_url():
    assert reg.parse_jdbc_url("jdbc:mysql://10.0.0.5:3307/jra?useSSL=true") == ("10.0.0.5", 3307, "jra")
    assert reg.parse_jdbc_url("jdbc:mysql://db/jra") == ("db", 3306, "jra")
    for bad in ("mysql://x/y", "jdbc:postgresql://x/y", "jdbc:mysql://x/", "jdbc:mysql:///y"):
        with pytest.raises(UsageError):
            reg.parse_jdbc_url(bad)


def test_mysql_error_mapping_without_connection():
    r = object.__new__(reg.MySqlRegistry)
    assert isinstance(r._map(pymysql.err.OperationalError(2003, "x")), reg.RegistryUnavailable)
    assert isinstance(r._map(pymysql.err.OperationalError(1213, "x")), reg.RegistryUnavailable)
    assert isinstance(r._map(pymysql.err.IntegrityError(1062, "dup")), reg.DuplicateModel)
    e = r._map(pymysql.err.OperationalError(1045, "denied"))
    assert e.reason == "DB_AUTH_FAILED" and e.exit_code == 1
    assert r._map(pymysql.err.ProgrammingError(1064, "syntax")).reason == "DB_ERROR"
    assert r._map(pymysql.err.OperationalError(1142, "INSERT denied")).reason == "DB_AUTH_FAILED"
