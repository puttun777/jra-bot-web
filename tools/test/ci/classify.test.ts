/** 変更判定（旧 tools/ci/tests/test_classify.py の全ケースを移植。パスは TypeScript 移行後の配置に更新）。 */
import assert from "node:assert/strict";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { describe, it } from "node:test";
import { changedFiles, classify, fullPlan, KNOWN_TAGS, outputs, type Plan, planForEvent, RULES } from "../../src/ci/classify.js";
import { run } from "../../src/lib/proc.js";
import { withTempDir } from "../lib/helpers.js";

const plan = (...files: string[]): Plan => classify(files);
const jobs = (p: Plan) => ({
  mode: p.mode,
  build: p.runBuild,
  release: p.runRelease,
  images: p.runImages,
  c26: p.runConnectorj26,
  db: p.dbMatrix,
  suites: p.dbSuites,
});

describe("scenario", () => {
  it("01 docs only", () => {
    const p = plan("docs/PROGRESS.md", "README.md", "docs/VERSIONS.md", "tools/golden/README.md", "bot/README.md");
    assert.deepEqual(jobs(p), { mode: "docs", build: false, release: false, images: false, c26: false, db: [], suites: [] });
  });
  it("02 kotlin domain only", () => {
    assert.deepEqual(jobs(plan("domain/src/main/kotlin/jp/jrabot/domain/Odds.kt")), {
      mode: "code",
      build: true,

      release: false,
      images: false,
      c26: false,
      db: ["8.4"],
      suites: ["persistence", "apps"],
    });
  });
  it("03 persistence only", () => {
    const p = plan("persistence/src/main/kotlin/jp/jrabot/persistence/RaceDao.kt");
    assert.deepEqual(p.dbMatrix, ["8.4", "9.7"]);
    assert.deepEqual(p.dbSuites, ["migration", "persistence", "apps"]);
    assert.equal(p.runBuild, true);
    assert.equal(p.runImages, false);
    assert.equal(p.runConnectorj26, false);
  });
  it("04 flyway only", () => {
    const p = plan("db-migration/src/main/resources/db/migration/V3__x.sql");
    assert.deepEqual(p.dbMatrix, ["8.4", "9.7"]);
    assert.equal(p.runRelease, true); // flyway.latest_version の整合
    assert.equal(p.runImages, true); // 焼き込まれる + スモークで実 migrate
  });
  it("05 ci config only (workflows, scripts, TS tooling and npm dependencies)", () => {
    for (const f of [
      ".github/workflows/ci.yml",
      ".github/scripts/check-repo-hygiene.sh",
      ".github/actions/setup-tools/action.yml",
      "tools/ci/classify.py",
      "tools/src/ci/classify.ts",
      "tools/test/ci/classify.test.ts",
      "tools/src/lib/proc.ts",
      "tools/src/main.ts",
      "tools/test/lib/helpers.ts",
      "tools/tsconfig.json",
      "tools/run-tests.mjs",
      "package.json",
      "package-lock.json",
      "biome.json",
    ]) {
      const p = plan(f);
      assert.equal(p.mode, "full", f);
      assert.ok(p.runBuild && p.runRelease && p.runImages, f);
      assert.deepEqual(p.dbMatrix, ["8.4", "9.7"]);
      assert.equal(p.runConnectorj26, false, f); // 非ゲートの互換性確認は CI 設定の変更では走らせない
    }
  });
  it("06 dockerfile only", () => {
    for (const f of ["Dockerfile", ".dockerignore"]) {
      assert.deepEqual(jobs(plan(f)), { mode: "code", build: false, release: true, images: true, c26: false, db: [], suites: [] }, f);
    }
  });
  it("06b release tooling (TypeScript) runs release tests and images", () => {
    for (const f of ["tools/src/release/manifest.ts", "tools/test/release/manifest.test.ts"]) {
      assert.deepEqual(jobs(plan(f)), { mode: "code", build: false, release: true, images: true, c26: false, db: [], suites: [] }, f);
    }
  });
  it("07 golden fixture only", () => {
    assert.deepEqual(jobs(plan("tools/golden/fixtures/GD01-EV-001.json")), {
      mode: "code",
      build: true,
      release: false,
      images: false,
      c26: false,
      db: [],
      suites: [],
    });
  });
  it("07b golden runner and domain always run the kotlin comparison", () => {
    for (const f of ["golden-runner/src/main/kotlin/jp/jrabot/golden/GoldenCalls.kt", "golden-runner/build.gradle.kts"]) {
      assert.deepEqual(jobs(plan(f)), { mode: "code", build: true, release: false, images: false, c26: false, db: [], suites: [] }, f);
    }
    const p = plan("domain/src/main/kotlin/jp/jrabot/domain/decision/EvMath.kt");
    assert.equal(p.runBuild, true);
    assert.deepEqual([p.dbMatrix, p.dbSuites], [["8.4"], ["persistence", "apps"]]);
  });
  it("07c golden docs only does not build", () => {
    assert.equal(plan("tools/golden/README.md").mode, "docs");
  });
  it("08 multiple areas", () => {
    let p = plan(
      "docs/PLAN.md",
      "bot/src/main/kotlin/jp/jrabot/bot/BotLauncher.kt",
      "tools/golden/fixtures/GD01-EV-001.json",
      "Dockerfile",
    );
    assert.deepEqual(jobs(p), {
      mode: "code",
      build: true,
      release: true,
      images: true,
      c26: false,
      db: ["8.4"],
      suites: ["apps"],
    });
    p = plan("bot/src/main/kotlin/A.kt", "persistence/src/main/kotlin/B.kt");
    assert.deepEqual(p.dbMatrix, ["8.4", "9.7"]); // 広い方に合わせる
    assert.deepEqual(p.dbSuites, ["migration", "persistence", "apps"]);
  });
  it("09 unknown or missing files fail closed", () => {
    for (const files of [[], ["new-top-level/thing.bin"], ["docs/a.md", "unmapped/main.py"], ["tools/src/unknown-area/x.ts"]]) {
      const p = classify(files);
      assert.equal(p.mode, "full", JSON.stringify(files));
      assert.ok(p.runBuild && p.runRelease && p.runImages && JSON.stringify(p.dbMatrix) === '["8.4","9.7"]');
    }
  });
  it("12 dependabot PRs", () => {
    const gradle = plan("gradle/libs.versions.toml");
    assert.ok(gradle.runBuild && gradle.runConnectorj26 && gradle.runImages);
    assert.deepEqual(gradle.dbMatrix, ["8.4", "9.7"]);
    assert.equal(plan(".github/workflows/ci.yml").mode, "full");
    assert.equal(plan("tools/golden/requirements.txt").mode, "full"); // 旧 Python の依存は削除済み（D-258）。復活したら全部実行
    assert.equal(plan("Dockerfile").runImages, true);
    assert.deepEqual(jobs(plan(".github/dependabot.yml")), {
      mode: "code",
      build: false,

      release: true,
      images: false,
      c26: false,
      db: [],
      suites: [],
    });
    assert.equal(plan("package-lock.json").mode, "full"); // npm の依存更新は CI 自体の変更（安全側）
  });
  it("connectorj triggers", () => {
    for (const f of [
      "gradle/libs.versions.toml",
      "persistence/build.gradle.kts",
      "db-migration/build.gradle.kts",
      ".github/workflows/compat-connectorj26.yml",
    ]) {
      assert.equal(plan(f).runConnectorj26, true, f);
    }
    for (const f of ["persistence/src/main/kotlin/A.kt", "bot/build.gradle.kts", "docs/VERSIONS.md"])
      assert.equal(plan(f).runConnectorj26, false, f);
  });
  it("dependency and spring config changes reach db tests", () => {
    for (const f of [
      "build.gradle.kts",
      "gradle.properties",
      "build-logic/src/main/kotlin/SpringBootAppPlugin.kt",
      "gradle/wrapper/gradle-wrapper.properties",
      ".editorconfig",
    ]) {
      const p = plan(f);
      assert.deepEqual(p.dbMatrix, ["8.4", "9.7"], f);
      assert.ok(p.runBuild && p.runImages, f);
    }
  });
  it("app build definition changes update manifest checks and images", () => {
    const p = plan("ingest/build.gradle.kts");
    assert.ok(p.runRelease && p.runImages && p.runBuild);
    assert.deepEqual(p.dbMatrix, ["8.4"]);
  });
  it("contracts.md runs release tools but nothing heavy", () => {
    assert.deepEqual(jobs(plan("docs/CONTRACTS.md")), {
      mode: "code",
      build: false,

      release: true,
      images: false,
      c26: false,
      db: [],
      suites: [],
    });
  });
  it("every rule pattern has known tags", () => {
    for (const [pattern, tags] of RULES) for (const t of tags) assert.ok(KNOWN_TAGS.has(t), String(pattern));
  });
});

