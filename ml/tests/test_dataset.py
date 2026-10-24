import gzip
import json

import numpy as np
import pytest

from ml_train import dataset as ds_mod
from ml_train.errors import DatasetInsufficientError, DatasetInvalidError
from tests.synth import make_dataset
from tools.ml import canonical


def _load(gz, mf, **kw):
    return ds_mod.load(gz, mf, tenant_id=kw.get("tenant_id", 1), dataset_id=kw.get("dataset_id", "ds-synth-1"))


def _rewrite(gz, mf, csv_edit=None, manifest_edit=None):
    import hashlib

    text = gzip.decompress(gz)
    if csv_edit:
        text = csv_edit(text.decode()).encode()
    m = json.loads(mf)
    m["csv"]["sha256"] = hashlib.sha256(text).hexdigest()
    m["csv"]["bytes"] = len(text)
    if manifest_edit:
        manifest_edit(m)
    m.pop("sha256")
    m["sha256"] = canonical.sha256_hex(m)
    return gzip.compress(text, mtime=0), canonical.dumps(m) + b"\n"


@pytest.mark.parametrize(("kind", "n"), [("calibration", 40), ("alpha", 35)])
def test_load_ok(kind, n):
    ds = _load(*make_dataset(kind=kind))
    assert ds.kind == kind
    assert ds.X.shape == (1152, n)
    assert ds.is_train.sum() == 864
    assert ds.has_odds == (kind == "calibration")
    ds_mod.require_trainable(ds)


def test_rows_stay_in_kotlin_order_and_split_is_not_redone():
    ds = _load(*make_dataset())
    keys = list(zip(ds.race_date, ds.race_id, ds.umaban, strict=True))
    assert keys == sorted(keys)
    train_dates = {d for d, t in zip(ds.race_date, ds.is_train, strict=True) if t}
    valid_dates = {d for d, t in zip(ds.race_date, ds.is_train, strict=True) if not t}
    assert max(train_dates) < min(valid_dates)


def test_tamper_is_rejected():
    gz, mf = make_dataset()
    with pytest.raises(DatasetInvalidError):
        _load(gzip.compress(gzip.decompress(gz).replace(b",train", b",valid", 1)), mf)
    bad = json.loads(mf)
    bad["rows"] += 1
    with pytest.raises(DatasetInvalidError):
        _load(gz, canonical.dumps(bad) + b"\n")
    with pytest.raises(DatasetInvalidError):
        _load(b"not gzip", mf)


def test_tenant_and_id_mismatch_rejected():
    gz, mf = make_dataset(tenant_id=1)
    with pytest.raises(DatasetInvalidError, match="tenant_id"):
        _load(gz, mf, tenant_id=2)
    with pytest.raises(DatasetInvalidError, match="dataset_id"):
        _load(gz, mf, dataset_id="other")


def test_content_errors_with_consistent_hashes():
    gz, mf = make_dataset()
    cases = {
        "label": lambda t: t.replace(",0,train", ",2,train", 1),
        "nan": lambda t: t.replace(",1.0,", ",nan,", 1),
        "future_as_of": lambda t: t.replace("T05:50:00Z", "T20:50:00Z", 1),
        "tenant_row": lambda t: t.replace("\n1,", "\n2,", 1),
    }
    for name, edit in cases.items():
        g, m = _rewrite(gz, mf, csv_edit=edit)
        with pytest.raises(DatasetInvalidError):
            _load(g, m)
        assert name
    g, m = _rewrite(gz, mf, manifest_edit=lambda m: m["builder"].update(legacy_compat_used=True))
    with pytest.raises(DatasetInvalidError, match="旧互換"):
        _load(g, m)


def test_duplicate_samples_rejected():
    gz, mf = make_dataset()

    def dup(t):
        lines = t.split("\n")
        lines.insert(3, lines[2])
        return "\n".join(lines)

    g, m = _rewrite(gz, mf, csv_edit=dup, manifest_edit=lambda m: m.update(rows=m["rows"] + 1))
    with pytest.raises(DatasetInvalidError):
        _load(g, m)


def test_gzip_bomb_is_bounded():
    gz, mf = make_dataset()
    m = json.loads(mf)
    big = gzip.compress(b"a" * 10_000_000, mtime=0)  # 宣言（manifest.csv.bytes）よりずっと大きく展開される
    with pytest.raises(DatasetInvalidError, match="展開"):
        _load(big, canonical.dumps(m) + b"\n")


def test_unsupported_schema_dimension():
    with pytest.raises(DatasetInvalidError, match="未対応"):
        ds_mod.kind_for_schema(["a", "b"])


def test_single_class_and_too_few_days_are_rejected():
    ds = _load(*make_dataset(one_class=True))
    with pytest.raises(DatasetInsufficientError, match="片方のクラス"):
        ds_mod.require_trainable(ds)
    ds = _load(*make_dataset(n_days=2, valid_fraction=0.5))
    with pytest.raises(DatasetInsufficientError, match="2 日未満"):
        ds_mod.require_trainable(ds)


def test_no_valid_rows():
    ds = _load(*make_dataset(n_days=1))
    assert np.all(ds.is_train)
    with pytest.raises(DatasetInsufficientError):
        ds_mod.require_trainable(ds)
