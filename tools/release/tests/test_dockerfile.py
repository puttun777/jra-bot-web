"""Dockerfile / .dockerignore の静的な規則検査（Docker 不要。実ビルドとスモークは images ジョブ）。"""
import re
import unittest
from pathlib import Path

from tools.release import image_smoke, repo_facts

ROOT = repo_facts.repo_root()
DOCKERFILE_RAW = (ROOT / "Dockerfile").read_text(encoding="utf-8")
DOCKERFILE = "\n".join(l for l in DOCKERFILE_RAW.splitlines() if not l.lstrip().startswith("#")) + "\n"  # コメントは検査しない
STAGES = re.findall(r"^FROM (\S+)(?: AS (\S+))?$", DOCKERFILE, re.M)
TARGETS = ("dashboard", "ingest", "bot", "db-migration")


def stage_body(name: str) -> str:
    m = re.search(rf"^FROM \S+ AS {re.escape(name)}$(.*?)(?=^FROM |\Z)", DOCKERFILE, re.M | re.S)
    assert m, name
    return m.group(1)


class DockerfileTest(unittest.TestCase):
    def test_base_images_are_pinned_by_digest_and_stable(self):
        externals = [ref for ref, alias in STAGES if ref not in {a for _, a in STAGES if a}]
        self.assertEqual(2, len(externals))  # JDK（build）と JRE（runtime）
        for ref in externals:
            self.assertRegex(ref, r"^eclipse-temurin:25-(jdk|jre)-noble@sha256:[0-9a-f]{64}$")
            self.assertNotRegex(ref, r"(?i)latest|ea|rc|beta|alpha")

    def test_all_targets_exist_with_exec_form_entrypoint(self):
        for t in TARGETS:
            body = stage_body(t)
            m = re.search(r"^ENTRYPOINT (\[.*\])$", body, re.M)
            self.assertIsNotNone(m, t)
            self.assertNotIn("sh", re.findall(r'"([^"]+)"', m.group(1))[:1], t)  # シェルを挟まない（SIGTERM・終了コード）
            self.assertTrue(re.findall(r'"([^"]+)"', m.group(1))[0] == "java", t)
            self.assertNotRegex(body, r"(?m)^CMD ", t)

    def test_entrypoints_match_what_the_smoke_expects(self):
        for t in TARGETS:
            declared = re.findall(r'"([^"]+)"', re.search(r"^ENTRYPOINT (\[.*\])$", stage_body(t), re.M).group(1))
            self.assertEqual(image_smoke.EXPECTED_ENTRYPOINT[t], declared, t)

    def test_runtime_is_non_root_and_has_no_build_tools(self):
        base = stage_body("runtime-base")
        self.assertRegex(base, r"(?m)^USER 10001:10001$")
        self.assertNotRegex(base, r"(?i)apt-get install|gradle|maven|curl|wget|git ")
        for t in TARGETS:
            body = stage_body(t)
            self.assertNotRegex(body, r"(?m)^USER (root|0)", t)
            self.assertNotRegex(body, r"(?m)^RUN ", t)  # 実行ステージでは何も実行・インストールしない
            self.assertNotRegex(body, r"COPY --from=build [^\n]*(/src/(docs|tools|\.git|\.github)|\.env|\.pem)", t)

    def test_no_secrets_or_healthcheck(self):
        self.assertNotRegex(DOCKERFILE, r"(?m)^\s*(ENV|ARG)\s+\w*(PASSWORD|SECRET|TOKEN|CREDENTIAL|KEY)\w*", "秘密を ENV/ARG に置かない")
        self.assertNotRegex(DOCKERFILE, r"(?m)^HEALTHCHECK")
        self.assertNotRegex(DOCKERFILE, r"(?i)REQUIRED_SCHEMA_VERSION")  # 焼き込みは Gradle（jrabot-app.properties）。Dockerfile で上書きしない

    def test_build_uses_wrapper_and_git_sha_label(self):
        self.assertIn("./gradlew", DOCKERFILE)
        self.assertNotRegex(DOCKERFILE, r"(?m)^RUN (gradle|apt)")
        for t in TARGETS:
            body = stage_body(t)
            self.assertIn('org.opencontainers.image.revision="${GIT_SHA}"', body, t)
            self.assertIn('org.opencontainers.image.version="${GIT_SHA}"', body, t)

    def test_no_registry_push_or_deploy_instructions(self):
        self.assertNotRegex(DOCKERFILE, r"(?i)docker push|gcloud|pkg\.dev")