describe("agent", () => {
  it("agent docs only is docs", () => {
    const p = classify(["agent/README.md", "agent/POC.md"]);
    assert.equal(p.mode, "docs");
    assert.ok(!(p.runAgent || p.runAgentWindows));
  });
  it("agent cli and tests run the linux agent job only", () => {
    for (const f of ["agent/cs/tests/JraAgent.Core.Tests/QueueTests.cs", "agent/cs/src/JraAgent.Cli/AgentLog.cs", "agent/cs/.gitignore"]) {
      const p = classify([f]);
      assert.equal(p.runAgent, true, f);
      assert.ok(!(p.runAgentWindows || p.runBuild || p.dbMatrix.length > 0), f);
    }
  });
  it("agent core, the interop driver and the vectors also run kotlin interop on 8.4 only", () => {
    for (const f of [
      "agent/cs/src/JraAgent.Core/Sender.cs",
      "agent/cs/tools/JraAgent.Interop/Program.cs",
      "agent/tests/vectors/signing_vectors.json",
    ]) {
      const p = classify([f]);
      assert.ok(p.runAgent && p.runBuild, f);
      assert.equal(p.runAgentWindows, false, f);
      assert.deepEqual(p.dbMatrix, ["8.4"]);
      assert.deepEqual(p.dbSuites, ["apps"]);
    }
  });
  it("windows specific files run windows runner", () => {
    for (const f of [
      "agent/cs/src/JraAgent.Windows/ComJvLink.cs",
      "agent/cs/src/JraAgent.Core/Worker.cs",
      "agent/cs/src/JraAgent.Core/InstanceLock.cs",
      "agent/cs/src/JraAgent.Core/Update.cs",
      "agent/cs/src/JraAgent.Core/OutboxQueue.cs",
      "agent/cs/src/JraAgent.Cli/Program.cs",
      "agent/cs/src/JraAgent.Cli/WorkerCommand.cs",
      "agent/cs/tests/JraAgent.Core.Tests/WindowsTests.cs",
      "agent/cs/Directory.Packages.props",
      "agent/cs/src/JraAgent.Core/packages.lock.json",
      "agent/scripts/install-task.ps1",
    ]) {
      assert.equal(classify([f]).runAgentWindows, true, f);
    }
  });
  it("a resurrected python agent file only runs the C# agent job (python is not run in CI: D-250)", () => {
    const p = classify(["agent/src/jra_agent/sender.py"]);
    assert.equal(p.runAgent, true);
    assert.ok(!(p.runAgentWindows || p.runBuild));
  });
  it("full runs everything including agent jobs", () => {
    const p = planForEvent("workflow_dispatch");
    assert.ok(p.runAgent && p.runAgentWindows);
    assert.equal(classify([".github/workflows/ci.yml"]).runAgentWindows, true);
  });
});

