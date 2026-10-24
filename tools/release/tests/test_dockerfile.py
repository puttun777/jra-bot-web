"""Dockerfile / .dockerignore の静的な規則検査（Docker 不要。実ビルドとスモークは images ジョブ）。"""
import re
import unittest
from pathlib import Path

from tools.release import image_smoke, repo_facts

ROOT = repo_facts.repo_root()
DOCKERFILE_RAW = (ROOT / "Dockerfile").read_text(encoding="utf-8")
DOCKERFILE = "\n".join(l for l in DOCKERFILE_RAW.splitlines() if not l.lstrip().startswith("#")) + "\n"  # コメントは検査しない
STAGES = re.findall(r"^FROM (\S+)(?: AS (\S+))?$", DOCKERFILE, re.M)
TARGETS = ("dashboard", "ingest", "bot", "db-migration")  # JVM のターゲット。ml-train（Python）は test_ml_train_target


def stage_body(name: str) -> str:
    m = re.search(rf"^FROM \S+ AS {re.escape(name)}$(.*?)(?=^FROM |\Z)", DOCKERFILE, re.M | re.S)
    assert m, name
    return m.group(1)


class DockerfileTest(unittest.TestCase):
    def test_base_images_are_pinned_by_digest_and_stable(self):
        externals = [ref for ref, alias in STAGES if ref not in {a for _, a in STAGES if a}]
        jvm = [r for r in externals if r.startswith("eclipse-temurin")]
        python = [r for r in externals if r.startswith("python:")]
        self.assertEqual(2, len(jvm))  # JDK（build）と JRE（runtime）
        self.assertEqual(2, len(python))  # ml-train の依存ビルドと実行（同じ digest）
        self.assertEqual(1, len(set(python)))
        self.assertEqual(len(externals), len(jvm) + len(python))
        for ref in jvm:
            self.assertRegex(ref, r"^eclipse-temurin:25-(jdk|jre)-noble@sha256:[0-9a-f]{64}$")
        for ref in python:
            self.assertRegex(ref, r"^python:3\.13\.\d+-slim-bookworm@sha256:[0-9a-f]{64}$")
        for ref in externals:
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

    def test_ml_train_target(self):
        body = stage_body("ml-train")
        m = re.search(r"^ENTRYPOINT (\[.*\])$", body, re.M)
        self.assertEqual(["/opt/venv/bin/python", "-m", "ml_train"], re.findall(r'"([^"]+)"', m.group(1)))
        self.assertEqual(image_smoke.EXPECTED_ENTRYPOINT["ml-train"], re.findall(r'"([^"]+)"', m.group(1)))
        self.assertNotRegex(body, r"(?m)^CMD ")
        self.assertRegex(body, r"(?m)^USER 10001:10001$")
        self.assertNotRegex(body, r"(?i)apt-get|curl|wget|gcc|git |pip install")  # 実行ステージでは何もインストールしない
        deps = stage_body("ml-train-deps")
        self.assertIn("--no-deps --only-binary=:all: -r /tmp/requirements.txt", deps)  # 固定した wheel だけ
        self.assertIn('org.opencontainers.image.revision="${GIT_SHA}"', body)
        self.assertIn("TZ=Asia/Tokyo", body)
        self.assertIn("OMP_NUM_THREADS=1", body)
        copies = re.findall(r"^COPY (?!--from)(?:--\S+ )*(.+) \S+$", body, re.M)
        flat = " ".join(copies)
        for forbidden in ("signing.py", "make_test_artifacts", "tests", ".pkl", ".env"):
            self.assertNotIn(forbidden, flat)
        self.assertIn("ml/src/ml_train", flat)

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
            {
                "/gradlew", "/settings.gradle.kts", "/build.gradle.kts", "/gradle.properties", "/gradle/", "/build-logic/", "/domain/", "/persistence/", "/cloud-gcp/",
                "/db-migration/", "/bot/", "/ingest/", "/dashboard/", "/golden-runner/",
                # ml-train（T3.6-B）: 依存の固定リスト・ソースと、データセット契約の参照実装 2 ファイルだけ。tools 全体・ml 全体（tests）は許可しない
                "/ml/requirements.txt", "/ml/src/", "/tools/__init__.py", "/tools/ml/__init__.py", "/tools/ml/canonical.py", "/tools/ml/dataset_contract.py",
            },
            allowed,
        )
        for forbidden in ("/docs", "/tools", "/tools/", "/.git", "/.github", "/agent", "/ml", "/ml/", "/.env", "/tools/ml/signing.py"):
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
            # 許可はディレクトリ単位（末尾 /）かファイル単位。COPY の元は、許可されたパスそのもの、またはその配下
            self.assertTrue(any(c == a or c.startswith(a + "/") for a in allowed), f"COPY {c} が .dockerignore で許可されていない")


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
