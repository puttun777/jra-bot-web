"""CI 品質ゲート 14 ケース（ユーザー要件 §8）のうち、変更判定で決まるものと、差分取得の fail closed を検証する。"""
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.ci import classify

ALL_JOBS = dict(run_build=True, run_golden=True, run_release=True, run_images=True)


def plan(*files, **kw):
    return classify.classify(list(files))


def jobs(p: classify.Plan) -> dict:
    return dict(mode=p.mode, build=p.run_build, golden=p.run_golden, release=p.run_release, images=p.run_images, c26=p.run_connectorj26, db=p.db_matrix, suites=p.db_suites)


class ScenarioTest(unittest.TestCase):
    def test_01_docs_only(self):
        p = plan("docs/PROGRESS.md", "README.md", "docs/VERSIONS.md", "tools/golden/README.md", "bot/README.md")
        self.assertEqual("docs", p.mode)
        self.assertEqual(dict(mode="docs", build=False, golden=False, release=False, images=False, c26=False, db=[], suites=[]), jobs(p))

    def test_02_kotlin_domain_only(self):
        p = plan("domain/src/main/kotlin/jp/jrabot/domain/Odds.kt")
        self.assertEqual(dict(mode="code", build=True, golden=False, release=False, images=False, c26=False, db=["8.4"], suites=["persistence", "apps"]), jobs(p))

    def test_03_persistence_only(self):
        p = plan("persistence/src/main/kotlin/jp/jrabot/persistence/RaceDao.kt")
        self.assertEqual(["8.4", "9.7"], p.db_matrix)
        self.assertEqual(["migration", "persistence", "apps"], p.db_suites)
        self.assertTrue(p.run_build)
        self.assertFalse(p.run_images)
        self.assertFalse(p.run_connectorj26)

    def test_04_flyway_only(self):
        p = plan("db-migration/src/main/resources/db/migration/V3__x.sql")
        self.assertEqual(["8.4", "9.7"], p.db_matrix)
        self.assertTrue(p.run_release)  # flyway.latest_version の整合
        self.assertTrue(p.run_images)  # 焼き込まれる + スモークで実 migrate
        self.assertFalse(p.run_golden)

    def test_05_ci_config_only(self):
        for f in (".github/workflows/ci.yml", ".github/scripts/verify_test_results.py", "tools/ci/classify.py"):
            p = plan(f)
            self.assertEqual("full", p.mode, f)
            self.assertTrue(p.run_build and p.run_golden and p.run_release and p.run_images, f)
            self.assertEqual(["8.4", "9.7"], p.db_matrix)
            self.assertFalse(p.run_connectorj26, f)  # 非ゲートの互換性確認は CI 設定の変更では走らせない

    def test_06_dockerfile_only(self):
        for f in ("Dockerfile", ".dockerignore"):
            p = plan(f)
            self.assertEqual(dict(mode="code", build=False, golden=False, release=True, images=True, c26=False, db=[], suites=[]), jobs(p), f)

    def test_07_golden_fixture_only(self):
        # フィクスチャが変わったら Python の再生成検証に加え、Kotlin との比較（build ジョブの中）も必要。DB・イメージは不要
        p = plan("tools/golden/fixtures/GD01-EV-001.json")
        self.assertEqual(dict(mode="code", build=True, golden=True, release=False, images=False, c26=False, db=[], suites=[]), jobs(p))

    def test_07b_golden_runner_and_domain_always_run_the_kotlin_comparison(self):
        # :domain の計算を変えたのに Kotlin と旧 Python の比較が漏れないこと（比較は build ジョブの中）
        for f in ("golden-runner/src/main/kotlin/jp/jrabot/golden/GoldenCalls.kt", "golden-runner/build.gradle.kts"):
            self.assertEqual(dict(mode="code", build=True, golden=False, release=False, images=False, c26=False, db=[], suites=[]), jobs(plan(f)), f)
        p = plan("domain/src/main/kotlin/jp/jrabot/domain/decision/EvMath.kt")
        self.assertTrue(p.run_build)
        self.assertFalse(p.run_golden)  # Python 側（旧実装の再生成）は domain の変更に影響されない
        self.assertEqual((["8.4"], ["persistence", "apps"]), (p.db_matrix, p.db_suites))  # 既存の domain の扱いのまま

    def test_07c_golden_docs_only_does_not_build(self):
        self.assertEqual("docs", plan("tools/golden/README.md").mode)

    def test_08_multiple_areas(self):
        p = plan("docs/PLAN.md", "bot/src/main/kotlin/jp/jrabot/bot/BotLauncher.kt", "tools/golden/generate.py", "Dockerfile")
        self.assertEqual(dict(mode="code", build=True, golden=True, release=True, images=True, c26=False, db=["8.4"], suites=["apps"]), jobs(p))
        p = plan("bot/src/main/kotlin/A.kt", "persistence/src/main/kotlin/B.kt")
        self.assertEqual(["8.4", "9.7"], p.db_matrix)  # 広い方に合わせる
        self.assertEqual(["migration", "persistence", "apps"], p.db_suites)

    def test_09_unknown_or_missing_files_fail_closed(self):
        for files in ([], ["new-top-level/thing.bin"], ["docs/a.md", "unmapped/main.py"]):
            p = classify.classify(files)
            self.assertEqual("full", p.mode, files)
            self.assertTrue(p.run_build and p.run_golden and p.run_release and p.run_images and p.db_matrix == ["8.4", "9.7"])

    def test_12_dependabot_prs(self):
        gradle = plan("gradle/libs.versions.toml")
        self.assertTrue(gradle.run_build and gradle.run_connectorj26 and gradle.run_images)
        self.assertEqual(["8.4", "9.7"], gradle.db_matrix)  # Boot BOM・Doma・Flyway の版が DB 連携に影響する
        actions = plan(".github/workflows/ci.yml")
        self.assertEqual("full", actions.mode)
        self.assertEqual("code", plan("tools/golden/requirements.txt").mode)
        self.assertTrue(plan("tools/golden/requirements.txt").run_golden)
        self.assertTrue(plan("Dockerfile").run_images)
        cfg = plan(".github/dependabot.yml")
        self.assertEqual(dict(mode="code", build=False, golden=False, release=True, images=False, c26=False, db=[], suites=[]), jobs(cfg))

    def test_connectorj_triggers(self):
        for f in ("gradle/libs.versions.toml", "persistence/build.gradle.kts", "db-migration/build.gradle.kts", ".github/workflows/compat-connectorj26.yml"):
            self.assertTrue(plan(f).run_connectorj26, f)
        for f in ("persistence/src/main/kotlin/A.kt", "bot/build.gradle.kts", "docs/VERSIONS.md"):
            self.assertFalse(plan(f).run_connectorj26, f)

    def test_dependency_and_spring_config_changes_reach_db_tests(self):
        for f in ("build.gradle.kts", "gradle.properties", "build-logic/src/main/kotlin/SpringBootAppPlugin.kt", "gradle/wrapper/gradle-wrapper.properties", ".editorconfig"):
            p = plan(f)
            self.assertEqual(["8.4", "9.7"], p.db_matrix, f)
            self.assertTrue(p.run_build and p.run_images, f)

    def test_app_build_definition_changes_update_manifest_checks_and_images(self):
        p = plan("ingest/build.gradle.kts")  # requiredSchemaVersion の出どころ
        self.assertTrue(p.run_release and p.run_images and p.run_build)
        self.assertEqual(["8.4"], p.db_matrix)

    def test_contracts_md_runs_release_tools_but_nothing_heavy(self):
        p = plan("docs/CONTRACTS.md")
        self.assertEqual(dict(mode="code", build=False, golden=False, release=True, images=False, c26=False, db=[], suites=[]), jobs(p))

    def test_every_rule_pattern_compiles_and_tags_known(self):
        known = {"docs", "kotlin_app", "kotlin_core", "kotlin_db", "build_cfg", "golden", "kotlin_golden", "release", "docker", "connectorj", "ci", "agent", "agent_windows", "ml_tools"}
        for pattern, tags in classify.RULES:
            self.assertTrue(set(tags) <= known, pattern)


