import pytest

from ml_train.errors import UsageError
from ml_train.storage import ArtifactConflict, ArtifactStore, LocalBlobStore, StoreError, Written, open_store


def test_put_if_absent_semantics(tmp_path):
    s = LocalBlobStore(tmp_path)
    assert s.put_if_absent("1/models/a.json", b"x") == "CREATED"
    assert s.put_if_absent("1/models/a.json", b"x") == "UNCHANGED"
    assert s.put_if_absent("1/models/a.json", b"y") == "CONFLICT"
    assert s.get("1/models/a.json") == b"x" and s.get("1/models/none") is None
    assert [p.name for p in (tmp_path / "1" / "models").iterdir()] == ["a.json"]  # 一時ファイルが残らない


@pytest.mark.parametrize("key", ["../x", "1/../../etc/passwd", "1//a", "/abs", "1/models/a b", "", "1/.."])
def test_path_traversal_is_rejected(tmp_path, key):
    with pytest.raises(StoreError):
        LocalBlobStore(tmp_path).get(key)


def test_open_store_uri_rules(tmp_path):
    store, prefix = open_store(f"file://{tmp_path}")
    assert prefix == f"file://{tmp_path}"
    for bad, reason in (
        (None, "MODEL_STORE_URI_MISSING"),
        ("gs://b/p", "MODEL_STORE_URI_UNSUPPORTED"),
        ("/abs", "MODEL_STORE_URI_INVALID"),
        ("file://host/x", "MODEL_STORE_URI_INVALID"),
        (f"file://{tmp_path}/nope", "MODEL_STORE_URI_INVALID"),
    ):
        with pytest.raises(UsageError) as e:
            open_store(bad)
        assert e.value.reason == reason
    assert store


def test_write_model_order_conflict_and_cleanup(tmp_path):
    a = ArtifactStore(LocalBlobStore(tmp_path), "file://x")
    w = Written()
    a.write_model(1, "m1", b"J", b"S", b"R", w)
    assert sorted(w.created) == ["1/models/m1.json", "1/models/m1.sig", "1/reports/m1.report.json"]
    assert a.read_model(1, "m1") == (b"J", b"S")
    w2 = Written()
    a.write_model(1, "m1", b"J", b"S", b"R", w2)  # 同一内容の再書込みは何も作らない
    assert w2.created == []
    with pytest.raises(ArtifactConflict):
        a.write_model(1, "m1", b"J2", b"S", b"R", Written())
    assert a.cleanup(w) == [] and a.read_model(1, "m1") is None


def test_json_without_signature_is_an_error_not_missing(tmp_path):
    b = LocalBlobStore(tmp_path)
    b.put_if_absent("1/models/m.json", b"J")
    with pytest.raises(StoreError):
        ArtifactStore(b, "file://x").read_model(1, "m")


def test_dataset_read_requires_manifest_as_commit_marker(tmp_path):
    b = LocalBlobStore(tmp_path)
    a = ArtifactStore(b, "file://x")
    b.put_if_absent("1/datasets/d.csv.gz", b"gz")
    assert a.read_dataset(1, "d") is None  # CSV だけの途中状態は『まだ無い』
    b.put_if_absent("1/datasets/d.manifest.json", b"mf")
    assert a.read_dataset(1, "d") == (b"mf", b"gz")
    assert a.read_dataset(2, "d") is None  # テナントが違えば別