describe("ml train (Kotlin, D-257)", () => {
  it("ml-train source runs build, the app DB suite (the bot interop test launches the jar) and the image", () => {
    const p = classify(["ml-train/src/main/kotlin/jp/jrabot/mltrain/job/TrainJob.kt"]);
    assert.ok(p.runBuild && p.runImages);
    assert.deepEqual(p.dbMatrix, ["8.4"]);
    assert.deepEqual(p.dbSuites, ["apps"]);
    assert.equal(p.runRelease, false);
  });
  it("ml-train tests run build and the app DB suite without images", () => {
    const p = classify([
      "ml-train/src/test/kotlin/jp/jrabot/mltrain/job/TrainJobTest.kt",
      "ml-train/src/test/resources/legacy-pkl/expected.json",
    ]);
    assert.ok(p.runBuild && !p.runImages && !p.runRelease);
    assert.deepEqual(p.dbSuites, ["apps"]);
  });
  it("ml-train build definition also runs release checks (requiredSchemaVersion feeds the manifest)", () => {
    const p = classify(["ml-train/build.gradle.kts"]);
    assert.ok(p.runBuild && p.runImages && p.runRelease);
  });
  it("synthetic ML artifacts run build with the domain DB suites, golden comparison and release checks", () => {
    const p = classify(["domain/src/test/resources/ml/models/cal_test_001.json"]);
    assert.ok(p.runBuild && p.runRelease);
    assert.deepEqual(p.dbSuites, ["persistence", "apps"]);
  });
  it("the removed Python locations are not silently classified (unknown = full run), docs stay docs", () => {
    for (const f of ["ml/src/ml_train/job.py", "ml/requirements.txt", "tools/ml/canonical.py", "tools/ml/signing.py"]) {
      assert.equal(classify([f]).mode, "full", f);
    }
    assert.equal(classify(["ml/README.md"]).mode, "docs");
  });
  it("outputs have no Python ML switches any more", () => {
    const o = outputs(planForEvent("workflow_dispatch"));
    assert.ok(!("run_ml_tools" in o) && !("run_ml_train" in o));
  });
});

