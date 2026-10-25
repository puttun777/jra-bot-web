import base64
import json
import os
import pickle

import numpy as np
import pytest

from ml_train import cli
from ml_train import modeljson as mj
from ml_train.errors import UsageError
from ml_train.legacy_pkl import cli as legacy_cli
from ml_train.legacy_pkl import unpickle
from ml_train.legacy_pkl.convert import ConversionRefused, KnownSchemas, convert
from tests.legacy_support import legacy_pickle
from tests.synth import RES
from tools.ml import canonical

SCHEMAS = KnownSchemas.load(RES)
SEED = bytes(range(1, 33))


class WinProbabilityModel:  # 許可外のモジュール（tests.test_legacy_pkl）にある同名クラス
    pass


def _convert(data, **kw):
    return convert(
        unpickle.load_legacy_model(data),
        schemas=SCHEMAS,
        model_id="legacy-test-1",
        tenant_id=1,
        created_at="2026-10-24T00:00:00Z",
        source_sha256="a" * 64,
        **kw,
    )


@pytest.mark.parametrize(
    ("kind", "expected_kind", "dims"), [("market5", "legacy_compat", 5), ("calibration40", "legacy_compat", 40), ("alpha35", "alpha", 35)]
)
@pytest.mark.parametrize("protocol", [3, 4, 5])  # 2 以下は numpy の bytes を _codecs.encode で書く（許可しない）
def test_valid_legacy_models_convert_and_verify(kind, expected_kind, dims, protocol):
    c = _convert(legacy_pickle(kind=kind, protocol=protocol))
    m = c.model
    assert (m["kind"], len(m["weights"])) == (expected_kind, dims)
    assert m["feature_schema"]["hash"] == {5: SCHEMAS.market5, 40: SCHEMAS.calibration40, 35: SCHEMAS.alpha35}[dims]["hash"]
    assert (
        m["calibration"] == {"type": "platt", "a": 1.1, "b": -0.2}
        and m["preprocessing"] is None
        and m["market_blend"] == {"alpha_default": 0.25}
    )
    raw = canonical.dumps(m)
    pub = mj.public_key_of(SEED)
    mj.verify_model_bytes(
        raw, mj.sign(SEED, raw), [pub], tenant_id=1, model_id="legacy-test-1"
    )  # Kotlin の ModelLoader と同じ契約で読める形


def test_weights_are_not_changed_by_conversion():
    data = legacy_pickle(kind="alpha35")
    shell = unpickle.load_legacy_model(data)
    c = _convert(data)
    assert c.model["weights"] == [float(x) for x in shell.weights] and c.model["bias"] == -1.25


def test_no_calibration_is_null_and_half_calibration_is_refused():
    assert _convert(legacy_pickle(kind="market5", calib_a=None, calib_b=None)).model["calibration"] is None
    with pytest.raises(ConversionRefused) as e:
        _convert(legacy_pickle(kind="market5", calib_a=None))
    assert e.value.reason == "INVALID_CALIBRATION"


def test_dimension_mismatch_is_refused_not_padded():
    for kind, dim in (("calibration40", 35), ("calibration40", 41), ("alpha35", 40), ("market5", 4)):
        with pytest.raises(ConversionRefused) as e:
            _convert(legacy_pickle(kind=kind, dim=dim))
        assert e.value.reason == "DIMENSION_MISMATCH"


@pytest.mark.parametrize(
    ("override", "reason"),
    [
        ({"feature_names": ["a", "b"]}, "UNKNOWN_FEATURE_SCHEMA"),
        ({"feature_names": "odds"}, "INVALID_FEATURE_NAMES"),
        ({"feature_names": []}, "INVALID_FEATURE_NAMES"),
        ({"feature_names": ["odds", "odds", "x", "y", "z"]}, "INVALID_FEATURE_NAMES"),
        ({"feature_names": ["odds", 1, "x", "y", "z"]}, "INVALID_FEATURE_NAMES"),
        ({"feature_names": [""] * 5}, "INVALID_FEATURE_NAMES"),
        ({"bias": float("nan")}, "INVALID_NUMBER"),
        ({"bias": float("inf")}, "INVALID_NUMBER"),
        ({"bias": "x"}, "INVALID_NUMBER"),
        ({"calib_a": float("nan")}, "INVALID_NUMBER"),
        ({"market_blend_alpha": 1.5}, "INVALID_NUMBER"),
        ({"weights": np.array([1.0, np.nan, 0.0, 0.0, 0.0])}, "INVALID_NUMBER"),
        ({"weights": np.array([1, 2, 3, 4, 5])}, "INVALID_WEIGHTS"),  # int 配列
        ({"weights": [1.0] * 5}, "INVALID_WEIGHTS"),  # list
        ({"weights": np.zeros((5, 1))}, "INVALID_WEIGHTS"),
        ({"weights": None}, "INVALID_WEIGHTS"),
        ({"extra_attr": 1}, "UNKNOWN_ATTRIBUTES"),
    ],
)
def test_invalid_contents_are_refused(override, reason):
    with pytest.raises(ConversionRefused) as e:
        _convert(legacy_pickle(kind="market5", **override))
    assert e.value.reason == reason


def test_missing_attribute_is_refused():
    shell = unpickle.load_legacy_model(legacy_pickle(kind="market5"))
    del shell.__dict__["weights"]
    with pytest.raises(ConversionRefused) as e:
        convert(shell, schemas=SCHEMAS, model_id="m", tenant_id=1, created_at="2026-10-24T00:00:00Z", source_sha256="a" * 64)
    assert e.value.reason == "MISSING_ATTRIBUTES"


