import gzip
import json
import pathlib
import unittest

from tools.ml import canonical, dataset_contract as dc

RES = pathlib.Path(__file__).resolve().parents[3] / "domain" / "src" / "test" / "resources" / "ml"
DS = RES / "dataset"


def _load(name):
    # 合成 CSV は非圧縮で Git に置く（*.csv.gz は実データ混入防止のため Git に入れない）。gzip はここで作る
    return gzip.compress((DS / f"{name}.csv").read_bytes(), mtime=0), (DS / f"{name}.manifest.json").read_bytes()


def _rewrite(csv_gz, manifest_raw, csv_edit=None, manifest_edit=None):
    """CSV / manifest を書き換えて、manifest の sha256・csv.sha256・bytes を整合させ直す（『中身だけが不正』を作る）。"""
    text = gzip.decompress(csv_gz)
    if csv_edit:
        text = csv_edit(text.decode("utf-8")).encode("utf-8")
    m = json.loads(manifest_raw)
    import hashlib
    m["csv"]["sha256"] = hashlib.sha256(text).hexdigest()
    m["csv"]["bytes"] = len(text)
    if manifest_edit:
        manifest_edit(m)
    m.pop("sha256", None)
    m["sha256"] = canonical.sha256_hex(m)
    return gzip.compress(text), canonical.dumps(m) + b"\n"


class DatasetContractTest(unittest.TestCase):
    def test_committed_synthetic_datasets_are_valid(self):
        for name, nfeat in (("synthetic-calibration", 40), ("synthetic-alpha", 35)):
            s = dc.verify(*_load(name))
            self.assertEqual(s["rows"], 60)
            self.assertEqual((s["n_train"], s["n_valid"]), (48, 12))
            self.assertEqual(s["features"], nfeat)
            self.assertEqual(s["positives"], 10)

    def test_feature_schema_file_must_match(self):
        csv_gz, mf = _load("synthetic-calibration")
        dc.verify(csv_gz, mf, (RES / "feature_schema.calibration40.json").read_bytes())
        with self.assertRaises(dc.ContractError):
            dc.verify(csv_gz, mf, (RES / "feature_schema.alpha35.json").read_bytes())

    def test_tampering_is_detected(self):
        csv_gz, mf = _load("synthetic-alpha")
        text = gzip.decompress(csv_gz)
        with self.assertRaises(dc.ContractError):  # CSV を書き換えて manifest はそのまま
            dc.verify(gzip.compress(text.replace(b",train", b",valid", 1)), mf)
        bad = json.loads(mf)
        bad["rows"] += 1
        with self.assertRaises(dc.ContractError):  # manifest を書き換えて sha256 はそのまま
            dc.verify(csv_gz, canonical.dumps(bad) + b"\n")
        with self.assertRaises(dc.ContractError):  # 正準でない整形
            dc.verify(csv_gz, json.dumps(json.loads(mf), indent=2).encode())

    def test_content_errors_with_consistent_hashes(self):
        csv_gz, mf = _load("synthetic-calibration")

        def expect(csv_edit=None, manifest_edit=None):
            g, m = _rewrite(csv_gz, mf, csv_edit, manifest_edit)
            with self.assertRaises(dc.ContractError):
                dc.verify(g, m)

        expect(csv_edit=lambda t: t.replace(",0,train", ",2,train", 1))                                   # label が 0/1 でない
        expect(csv_edit=lambda t: t.replace("train", "valid", 1))                                         # valid が train より前の日を含む
        expect(csv_edit=lambda t: t.replace("2026-01-04T06:15:00Z", "2026-01-04T20:15:00Z", 1))          # as_of（JST）がレース日の翌日
        expect(csv_edit=lambda t: t.replace(",0.0,", ",nan,", 1))                                         # 非有限値
        expect(csv_edit=lambda t: t.replace("1,2026-01-04", "2,2026-01-04", 1))                            # tenant_id が manifest と違う（越境）
        expect(manifest_edit=lambda m: m["builder"].update(legacy_compat_used=True))                      # 旧互換モードで作られたもの
        expect(manifest_edit=lambda m: m["builder"].update(mode="legacy"))
        expect(manifest_edit=lambda m: m["columns"].reverse())                                            # 列順

    def test_rows_must_be_sorted_and_unique(self):
        csv_gz, mf = _load("synthetic-alpha")

        def dup(t):
            lines = t.split("\n")
            lines.insert(2, lines[1])
            return "\n".join(lines)

        g, m = _rewrite(csv_gz, mf, dup, lambda x: x.update(rows=x["rows"] + 1))
        with self.assertRaises(dc.ContractError):
            dc.verify(g, m)

    def test_cli(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            gz = pathlib.Path(d) / "x.csv.gz"
            gz.write_bytes(_load("synthetic-calibration")[0])
            self.assertEqual(
                dc.main(["--csv-gz", str(gz), "--manifest", str(DS / "synthetic-calibration.manifest.json"),
                         "--feature-schema", str(RES / "feature_schema.calibration40.json")]), 0)
            self.assertEqual(dc.main(["--csv-gz", str(pathlib.Path(d) / "missing.csv.gz"), "--manifest", str(DS / "synthetic-calibration.manifest.json")]), 1)


if __name__ == "__main__":
    unittest.main()