describe("event", () => {
  it("14 workflow_dispatch and schedule are full with connectorj", () => {
    for (const ev of ["workflow_dispatch", "schedule"]) {
      const p = planForEvent(ev);
      assert.equal(p.mode, "full");
      assert.ok(p.runConnectorj26 && p.runBuild && p.runRelease && p.runImages);
      assert.deepEqual(p.dbMatrix, ["8.4", "9.7"]);
    }
  });
  it("unexpected event fails closed", () => {
    assert.equal(planForEvent("issue_comment").mode, "full");
    assert.equal(planForEvent("").mode, "full");
  });
  it("diff failure fails closed", () => {
    withTempDir((d) => {
      // git リポジトリではない → git が失敗
      const cases: [string, { before?: string; baseSha?: string }][] = [
        ["pull_request", { baseSha: "abc" }],
        ["push", { before: "abc" }],
        ["push", { before: "0".repeat(40) }],
        ["push", {}],
      ];
      for (const [ev, kw] of cases) {
        const p = planForEvent(ev, { ...kw, cwd: d });
        assert.equal(p.mode, "full", `${ev} ${JSON.stringify(kw)}`);
        assert.match(p.reason, /fail closed/);
      }
    });
  });
  it("outputs are strings for github and control characters are removed from the reason", () => {
    const out = outputs(classify(["docs/a.md"]));
    for (const v of Object.values(out)) assert.equal(typeof v, "string");
    assert.equal(out["db_matrix"], "[]");
    assert.deepEqual(JSON.parse(outputs(fullPlan("x"))["db_matrix"] ?? ""), ["8.4", "9.7"]);
    const evil = outputs(classify(["evil\nrun_build=false\u0000.bin"]));
    assert.ok(!(evil["reason"] ?? "").includes("\n"));
    assert.equal(evil["mode"], "full");
  });
});