class DockerignoreTest(unittest.TestCase):
    LINES = [l.strip() for l in (ROOT / ".dockerignore").read_text(encoding="utf-8").splitlines() if l.strip() and not l.startswith("#")]

    def test_default_deny(self):
        self.assertEqual("*", self.LINES[0])

    def test_only_build_inputs_are_allowed(self):
        allowed = {l[1:] for l in self.LINES if l.startswith("!")}
        self.assertEqual(
            {"/gradlew", "/settings.gradle.kts", "/build.gradle.kts", "/gradle.properties", "/gradle/", "/build-logic/", "/domain/", "/persistence/", "/cloud-gcp/", "/db-migration/", "/bot/", "/ingest/", "/dashboard/", "/golden-runner/"},
            allowed,
        )
        for forbidden in ("/docs", "/tools", "/.git", "/.github", "/agent", "/ml", "/.env"):
            self.assertNotIn(forbidden, allowed)

    def test_secret_and_data_patterns_excluded(self):
        for pat in ("**/.env", "**/.env.*", "**/*.pem", "**/*.key", "**/*.sqlite", "**/*.pkl", "**/*.tfstate*", "**/service-account*.json", "**/build/"):
            self.assertIn(pat, self.LINES)

    def test_every_copied_path_is_allowed(self):
        allowed = {l[2:].rstrip("/") for l in self.LINES if l.startswith("!/")}
        copied = set()
        for src in re.findall(r"^COPY (?!--from)(?:--\S+ )*(\S+) \S+$", DOCKERFILE, re.M):
            copied.add(src.rstrip("/").lstrip("./"))
        for src in re.findall(r"^COPY (?!--from)(?:--\S+ )*(.+) \./$", DOCKERFILE, re.M):
            copied |= {s for s in src.split()}
        self.assertTrue(copied)
        for c in copied:
            self.assertIn(c, allowed, f"COPY {c} が .dockerignore で許可されていない")


class SmokePatternTest(unittest.TestCase):
    def test_forbidden_file_names(self):
        for bad in ("app/.env", "app/.env.prod", "home/x/id_rsa", "app/service-account-key.json", "tmp/creds.pem", "opt/data/races.sqlite", "app/model.pkl", "srv/dump.bak", "app/x.pdf", "app/terraform.tfstate"):
            self.assertTrue(image_smoke.FORBIDDEN_FILE.search(bad), bad)
        for ok in ("app/app.jar", "app/lib/flyway-core-13.11.1.jar", "app/lib/kotlin-stdlib-2.4.21.jar", "BOOT-INF/classes/application.properties", "BOOT-INF/lib/jackson-core.jar", "BOOT-INF/classes/db/migration/V1__baseline.sql"):
            self.assertFalse(image_smoke.FORBIDDEN_FILE.search(ok), ok)

    def test_secret_assignment_detection(self):
        self.assertTrue(image_smoke.SECRET_ASSIGNMENT.search("db.password=hunter2"))
        self.assertTrue(image_smoke.SECRET_ASSIGNMENT.search("api_key: abcdef"))
        self.assertFalse(image_smoke.SECRET_ASSIGNMENT.search("db.password=${DB_PASSWORD}"))
        self.assertFalse(image_smoke.SECRET_ASSIGNMENT.search("server.port=${PORT:8080}"))
        self.assertTrue(image_smoke.PRIVATE_KEY.search("-----BEGIN " + "RSA PRIVATE KEY-----"))
        self.assertTrue(image_smoke.SECRET_ENV_NAME.search("DB_PASSWORD"))
        self.assertFalse(image_smoke.SECRET_ENV_NAME.search("JAVA_HOME"))


if __name__ == "__main__":
    unittest.main()
