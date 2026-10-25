import base64
import json
from datetime import UTC, datetime
from typing import Any

import pytest

from ml_train import modeljson as mj
from ml_train.config import GateConfig, TrainConfig
from ml_train.errors import DatasetInsufficientError, DatasetInvalidError, MlTrainError, Terminated, UsageError
from ml_train.job import JobDeps, derive_model_id, run_train
from ml_train.registry import DuplicateModel, InMemoryRegistry, NewModel, RegistryRow, RegistryUnavailable
from ml_train.storage import ArtifactStore, LocalBlobStore
from tests.synth import make_dataset
from tools.ml import canonical

SEED = bytes(range(10, 42))
PUB = mj.public_key_of(SEED)
NOW = datetime(2026, 10, 24, 1, 2, 3, tzinfo=UTC)
GOOD_GATE = GateConfig("synthetic_test", {"min_valid_rows": 50, "min_valid_positives": 5, "max_valid_logloss": 2.0})
CFG = TrainConfig.from_json({"kind": "calibration", "seed": 42, "l2_c": 1.0, "recency_halflife_days": 90})


class Env:
    def __init__(self, tmp_path, *, kind="calibration", **ds_kw):
        self.blob = LocalBlobStore(tmp_path)
        self.store = ArtifactStore(self.blob, f"file://{tmp_path}")
        self.registry = InMemoryRegistry()
        gz, mf = make_dataset(kind=kind, **ds_kw)
        self.put_dataset(gz, mf)
        self.terminate = False

    def put_dataset(self, gz, mf, tenant=1, ds="ds-synth-1"):
        self.blob.put_if_absent(f"{tenant}/datasets/{ds}.csv.gz", gz)
        self.blob.put_if_absent(f"{tenant}/datasets/{ds}.manifest.json", mf)

    def deps(self, **kw):
        d = dict(
            store=self.store,
            registry=self.registry,
            signing_seed=SEED,
            verify_pubkeys=[PUB],
            clock=lambda: NOW,
            git_sha="abc",
            should_terminate=lambda: self.terminate,
        )
        d.update(kw)
        return JobDeps(**d)

    def files(self):
        return sorted(str(p.relative_to(self.blob.root)) for p in self.blob.root.rglob("*") if p.is_file() and "/datasets/" not in str(p))


def _read(e, model_id):
    got = e.store.read_model(1, model_id)
    assert got is not None
    return got[0]


def test_pass_registers_candidate_with_verifiable_signed_model(tmp_path):
    e = Env(tmp_path)
    out = run_train(1, "ds-synth-1", CFG, GOOD_GATE, e.deps())
    assert (out.reason, out.status) == ("CANDIDATE_REGISTERED", "CANDIDATE")
    row = e.registry.rows[(1, out.model_id)]
    assert row.status == "CANDIDATE" and row.kind == "calibration"
    raw, sig = e.store.read_model(1, out.model_id)
    v = mj.verify_model_bytes(
        raw, sig, [PUB], tenant_id=1, model_id=out.model_id, expected_sha256=row.sha256, expected_schema_hash=row.feature_schema_hash
    )
    m = v.model
    assert m["training"]["dataset_sha256"] == json.loads(e.blob.get("1/datasets/ds-synth-1.manifest.json"))["csv"]["sha256"]
    assert m["training"]["class_weight"] == "none" and m["preprocessing"]["type"] == "standardize" and m["calibration"]["type"] == "platt"
    assert len(m["weights"]) == 40 and m["metrics"]["gate_verdict"] == "PASS"
    assert row.artifact_uri.endswith(f"/1/models/{out.model_id}.json")
    report = json.loads(e.blob.get(f"1/reports/{out.model_id}.report.json"))
    assert (
        report["status"] == "CANDIDATE"
        and report["gate"]["verdict"] == "PASS"
        and report["metrics_eval"]["market_baseline_available"] is True
    )


def test_model_learns_the_synthetic_signal(tmp_path):
    e = Env(tmp_path, signal=1.5)
    out = run_train(1, "ds-synth-1", CFG, GOOD_GATE, e.deps())
    m = json.loads(e.blob.get(f"1/reports/{out.model_id}.report.json"))["metrics_eval"]
    assert m["auc"] > 0.6 and m["logloss"] < 0.45


def test_gate_reject_is_registered_as_rejected_with_artifacts_kept(tmp_path):
    e = Env(tmp_path)
    strict = GateConfig("synthetic_test", {"max_valid_logloss": 0.01})
    out = run_train(1, "ds-synth-1", CFG, strict, e.deps())
    assert (out.reason, out.status, out.gate_verdict) == ("MODEL_REJECTED", "REJECTED", "FAIL")
    assert e.registry.rows[(1, out.model_id)].status == "REJECTED"
    assert e.store.read_model(1, out.model_id) is not None
    assert "max_valid_logloss:FAIL" in json.loads(e.blob.get(f"1/reports/{out.model_id}.report.json"))["gate"]["reasons"]