describe("git diff", () => {
  const g = (cwd: string, ...a: string[]): string => {
    const r = run("git", ["-c", "user.email=t@example.invalid", "-c", "user.name=t", "-c", "commit.gpgsign=false", ...a], { cwd });
    assert.equal(r.status, 0, r.stderr);
    return r.stdout.trim();
  };
  it("pull_request and push diffs", () => {
    withTempDir((r) => {
      g(r, "init", "-q", "-b", "main");
      writeFileSync(join(r, "README.md"), "a");
      g(r, "add", "-A");
      g(r, "commit", "-qm", "base");
      const base = g(r, "rev-parse", "HEAD");
      g(r, "checkout", "-qb", "feature");
      mkdirSync(join(r, "bot", "src"), { recursive: true });
      writeFileSync(join(r, "bot", "src", "A.kt"), "x");
      mkdirSync(join(r, "docs"));
      writeFileSync(join(r, "docs", "a.md"), "x");
      g(r, "add", "-A");
      g(r, "commit", "-qm", "feature");
      g(r, "checkout", "-q", "main");
      writeFileSync(join(r, "main-only.md"), "x"); // ベース側の別の変更（PR の差分に含めない）
      g(r, "add", "-A");
      g(r, "commit", "-qm", "main moved");
      const mainBefore = g(r, "rev-parse", "HEAD");
      g(r, "merge", "-q", "--no-ff", "-m", "merge", "feature");
      // pull_request: HEAD = merge コミット。第 1 親（mainBefore）との差分 = PR の変更だけ
      assert.deepEqual(changedFiles("pull_request", undefined, base, r).sort(), ["bot/src/A.kt", "docs/a.md"]);
      assert.deepEqual(changedFiles("push", mainBefore, undefined, r).sort(), ["bot/src/A.kt", "docs/a.md"]);
      const p = planForEvent("push", { before: mainBefore, cwd: r });
      assert.equal(p.mode, "code");
      assert.deepEqual(p.dbMatrix, ["8.4"]);
      // ファイル名に空白や日本語があっても -z で正しく扱う
      writeFileSync(join(r, "docs", "メモ 1.md"), "x");
      g(r, "add", "-A");
      g(r, "commit", "-qm", "jp");
      assert.deepEqual(changedFiles("push", g(r, "rev-parse", "HEAD~1"), undefined, r), ["docs/メモ 1.md"]);
    });
  });
});

describe("dashboard frontend (TypeScript, D-263)", () => {
  it("frontend source, tsconfig and static files run release tools (type check + node:test), build and images", () => {
    for (const f of [
      "dashboard/src/main/ts/app.ts",
      "dashboard/src/main/ts/schema.ts",
      "dashboard/tsconfig.json",
      "dashboard/tsconfig.test.json",
      "dashboard/src/main/resources/static/index.html",
      "dashboard/src/main/resources/static/style.css",
    ]) {
      const p = classify([f]);
      assert.ok(p.runBuild && p.runRelease && p.runImages, f);
      assert.deepEqual(p.dbSuites, ["apps"], f);
    }
  });
  it("frontend tests and the contract samples run release tools and build (the Kotlin integration test reads the samples) without images", () => {
    for (const f of [
      "dashboard/src/test/ts/view.test.ts",
      "dashboard/run-tests.mjs",
      "dashboard/src/test/resources/frontend-contract/status.json",
      "dashboard/src/test/resources/frontend-contract/README.md",
    ]) {
      const p = classify([f]);
      assert.ok(p.runBuild && p.runRelease && !p.runImages, f);
      assert.deepEqual(p.dbSuites, ["apps"], f);
    }
  });
  it("kotlin-only dashboard changes still do not run release tools", () => {
    const p = classify(["dashboard/src/main/kotlin/jp/jrabot/dashboard/api/ReadApi.kt"]);
    assert.ok(p.runBuild && !p.runRelease);
  });
});

describe("legacy import tool (Kotlin, D-271)", () => {
  it("tool source and tests run the build job only; docs stay docs", () => {
    for (const f of [
      "legacy-import/src/main/kotlin/jp/jrabot/legacyimport/Preflight.kt",
      "legacy-import/src/main/resources/legacy-schema/schema.sql",
    ]) {
      const p = classify([f]);
      assert.ok(p.runBuild && !p.runImages && !p.runRelease, f);
      assert.deepEqual(p.dbMatrix, [], f);
    }
    assert.equal(classify(["legacy-import/src/main/resources/legacy-schema/README.md"]).mode, "docs");
  });
  it("its build definition also runs images (the Docker build stage evaluates settings)", () => {
    const p = classify(["legacy-import/build.gradle.kts"]);
    assert.ok(p.runBuild && p.runImages);
  });
});