class AgentTest(unittest.TestCase):
    """T3.5: agent/（Python）の変更判定。Windows ランナー（課金 2 倍）は Windows 固有のファイルとフル検証だけ。"""

    def test_agent_docs_only_is_docs(self):
        p = classify.classify(["agent/README.md", "agent/POC.md"])
        self.assertEqual("docs", p.mode)
        self.assertFalse(p.run_agent or p.run_agent_windows)

    def test_agent_tests_and_poc_run_python_only(self):
        for f in ("agent/tests/test_queue.py", "agent/poc/poc_mock.py"):
            p = classify.classify([f])
            self.assertTrue(p.run_agent, f)
            self.assertFalse(p.run_agent_windows or p.run_build or p.db_matrix, f)

    def test_agent_source_also_runs_kotlin_interop_on_8_4_only(self):
        for f in ("agent/src/jra_agent/sender.py", "agent/interop/driver.py", "agent/tests/vectors/signing_vectors.json", "agent/pyproject.toml"):
            p = classify.classify([f])
            self.assertTrue(p.run_agent and p.run_build, f)
            self.assertFalse(p.run_agent_windows, f)
            self.assertEqual(["8.4"], p.db_matrix)
            self.assertEqual(["apps"], p.db_suites)

    def test_windows_specific_files_run_windows_runner(self):
        for f in ("agent/src/jra_agent/winapi.py", "agent/src/jra_agent/lock.py", "agent/src/jra_agent/jvlink/supervisor.py", "agent/tests/test_winapi_windows.py", "agent/scripts/install-task.ps1"):
            self.assertTrue(classify.classify([f]).run_agent_windows, f)

    def test_full_runs_everything_including_agent_jobs(self):
        p = classify.plan_for_event("workflow_dispatch")
        self.assertTrue(p.run_agent and p.run_agent_windows)
        self.assertTrue(classify.classify([".github/workflows/ci.yml"]).run_agent_windows)


