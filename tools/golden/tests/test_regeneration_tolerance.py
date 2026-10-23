"""再生成の比較（generate.regeneration_problems）。旧実装は不要。実フィクスチャを元に、CPU 差を模した摂動で検査する。"""
import copy
import math
import unittest

from tools.golden import constants as C, generate, jsonutil

CASE = "G1-MODEL-001.json"      # abs_tol=TOL_PROB の浮動小数を含む
EXACT_CASE = "G2-HASH-001.json"  # 完全一致（abs_tol=0）


def committed():
    return {p.name: p.read_bytes() for p in C.FIXTURE_DIR.glob("*.json")}


def mutate(files, name, fn):
    """fn(parsed) で書き換えた版を正準形式で返す（manifest の sha256 は元のまま）。"""
    out = dict(files)
    doc = jsonutil.loads(files[name].decode("utf-8"))
    fn(doc)
    out[name] = jsonutil.dumps(doc).encode("utf-8")
    return out


def first_float(node, path=()):
    if isinstance(node, float):
        return path
    if isinstance(node, dict):
        for k in sorted(node):
            r = first_float(node[k], path + (k,))
            if r:
                return r
    if isinstance(node, list):
        for i, v in enumerate(node):
            r = first_float(v, path + (i,))
            if r:
                return r
    return None


def get(doc, path):
    for p in path:
        doc = doc[p]
    return doc


def set_(doc, path, v):
    get(doc, path[:-1])[path[-1]] = v


class RegenerationToleranceTest(unittest.TestCase):
    def setUp(self):
        self.files = committed()
        doc = jsonutil.loads(self.files[CASE].decode("utf-8"))
        self.path = ("expected_output",) + first_float(doc["expected_output"])

    def perturb(self, delta):
        def f(doc):
            set_(doc, self.path, get(doc, self.path) + delta)
        return mutate(self.files, CASE, f)

    def test_identical_is_ok(self):
        self.assertEqual(generate.regeneration_problems(self.files, dict(self.files)), [])

    def test_two_ulp_drift_is_accepted(self):
        v = get(jsonutil.loads(self.files[CASE].decode("utf-8")), self.path)
        drift = 2 * math.ulp(v)
        regenerated = self.perturb(drift)
        self.assertNotEqual(regenerated[CASE], self.files[CASE])  # バイトは実際に違う
        self.assertEqual(generate.regeneration_problems(self.files, regenerated), [])

    def test_drift_above_the_case_tolerance_is_rejected(self):
        problems = generate.regeneration_problems(self.files, self.perturb(1e-6))
        self.assertEqual(len(problems), 1)
        self.assertIn("value_mismatch", problems[0])
        self.assertIn(CASE, problems[0])

    def test_exact_case_stays_exact(self):
        def f(doc):
            p = ("expected_output",) + first_int(doc["expected_output"])
            set_(doc, p, get(doc, p) + 1)
        problems = generate.regeneration_problems(self.files, mutate(self.files, EXACT_CASE, f))
        self.assertEqual(len(problems), 1)

    def test_type_change_is_rejected(self):
        def f(doc):
            set_(doc, self.path, int(get(doc, self.path)))  # float → int
        problems = generate.regeneration_problems(self.files, mutate(self.files, CASE, f))
        self.assertTrue(any("type_mismatch" in m for m in problems), problems)

    def test_changes_outside_expected_output_are_rejected(self):
        for key, value in (("source_commit", "0" * 40), ("description", "x")):
            problems = generate.regeneration_problems(self.files, mutate(self.files, CASE, lambda d: d.update({key: value})))
            self.assertEqual(len(problems), 1, key)
            self.assertIn("expected_output 以外", problems[0])

    def test_input_change_is_rejected(self):
        def f(doc):
            doc["input"]["calls"] = doc["input"]["calls"][:-1]
        self.assertEqual(len(generate.regeneration_problems(self.files, mutate(self.files, CASE, f))), 1)

    def test_comparison_rules_change_is_rejected(self):
        def f(doc):
            doc["comparison_rules"]["default"]["float"]["abs_tol"] = 1.0
        self.assertEqual(len(generate.regeneration_problems(self.files, mutate(self.files, CASE, f))), 1)

    def test_missing_and_extra_files_are_rejected(self):
        less = {k: v for k, v in self.files.items() if k != CASE}
        self.assertIn(f"余分なファイル: {CASE}", generate.regeneration_problems(self.files, less))
        more = dict(self.files, **{"EXTRA.json": b"{}"})
        self.assertIn("欠落: EXTRA.json", generate.regeneration_problems(self.files, more))

    def test_manifest_sha_may_differ_but_nothing_else(self):
        sha_only = mutate(self.files, C.MANIFEST_NAME, lambda d: d["fixtures"][0].update(sha256="0" * 64))
        self.assertEqual(generate.regeneration_problems(self.files, sha_only), [])
        other = mutate(self.files, C.MANIFEST_NAME, lambda d: d["fixtures"].pop())
        self.assertEqual(len(generate.regeneration_problems(self.files, other)), 1)
        other2 = mutate(self.files, C.MANIFEST_NAME, lambda d: d.update(source_commit="1" * 40))
        self.assertEqual(len(generate.regeneration_problems(self.files, other2)), 1)

    def test_exception_message_is_compared_strictly(self):
        """比較ルールの ignore（Kotlin の文言差用）は再生成には持ち込まない。"""
        name = "GD01-KELLY-002.json"
        doc = jsonutil.loads(self.files[name].decode("utf-8"))
        self.assertTrue(any(o.get("mode") == "ignore" for o in doc["comparison_rules"]["overrides"]))

        changed = []
        problems = generate.regeneration_problems(
            self.files, mutate(self.files, name, lambda d: changed.append(replace_message(d["expected_output"]))))
        self.assertEqual(changed, [True])
        self.assertTrue(problems)
        self.assertTrue(all("value_mismatch" in m and "message" in m for m in problems), problems)


def first_int(node, path=()):
    if isinstance(node, bool):
        return None
    if isinstance(node, int):
        return path
    if isinstance(node, dict):
        for k in sorted(node):
            r = first_int(node[k], path + (k,))
            if r:
                return r
    if isinstance(node, list):
        for i, v in enumerate(node):
            r = first_int(v, path + (i,))
            if r:
                return r
    return None


def replace_message(node):
    done = False
    if isinstance(node, dict):
        for k, v in node.items():
            if k == "message" and isinstance(v, str):
                node[k] = v + " (changed)"
                return True
            done = replace_message(v) or done
    elif isinstance(node, list):
        for v in node:
            done = replace_message(v) or done
    return done


if __name__ == "__main__":
    unittest.main()
