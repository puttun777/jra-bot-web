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
const RELEASE_WF = "release.yml";

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
    assert.deepEqual(Object.keys(WORKFLOWS).sort(), ["ci.yml", "compat-connectorj26.yml", "images.yml", "release.yml"]);
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
  it("permissions are read-only and there are no secrets or cloud auth (except the manual release workflow, checked below)", () => {
    for (const [name, text] of Object.entries(WORKFLOWS)) {
      if (name === RELEASE_WF) continue;
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
  it("a run block that pipes into tee keeps the exit status (pipefail), so a failing check is not hidden", () => {
    // shell: bash（GitHub は bash --noprofile --norc -eo pipefail {0}）か、tee より前の行の set -o pipefail。コメント・文字列の中の記述は数えない
    const shellOf = (node: unknown): unknown =>
      isRecord(node) && isRecord(node["defaults"]) && isRecord(node["defaults"]["run"]) ? node["defaults"]["run"]["shell"] : undefined;
    const keepsStatus = (run: string): boolean => {
      const lines = run.split("\n").map((l) => l.trim());
      const teeAt = lines.findIndex((l) => !l.startsWith("#") && /\|&?\s*(sudo\s+)?tee\b/.test(l));
      if (teeAt < 0) return true;
      return lines.slice(0, teeAt).some((l) => /^set\s+-[a-z]*o\s+pipefail\b/.test(l) || /^set\s+-o\s+pipefail\b/.test(l));
    };
    for (const [file, text] of Object.entries(WORKFLOWS)) {
      const doc: unknown = parseYaml(text);
      const jobsNode = isRecord(doc) && isRecord(doc["jobs"]) ? doc["jobs"] : {};
      for (const [jobName, j] of Object.entries(jobsNode)) {
        const steps = isRecord(j) && Array.isArray(j["steps"]) ? j["steps"] : [];
        for (const s of steps) {
          if (!isRecord(s) || typeof s["run"] !== "string") continue;
          const shell = s["shell"] ?? shellOf(j) ?? shellOf(doc);
          assert.ok(shell === "bash" || keepsStatus(s["run"]), `${file}/${jobName}/${String(s["name"])}`);
        }
      }
    }
    // 判定そのものの試験（抜け道: コメント・後置・|& tee）
    assert.equal(keepsStatus("set -o pipefail\nx | tee a"), true);
    assert.equal(keepsStatus("set -euo pipefail\nx | tee a"), true);
    assert.equal(keepsStatus("# set -o pipefail\nx | tee a"), false);
    assert.equal(keepsStatus('echo "set -o pipefail"\nx | tee a'), false);
    assert.equal(keepsStatus("x | tee a\nset -o pipefail"), false);
    assert.equal(keepsStatus("x |& tee a"), false);
    assert.equal(keepsStatus("x | sudo tee a"), false);
    assert.equal(keepsStatus("# x | tee a\necho ok"), true);
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
      "--min 182",
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
    // 画面（TypeScript。D-263）の型検査と単体試験も同じジョブで、件数を検査する
    assert.ok(r.includes("npm run --silent build:frontend") && r.includes("npm run --silent test:frontend"));
    const fe = readFileSync(join(ROOT, "dashboard", "run-tests.mjs"), "utf8");
    assert.match(fe, /const MIN = \d{2,};/);
    assert.match(fe, /verify-junit/);
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
    assert.ok(WORKFLOWS[RELEASE_WF], "release.yml");
    for (const [name, text] of Object.entries(WORKFLOWS)) {
      assert.doesNotMatch(text, /tools\.ci\.|tools\.release\.|verify_test_results\.py/, name);
    }
  });
});

describe("release workflow (L9. D-276): the only workflow with write permissions and cloud auth", () => {
  const T = WORKFLOWS[RELEASE_WF] ?? "";
  const Y = yamlOf(T);
  const job = (() => {
    const j = Y["jobs"];
    assert.ok(isRecord(j) && isRecord(j["publish"]));
    assert.deepEqual(Object.keys(j), ["publish"]);
    return j["publish"];
  })();
  const steps = (() => {
    const st = job["steps"];
    assert.ok(Array.isArray(st));
    return st.filter(isRecord);
  })();
  const stepIndex = (needle: string): number => steps.findIndex((s) => JSON.stringify(s).includes(needle));

  it("runs only by hand (workflow_dispatch), never on push/PR/schedule, one at a time", () => {
    const on = Y["on"];
    assert.ok(isRecord(on));
    assert.deepEqual(Object.keys(on), ["workflow_dispatch"]);
    assert.ok(isRecord(Y["concurrency"]) && Y["concurrency"]["cancel-in-progress"] === false);
  });
  it("top-level permissions are read-only; the job alone gets exactly contents:write, actions:read, id-token:write and requires the release environment", () => {
    assert.deepEqual(Y["permissions"], { contents: "read" });
    assert.deepEqual(job["permissions"], { contents: "write", actions: "read", "id-token": "write" });
    assert.equal(job["environment"], "release");
  });
  it("uses no secrets, no long-lived keys and no third-party auth actions (WIF is done by our own TypeScript)", () => {
    assert.ok(!T.includes("secrets."));
    assert.doesNotMatch(T, /google-github-actions|docker\/login-action|credentials_json|GOOGLE_APPLICATION_CREDENTIALS|password:/i);
    assert.ok(T.includes("publish registry-login"));
    for (const line of T.split("\n")) {
      const m = /^\s*-?\s*uses:\s*(\S+)/.exec(line);
      if (m?.[1] === undefined || m[1].startsWith("./")) continue;
      assert.match(m[1], /^actions\/checkout@/, `release.yml で使える外部 action は checkout だけ: ${line.trim()}`);
    }
  });
  it("no expression is interpolated inside run (inputs and contexts reach scripts only through env); pipes keep their exit status; checkout does not persist credentials", () => {
    for (const st of steps) {
      if (typeof st["run"] !== "string") continue;
      assert.doesNotMatch(st["run"], /\$\{\{/, String(st["name"]));
      if (/(?<!\|)\|(?!\|)/.test(st["run"]) || st["run"].trim().includes("\n")) {
        assert.match(st["run"], /set -euo pipefail/, `${String(st["name"])}: 複数行・パイプの run は set -euo pipefail`);
        assert.equal(st["shell"], "bash", String(st["name"]));
      }
    }
    assert.match(T, /RELEASE: \$\{\{ inputs\.release \}\}/);
    assert.equal(T.split("actions/checkout@").length, T.split("persist-credentials: false").length);
  });
  it("the guard checks main, the schema's release format, the notes and the configuration; the tag check treats only 404 as absent and the tag is verified after creation", () => {
    const guard = JSON.stringify(steps[0]);
    for (const needle of [
      "refs/heads/main",
      "(0?[1-9]|1[0-2])",
      "2000",
      "[[:cntrl:]]",
      "GCP_PROJECT_ID GCP_WIF_PROVIDER_WEB GCP_PUSH_SERVICE_ACCOUNT",
    ]) {
      assert.ok(guard.includes(needle), needle);
    }
    const tag = JSON.stringify(
      steps.find((x) => JSON.stringify(x).includes("git/ref/tags") && !JSON.stringify(x).includes("gh release create")),
    );
    assert.ok(tag.includes('\\"404\\"') && tag.includes('\\"200\\"'), "404 だけを『無い』とみなす");
    const publish = JSON.stringify(steps.find((x) => JSON.stringify(x).includes("gh release create")));
    assert.ok(publish.includes(".object.sha") && publish.includes("GITHUB_SHA"), "タグが対象のコミットを指すことを確かめる");
    assert.ok(T.includes('--notes="$NOTES"'), "notes が - で始まっても引数として解釈されない");
  });
  it("gates come first: main only + release format + configuration, then ci gate success, then the tag check, then build and smoke, then push", () => {
    const guard = stepIndex("refs/heads/main");
    const ci = stepIndex("publish check-ci");
    const tag = stepIndex("git/ref/tags");
    const smoke = stepIndex("main.js image-smoke");
    const login = stepIndex("publish registry-login");
    const push = stepIndex("publish push");
    const manifest = stepIndex("release production");
    const validate = stepIndex("release validate --kind production");
    const publish = stepIndex("gh release create");
    assert.ok([guard, ci, tag, smoke, login, push, manifest, validate, publish].every((i) => i >= 0));
    assert.ok(
      guard === 0 &&
        guard < ci &&
        ci < tag &&
        tag < smoke &&
        smoke < login &&
        login < push &&
        push < manifest &&
        manifest <= validate &&
        validate <= publish,
    );
    const logout = steps.find((s) => JSON.stringify(s).includes("docker logout"));
    assert.ok(logout && logout["if"] === "always()", "レジストリの認証を必ず消す");
    assert.ok(T.includes('--min-app-release "$RELEASE"'), "検証済みのロールバック範囲は release 自身だけ");
  });
  it("does not deploy and pushes only to the Artifact Registry of the configured project", () => {
    assert.doesNotMatch(T, /gcloud run|deploy\.yml|ops deploy|run\.googleapis|pulumi|terraform/i);
    assert.ok(T.includes("REGISTRY: asia-northeast1-docker.pkg.dev"));
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
      ["nuget", "/agent/cs"],
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
    assert.ok(ignores("nuget").includes("Microsoft.*") && ignores("nuget").includes("version-update:semver-major"));
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