class MlToolsTest(unittest.TestCase):
    """T3.6-A: tools/ml（Python）と合成成果物の変更判定。Kotlin のテストも合成成果物を使うので build は走らせるが、DB 統合は要らない。"""

    def test_ml_docs_only_is_docs(self):
        p = classify.classify(["tools/ml/README.md"])
        self.assertEqual("docs", p.mode)
        self.assertFalse(p.run_ml_tools)

    def test_ml_python_runs_only_the_python_job(self):
        p = classify.classify(["tools/ml/canonical.py", "tools/ml/tests/test_artifacts.py"])
        self.assertTrue(p.run_ml_tools)
        self.assertFalse(p.run_build or p.run_golden or p.db_matrix)

    def test_synthetic_artifacts_run_python_and_kotlin_build_without_db(self):
        p = classify.classify(["domain/src/test/resources/ml/models/cal_test_001.json"])
        self.assertTrue(p.run_ml_tools and p.run_build)
        self.assertEqual([], p.db_matrix)

    def test_full_runs_ml_tools(self):
        self.assertTrue(classify.plan_for_event("workflow_dispatch").run_ml_tools)
        self.assertTrue(classify.classify([".github/workflows/ci.yml"]).run_ml_tools)
        self.assertFalse(classify.classify(["bot/src/main/kotlin/A.kt"]).run_ml_tools)