def test_missing_gate_is_fail_closed(tmp_path):
    e = Env(tmp_path)
    out = run_train(1, "ds-synth-1", CFG, GateConfig.unconfigured(), e.deps())
    assert out.status == "REJECTED" and out.gate_verdict == "NOT_CONFIGURED"
    assert e.registry.rows[(1, out.model_id)].metrics["gate_reasons"] == ["QUALITY_GATE_NOT_CONFIGURED"]


def test_alpha_has_no_market_baseline_and_a_baseline_check_rejects(tmp_path):
    e = Env(tmp_path, kind="alpha")
    cfg = TrainConfig.from_json({"kind": "alpha"})
    out = run_train(1, "ds-synth-1", cfg, GateConfig("synthetic_test", {"max_logloss_minus_market": 0.0}), e.deps())
    assert out.status == "REJECTED"
    rep = json.loads(e.blob.get(f"1/reports/{out.model_id}.report.json"))
    assert rep["metrics_eval"]["baseline_market_logloss"] is None and "baseline_market_logloss" in rep["unavailable_metrics"]
    assert rep["gate"]["checks"][0]["status"] == "UNAVAILABLE"
    ok = run_train(1, "ds-synth-1", cfg, GOOD_GATE, e.deps())  # 市場ベースラインを要求しないゲートなら通る
    assert ok.status == "CANDIDATE" and len(json.loads(_read(e, ok.model_id))["weights"]) == 35


def test_rerun_is_idempotent_and_does_not_duplicate(tmp_path):
    e = Env(tmp_path)
    first = run_train(1, "ds-synth-1", CFG, GOOD_GATE, e.deps())
    files = e.files()
    again = run_train(1, "ds-synth-1", CFG, GOOD_GATE, e.deps(clock=lambda: datetime(2026, 11, 1, tzinfo=UTC)))
    assert (again.reason, again.model_id) == ("ALREADY_REGISTERED", first.model_id)
    assert len(e.registry.rows) == 1 and e.files() == files
    other = run_train(1, "ds-synth-1", TrainConfig.from_json({"kind": "calibration", "seed": 1}), GOOD_GATE, e.deps())
    assert other.model_id != first.model_id and len(e.registry.rows) == 2


def test_registered_row_with_tampered_artifact_is_an_error(tmp_path):
    e = Env(tmp_path)
    first = run_train(1, "ds-synth-1", CFG, GOOD_GATE, e.deps())
    (tmp_path / "1" / "models" / f"{first.model_id}.json").write_bytes(b"{}")
    with pytest.raises(MlTrainError):
        run_train(1, "ds-synth-1", CFG, GOOD_GATE, e.deps())


def test_invalid_dataset_never_trains_stores_or_registers(tmp_path):
    e = Env(tmp_path)
    gz, mf = make_dataset(dataset_id="ds-bad")
    bad = json.loads(mf)
    bad["rows"] += 1
    e.put_dataset(gz, canonical.dumps(bad) + b"\n", ds="ds-bad")
    before = e.files()
    with pytest.raises(DatasetInvalidError):
        run_train(1, "ds-bad", CFG, GOOD_GATE, e.deps())
    with pytest.raises(DatasetInvalidError):
        run_train(1, "nope", CFG, GOOD_GATE, e.deps())  # 無い
    assert e.files() == before and not e.registry.rows


def test_insufficient_datasets_are_rejected_explicitly(tmp_path):
    e = Env(tmp_path, one_class=True)
    with pytest.raises(DatasetInsufficientError):
        run_train(1, "ds-synth-1", CFG, GOOD_GATE, e.deps())
    assert not e.registry.rows and e.files() == []


def test_tenant_boundary(tmp_path):
    e = Env(tmp_path, tenant_id=1)
    with pytest.raises(DatasetInvalidError):  # テナント 2 としてテナント 1 のデータを読もうとしても、パスが別なので見つからない
        run_train(2, "ds-synth-1", CFG, GOOD_GATE, e.deps())
    gz, mf = make_dataset(tenant_id=1)
    e.put_dataset(gz, mf, tenant=2, ds="ds-synth-1")  # 他テナントのデータを自分の置き場に置かれても manifest の tenant_id で拒否
    with pytest.raises(DatasetInvalidError, match="tenant_id"):
        run_train(2, "ds-synth-1", CFG, GOOD_GATE, e.deps())
    assert not e.registry.rows


def test_kind_mismatch_is_usage_error(tmp_path):
    e = Env(tmp_path)
    with pytest.raises(UsageError):
        run_train(1, "ds-synth-1", TrainConfig.from_json({"kind": "alpha"}), GOOD_GATE, e.deps())


