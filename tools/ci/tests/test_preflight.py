import re
import unittest
from pathlib import Path

from tools.ci import classify, consistency, preflight

ROOT = Path(__file__).resolve().parents[3]
CI_YML = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")


def ids(plan):
    return [s.id for s in preflight.steps_for(plan, [])]


class SelectionTests(unittest.TestCase):
    def test_docs_only_runs_only_the_cheap_checks(self):
        got = ids(classify.classify(["docs/PROGRESS.md"]))
        self.assertEqual(got, ["diff-check", "hygiene", "classify-self", "consistency"])

    def test_app_change_selects_build_golden_compare_and_db_apps_on_8_4(self):
        got = ids(classify.classify(["dashboard/src/main/kotlin/jp/jrabot/dashboard/X.kt"]))
        self.assertIn("kotlin-build", got)
        self.assertIn("kotlin-golden", got)
        self.assertIn("db-it-8.4", got)
        self.assertNotIn("db-it-9.7", got)

    def test_db_change_selects_both_mysql_versions(self):
        got = ids(classify.classify(["persistence/src/main/kotlin/jp/jrabot/persistence/X.kt"]))
        self.assertIn("db-it-8.4", got)
        self.assertIn("db-it-9.7", got)

    def test_ci_change_or_unknown_file_selects_everything(self):
        for files in (["tools/ci/classify.py"], ["something/unknown.bin"]):
            got = ids(classify.classify(files))
            for x in ("kotlin-build", "db-it-8.4", "db-it-9.7", "agent", "ml-train", "ml-tools", "images", "golden-py"):
                self.assertIn(x, got, files)

    def test_app_runtime_change_adds_local_image_smoke_even_if_ci_skips_it(self):
        files = ["dashboard/src/main/kotlin/jp/jrabot/dashboard/X.kt"]
        plan = classify.classify(files)
        self.assertFalse(plan.run_images)
        self.assertIn("images", [s.id for s in preflight.steps_for(plan, files)])

    def test_python_only_change_does_not_run_kotlin(self):
        got = ids(classify.classify(["agent/src/jra_agent/queue.py"]))
        self.assertIn("agent", got)
        self.assertIn("kotlin-build", got)  # agent/src は kotlin_app も付く（AgentInteropIntegrationTest）
        got2 = ids(classify.classify(["tools/ml/run_tests.py"]))
        self.assertIn("ml-tools", got2)


class MirrorsCiTests(unittest.TestCase):
    """preflight が実行する Gradle タスク・Python モジュールが ci.yml に実在する（CI の手順を変えたら落ちる = 二重管理のずれを検出）。"""

    def test_gradle_tasks_and_python_modules_exist_in_ci_yml(self):
        plan = classify.full_plan("test")
        for step in preflight.steps_for(plan, []):
            for cmd in step.commands:
                if cmd[0] == "./gradlew":
                    for task in [c for c in cmd[1:] if not c.startswith("-")]:
                        self.assertIn(task, CI_YML, f"{step.id}: ci.yml に Gradle タスク {task} が無い")
                if cmd[:2] == ["python3", "-m"]:
                    self.assertIn(cmd[2], CI_YML, f"{step.id}: ci.yml に {cmd[2]} が無い")
        self.assertIn("check-repo-hygiene.sh", CI_YML)

    def test_verify_steps_exist_in_ci_yml_and_are_extractable(self):
        for step in preflight.steps_for(classify.full_plan("test"), []):
            for name in step.verify:
                block = preflight.ci_verify_block(name)
                self.assertIn("test-results", block, name)  # テスト結果ディレクトリを検査するブロック
                self.assertTrue(block.strip(), name)

    def test_legacy_commit_matches_golden_constants(self):
        text = (ROOT / "tools/golden/constants.py").read_text(encoding="utf-8")
        self.assertIn(preflight.LEGACY_COMMIT, text)

    def test_ci_jdk_matches_workflow(self):
        self.assertRegex(CI_YML, rf'java-version: "{preflight.CI_JDK}"')


