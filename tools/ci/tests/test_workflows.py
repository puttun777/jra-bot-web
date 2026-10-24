"""ワークフローと Dependabot 設定の静的な規則検査（YAML ライブラリ無しの行ベース。構文は actionlint が別途検査）。"""
import re
import unittest
from pathlib import Path

from tools.ci import gate

ROOT = Path(__file__).resolve().parents[3]
WORKFLOWS = {p.name: p.read_text(encoding="utf-8") for p in (ROOT / ".github" / "workflows").glob("*.yml")}
CI = WORKFLOWS["ci.yml"]


def job_blocks(text: str) -> dict[str, str]:
    body = text.split("\njobs:\n", 1)[1]
    parts = re.split(r"(?m)^  ([a-z0-9][a-z0-9-]*):\s*$", body)
    return {parts[i]: parts[i + 1] for i in range(1, len(parts), 2)}


class WorkflowRulesTest(unittest.TestCase):
    def test_expected_workflows_exist(self):
        self.assertEqual({"ci.yml", "images.yml", "compat-connectorj26.yml"}, set(WORKFLOWS))

    def test_all_external_actions_pinned_to_commit_sha(self):
        for name, text in WORKFLOWS.items():
            for line in text.splitlines():
                m = re.match(r"\s*-?\s*uses:\s*(\S+)", line)
                if m and not m.group(1).startswith("./"):
                    self.assertRegex(m.group(1), r"@[0-9a-f]{40}$", f"{name}: {line.strip()}")
                    self.assertRegex(line, r"# v\d", f"{name}: 版のコメントが無い（Dependabot が更新する）")

    def test_permissions_are_read_only_and_no_secrets_or_cloud_auth(self):
        for name, text in WORKFLOWS.items():
            self.assertRegex(text, r"(?m)^permissions:\n  contents: read\n", name)
            self.assertNotRegex(text, r"(?m)^\s+[a-z-]+:\s*write\s*$", f"{name}: write 権限")
            self.assertNotIn("secrets.", text.replace("Secrets は", ""), name)
            self.assertNotIn("id-token", text, name)
            self.assertNotRegex(text, r"(?i)pull_request_target|workflow_run:", name)
            self.assertNotRegex(text, r"(?i)google-github-actions|gcloud |docker/login-action|docker push|ghcr\.io|pkg\.dev|aws-actions|azure/login", name)

    def test_checkout_never_persists_credentials(self):
        for name, text in WORKFLOWS.items():
            for m in re.finditer(r"uses: actions/checkout@\S+[^\n]*\n(        with:\n(?:          [^\n]*\n)+)", text):
                self.assertIn("persist-credentials: false", m.group(1), name)
            self.assertEqual(text.count("actions/checkout@"), text.count("persist-credentials: false"), name)

    def test_ci_triggers_have_no_path_filters(self):
        on = CI.split("\npermissions:", 1)[0]
        self.assertNotRegex(on, r"(?m)^\s+paths(-ignore)?:", "workflow 全体の paths フィルタは required check を Pending のままにする")
        for trig in ("pull_request:", "push:", "workflow_dispatch:", "schedule:"):
            self.assertIn(trig, on)

    def test_every_gated_job_depends_on_changes_and_has_an_if(self):
        blocks = job_blocks(CI)
        self.assertEqual({"changes", "build", "release-tools", "golden", "db-integration", "images", "connectorj26", "agent", "agent-windows", "ml-tools", "ml-train", "ci-gate"}, set(blocks))
        for name, b in blocks.items():
            if name in ("changes", "ci-gate"):
                continue
            self.assertRegex(b, r"(?m)^    needs: changes$", name)
            self.assertRegex(b, r"(?m)^    if: needs\.changes\.outputs\.", name)

    def test_gate_covers_every_job_and_always_runs(self):
        blocks = job_blocks(CI)
        g = blocks["ci-gate"]
        self.assertRegex(g, r"(?m)^    if: always\(\)$")
        needs = re.search(r"(?m)^    needs: \[([^\]]+)\]$", g).group(1).replace(" ", "").split(",")
        self.assertEqual(set(blocks) - {"ci-gate"}, set(needs))
        self.assertEqual(set(needs) - {"changes"}, set(gate.REQUIRED) | set(gate.INFORMATIONAL))
        self.assertIn("toJSON(needs)", g)

    def test_gate_job_name_is_stable_for_branch_protection(self):
        self.assertRegex(job_blocks(CI)["ci-gate"], r"(?m)^    name: ci gate$")
    def test_connectorj26_is_not_run_on_every_pr(self):
        self.assertNotIn("connectorj", re.sub(r"(?m)^\s*#.*$", "", job_blocks(CI)["build"]))
        self.assertRegex(job_blocks(CI)["connectorj26"], r"run_connectorj26 == 'true'")
        c = WORKFLOWS["compat-connectorj26.yml"]
        self.assertIn("workflow_dispatch", c)
        self.assertIn("continue-on-error: true", c)
        self.assertIn('-PconnectorJVersion="$CONNECTOR_J_VERSION"', c)
        self.assertIn('mysql: ["8.4", "9.7"]', c)  # 検証自体は削除していない

    def test_default_connector_j_is_unchanged_and_not_26(self):
        self.assertNotRegex((ROOT / "gradle" / "libs.versions.toml").read_text(), r"connector-j\s*=\s*\"26")

    def test_main_pushes_still_get_verified(self):
        on = CI.split("\npermissions:", 1)[0]
        self.assertRegex(on, r"(?m)^  push:\n    branches: \[main\]")
        self.assertIn("cancel-in-progress: ${{ github.event_name == 'pull_request' }}", CI)  # main の途中の実行は止めない

    def test_monthly_schedule_exists(self):
        self.assertRegex(CI, r'cron: "\d+ \d+ 1 \* \*"')

    def test_mandatory_checks_are_kept_in_build_job(self):
        b = job_blocks(CI)["build"]
        for needle in ('java-version: "25"', "./gradlew build", "checkModuleDependencies ktlintCheck", "EXPECTED_JDK", "verify_test_results.py"):
            self.assertIn(needle, b)
        # T3.1: Kotlin と旧 Python のゴールデン比較は build ジョブの中。domain の変更で必ず走る（classify の kotlin_core → run_build）
        for needle in (":golden-runner:run", "tools.golden.kotlin_compare", "tools.golden.verify", "kotlin_coverage.json"):
            self.assertIn(needle, b)
        d = job_blocks(CI)["db-integration"]
        for needle in ("integrationTest", ":persistence:integrationTest", ":bot:integrationTest :ingest:integrationTest :dashboard:integrationTest", "tests < 44", "tests < 167"):
            self.assertIn(needle, d)
        self.assertIn("check-repo-hygiene.sh", job_blocks(CI)["changes"])
        g = job_blocks(CI)["golden"]
        for needle in ("--min-tests 85 --max-skipped 0", "--check", "tools.golden.verify"):
            self.assertIn(needle, g)

    def test_ml_train_job_runs_lint_types_and_tests_and_rejects_zero_or_skipped(self):
        m = job_blocks(CI)["ml-train"]
        self.assertRegex(m, r"run_ml_train == 'true'")
        for needle in ("ruff check", "mypy", "pytest", "requirements-dev.txt", "skipped"):
            self.assertIn(needle, m)
        self.assertNotRegex(m, r"(?i)secrets\.")
        self.assertIn("ml-train", gate.REQUIRED)

    def test_ml_tools_job_checks_signatures_schema_and_datasets_without_secrets(self):
        m = job_blocks(CI)["ml-tools"]
        for needle in ("tools/ml/requirements.txt", "tools.ml.run_tests --min-tests 20 --max-skipped 0", "tools.ml.make_test_artifacts", "--check", "tools.ml.dataset_contract"):
            self.assertIn(needle, m)
        self.assertNotIn("secrets.", m)  # 本番の鍵・Secrets を使わない（テスト専用の決定的な鍵だけ）
        b = job_blocks(CI)["build"]
        for needle in ("domain/build/test-results/test 811", "ModelLoaderTest", "ProbabilityPipelineTest", "FeatureBuilderTest", "GoldenMlTest"):
            self.assertIn(needle, b)

    def test_images_workflow_is_local_only(self):
        t = WORKFLOWS["images.yml"]
        self.assertIn("workflow_call", t)
        self.assertIn("workflow_dispatch", t)
        self.assertIn("docker build", t)
        self.assertIn("tools.release.image_smoke", t)
        self.assertIn("manifest-preview.json", t)
        self.assertNotIn("release-manifest.json", t.replace("本番の release-manifest.json ではない", ""))
        self.assertIn("rc=1", t.replace('test "$rc" = 1', "rc=1"))  # プレビューを本番として検証して拒否されること


