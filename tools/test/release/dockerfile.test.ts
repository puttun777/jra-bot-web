/** Dockerfile / .dockerignore の静的な規則検査（旧 tools/release/tests/test_dockerfile.py の移植。Docker 不要。実ビルドとスモークは images ジョブ）。 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, it } from "node:test";
import { EXPECTED_ENTRYPOINT, FORBIDDEN_FILE, PRIVATE_KEY, SECRET_ASSIGNMENT, SECRET_ENV_NAME } from "../../src/release/image-smoke.js";
import { ROOT } from "../lib/helpers.js";

const RAW = readFileSync(join(ROOT, "Dockerfile"), "utf8");
const DOCKERFILE = `${RAW.split("\n")
  .filter((l) => !l.trimStart().startsWith("#"))
  .join("\n")}\n`; // コメントは検査しない
const STAGES = [...DOCKERFILE.matchAll(/^FROM (\S+)(?: AS (\S+))?$/gm)].map((m) => [m[1] ?? "", m[2] ?? ""] as const);
const TARGETS = ["dashboard", "ingest", "bot", "db-migration"] as const; // JVM のターゲット。ml-train（Python）は別

function stageBody(name: string): string {
  const re = new RegExp(`^FROM \\S+ AS ${name.replace(/[-]/g, "\\-")}$([\\s\\S]*?)(?=^FROM |(?![\\s\\S]))`, "m");
  const m = re.exec(DOCKERFILE);
  assert.ok(m?.[1] !== undefined, name);
  return m[1];
}

function entrypoint(body: string): string[] {
  const m = /^ENTRYPOINT (\[.*\])$/m.exec(body);
  assert.ok(m?.[1] !== undefined);
  return [...m[1].matchAll(/"([^"]+)"/g)].map((x) => x[1] ?? "");
}

describe("dockerfile", () => {
  it("base images are pinned by digest and stable", () => {
    const aliases = new Set(STAGES.map(([, a]) => a).filter((a) => a !== ""));
    const externals = STAGES.map(([r]) => r).filter((r) => !aliases.has(r));
    const jvm = externals.filter((r) => r.startsWith("eclipse-temurin"));
    const python = externals.filter((r) => r.startsWith("python:"));
    assert.equal(jvm.length, 2); // JDK（build）と JRE（runtime）
    assert.equal(python.length, 2); // ml-train の依存ビルドと実行（同じ digest）
    assert.equal(new Set(python).size, 1);
    assert.equal(externals.length, jvm.length + python.length);
    for (const r of jvm) assert.match(r, /^eclipse-temurin:25-(jdk|jre)-noble@sha256:[0-9a-f]{64}$/);
    for (const r of python) assert.match(r, /^python:3\.13\.\d+-slim-bookworm@sha256:[0-9a-f]{64}$/);
    for (const r of externals) assert.doesNotMatch(r, /latest|ea|rc|beta|alpha/i);
  });
  it("all targets exist with an exec form entrypoint and no CMD", () => {
    for (const t of TARGETS) {
      const body = stageBody(t);
      const ep = entrypoint(body);
      assert.equal(ep[0], "java", t); // シェルを挟まない（SIGTERM・終了コード）
      assert.doesNotMatch(body, /^CMD /m, t);
    }
  });
  it("entrypoints match what the smoke expects", () => {
    for (const t of TARGETS) assert.deepEqual(entrypoint(stageBody(t)), EXPECTED_ENTRYPOINT[t], t);
  });
  it("ml-train target", () => {
    const body = stageBody("ml-train");
    assert.deepEqual(entrypoint(body), ["/opt/venv/bin/python", "-m", "ml_train"]);
    assert.deepEqual(entrypoint(body), EXPECTED_ENTRYPOINT["ml-train"]);
    assert.doesNotMatch(body, /^CMD /m);
    assert.match(body, /^USER 10001:10001$/m);
    assert.doesNotMatch(body, /apt-get|curl|wget|gcc|git |pip install/i); // 実行ステージでは何もインストールしない
    assert.ok(stageBody("ml-train-deps").includes("--no-deps --only-binary=:all: -r /tmp/requirements.txt"));
    assert.ok(body.includes('org.opencontainers.image.revision="${GIT_SHA}"'));
    assert.ok(body.includes("TZ=Asia/Tokyo"));
    assert.ok(body.includes("OMP_NUM_THREADS=1"));
    const flat = [...body.matchAll(/^COPY (?!--from)(?:--\S+ )*(.+) \S+$/gm)].map((m) => m[1] ?? "").join(" ");
    for (const forbidden of ["signing.py", "make_test_artifacts", "tests", ".pkl", ".env"]) assert.ok(!flat.includes(forbidden), forbidden);
    assert.ok(flat.includes("ml/src/ml_train"));
  });
  it("runtime is non-root and has no build tools", () => {
    const base = stageBody("runtime-base");
    assert.match(base, /^USER 10001:10001$/m);
    assert.doesNotMatch(base, /apt-get install|gradle|maven|curl|wget|git /i);
    for (const t of TARGETS) {
      const body = stageBody(t);
      assert.doesNotMatch(body, /^USER (root|0)/m, t);
      assert.doesNotMatch(body, /^RUN /m, t); // 実行ステージでは何も実行・インストールしない
      assert.doesNotMatch(body, /COPY --from=build [^\n]*(\/src\/(docs|tools|\.git|\.github)|\.env|\.pem)/, t);
    }
  });
  it("no secrets or healthcheck", () => {
    assert.doesNotMatch(DOCKERFILE, /^\s*(ENV|ARG)\s+\w*(PASSWORD|SECRET|TOKEN|CREDENTIAL|KEY)\w*/m, "秘密を ENV/ARG に置かない");
    assert.doesNotMatch(DOCKERFILE, /^HEALTHCHECK/m);
    assert.doesNotMatch(DOCKERFILE, /REQUIRED_SCHEMA_VERSION/i); // 焼き込みは Gradle（jrabot-app.properties）
  });
  it("build uses the wrapper and git sha labels", () => {
    assert.ok(DOCKERFILE.includes("./gradlew"));
    assert.doesNotMatch(DOCKERFILE, /^RUN (gradle|apt)/m);
    for (const t of TARGETS) {
      const body = stageBody(t);
      assert.ok(body.includes('org.opencontainers.image.revision="${GIT_SHA}"'), t);
      assert.ok(body.includes('org.opencontainers.image.version="${GIT_SHA}"'), t);
    }
  });
  it("no registry push or deploy instructions", () => assert.doesNotMatch(DOCKERFILE, /docker push|gcloud|pkg\.dev/i));
});

