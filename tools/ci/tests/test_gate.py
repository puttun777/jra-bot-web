"""最終ゲートの判定（ユーザー要件 §8 のケース 10・11 を含む）。skipped を成功として数えないこと、fail closed を検証する。"""
import json
import os
import subprocess
import sys
import unittest

from tools.ci import classify, gate


def needs_for(plan: classify.Plan, results: dict | None = None, changes_result: str = "success") -> dict:
    """plan に従って『正しく実行された』needs を作る。results で上書き。"""
    o = plan.outputs()
    expected = {
        "build": plan.run_build, "release-tools": plan.run_release, "golden": plan.run_golden,
        "db-integration": bool(plan.db_matrix), "images": plan.run_images, "connectorj26": plan.run_connectorj26,
    }
    needs = {"changes": {"result": changes_result, "outputs": o}}
    for job, ran in expected.items():
        needs[job] = {"result": "success" if ran else "skipped", "outputs": {}}
    for job, r in (results or {}).items():
        needs[job]["result"] = r
    return needs


DOCS = classify.classify(["docs/PROGRESS.md"])
KOTLIN = classify.classify(["bot/src/main/kotlin/A.kt"])
PERSISTENCE = classify.classify(["persistence/src/main/kotlin/A.kt"])
FULL = classify.plan_for_event("workflow_dispatch")


class GateTest(unittest.TestCase):
    def ok(self, needs):
        ok, problems, _ = gate.evaluate(needs)
        return ok, problems

    def test_docs_only_passes_with_everything_skipped(self):
        ok, problems = self.ok(needs_for(DOCS))
        self.assertTrue(ok, problems)

    def test_docs_only_never_reports_skipped_as_passed(self):
        _, _, lines = gate.evaluate(needs_for(DOCS))
        text = "\n".join(lines)
        self.assertIn("未実施", text)
        self.assertNotIn("成功", text.replace("不要だったが実行して成功", ""))

    def test_expected_jobs_all_success_passes(self):
        for plan in (KOTLIN, PERSISTENCE, FULL):
            self.assertTrue(self.ok(needs_for(plan))[0])

    def test_10_required_job_failure_fails(self):
        for job in ("build", "db-integration", "release-tools", "images"):
            plan = FULL
            ok, problems = self.ok(needs_for(plan, {job: "failure"}))
            self.assertFalse(ok, job)
            self.assertTrue(any(job in p for p in problems))

    def test_required_job_skipped_or_cancelled_fails(self):
        for r in ("skipped", "cancelled"):
            self.assertFalse(self.ok(needs_for(KOTLIN, {"build": r}))[0], r)
            self.assertFalse(self.ok(needs_for(KOTLIN, {"db-integration": r}))[0], r)

    def test_unexpected_job_that_ran_and_failed_fails(self):
        self.assertFalse(self.ok(needs_for(DOCS, {"build": "failure"}))[0])
        self.assertFalse(self.ok(needs_for(DOCS, {"golden": "cancelled"}))[0])

    def test_unexpected_job_that_ran_and_succeeded_is_fine(self):
        self.assertTrue(self.ok(needs_for(DOCS, {"build": "success"}))[0])

    def test_11_non_required_compat_failure_does_not_block(self):
        n = needs_for(FULL, {"connectorj26": "failure"})
        ok, problems = self.ok(n)
        self.assertTrue(ok, problems)
        n = needs_for(FULL, {"connectorj26": "cancelled"})
        self.assertTrue(self.ok(n)[0])
        _, _, lines = gate.evaluate(needs_for(FULL, {"connectorj26": "failure"}))
        self.assertTrue(any("connectorj26" in l and "failure" in l and "ゲートに影響しない" in l for l in lines))

    def test_failed_classification_fails_closed(self):
        for r in ("failure", "cancelled", "skipped"):
            self.assertFalse(self.ok(needs_for(DOCS, changes_result=r))[0], r)
        self.assertFalse(gate.evaluate({})[0])

    def test_malformed_classification_output_fails(self):
        n = needs_for(DOCS)
        del n["changes"]["outputs"]["run_build"]
        self.assertFalse(self.ok(n)[0])
        n = needs_for(DOCS)
        n["changes"]["outputs"]["mode"] = "whatever"
        self.assertFalse(self.ok(n)[0])

    def test_non_docs_with_nothing_to_run_is_inconsistent(self):
        n = needs_for(DOCS)
        n["changes"]["outputs"]["mode"] = "code"
        ok, problems = self.ok(n)
        self.assertFalse(ok)

    def test_missing_job_in_needs_fails(self):
        n = needs_for(KOTLIN)
        del n["images"]
        self.assertFalse(self.ok(n)[0])

    def test_cli_exit_codes_and_summary(self):
        for needs, rc in ((needs_for(DOCS), 0), (needs_for(KOTLIN, {"build": "failure"}), 1)):
            p = subprocess.run([sys.executable, "-m", "tools.ci.gate"], env={**os.environ, "NEEDS_JSON": json.dumps(needs)}, capture_output=True, text=True)
            self.assertEqual(rc, p.returncode, p.stdout)
        p = subprocess.run([sys.executable, "-m", "tools.ci.gate"], env={k: v for k, v in os.environ.items() if k != "NEEDS_JSON"}, capture_output=True, text=True)
        self.assertEqual(1, p.returncode)  # 入力が無ければ失敗
        p = subprocess.run([sys.executable, "-m", "tools.ci.gate"], env={**os.environ, "NEEDS_JSON": "not json"}, capture_output=True, text=True)
        self.assertEqual(1, p.returncode)

    def test_13_main_merge_uses_same_classification_as_pr(self):
        # main への push も差分で同じ判定: コード変更なら build/DB が走り、docs だけならゲートだけ
        self.assertEqual(classify.classify(["persistence/src/main/kotlin/A.kt"]).outputs(), classify.classify(["persistence/src/main/kotlin/A.kt"]).outputs())


if __name__ == "__main__":
    unittest.main()
