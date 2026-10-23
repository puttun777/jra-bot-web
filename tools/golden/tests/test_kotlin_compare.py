import copy
import json
import unittest

from tools.golden import constants as C, fixtures_io, kotlin_compare as kc
from tools.golden.catalog import KOTLIN_PORT


class KotlinCompareTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fx = {fx["case_id"]: fx for _, (_, fx) in fixtures_io.load_dir(C.FIXTURE_DIR).items()}

    def docs(self):
        """対応表どおりに申告し、出力は期待値そのまま（比較器の自己検査。Kotlin の結果ではない）。"""
        sup = KOTLIN_PORT["supported"]
        actual = {"schema_version": 1, "results": {c: copy.deepcopy(self.fx[c]["expected_output"]) for c in sup}}
        cov = {"schema_version": 1,
               "supported": [{"case_id": c, "calls": len(self.fx[c]["input"]["calls"])} for c in sup],
               "unsupported": [{"case_id": c, "calls": len(self.fx[c]["input"]["calls"]), "reason": r}
                               for c, r in KOTLIN_PORT["unsupported"].items()]}
        return actual, cov

    def test_self_comparison_passes_and_counts_are_separated(self):
        actual, cov = self.docs()
        rep = kc.build_report(self.fx, actual, cov)
        n_sup = sum(len(self.fx[c]["input"]["calls"]) for c in KOTLIN_PORT["supported"])
        n_uns = sum(len(self.fx[c]["input"]["calls"]) for c in KOTLIN_PORT["unsupported"])
        self.assertEqual(rep["supported"], {"cases": len(KOTLIN_PORT["supported"]), "calls": n_sup, "passed": len(KOTLIN_PORT["supported"]),
                                            "failed": 0, "passed_calls": n_sup})
        self.assertEqual((rep["unsupported"]["cases"], rep["unsupported"]["calls"]), (len(KOTLIN_PORT["unsupported"]), n_uns))
        self.assertEqual(rep["total"], {"cases": len(self.fx), "calls": n_sup + n_uns})
        self.assertEqual(rep["max_abs_error"], 0.0)
        self.assertIn("未対応", kc.format_report(rep))

    def test_within_tolerance_is_pass_and_error_is_reported(self):
        actual, cov = self.docs()
        actual["results"]["GD01-EV-001"]["results"][5]["outcome"]["value"] += 5e-10  # 許容 1e-9 の内側
        rep = kc.build_report(self.fx, actual, cov)
        self.assertEqual(rep["supported"]["failed"], 0)
        self.assertAlmostEqual(rep["max_abs_error"], 5e-10, delta=1e-15)
        self.assertEqual(rep["max_abs_error_case"], "GD01-EV-001")

    def test_beyond_tolerance_fails_and_is_not_hidden(self):
        actual, cov = self.docs()
        actual["results"]["GD01-EV-001"]["results"][5]["outcome"]["value"] += 2e-9
        rep = kc.build_report(self.fx, actual, cov)
        self.assertEqual(rep["supported"]["failed"], 1)
        self.assertEqual(rep["supported"]["passed"], len(KOTLIN_PORT["supported"]) - 1)

    def test_exception_difference_and_type_change_fail(self):
        actual, cov = self.docs()
        res = actual["results"]["GD01-KELLY-002"]["results"]
        k = next(i for i, r in enumerate(res) if r["outcome"].get("exception_type") == "ZeroDivisionError")
        res[k]["outcome"] = {"status": "ok", "value": 0.0}  # 旧実装は例外。Kotlin が値を返したら不一致
        self.assertEqual(kc.build_report(self.fx, actual, cov)["supported"]["failed"], 1)
        actual, cov = self.docs()
        res = actual["results"]["GD01-EV-001"]["results"]
        res[0]["outcome"]["value"] = int(res[0]["outcome"]["value"])  # float → int は型の不一致
        self.assertEqual(kc.build_report(self.fx, actual, cov)["supported"]["failed"], 1)

    def test_exception_message_is_ignored_where_rules_say_so(self):
        actual, cov = self.docs()
        res = actual["results"]["GD01-KELLY-002"]["results"]
        k = next(i for i, r in enumerate(res) if r["outcome"].get("exception_type") == "ZeroDivisionError")
        res[k]["outcome"]["message"] = "別の文言"
        self.assertEqual(kc.build_report(self.fx, actual, cov)["supported"]["failed"], 0)

    def test_structural_problems_raise(self):
        actual, cov = self.docs()
        cases = {
            "出力に余分なケース": lambda a, c: a["results"].update(extra=copy.deepcopy(a["results"]["GD01-EV-001"])),
            "申告した対応ケースの出力が無い": lambda a, c: a["results"].pop("GD01-EV-001"),
            "未対応を空で埋めて対応と申告": lambda a, c: (c["unsupported"].pop(0), c["supported"].append({"case_id": "G1-MODEL-001", "calls": 73})),
            "分類漏れ": lambda a, c: c["unsupported"].pop(),
            "呼び出し数の食い違い": lambda a, c: c["supported"][0].update(calls=1),
            "未対応に理由が無い": lambda a, c: c["unsupported"][0].update(reason=""),
            "対応表との不一致（対応が黙って減る）": lambda a, c: (a["results"].pop("GD09-EVAL-001"), c["supported"].pop(
                next(i for i, e in enumerate(c["supported"]) if e["case_id"] == "GD09-EVAL-001")), c["unsupported"].append(
                {"case_id": "GD09-EVAL-001", "calls": 7, "reason": "x"})),
            "schema_version": lambda a, c: a.update(schema_version=2),
        }
        for name, mutate in cases.items():
            with self.subTest(name):
                a, c = copy.deepcopy(actual), copy.deepcopy(cov)
                mutate(a, c)
                with self.assertRaises(ValueError):
                    kc.build_report(self.fx, a, c)

    def test_float_errors_walks_both_trees(self):
        self.assertEqual(kc.float_errors({"a": [1.0, 2.0], "b": "x"}, {"a": [1.5, 2.0], "b": "y"}), [("a[0]", 0.5), ("a[1]", 0.0)])
        self.assertEqual(kc.float_errors(1.0, 1), [])  # 型違いは compare 側が報告する

    def test_catalog_port_table_matches_fixtures(self):
        self.assertEqual(set(KOTLIN_PORT["supported"]) | set(KOTLIN_PORT["unsupported"]), set(self.fx))
        self.assertFalse(set(KOTLIN_PORT["supported"]) & set(KOTLIN_PORT["unsupported"]))

    def test_cli_exit_codes(self):
        import tempfile
        from pathlib import Path
        actual, cov = self.docs()
        with tempfile.TemporaryDirectory() as d:
            ap, cp = Path(d) / "a.json", Path(d) / "c.json"
            ap.write_text(json.dumps(actual)), cp.write_text(json.dumps(cov))
            self.assertEqual(kc.main(["--actual", str(ap), "--coverage", str(cp)]), 0)
            actual["results"]["GD01-EV-001"]["results"][5]["outcome"]["value"] += 1.0
            ap.write_text(json.dumps(actual))
            self.assertEqual(kc.main(["--actual", str(ap), "--coverage", str(cp)]), 1)
            cov["supported"].pop()
            cp.write_text(json.dumps(cov))
            self.assertEqual(kc.main(["--actual", str(ap), "--coverage", str(cp)]), 2)


if __name__ == "__main__":
    unittest.main()