describe("dockerignore", () => {
  const LINES = readFileSync(join(ROOT, ".dockerignore"), "utf8")
    .split("\n")
    .map((l) => l.trim())
    .filter((l) => l !== "" && !l.startsWith("#"));
  it("default deny", () => assert.equal(LINES[0], "*"));
  it("only build inputs are allowed", () => {
    const allowed = new Set(LINES.filter((l) => l.startsWith("!")).map((l) => l.slice(1)));
    assert.deepEqual(
      [...allowed].sort(),
      [
        "/gradlew",
        "/settings.gradle.kts",
        "/build.gradle.kts",
        "/gradle.properties",
        "/gradle/",
        "/build-logic/",
        "/domain/",
        "/persistence/",
        "/cloud-gcp/",
        "/db-migration/",
        "/bot/",
        "/ingest/",
        "/dashboard/",
        "/golden-runner/",
        "/ml/requirements.txt",
        "/ml/src/",
        "/tools/__init__.py",
        "/tools/ml/__init__.py",
        "/tools/ml/canonical.py",
        "/tools/ml/dataset_contract.py",
      ].sort(),
    );
    for (const f of [
      "/docs",
      "/tools",
      "/tools/",
      "/.git",
      "/.github",
      "/agent",
      "/ml",
      "/ml/",
      "/.env",
      "/tools/ml/signing.py",
      "/node_modules/",
      "/package.json",
    ]) {
      assert.ok(!allowed.has(f), f);
    }
  });
  it("secret and data patterns excluded", () => {
    for (const p of [
      "**/.env",
      "**/.env.*",
      "**/*.pem",
      "**/*.key",
      "**/*.sqlite",
      "**/*.pkl",
      "**/*.tfstate*",
      "**/service-account*.json",
      "**/build/",
    ]) {
      assert.ok(LINES.includes(p), p);
    }
  });
  it("every copied path is allowed", () => {
    const allowed = LINES.filter((l) => l.startsWith("!/")).map((l) => l.slice(2).replace(/\/$/, ""));
    const copied = new Set<string>();
    for (const m of DOCKERFILE.matchAll(/^COPY (?!--from)(?:--\S+ )*(\S+) \S+$/gm))
      copied.add((m[1] ?? "").replace(/\/$/, "").replace(/^[./]+/, ""));
    for (const m of DOCKERFILE.matchAll(/^COPY (?!--from)(?:--\S+ )*(.+) \.\/$/gm))
      for (const s of (m[1] ?? "").split(/\s+/)) copied.add(s);
    assert.ok(copied.size > 0);
    for (const c of copied)
      assert.ok(
        allowed.some((a) => c === a || c.startsWith(`${a}/`)),
        `COPY ${c} が .dockerignore で許可されていない`,
      );
  });
});

describe("smoke patterns", () => {
  it("forbidden file names", () => {
    for (const bad of [
      "app/.env",
      "app/.env.prod",
      "home/x/id_rsa",
      "app/service-account-key.json",
      "tmp/creds.pem",
      "opt/data/races.sqlite",
      "app/model.pkl",
      "srv/dump.bak",
      "app/x.pdf",
      "app/terraform.tfstate",
    ]) {
      assert.match(bad, FORBIDDEN_FILE, bad);
    }
    for (const ok of [
      "app/app.jar",
      "app/lib/flyway-core-13.11.1.jar",
      "app/lib/kotlin-stdlib-2.4.21.jar",
      "BOOT-INF/classes/application.properties",
      "BOOT-INF/lib/jackson-core.jar",
      "BOOT-INF/classes/db/migration/V1__baseline.sql",
    ]) {
      assert.doesNotMatch(ok, FORBIDDEN_FILE, ok);
    }
  });
  it("secret assignment detection", () => {
    assert.match("db.password=hunter2", SECRET_ASSIGNMENT);
    assert.match("api_key: abcdef", SECRET_ASSIGNMENT);
    assert.doesNotMatch("db.password=${DB_PASSWORD}", SECRET_ASSIGNMENT);
    assert.doesNotMatch("server.port=${PORT:8080}", SECRET_ASSIGNMENT);
    assert.match(`-----BEGIN ${"RSA PRIVATE KEY-----"}`, PRIVATE_KEY);
    assert.match("DB_PASSWORD", SECRET_ENV_NAME);
    assert.doesNotMatch("JAVA_HOME", SECRET_ENV_NAME);
  });
});
