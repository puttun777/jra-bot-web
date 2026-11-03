/** ワークフロー・composite action・Dependabot 設定の静的な規則検査（旧 tools/ci/tests/test_workflows.py の移植。YAML として解析する）。 */
import assert from "node:assert/strict";
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, it } from "node:test";
import { parse as parseYaml } from "yaml";
import { INFORMATIONAL, REQUIRED } from "../../src/ci/gate.js";
import { isRecord } from "../../src/lib/json.js";
import { ROOT } from "../lib/helpers.js";

const WF_DIR = join(ROOT, ".github", "workflows");
const WORKFLOWS: Record<string, string> = Object.fromEntries(
  readdirSync(WF_DIR)
    .filter((f) => f.endsWith(".yml"))
    .map((f) => [f, readFileSync(join(WF_DIR, f), "utf8")]),
);
const ACTIONS: Record<string, string> = Object.fromEntries(
  readdirSync(join(ROOT, ".github", "actions")).map((d) => [d, readFileSync(join(ROOT, ".github", "actions", d, "action.yml"), "utf8")]),
);
const CI = WORKFLOWS["ci.yml"] ?? "";

function yamlOf(text: string): Record<string, unknown> {
  const v: unknown = parseYaml(text);
  assert.ok(isRecord(v));
  return v;
}

function jobs(): Record<string, Record<string, unknown>> {
  const j = yamlOf(CI)["jobs"];
  assert.ok(isRecord(j));
  const out: Record<string, Record<string, unknown>> = {};
  for (const [k, v] of Object.entries(j)) {
    assert.ok(isRecord(v), k);
    out[k] = v;
  }
  return out;
}

function job(name: string): Record<string, unknown> {
  const j = jobs()[name];
  assert.ok(j, name);
  return j;
}

/** ジョブの YAML 本文（コメントを除いた run・uses などの文字列をすべて連結） */
function jobText(name: string): string {
  return JSON.stringify(job(name));
}

