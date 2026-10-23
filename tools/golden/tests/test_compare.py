import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from tools.golden import compare, constants as C, fixtures_io

RULES = {"default": {"float": {"mode": "abs_tol", "abs_tol": 1e-9}},
         "overrides": [{"path": "results.*.outcome.message", "mode": "ignore"},
                       {"path": "results.*.outcome.value.exact", "mode": "exact"}],
         "strict": {"types": True, "key_set": True, "list_order": True, "nonfinite": "reject"}}


def ok(v):
    return {"status": "ok", "value": v}


class DiffTest(unittest.TestCase):
    def d(self, e, a):
        return compare.diff(e, a, RULES)

    def test_equal_and_within_tolerance(self):
        self.assertEqual(self.d({"x": 1.0}, {"x": 1.0 + 5e-10}), [])

    def test_out_of_tolerance_reports_values_and_tol(self):
        r = self.d({"x": [0.5]}, {"x": [0.5 + 1e-8]})
        self.assertEqual(len(r), 1)
        self.assertEqual((r[0]["path"], r[0]["kind"], r[0]["expected"], r[0]["abs_tol"]), ("x[0]", "value_mismatch", 0.5, 1e-9))

    def test_exact_override_has_zero_tolerance(self):
        e = {"results": [{"outcome": ok({"exact": 1.0})}]}
        a = {"results": [{"outcome": ok({"exact": 1.0 + 1e-15})}]}
        self.assertEqual(self.d(e, a)[0]["kind"], "value_mismatch")

    def test_type_mismatch_int_vs_float_and_bool(self):
        self.assertEqual(self.d({"x": 1.0}, {"x": 1})[0]["kind"], "type_mismatch")
        self.assertEqual(self.d({"x": 1}, {"x": True})[0]["kind"], "type_mismatch")
        self.assertEqual(self.d({"x": "1"}, {"x": 1})[0]["kind"], "type_mismatch")

    def test_missing_and_unexpected_keys(self):
        kinds = sorted(r["kind"] for r in self.d({"a": 1, "b": 2}, {"a": 1, "c": 3}))
        self.assertEqual(kinds, ["missing_key", "unexpected_key"])

    def test_list_order_and_length(self):
        self.assertEqual(len(self.d({"x": [1, 2]}, {"x": [2, 1]})), 2)
        self.assertEqual(self.d({"x": [1, 2]}, {"x": [1]})[0]["kind"], "length_mismatch")

    def test_nan_never_matches_finite(self):
        nan = float("nan")
        self.assertEqual(self.d({"x": 1.0}, {"x": nan})[0]["kind"], "nonfinite_mismatch")
        self.assertEqual(self.d({"x": nan}, {"x": nan})[0]["kind"], "nonfinite_mismatch")

    def test_tagged_nonfinite_requires_identical_tag(self):
        self.assertEqual(self.d({"x": {"$float": "inf"}}, {"x": {"$float": "inf"}}), [])
        self.assertEqual(self.d({"x": {"$float": "inf"}}, {"x": {"$float": "-inf"}})[0]["kind"], "nonfinite_mismatch")
        self.assertTrue(self.d({"x": 1.0}, {"x": {"$float": "nan"}}))

    def test_exception_type_compared_message_ignored(self):
        e = {"results": [{"outcome": {"status": "exception", "exception_type": "ZeroDivisionError", "message": "a"}}]}
        a = copy.deepcopy(e)
        a["results"][0]["outcome"]["message"] = "Division by zero (Kotlin)"
        self.assertEqual(self.d(e, a), [])
        a["results"][0]["outcome"]["exception_type"] = "ArithmeticException"
        self.assertEqual(self.d(e, a)[0]["kind"], "value_mismatch")


class CompareAllTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fx = {fx["case_id"]: fx for _, (_, fx) in fixtures_io.load_dir(C.FIXTURE_DIR).items()}

    def actual(self):
        return {"schema_version": 1, "results": {k: copy.deepcopy(v["expected_output"]) for k, v in self.fx.items()}}

    def test_self_comparison_passes_for_all_cases(self):
        # 比較器の自己検査。Kotlin の結果ではない（Kotlin 側は未実装）。
        rep = compare.compare_all(self.fx, self.actual())
        self.assertEqual(rep["summary"], {"passed": len(self.fx), "failed": 0, "not_provided": 0})

    def test_perturbed_value_is_detected_with_case_id(self):
        act = self.actual()
        act["results"]["GD01-EV-001"]["results"][5]["outcome"]["value"] += 1e-6
        rep = compare.compare_all(self.fx, act)
        self.assertEqual(rep["summary"]["failed"], 1)
        bad = [c for c in rep["cases"] if c["status"] == "failed"][0]
        self.assertEqual(bad["case_id"], "GD01-EV-001")
        self.assertIn("results[5].outcome.value", bad["diffs"][0]["path"])
        self.assertIn("GD01-EV-001", compare.format_report(rep))

    def test_missing_case_is_not_success(self):
        act = self.actual()
        del act["results"]["G1-MODEL-001"]
        self.assertEqual(compare.compare_all(self.fx, act)["summary"]["not_provided"], 1)

    def test_bad_actual_document_rejected(self):
        with self.assertRaises(ValueError):
            compare.compare_all(self.fx, {"results": {}})
        with self.assertRaises(ValueError):
            compare.compare_all(self.fx, self.actual(), only=["NOPE"])

    def test_cli_exit_codes_and_machine_report(self):
        with tempfile.TemporaryDirectory() as t:
            ap, rp = Path(t, "a.json"), Path(t, "r.json")
            act = self.actual()
            ap.write_text(json.dumps(act))
            run = lambda: subprocess.run([sys.executable, "-m", "tools.golden.compare", "--actual", str(ap), "--report", str(rp)],  # noqa: E731
                                         capture_output=True, text=True)
            self.assertEqual(run().returncode, 0)
            act["results"]["GD02-NORM-002"]["results"][4]["outcome"]["value"][0] += 1.0
            ap.write_text(json.dumps(act))
            r = run()
            self.assertEqual(r.returncode, 1)
            self.assertEqual(json.loads(rp.read_text())["summary"]["failed"], 1)
            del act["results"]["GD10-DEFAULTS-001"]
            ap.write_text(json.dumps(act))
            self.assertEqual(run().returncode, 2)
