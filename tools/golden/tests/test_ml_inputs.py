"""T3.6-A の ML 経路（G3〜G7）の合成入力の形・登録の整合（旧実装は使わない）。"""
import json
import unittest

from tools.golden import constants as C, fixtures_io, scenarios, scenarios_ml as M
from tools.golden.catalog import CATALOG, KOTLIN_PORT

ML_CASES = ("G6-FEATURES-001", "G3-ML-NORM-002", "G4-ENSEMBLE-002", "G5-BLEND-002", "G7-LEGACY-FAIL-001")


class MlScenarioTest(unittest.TestCase):
    def test_cases_registered_and_ported(self):
        in_catalog = {cid for e in CATALOG for cid in e["case_ids"]}
        for cid in ML_CASES + ("G1-MODEL-001", "G2-HASH-001"):
            self.assertIn(cid, scenarios.CASES_BY_ID)
            self.assertIn(cid, in_catalog)
            self.assertIn(cid, KOTLIN_PORT["supported"], cid)
        self.assertEqual(KOTLIN_PORT["unsupported"].keys(), {"GD10-DEFAULTS-001"})

    def test_synthetic_db_is_self_consistent(self):
        db = M.synthetic_db()
        races = {r["race_id"] for r in db["races"]}
        self.assertEqual(races, {M.RACE_TURF, M.RACE_DIRT, M.RACE_OBST})
        for e in db["race_horses"]:
            self.assertIn(e["race_id"], races)
        for r in db["races"]:
            self.assertEqual(r["race_id"][:8], r["date"])  # 旧実装は race_id 先頭 8 桁をレース日とする
        for h in db["horses"]:
            for col in ("pedigree_json", "style_tendency_json", "chaku_by_distance_json", "chaku_by_surface_json", "chaku_by_condition_json"):
                self.assertTrue(h[col] is None or isinstance(h[col], str), col)  # JSON 列は SQLite と同じく文字列

    def test_h3_h8_scenarios_are_present(self):
        db = M.synthetic_db()
        runs = [r for r in db["horse_past_runs"] if r["ketto_num"].endswith("001")]
        self.assertTrue(any(r["race_id"] == M.RACE_TURF for r in runs), "対象レース自身の過去走（H3）")
        self.assertTrue(any(r["date"] > "20260104" for r in runs), "レース後の過去走（H3）")
        nows = {c["args"]["now"] for c in M.g6_calls()}
        self.assertGreaterEqual(len(nows), 4, "now を変えて age_years の基準を確認する（H8）")

    def test_model_dimensions(self):
        self.assertEqual(len(M.w40()), 40)
        self.assertEqual(len(M.w35()), 35)
        self.assertTrue(all(isinstance(x, float) for x in M.w40() + M.w35()))

    def test_ml_calls_shape(self):
        for fn in (M.g3_calls, M.g4_calls, M.g5_calls, M.g7_legacy_calls):
            calls = fn()
            self.assertEqual(len({c["call_id"] for c in calls}), len(calls))
            for c in calls:
                a = c["args"]
                self.assertEqual(c["function"], "evaluate_odds_ml")
                self.assertTrue({"db", "now", "race_id", "odds", "models", "ml_cfg", "kwargs"} <= set(a))
                self.assertTrue(set(a["models"]) <= {"legacy", "calibration", "alpha"})
                self.assertEqual(a["kwargs"]["bet_types"], ["単勝"])

    def test_fixtures_contain_no_file_names_or_urls(self):
        text = json.dumps([M.g6_calls(), M.g3_calls(), M.g4_calls(), M.g5_calls(), M.g7_legacy_calls()], ensure_ascii=False)
        for bad in ("http", ".pkl", ".sqlite", "@"):
            self.assertNotIn(bad, text)

    def test_execution_parameters_describe_ml_runs(self):
        case = scenarios.CASES_BY_ID["G4-ENSEMBLE-002"]
        fx = fixtures_io.build_fixture(case, {"results": []})
        self.assertIn("合成 SQLite", fx["execution_parameters"]["ml_cfg"])
        legacy_case = scenarios.CASES_BY_ID["GD01-EV-001"]
        self.assertEqual(fixtures_io.build_fixture(legacy_case, {"results": []})["execution_parameters"]["ml_cfg"], {"enabled": False})


if __name__ == "__main__":
    unittest.main()


class NotifyCasesTest(unittest.TestCase):
    """T3.7: Discord 通知のケース（G8）は、実 Webhook・実データを含まず、長い文字列を repeat 表現で持ち、Kotlin の対応表と一致している。"""

    IDS = ("G8-SPLIT-001", "G8-RETRY-002", "G8-DEDUP-003", "G8-SINGLE-004", "G8-GROUPED-005", "G8-REASON-006")

    def test_registered_in_kotlin_port_and_catalog(self):
        from tools.golden.catalog import CATALOG, KOTLIN_PORT
        in_catalog = {cid for e in CATALOG for cid in e["case_ids"]}
        for cid in self.IDS:
            self.assertIn(cid, KOTLIN_PORT["supported"])
            self.assertIn(cid, in_catalog)

    def test_inputs_use_only_dummy_webhook_and_synthetic_ids(self):
        import json
        from tools.golden import scenarios
        for cid in self.IDS:
            text = json.dumps(scenarios.CASES_BY_ID[cid].calls, ensure_ascii=False)
            self.assertNotIn("discord.com", text, cid)
            self.assertNotIn("webhooks", text, cid)
            self.assertNotRegex(text, r"[A-Za-z0-9_-]{40,}", cid)

    def test_long_strings_are_expressed_as_repeat(self):
        from tools.golden import adapter, scenarios_notify as N
        expanded = adapter._expand_repeat(N._rep(("a", 1900), ("\n", 1)))
        self.assertEqual(len(expanded), 1901)
        self.assertEqual(adapter._expand_repeat("そのまま"), "そのまま")