describe("workflow rules", () => {
  it("expected workflows and composite actions exist", () => {
    assert.deepEqual(Object.keys(WORKFLOWS).sort(), ["ci.yml", "compat-connectorj26.yml", "images.yml"]);
    assert.deepEqual(Object.keys(ACTIONS).sort(), ["setup-tools"]);
  });
  it("all external actions are pinned to a commit sha with a version comment", () => {
    for (const [name, text] of [...Object.entries(WORKFLOWS), ...Object.entries(ACTIONS)]) {
      for (const line of text.split("\n")) {
        const m = /^\s*-?\s*uses:\s*(\S+)/.exec(line);
        if (m?.[1] === undefined || m[1].startsWith("./")) continue;
        assert.match(m[1], /@[0-9a-f]{40}$/, `${name}: ${line.trim()}`);
        assert.match(line, /# v\d/, `${name}: 版のコメントが無い（Dependabot が更新する）`);
      }
    }
  });
  it("permissions are read-only and there are no secrets or cloud auth", () => {
    for (const [name, text] of Object.entries(WORKFLOWS)) {
      assert.match(text, /^permissions:\n {2}contents: read\n/m, name);
      assert.doesNotMatch(text, /^\s+[a-z-]+:\s*write\s*$/m, `${name}: write 権限`);
      assert.ok(!text.replaceAll("Secrets は", "").includes("secrets."), name);
      assert.ok(!text.includes("id-token"), name);
      assert.doesNotMatch(text, /pull_request_target|workflow_run:/i, name);
      assert.doesNotMatch(
        text,
        /google-github-actions|gcloud |docker\/login-action|docker push|ghcr\.io|pkg\.dev|aws-actions|azure\/login/i,
        name,
      );
    }
  });
  it("checkout never persists credentials", () => {
    for (const [name, text] of Object.entries(WORKFLOWS)) {
      assert.equal(text.split("actions/checkout@").length, text.split("persist-credentials: false").length, name);
    }
  });
  it("npm install never runs install scripts and uses the lockfile", () => {
    const a = ACTIONS["setup-tools"] ?? "";
    assert.match(a, /npm ci --ignore-scripts/);
    assert.doesNotMatch(a, /npm install/);
    const pkg: unknown = JSON.parse(readFileSync(join(ROOT, "package.json"), "utf8"));
    assert.ok(isRecord(pkg) && isRecord(pkg["engines"]));
    const node = /node-version: "(\d+)\.\d+\.\d+"/.exec(a)?.[1];
    assert.ok(String(pkg["engines"]["node"]).includes(`<${Number(node) + 1}`), "setup-node の版と package.json の engines が一致しない");
    for (const deps of [pkg["dependencies"], pkg["devDependencies"]]) {
      assert.ok(isRecord(deps));
      for (const [n, v] of Object.entries(deps)) assert.match(String(v), /^\d+\.\d+\.\d+$/, `${n} は完全固定の版でなければならない`);
    }
    assert.ok(existsSync(join(ROOT, "package-lock.json")));
  });
  it("ci triggers have no path filters", () => {
    const on = CI.split("\npermissions:")[0] ?? "";
    assert.doesNotMatch(on, /^\s+paths(-ignore)?:/m, "workflow 全体の paths フィルタは required check を Pending のままにする");
    for (const trig of ["pull_request:", "push:", "workflow_dispatch:", "schedule:"]) assert.ok(on.includes(trig));
  });
  it("every gated job depends on changes and has an if", () => {
    const js = jobs();
    assert.deepEqual(
      Object.keys(js).sort(),
      ["agent", "agent-windows", "build", "changes", "ci-gate", "connectorj26", "db-integration", "images", "release-tools"].sort(),
    );
    for (const [name, b] of Object.entries(js)) {
      if (name === "changes" || name === "ci-gate") continue;
      assert.equal(b["needs"], "changes", name);
      assert.match(String(b["if"]), /^needs\.changes\.outputs\./, name);
    }
  });
  it("gate covers every job and always runs", () => {
    const g = job("ci-gate");
    assert.equal(g["if"], "always()");
    const needs = g["needs"];
    assert.ok(Array.isArray(needs));
    const all = Object.keys(jobs()).filter((n) => n !== "ci-gate");
    assert.deepEqual([...needs].map(String).sort(), all.sort());
    assert.deepEqual(
      needs
        .map(String)
        .filter((n) => n !== "changes")
        .sort(),
      [...Object.keys(REQUIRED), ...INFORMATIONAL].sort(),
    );
    assert.ok(jobText("ci-gate").includes("toJSON(needs)"));
    assert.ok(jobText("ci-gate").includes("main.js gate"));
  });
  it("gate job name is stable for branch protection", () => assert.equal(job("ci-gate")["name"], "ci gate"));
  it("connectorj26 is not run on every pr", () => {
    assert.ok(!jobText("build").includes("connectorj"));
    assert.match(String(job("connectorj26")["if"]), /run_connectorj26 == 'true'/);
    const c = WORKFLOWS["compat-connectorj26.yml"] ?? "";
    assert.ok(c.includes("workflow_dispatch"));
    assert.ok(c.includes("continue-on-error: true"));
    assert.ok(c.includes('-PconnectorJVersion="$CONNECTOR_J_VERSION"'));
    assert.ok(c.includes('mysql: ["8.4", "9.7"]')); // 検証自体は削除していない
  });
  it("default connector/j is unchanged and not 26", () => {
    assert.doesNotMatch(readFileSync(join(ROOT, "gradle", "libs.versions.toml"), "utf8"), /connector-j\s*=\s*"26/);
  });
  it("main pushes still get verified", () => {
    const on = CI.split("\npermissions:")[0] ?? "";
    assert.match(on, /^ {2}push:\n {4}branches: \[main\]/m);
    assert.ok(CI.includes("cancel-in-progress: ${{ github.event_name == 'pull_request' }}"));
  });
  it("monthly schedule exists", () => assert.match(CI, /cron: "\d+ \d+ 1 \* \*"/));
  it("mandatory checks are kept in build and db jobs", () => {
    const b = jobText("build");
    for (const needle of [
      '"java-version":"25"',
      "./gradlew build",
      "checkModuleDependencies ktlintCheck",
      "EXPECTED_JDK",
      "verify-junit",
      ":golden-runner:run",
      "check --fixtures",
      "tools/golden/catalog.json",
    ]) {
      assert.ok(b.includes(needle), needle);
    }
    const d = jobText("db-integration");
    for (const needle of [
      "integrationTest",
      ":persistence:integrationTest",
      ":bot:integrationTest :ingest:integrationTest :dashboard:integrationTest",
      "--min 44",
      "--min 179",
    ]) {
      assert.ok(d.includes(needle), needle);
    }
    assert.ok(jobText("changes").includes("check-repo-hygiene.sh"));
    assert.ok(!("golden" in jobs()), "Python の golden ジョブは削除（D-258）");
    assert.ok(!/tools\.golden\.|tools\/golden\/requirements/.test(CI));
  });
  it("tools job lints, type checks and requires the unit test count", () => {
    const r = jobText("release-tools");
    assert.ok(r.includes("./.github/actions/setup-tools"));
    assert.ok(r.includes("npm run --silent lint"));
    assert.ok(r.includes("node tools/run-tests.mjs"));
    const runner = readFileSync(join(ROOT, "tools", "run-tests.mjs"), "utf8");
    assert.match(runner, /const MIN = \d{3};/);
    assert.match(runner, /verify-junit/);
  });
  it("no Python ML job remains and the Kotlin ML tests are counted by the build job", () => {
    assert.ok(!("ml-train" in REQUIRED) && !("ml-tools" in REQUIRED));
    assert.ok(!/ml\/requirements|tools\.ml\.|ML_TRAIN_PYTHON/.test(CI));
    const b = jobText("build");
    for (const needle of [
      "domain/build/test-results/test --min 822",
      "ml-train/build/test-results/test --min 47",
      "TrainJobTest",
      "LegacyPickleTest",
      "ModelLoaderTest",
      "ProbabilityPipelineTest",
      "FeatureBuilderTest",
      "GoldenMlTest",
    ])
      assert.ok(b.includes(needle), needle);
  });
  it("images workflow is local only", () => {
    const t = WORKFLOWS["images.yml"] ?? "";
    for (const needle of ["workflow_call", "workflow_dispatch", "docker build", "main.js image-smoke", "manifest-preview.json"])
      assert.ok(t.includes(needle), needle);
    assert.ok(!t.replaceAll("本番の release-manifest.json ではない", "").includes("release-manifest.json"));
    assert.ok(t.includes('test "$rc" = 1')); // プレビューを本番として検証して拒否されること
  });
  it("no workflow calls the removed python ci/release tools", () => {
    for (const [name, text] of Object.entries(WORKFLOWS)) {
      assert.doesNotMatch(text, /tools\.ci\.|tools\.release\.|verify_test_results\.py/, name);
    }
  });
});

describe("dependabot", () => {
  const TEXT = readFileSync(join(ROOT, ".github", "dependabot.yml"), "utf8");
  const doc = yamlOf(TEXT);
  const updates = Array.isArray(doc["updates"]) ? doc["updates"].filter(isRecord) : [];

  it("ecosystems and directories match the repository", () => {
    const found: [string, string][] = [];
    for (const e of updates) {
      const eco = String(e["package-ecosystem"]);
      const dirs = Array.isArray(e["directories"]) ? e["directories"].map(String) : [String(e["directory"])];
      for (const d of dirs) {
        found.push([eco, d]);
        const base = d.replace(/\/\*$/, "");
        assert.ok(statSync(join(ROOT, base.replace(/^\//, ""))).isDirectory(), d);
      }
      assert.ok(isRecord(e["schedule"]) && e["schedule"]["interval"] === "weekly", eco);
    }
    assert.deepEqual(found, [
      ["gradle", "/"],
      ["github-actions", "/"],
      ["github-actions", "/.github/actions/*"],
      ["npm", "/"],
      ["docker", "/"],
    ]);
  });
  it("updates are grouped and limited", () => {
    for (const e of updates) {
      assert.ok(Number(e["open-pull-requests-limit"]) >= 1 && Number(e["open-pull-requests-limit"]) <= 3);
      const groups = JSON.stringify(e["groups"]);
      assert.ok(groups.includes("minor") && groups.includes("patch"));
    }
  });
  it("gated dependencies are ignored", () => {
    const ignores = (eco: string): string => JSON.stringify(updates.filter((e) => e["package-ecosystem"] === eco).map((e) => e["ignore"]));
    assert.ok(ignores("gradle").includes("org.springframework.boot*"));
    assert.ok(ignores("gradle").includes("com.mysql:mysql-connector-j"));
    assert.ok(ignores("gradle").includes("version-update:semver-minor"));
    assert.ok(ignores("docker").includes("eclipse-temurin"));
    assert.ok(ignores("npm").includes('"typescript"') && ignores("npm").includes("@types/node"));
    for (const e of updates.filter((x) => x["package-ecosystem"] === "pip")) {
      const ig = Array.isArray(e["ignore"]) ? e["ignore"].filter(isRecord) : [];
      const numpy = ig.find((i) => i["dependency-name"] === "numpy");
      assert.ok(numpy !== undefined && numpy["update-types"] === undefined); // numpy は全版を ignore（D-103）
    }
  });
  it("no automerge and no prerelease opt in", () => {
    const code = TEXT.split("\n")
      .filter((l) => !l.trimStart().startsWith("#"))
      .join("\n");
    assert.doesNotMatch(code, /auto-?merge|allow:/i);
    for (const [name, text] of Object.entries(WORKFLOWS)) {
      assert.doesNotMatch(text, /dependabot\/fetch-metadata|gh pr merge|enable_pr_auto_merge|--auto/i, name);
    }
  });
});
