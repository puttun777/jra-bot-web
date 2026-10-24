import base64
import json
import pathlib

import pytest

from ml_train import modeljson as mj
from ml_train.errors import UsageError
from tests.synth import RES, schema
from tools.ml import canonical

SEED = bytes(range(32))
PUB = mj.public_key_of(SEED)


def _model(feature_kind="calibration", **over):
    sch = schema(feature_kind)
    n = len(sch["names"])
    kw = dict(
        model_id="m-test", tenant_id=1, kind=feature_kind, feature_schema=sch, weights=[0.01 * i for i in range(n)], bias=-1.5,
        mean=[0.0] * n, std=[1.0] * n, platt=(1.1, -0.2), market_blend_alpha_default=0.25,
        training={"n_train": 10, "libs": {"x": "1"}}, metrics={"logloss": 0.3, "auc": None, "n": 5}, created_at="2026-10-24T00:00:00Z", producer="ml-train/test",
    )  # fmt: skip
    kw.update(over)
    return mj.build_model(**kw)


def _signed(m):
    raw = canonical.dumps(m)
    return raw, mj.sign(SEED, raw)


def test_build_and_verify_roundtrip():
    m = _model()
    raw, sig = _signed(m)
    v = mj.verify_model_bytes(
        raw,
        sig,
        [PUB],
        tenant_id=1,
        model_id="m-test",
        expected_kind="calibration",
        expected_schema_hash=m["feature_schema"]["hash"],
        expected_sha256=m["sha256"],
    )
    assert v.sha256 == m["sha256"] and len(v.signature_sha256) == 64
    assert all(isinstance(x, float) for x in m["weights"])  # 整数で書かない（正準形が変わる）
    assert raw == canonical.dumps(json.loads(raw))


def test_alpha_35_ok_and_kind_dimension_mismatch_rejected():
    m = _model("alpha")
    raw, sig = _signed(m)
    mj.verify_model_bytes(raw, sig, [PUB], tenant_id=1, model_id="m-test", expected_kind="alpha")
    bad = _model("calibration", kind="alpha")  # 40 次元のスキーマなのに kind=alpha
    raw, sig = _signed(bad)
    with pytest.raises(mj.ModelVerificationError) as e:
        mj.verify_model_bytes(raw, sig, [PUB], tenant_id=1, model_id="m-test")
    assert e.value.reason == "FEATURE_SCHEMA_MISMATCH"


@pytest.mark.parametrize(
    ("mutate", "fix_sha", "reason"),
    [
        (lambda m: m.update(sha256="f" * 64), False, "MODEL_HASH_INVALID"),
        (lambda m: m.update(weights=m["weights"][:-1]), True, "INVALID_MODEL_DIMENSION"),
        (lambda m: m.update(tenant_id=2), True, "MODEL_INVALID"),
        (lambda m: m.update(model_schema_version=2), True, "MODEL_SCHEMA_MISMATCH"),
        (lambda m: m.update(extra="x"), True, "MODEL_INVALID"),
        (lambda m: m["feature_schema"].update(hash="sha256:" + "0" * 64), True, "FEATURE_SCHEMA_MISMATCH"),
    ],
)
def test_content_errors_are_rejected_with_kotlin_reason_codes(mutate, fix_sha, reason):
    m = _model()
    mutate(m)
    if fix_sha:
        m["sha256"] = canonical.model_sha256(m)
    raw, sig = _signed(m)
    with pytest.raises(mj.ModelVerificationError) as e:
        mj.verify_model_bytes(raw, sig, [PUB], tenant_id=1, model_id="m-test")
    assert e.value.reason == reason


def test_signature_failures():
    m = _model()
    raw, sig = _signed(m)
    other = mj.public_key_of(bytes(reversed(range(32))))
    for s, keys in ((sig, [other]), (bytes(64), [PUB]), (sig[:-1], [PUB]), (b"", [PUB]), (sig, [])):
        with pytest.raises(mj.ModelVerificationError) as e:
            mj.verify_model_bytes(raw, s, keys, tenant_id=1, model_id="m-test")
        assert e.value.reason == "MODEL_SIGNATURE_INVALID"
    flipped = bytes([sig[0] ^ 1]) + sig[1:]
    with pytest.raises(mj.ModelVerificationError):
        mj.verify_model_bytes(raw, flipped, [PUB], tenant_id=1, model_id="m-test")
    with pytest.raises(mj.ModelVerificationError):  # 内容 1 バイト改ざん
        mj.verify_model_bytes(raw.replace(b"m-test", b"m-tesu", 1), sig, [PUB], tenant_id=1, model_id="m-test")
    mj.verify_model_bytes(raw, sig, [other, PUB], tenant_id=1, model_id="m-test")  # 鍵のローテーション（複数）


def test_non_finite_numbers_cannot_be_written():
    for bad in (float("nan"), float("inf")):
        with pytest.raises(mj.ModelVerificationError):
            _model(bias=bad)
        with pytest.raises(mj.ModelVerificationError):
            _model(metrics={"logloss": bad})
    assert mj.floats([-0.0])[0] == 0.0 and str(mj.floats([-0.0])[0]) == "0.0"


def test_signing_key_parsing_never_echoes_the_key():
    secret = base64.b64encode(SEED).decode()
    assert mj.load_signing_seed(secret) == SEED
    for bad in (None, "", "!!!", base64.b64encode(b"short").decode()):
        with pytest.raises(UsageError) as e:
            mj.load_signing_seed(bad)
        assert (secret not in str(e.value) and (bad or "x") not in str(e.value)) or not bad
    assert len(mj.parse_pubkeys(base64.b64encode(PUB).decode() + "," + base64.b64encode(PUB).decode())) == 2
    with pytest.raises(UsageError):
        mj.parse_pubkeys("AAAA")


def test_python_output_matches_the_committed_kotlin_visible_fixture_format():
    """Kotlin が読む既存の署名つき成果物（tools/ml が生成）と同じ検証が通る = 同じ契約を Python が守っている。"""
    exp = json.loads((RES / "expected.json").read_text())
    pub = base64.b64decode(exp["public_key_primary"])
    raw = pathlib.Path(RES / "models/cal_test_001.json").read_bytes()
    sig = pathlib.Path(RES / "models/cal_test_001.sig").read_bytes()
    v = mj.verify_model_bytes(raw, sig, [pub], tenant_id=1, model_id="cal_test_001", expected_kind="calibration")
    assert v.model["feature_schema"]["hash"] == exp["feature_schemas"]["calibration40"]