def test_registry_failure_cleans_up_artifacts_and_keeps_active(tmp_path):
    e = Env(tmp_path)
    active = RegistryRow(1, "active-1", "calibration", 1, "sha256:" + "a" * 64, "u", "b" * 64, "c" * 64, "ACTIVE", {"logloss": 0.3})
    e.registry.actives[(1, "calibration")] = active
    e.registry.rows[(1, "active-1")] = active
    for exc, exit_code in ((RegistryUnavailable(), 75), (MlTrainError("DB_ERROR"), 1), (RuntimeError("boom"), 1)):
        e.registry.fail_insert_with = exc
        with pytest.raises(MlTrainError) as err:
            run_train(1, "ds-synth-1", CFG, GOOD_GATE, e.deps())
        assert err.value.exit_code == exit_code
        assert e.files() == []  # この実行が作った成果物は消える
        assert e.registry.rows == {(1, "active-1"): active} and e.registry.actives[(1, "calibration")] is active


def test_duplicate_insert_race_is_reported(tmp_path):
    e = Env(tmp_path)
    e.registry.fail_insert_with = DuplicateModel()
    with pytest.raises(MlTrainError) as err:
        run_train(1, "ds-synth-1", CFG, GOOD_GATE, e.deps())
    assert err.value.reason == "MODEL_ALREADY_REGISTERED" and e.files() == []


def test_storage_failure_registers_nothing(tmp_path, monkeypatch):
    from ml_train.storage import StoreError

    e = Env(tmp_path)
    real = e.blob.put_if_absent

    def flaky(key, data):
        if key.endswith(".json") and "/models/" in key:  # 署名とレポートは書けたが、確定の印（JSON）の書込みで失敗
            raise StoreError("simulated")
        return real(key, data)

    monkeypatch.setattr(e.blob, "put_if_absent", flaky)
    with pytest.raises(MlTrainError) as err:
        run_train(1, "ds-synth-1", CFG, GOOD_GATE, e.deps())
    assert err.value.exit_code == 75 and not e.registry.rows
    assert e.files() == []  # 先に置いた署名・レポートも片付く


def test_signature_failure_blocks_registration(tmp_path):
    e = Env(tmp_path)
    wrong = mj.public_key_of(bytes(32))
    with pytest.raises(MlTrainError) as err:
        run_train(1, "ds-synth-1", CFG, GOOD_GATE, e.deps(verify_pubkeys=[wrong]))  # 自分の署名を検証できない鍵設定
    assert err.value.reason == "MODEL_SIGNATURE_INVALID" and not e.registry.rows and e.files() == []


def test_tampered_store_after_write_blocks_registration(tmp_path, monkeypatch):
    e = Env(tmp_path)
    real = e.store.write_model

    def corrupt(tenant_id, model_id, raw, sig, report, written):
        real(tenant_id, model_id, raw, sig, report, written)
        (tmp_path / str(tenant_id) / "models" / f"{model_id}.json").write_bytes(raw + b" ")

    monkeypatch.setattr(e.store, "write_model", corrupt)
    with pytest.raises(MlTrainError):
        run_train(1, "ds-synth-1", CFG, GOOD_GATE, e.deps())
    assert not e.registry.rows


def test_sigterm_before_commit_aborts_cleanly(tmp_path):
    e = Env(tmp_path)
    calls = {"n": 0}

    def term():
        calls["n"] += 1
        return calls["n"] >= 5  # 学習後〜保存後のどこかで SIGTERM

    with pytest.raises(Terminated) as err:
        run_train(1, "ds-synth-1", CFG, GOOD_GATE, e.deps(should_terminate=term))
    assert err.value.exit_code == 75 and not e.registry.rows and e.files() == []


def test_new_model_can_never_be_active():
    base: dict[str, Any] = dict(tenant_id=1, model_id="m", kind="calibration", schema_version=1, feature_schema_hash="sha256:" + "a" * 64, artifact_uri="u", sha256="b" * 64,
                signature_sha256="c" * 64, trained_from=None, trained_to=None, n_train=1, metrics={}, created_at=NOW)  # fmt: skip
    for status in ("ACTIVE", "RETIRED", "active", ""):
        with pytest.raises(ValueError):
            NewModel(status=status, **base)
    NewModel(status="CANDIDATE", **base)
    NewModel(status="REJECTED", **base)


def test_model_id_is_deterministic_and_depends_on_inputs():
    a = derive_model_id(1, "calibration", "x" * 64, CFG, GOOD_GATE)
    assert a == derive_model_id(1, "calibration", "x" * 64, CFG, GOOD_GATE)
    assert len({a, derive_model_id(2, "calibration", "x" * 64, CFG, GOOD_GATE), derive_model_id(1, "calibration", "y" * 64, CFG, GOOD_GATE),
                derive_model_id(1, "calibration", "x" * 64, CFG, GateConfig.unconfigured())}) == 4  # fmt: skip
    assert mj.MODEL_ID_RE.fullmatch(a)


def test_reproducible_weights_with_same_seed(tmp_path):
    w = []
    for i in range(2):
        e = Env(tmp_path / f"r{i}")
        out = run_train(1, "ds-synth-1", CFG, GOOD_GATE, e.deps())
        w.append(json.loads(_read(e, out.model_id))["weights"])
    assert w[0] == w[1]
    assert base64.b64encode(PUB)