class EventTest(unittest.TestCase):
    def test_14_workflow_dispatch_and_schedule_are_full_with_connectorj(self):
        for ev in ("workflow_dispatch", "schedule"):
            p = classify.plan_for_event(ev)
            self.assertEqual("full", p.mode)
            self.assertTrue(p.run_connectorj26 and p.run_build and p.run_golden and p.run_release and p.run_images)
            self.assertEqual(["8.4", "9.7"], p.db_matrix)

    def test_unexpected_event_fails_closed(self):
        self.assertEqual("full", classify.plan_for_event("issue_comment").mode)
        self.assertEqual("full", classify.plan_for_event("").mode)

    def test_diff_failure_fails_closed(self):
        with tempfile.TemporaryDirectory() as t:
            cwd = os.getcwd()
            os.chdir(t)  # git リポジトリではない → git が失敗
            try:
                for ev, kw in (("pull_request", {"base_sha": "abc"}), ("push", {"before": "abc"}), ("push", {"before": "0" * 40}), ("push", {})):
                    p = classify.plan_for_event(ev, **kw)
                    self.assertEqual("full", p.mode, (ev, kw))
                    self.assertIn("fail closed", p.reason)
            finally:
                os.chdir(cwd)

    def test_outputs_are_strings_for_github(self):
        out = classify.classify(["docs/a.md"]).outputs()
        self.assertTrue(all(isinstance(v, str) for v in out.values()))
        self.assertEqual("[]", out["db_matrix"])
        self.assertEqual(["8.4", "9.7"], json.loads(classify.full_plan("x").outputs()["db_matrix"]))


class GitDiffTest(unittest.TestCase):
    """実際の git リポジトリで、PR（merge コミット）と push の差分取得を検証する。"""

    def sh(self, *a, cwd):
        return subprocess.run(["git", "-c", "user.email=t@example.invalid", "-c", "user.name=t", *a], cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()

    def test_pull_request_and_push_diffs(self):
        with tempfile.TemporaryDirectory() as t:
            r = Path(t)
            self.sh("init", "-q", "-b", "main", cwd=r)
            (r / "README.md").write_text("a")
            self.sh("add", "-A", cwd=r)
            self.sh("commit", "-qm", "base", cwd=r)
            base = self.sh("rev-parse", "HEAD", cwd=r)
            self.sh("checkout", "-qb", "feature", cwd=r)
            (r / "bot" / "src").mkdir(parents=True)
            (r / "bot" / "src" / "A.kt").write_text("x")
            (r / "docs").mkdir()
            (r / "docs" / "a.md").write_text("x")
            self.sh("add", "-A", cwd=r)
            self.sh("commit", "-qm", "feature", cwd=r)
            self.sh("checkout", "-q", "main", cwd=r)
            (r / "main-only.md").write_text("x")  # ベース側の別の変更（PR の差分に含めない）
            self.sh("add", "-A", cwd=r)
            self.sh("commit", "-qm", "main moved", cwd=r)
            main_before = self.sh("rev-parse", "HEAD", cwd=r)
            self.sh("merge", "-q", "--no-ff", "-m", "merge", "feature", cwd=r)
            cwd = os.getcwd()
            os.chdir(r)
            try:
                # pull_request: HEAD = merge コミット。第 1 親（main_before）との差分 = PR の変更だけ
                self.assertEqual(["bot/src/A.kt", "docs/a.md"], sorted(classify.changed_files("pull_request", base_sha=base)))
                # push: before..HEAD
                self.assertEqual(["bot/src/A.kt", "docs/a.md"], sorted(classify.changed_files("push", before=main_before)))
                p = classify.plan_for_event("push", before=main_before)
                self.assertEqual("code", p.mode)
                self.assertEqual(["8.4"], p.db_matrix)
                # ファイル名に空白や日本語があっても -z で正しく扱う
                (r / "docs" / "メモ 1.md").write_text("x")
                self.sh("add", "-A", cwd=r)
                self.sh("commit", "-qm", "jp", cwd=r)
                self.assertEqual(["docs/メモ 1.md"], classify.changed_files("push", before=self.sh("rev-parse", "HEAD~1", cwd=r)))
            finally:
                os.chdir(cwd)


if __name__ == "__main__":
    unittest.main()