def test_unknown_class_is_refused_and_nothing_runs(tmp_path):
    marker = tmp_path / "pwned"

    class Evil:
        def __reduce__(self):
            return (os.system, (f"touch {marker}",))

    with pytest.raises(unpickle.UnpickleRefused) as e:
        unpickle.load_legacy_model(pickle.dumps(Evil()))
    assert e.value.reason == "UNKNOWN_CLASS" and not marker.exists()
    for raw in (b"cos\nsystem\n(S'touch /tmp/x'\ntR.", b"cbuiltins\neval\n(S'1+1'\ntR.", b"csubprocess\nPopen\n(S'id'\ntR."):
        with pytest.raises(unpickle.UnpickleRefused):
            unpickle.load_legacy_model(raw)


def test_other_module_with_same_class_name_and_plain_objects_are_unknown():
    with pytest.raises(unpickle.UnpickleRefused) as e:
        unpickle.load_legacy_model(pickle.dumps(WinProbabilityModel()))
    assert e.value.reason == "UNKNOWN_CLASS"
    with pytest.raises(unpickle.UnpickleRefused) as e:
        unpickle.load_legacy_model(pickle.dumps({"weights": [1.0]}))
    assert e.value.reason == "NOT_A_MODEL"


def test_malformed_oversized_and_forbidden_opcodes():
    good = legacy_pickle(kind="market5")
    for data, reason in (
        (b"", "SIZE"),
        (good[: len(good) // 2], "MALFORMED"),
        (b"\x00" * 100, "MALFORMED"),
        (b"x" * (unpickle.MAX_BYTES + 1), "SIZE"),
    ):
        with pytest.raises(unpickle.UnpickleRefused) as e:
            unpickle.load_legacy_model(data)
        assert e.value.reason == reason
    with pytest.raises(unpickle.UnpickleRefused) as e:
        unpickle.load_legacy_model(b"\x80\x04\x95\x00\x00\x00\x00\x00\x00\x00\x00N\x82\x01.")  # EXT1
    assert e.value.reason in ("FORBIDDEN_OPCODE", "MALFORMED")


def test_object_dtype_arrays_are_refused():
    with pytest.raises(ConversionRefused):
        _convert(legacy_pickle(kind="market5", weights=np.array([1.0, 2.0, 3.0, 4.0, 5.0], dtype=object)))


def _cli_env(tmp_path, **over):
    e = {"MODEL_SIGNING_KEY": base64.b64encode(SEED).decode(), "APP_ENV": "local"}
    e.update(over)
    return e


def _args(tmp_path, pkl, *extra):
    return [
        "convert-pkl",
        "--pkl",
        str(pkl),
        "--out-dir",
        str(tmp_path / "out"),
        "--tenant-id",
        "1",
        "--model-id",
        "legacy-cli-1",
        "--schema-dir",
        str(RES),
        *extra,
    ]


def test_cli_requires_confirmation_and_refuses_cloud_env(tmp_path):
    pkl = tmp_path / "old.pkl"
    pkl.write_bytes(legacy_pickle(kind="alpha35"))
    assert cli.main(_args(tmp_path, pkl), _cli_env(tmp_path)) == 2  # 確認フラグなし
    for over in ({"APP_ENV": "prod"}, {"OPS_MODE_SOURCE": "file:///x"}, {"K_SERVICE": "x"}, {"CLOUD_RUN_JOB": "x"}):
        assert cli.main(_args(tmp_path, pkl, "--confirm-own-local-file"), _cli_env(tmp_path, **over)) == 2
    assert not (tmp_path / "out").exists()


def test_cli_converts_writes_signed_files_and_never_overwrites(tmp_path):
    pkl = tmp_path / "old.pkl"
    pkl.write_bytes(legacy_pickle(kind="alpha35"))
    assert cli.main(_args(tmp_path, pkl, "--confirm-own-local-file", "--created-at", "2026-10-24T00:00:00Z"), _cli_env(tmp_path)) == 0
    out = tmp_path / "out"
    assert sorted(p.name for p in out.iterdir()) == ["legacy-cli-1.conversion.json", "legacy-cli-1.json", "legacy-cli-1.sig"]
    raw, sig = (out / "legacy-cli-1.json").read_bytes(), (out / "legacy-cli-1.sig").read_bytes()
    mj.verify_model_bytes(raw, sig, [mj.public_key_of(SEED)], tenant_id=1, model_id="legacy-cli-1", expected_kind="alpha")
    assert json.loads((out / "legacy-cli-1.conversion.json").read_text())["dims"] == 35
    assert cli.main(_args(tmp_path, pkl, "--confirm-own-local-file"), _cli_env(tmp_path)) == 2  # 上書きしない
    bad = tmp_path / "evil.pkl"
    bad.write_bytes(b"cos\nsystem\n(S'true'\ntR.")
    args = _args(tmp_path, bad, "--confirm-own-local-file")
    args[args.index("--out-dir") + 1] = str(tmp_path / "out2")
    assert cli.main(args, _cli_env(tmp_path)) == 1
    assert not (tmp_path / "out2").exists()


def test_cli_signing_key_missing_is_usage_error(tmp_path):
    pkl = tmp_path / "old.pkl"
    pkl.write_bytes(legacy_pickle(kind="alpha35"))
    with pytest.raises(UsageError):
        legacy_cli.run(
            type(
                "A",
                (),
                {
                    "confirm_own_local_file": True,
                    "tenant_id": 1,
                    "model_id": "m",
                    "pkl": str(pkl),
                    "out_dir": str(tmp_path / "o"),
                    "schema_dir": str(RES),
                    "created_at": None,
                },
            )(),
            {},
        )
