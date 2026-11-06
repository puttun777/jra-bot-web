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
const TARGETS = ["dashboard", "ingest", "bot", "db-migration", "ml-train"] as const; // すべて JVM（ml-train も Kotlin: D-257）

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
    const node = externals.filter((r) => r.startsWith("node:"));
    assert.equal(jvm.length, 2); // JDK（build）と JRE（runtime）
    assert.equal(node.length, 1); // 画面の TypeScript の変換だけ（frontend ステージ。D-263）
    assert.equal(externals.length, jvm.length + node.length); // Python などの他のベースイメージは無い（D-250・D-257）
    for (const r of jvm) assert.match(r, /^eclipse-temurin:25-(jdk|jre)-noble@sha256:[0-9a-f]{64}$/);
    for (const r of node) assert.match(r, /^node:24\.\d+\.\d+-trixie-slim@sha256:[0-9a-f]{64}$/);
    // 版の部分（@sha256 の前）だけを見る（digest の 16 進数に "ea" などが現れ得る）
    for (const r of externals) assert.doesNotMatch(r.split("@")[0] ?? r, /latest|ea|rc|beta|alpha/i);
  });
  it("the Node stage only compiles the dashboard TypeScript and only the build stage uses its output (D-263)", () => {
    // Node の版は CI（setup-tools の actions/setup-node）と同じ。npm ci は lockfile どおり・install スクリプトを実行しない
    const setup = readFileSync(join(ROOT, ".github", "actions", "setup-tools", "action.yml"), "utf8");
    const ciNode = /node-version: "([0-9.]+)"/.exec(setup)?.[1];
    assert.ok(ciNode !== undefined);
    assert.ok(STAGES.some(([r, a]) => a === "frontend" && r.startsWith(`node:${ciNode}-trixie-slim@sha256:`)));
    const fe = stageBody("frontend");
    assert.match(fe, /npm ci --ignore-scripts --no-audit --no-fund/);
    assert.match(fe, /node node_modules\/typescript\/bin\/tsc -p dashboard\/tsconfig\.json/);
    const copies = [...fe.matchAll(/^COPY (.+) \S+$/gm)].map((m) => m[1]);
    assert.deepEqual(copies, ["package.json package-lock.json", "dashboard/tsconfig.json", "dashboard/src/main/ts"]);
    assert.equal([...DOCKERFILE.matchAll(/--from=frontend\b/g)].length, 1);
    const build = stageBody("build");
    assert.match(build, /COPY --from=frontend \/src\/dashboard\/build\/generated\/frontend \.\/dashboard\/build\/generated\/frontend/);
    assert.match(build, /-PfrontendPrebuilt=true/);
    for (const t of TARGETS) assert.doesNotMatch(stageBody(t), /\bnode\b|\bnpm\b/i, t); // 実行イメージに Node は入らない
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
  it("ml-train target is the Kotlin jar built in the shared build stage", () => {
    const body = stageBody("ml-train");
    assert.match(body, /COPY --from=build --chmod=0444 \/src\/ml-train\/build\/libs\/ml-train\.jar \/app\/app\.jar/);
    assert.ok(stageBody("build").includes(":ml-train:bootJar"));
    assert.ok(!/python|venv|pip /i.test(DOCKERFILE), "Dockerfile に Python が残っていない");
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
        "/package.json",
        "/package-lock.json",
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
        "/ml-train/",
        "/legacy-import/",
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
      "/node_modules/", // npm の依存はイメージの文脈に入れない（frontend ステージが npm ci で取得する。package.json と lockfile だけ許可: D-263）
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
