import copy
import json
import shutil
import tempfile
import unittest
from pathlib import Path

from tools.golden import constants as C, fixtures_io, jsonutil, verify


class VerifyTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        for p in C.FIXTURE_DIR.glob("*.json"):
            shutil.copy(p, self.dir / p.name)

    def tearDown(self):
        self.tmp.cleanup()

    def errs(self):
        return verify.verify_dir(self.dir)[0]

    def rewrite(self, name, mutate, update_manifest=True):
        p = self.dir / name
        fx = jsonutil.loads(p.read_text(encoding="utf-8"))
        mutate(fx)
        p.write_text(jsonutil.dumps(fx), encoding="utf-8")
        if update_manifest:
            m = fixtures_io.load_manifest(self.dir)
            for e in m["fixtures"]:
                if e["path"] == name:
                    e["sha256"] = fixtures_io.sha256_bytes(p.read_bytes())
            (self.dir / C.MANIFEST_NAME).write_text(jsonutil.dumps(m), encoding="utf-8")

    def assertFails(self, needle):
        e = self.errs()
        self.assertTrue(any(needle in x for x in e), f"{needle!r} not in {e}")

    def test_committed_fixtures_pass(self):
        self.assertEqual(self.errs(), [])

    def test_tampered_expected_value_detected_by_manifest_hash(self):
        self.rewrite("GD01-EV-001.json", lambda fx: fx["expected_output"]["results"][0]["outcome"].update(value=123.0), False)
        self.assertFails("sha256")

    def test_tamper_with_manifest_update_still_needs_other_checks(self):
        # manifest も合わせて更新した改ざんは検証では検出できない（PR 差分レビューと generate --check が防御線）
        self.rewrite("GD01-EV-001.json", lambda fx: fx["expected_output"]["results"][0]["outcome"].update(value=123.0))
        self.assertEqual(self.errs(), [])

    def test_missing_metadata(self):
        for key in ("source_commit", "comparison_rules", "execution_parameters", "input", "expected_output", "source_function"):
            with self.subTest(key=key):
                shutil.copy(C.FIXTURE_DIR / "GD02-PROB-001.json", self.dir / "GD02-PROB-001.json")
                self.rewrite("GD02-PROB-001.json", lambda fx, k=key: fx.pop(k))
                self.assertFails("必須メタデータ")

    def test_wrong_commit(self):
        self.rewrite("GD02-PROB-001.json", lambda fx: fx.update(source_commit="0" * 40))
        self.assertFails("固定値と異なる")

    def test_short_commit(self):
        self.rewrite("GD02-PROB-001.json", lambda fx: fx.update(source_commit="f7dbf69"))
        self.assertFails("完全 SHA")

    def test_unsupported_schema_version(self):
        self.rewrite("GD02-PROB-001.json", lambda fx: fx.update(schema_version=99))
        self.assertFails("schema_version")

    def test_invalid_json(self):
        (self.dir / "GD02-PROB-001.json").write_text("{not json")
        self.assertTrue(self.errs())

    def test_nan_literal_rejected(self):
        (self.dir / "GD02-PROB-001.json").write_text('{"a": NaN}')
        self.assertTrue(self.errs())

    def test_tagged_nonfinite_in_expected_rejected(self):
        self.rewrite("GD02-PROB-001.json", lambda fx: fx["expected_output"]["results"][0]["outcome"].update(value={"$float": "nan"}))
        self.assertFails("NaN/Infinity")

    def test_duplicate_case_id(self):
        shutil.copy(self.dir / "GD02-PROB-001.json", self.dir / "COPY.json")
        self.assertTrue(any("重複" in e or "ファイル名" in e for e in self.errs()))

    def test_manifest_file_mismatch(self):
        (self.dir / "EXTRA.json").write_text("{}")
        self.assertFails("manifest と実ファイルが不一致")

    def test_bad_comparison_rules(self):
        self.rewrite("GD02-PROB-001.json", lambda fx: fx["comparison_rules"]["default"]["float"].update(abs_tol=0.5))
        self.assertFails("comparison_rules")
        self.rewrite("GD02-PROB-001.json", lambda fx: fx["comparison_rules"].update(strict={"types": False}))
        self.assertFails("comparison_rules")

    def test_not_synthetic(self):
        self.rewrite("GD02-PROB-001.json", lambda fx: fx.update(data_classification="real"))
        self.assertFails("synthetic")

    def test_forbidden_content_in_input(self):
        for bad in ("https://discord.com/api/webhooks/1/abc", "x@example.com", "/data/jra.sqlite", "model.pkl", "A" * 40):
            with self.subTest(bad=bad):
                shutil.copy(C.FIXTURE_DIR / "G2-HASH-001.json", self.dir / "G2-HASH-001.json")
                self.rewrite("G2-HASH-001.json", lambda fx, b=bad: fx["input"]["calls"][0]["args"].update(s=b))
                self.assertFails("禁止パターン")

    def test_call_ids_must_correspond(self):
        self.rewrite("GD02-PROB-001.json", lambda fx: fx["expected_output"]["results"].pop())
        self.assertFails("call_id")

    def test_non_canonical_format(self):
        p = self.dir / "GD02-PROB-001.json"
        fx = json.loads(p.read_text(encoding="utf-8"))
        p.write_text(json.dumps(fx), encoding="utf-8")
        m = fixtures_io.load_manifest(self.dir)
        for e in m["fixtures"]:
            if e["path"] == p.name:
                e["sha256"] = fixtures_io.sha256_bytes(p.read_bytes())
        (self.dir / C.MANIFEST_NAME).write_text(jsonutil.dumps(m), encoding="utf-8")
        self.assertFails("正準")

    def test_cli_exit_code_nonzero_and_fixtures_untouched(self):
        import subprocess, sys
        self.rewrite("GD02-PROB-001.json", lambda fx: fx.update(source_commit="0" * 40))
        before = (self.dir / "GD02-PROB-001.json").read_bytes()
        r = subprocess.run([sys.executable, "-m", "tools.golden.verify", "--dir", str(self.dir)], capture_output=True, text=True)
        self.assertEqual(r.returncode, 1)
        self.assertEqual(before, (self.dir / "GD02-PROB-001.json").read_bytes())


class CoverageContentTest(unittest.TestCase):
    """フィクスチャ内容の健全性（旧実装由来の事実の記録として）。"""

    @classmethod
    def setUpClass(cls):
        cls.fx = {fx["case_id"]: fx for _, (_, fx) in fixtures_io.load_dir(C.FIXTURE_DIR).items()}

    def test_e5_zero_division_recorded_as_actual_legacy_behavior(self):
        res = {r["call_id"]: r["outcome"] for r in self.fx["GD01-KELLY-002"]["expected_output"]["results"]}
        self.assertEqual(res["kf-007"]["exception_type"], "ZeroDivisionError")

    def test_case_set_matches_scenarios(self):
        from tools.golden.scenarios import CASES_BY_ID
        self.assertEqual(set(self.fx), set(CASES_BY_ID))

    def test_inputs_match_scenario_definitions(self):
        from tools.golden.scenarios import CASES_BY_ID
        for cid, fx in self.fx.items():
            self.assertEqual(fx["input"]["calls"], json.loads(json.dumps(CASES_BY_ID[cid].calls)), cid)