class DependabotTest(unittest.TestCase):
    TEXT = (ROOT / ".github" / "dependabot.yml").read_text(encoding="utf-8")

    def entries(self):
        return [e.replace('"', "", 2) for e in re.split(r"(?m)^  - package-ecosystem: ", self.TEXT)[1:]]

    def test_ecosystems_and_directories_match_the_repository(self):
        found = []
        for e in self.entries():
            eco = e.splitlines()[0].strip().strip('"')
            d = re.search(r'(?m)^    directory: "([^"]+)"', e).group(1)
            found.append((eco, d))
            self.assertTrue((ROOT / d.lstrip("/")).is_dir(), d)
            self.assertIn('interval: "weekly"', e, eco)
        self.assertEqual(
            [("gradle", "/"), ("github-actions", "/"), ("docker", "/"), ("pip", "/tools/golden"), ("pip", "/ml")],
            found,
        )
        self.assertTrue((ROOT / "gradle" / "libs.versions.toml").exists())
        self.assertTrue((ROOT / "Dockerfile").exists())
        self.assertTrue((ROOT / "tools" / "golden" / "requirements.txt").exists())
        self.assertTrue((ROOT / "ml" / "requirements.txt").exists())
        self.assertTrue((ROOT / ".github" / "workflows").is_dir())

    def test_updates_are_grouped_and_limited(self):
        for e in self.entries():
            self.assertRegex(e, r"open-pull-requests-limit: [1-3]\b")
            self.assertIn("groups:", e)
            self.assertIn("minor", e)
            self.assertIn("patch", e)

    def test_gated_dependencies_are_ignored(self):
        gradle = [e for e in self.entries() if e.startswith("gradle")][0]
        self.assertIn('dependency-name: "org.springframework.boot*"', gradle)  # Boot 4.2 は VERSIONS §7 のゲート
        self.assertIn('dependency-name: "com.mysql:mysql-connector-j"', gradle)  # Connector/J 26.x は採否が未決
        self.assertIn("version-update:semver-minor", gradle)
        docker = [e for e in self.entries() if e.startswith("docker")][0]
        self.assertIn('dependency-name: "eclipse-temurin"', docker)  # JDK 25 LTS 固定。メジャーは VERSIONS で判断
        self.assertIn("version-update:semver-major", docker)
        for pip in [e for e in self.entries() if e.startswith("pip")]:
            self.assertRegex(pip, r'(?m)^      - dependency-name: "numpy"\n(?!\s+update-types:)')  # numpy は全版を ignore（D-103。golden・ml の固定版と一体）

    def test_no_automerge_and_no_prerelease_opt_in(self):
        code = "\n".join(l for l in self.TEXT.splitlines() if not l.lstrip().startswith("#"))
        self.assertNotRegex(code, r"(?i)auto-?merge|allow:")
        for name, text in WORKFLOWS.items():
            self.assertNotRegex(text, r"(?i)dependabot/fetch-metadata|gh pr merge|enable_pr_auto_merge|--auto", name)


if __name__ == "__main__":
    unittest.main()
