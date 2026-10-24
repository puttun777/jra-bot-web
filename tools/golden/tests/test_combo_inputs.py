"""T3.2: ワイド/三連複のシナリオの入力形式とアダプターの変換（旧実装は使わない。標準ライブラリだけ）。"""
import types
import unittest

from tools.golden import adapter, scenarios

NEW_CASES = ("GD05-PL-005", "GD05-FLOW-006", "GD05-BOX-007", "GD05-REAL-008", "GD05-FILTER-009", "GD07-COMBO-STAKE-010", "GD06-EVAL-011")


class DecodeOddsMapsTest(unittest.TestCase):
    def test_arrays_become_legacy_tuple_keyed_dicts(self):
        kw = adapter._decode_odds_maps({"wide_odds_map": [[1, 2, 3.5, 4.5], [2, 5, 1.0, 1.0]],
                                        "sanrenpuku_odds_map": [[1, 2, 3, 30.0]], "other": 1})
        self.assertEqual(kw["wide_odds_map"], {(1, 2): (3.5, 4.5), (2, 5): (1.0, 1.0)})
        self.assertEqual(kw["sanrenpuku_odds_map"], {(1, 2, 3): 30.0})
        self.assertEqual(kw["other"], 1)

    def test_none_and_absent_are_left_alone_and_empty_becomes_empty_dict(self):
        kw = adapter._decode_odds_maps({"wide_odds_map": None})
        self.assertIsNone(kw["wide_odds_map"])
        self.assertNotIn("sanrenpuku_odds_map", kw)
        self.assertEqual(adapter._decode_odds_maps({"wide_odds_map": []})["wide_odds_map"], {})  # 空は偽（メモの文言が変わる）

    def test_suggest_call_passes_bet_strategy_none_and_int_keys(self):
        seen = {}

        def fake(*args, **kwargs):
            seen["args"], seen["kwargs"] = args, kwargs
            return {}
        L = types.SimpleNamespace(
            bet_strategy=types.SimpleNamespace(suggest_multiple_bet_strategies=fake),
            models=types.SimpleNamespace(OddsData=lambda **d: d))
        adapter._suggest(L, {"umaban": 1, "win_prob": 0.3, "win_odds": 3.0, "odds_list": [{"umaban": 1, "odds": 3.0, "popularity": 1}],
                             "kwargs": {"probs_by_umaban": {"1": 0.3}, "wide_odds_map": [[1, 2, 5.0, 6.0]], "max_tickets_per_type": 9}})
        self.assertIsNone(seen["kwargs"]["bet_strategy"])  # evaluate_odds と同じ経路（旧実装の隠れ変数 bet_strategy=None）
        self.assertEqual(seen["kwargs"]["probs_by_umaban"], {1: 0.3})
        self.assertEqual(seen["kwargs"]["wide_odds_map"], {(1, 2): (5.0, 6.0)})
        self.assertEqual(seen["kwargs"]["max_tickets_per_type"], 9)


class ScenarioShapeTest(unittest.TestCase):
    def cases(self):
        by_id = scenarios.CASES_BY_ID
        for cid in NEW_CASES:
            self.assertIn(cid, by_id)
            yield by_id[cid]

    def test_new_cases_call_only_the_entry_points_the_kotlin_runner_supports(self):
        for case in self.cases():
            self.assertTrue(case.calls)
            for c in case.calls:
                self.assertIn(c["function"], ("suggest_multiple_bet_strategies", "evaluate_odds"), case.case_id)

    def test_odds_maps_use_ascending_keys_and_positive_distinct_umaban(self):
        """旧実装は昇順のキーで検索する。シナリオが逆順のキーを渡すと、Kotlin（正規化して引く）との差が出てしまう。"""
        for case in self.cases():
            for c in case.calls:
                kw = c["args"]["kwargs"]
                for r in kw.get("wide_odds_map") or []:
                    self.assertTrue(0 < r[0] < r[1], (case.case_id, c["call_id"], r))
                    self.assertLessEqual(r[2], r[3])
                for r in kw.get("sanrenpuku_odds_map") or []:
                    self.assertTrue(0 < r[0] < r[1] < r[2], (case.case_id, c["call_id"], r))

    def test_direct_calls_give_a_probability_for_every_horse(self):
        for case in self.cases():
            for c in case.calls:
                if c["function"] != "suggest_multiple_bet_strategies":
                    continue
                a = c["args"]
                keys = set(a["kwargs"]["probs_by_umaban"])
                self.assertEqual(keys, {str(h["umaban"]) for h in a["odds_list"]}, (case.case_id, c["call_id"]))
                self.assertIn(a["umaban"], [h["umaban"] for h in a["odds_list"]])

    def test_new_cases_are_ported_in_the_kotlin_table_and_registered_in_the_catalog(self):
        from tools.golden.catalog import CATALOG, KOTLIN_PORT
        in_catalog = {cid for e in CATALOG for cid in e["case_ids"]}
        for cid in NEW_CASES + ("GD05-COMBO-001",):
            self.assertIn(cid, KOTLIN_PORT["supported"])
            self.assertIn(cid, in_catalog)
        self.assertNotIn("GD05-COMBO-001", KOTLIN_PORT["unsupported"])

    def test_existing_cases_are_unchanged_in_the_scenario_list(self):
        """既存 14 ケースの id が欠けていない（フィクスチャを書き換えない前提。内容の不変は add-new のテストと manifest が守る）。"""
        existing = {"GD01-EV-001", "GD01-KELLY-002", "GD02-PROB-001", "GD02-NORM-002", "G2-HASH-001", "G1-MODEL-001", "GD03-FILTER-001",
                    "GD05-COMBO-001", "GD09-EVAL-001", "GD04-WIN-HITRATE-003", "GD03-SNAPSHOT-002", "GD08-SELECT-003", "GD07-SIZING-004",
                    "GD10-DEFAULTS-001"}
        ml = {"G6-FEATURES-001", "G3-ML-NORM-002", "G4-ENSEMBLE-002", "G5-BLEND-002", "G7-LEGACY-FAIL-001"}  # T3.6-A で追加
        self.assertEqual(existing, set(scenarios.CASES_BY_ID) - set(NEW_CASES) - ml)


if __name__ == "__main__":
    unittest.main()