class ConsistencyTests(unittest.TestCase):
    def test_repository_is_consistent_without_infra(self):
        errors, _ = consistency.check(ROOT, infra=None)
        self.assertEqual(errors, [])

    def test_bot_run_required_env_is_read_and_covered_by_smoke(self):
        req = consistency.required_bot_run_env(ROOT)
        self.assertGreaterEqual(len(req), 10)
        self.assertTrue(req <= consistency.smoke_bot_run_env(ROOT))

    def test_every_dao_table_has_a_grant_and_a_create(self):
        tables = consistency.doma_tables(ROOT)
        self.assertGreater(len(tables), 15)
        self.assertEqual(tables - consistency.created_tables(ROOT), set())
        self.assertEqual(tables - consistency.granted_tables(ROOT), set())

    def test_schema_versions_follow_flyway(self):
        latest = consistency.flyway_latest(ROOT)
        self.assertEqual(set(consistency.app_schema_versions(ROOT).values()), {latest})

    def test_detects_a_missing_smoke_variable(self):
        import tempfile, shutil
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            for rel in ("db-migration/src/main/resources/db/migration", "bot", "ingest", "dashboard", "tools/release", "persistence/src/main/kotlin", "bot/src/main/kotlin/jp/jrabot/bot"):
                (root / rel).mkdir(parents=True, exist_ok=True)
            shutil.copy(ROOT / "db-migration/src/main/resources/db/migration/V1__baseline.sql", root / "db-migration/src/main/resources/db/migration/")
            (root / "db-migration/src/main/resources/db/migration/R__grants.sql").write_text("", encoding="utf-8")
            for app in ("bot", "ingest", "dashboard"):
                (root / app / "build.gradle.kts").write_text('extra["requiredSchemaVersion"] = "1"\n', encoding="utf-8")
            (root / "bot/src/main/kotlin/jp/jrabot/bot/BotRunSettings.kt").write_text('raw("BOT_RUN_A")\nlong("BOT_RUN_B", 1, 2)\n', encoding="utf-8")
            (root / "tools/release/image_smoke.py").write_text('BOT_RUN_ENV = {\n    "BOT_RUN_A": "1",\n}\n', encoding="utf-8")
            errors, _ = consistency.check(root, infra=None)
            self.assertTrue(any("BOT_RUN_B" in e for e in errors), errors)
            self.assertFalse(any("BOT_RUN_A" in e for e in errors), errors)




class TestEveryVerifyStepOfCiIsCoveredByPreflight(unittest.TestCase):
    """ci.yml の『Verify …』『Comparator smoke test』ステップは、preflight が必ず同じ検査を実行する（CI で落ちるのにローカルが通る偽陰性を防ぐ）。"""

    # ci.yml 側の検査のうち preflight が意図的に実行しないもの（理由つき）
    # 名前ではなく同じコマンドを直接実行しているもの（ci.yml のコマンド → preflight に現れるべき文字列）
    SAME_COMMAND = {"Verify committed fixtures (schema, provenance, hashes, synthetic-only)": "tools.golden.verify"}
    NOT_RUN_LOCALLY = {
        "Verify that the Windows tests actually ran": "Windows ランナーでのみ実行できる",
    }

    def test_all_verify_steps_are_referenced(self):
        import re

        source = (ROOT / "tools/ci/preflight.py").read_text(encoding="utf-8")
        names = re.findall(r"- name: (Verify[^\n]*|Comparator smoke test[^\n]*)\n", CI_YML)
        self.assertTrue(names)
        missing = [n for n in names if n not in self.NOT_RUN_LOCALLY and n not in source and n.split(" (")[0] not in source and self.SAME_COMMAND.get(n, "\0") not in source]
        self.assertEqual(missing, [], f"preflight が参照しない CI の検査: {missing}")

    def test_same_named_verify_steps_are_both_referenced(self):
        """『Verify that the tests actually ran』は agent と ml-train の 2 箇所にある。preflight は両方（nth=0,1）を参照する。"""
        import re

        source = (ROOT / "tools/ci/preflight.py").read_text(encoding="utf-8")
        count = len(re.findall(r"- name: Verify that the tests actually ran\n", CI_YML))
        for nth in range(count):
            self.assertIn(f'ci_verify_block("Verify that the tests actually ran", {nth})', source)


if __name__ == "__main__":
    unittest.main()
