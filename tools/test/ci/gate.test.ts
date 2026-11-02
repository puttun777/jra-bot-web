/** 最終ゲートの判定（旧 tools/ci/tests/test_gate.py の全ケースを移植）。skipped を成功として数えないこと、fail closed を検証する。 */
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { join } from "node:path";
import { describe, it } from "node:test";
import { classify, outputs, type Plan, planForEvent } from "../../src/ci/classify.js";
import { evaluate } from "../../src/ci/gate.js";
import { ROOT } from "../lib/helpers.js";

type Needs = Record<string, { result: string; outputs: Record<string, string> }>;

/** plan に従って『正しく実行された』needs を作る。results で上書き。 */
function needsFor(p: Plan, results: Record<string, string> = {}, changesResult = "success"): Needs {
  // GitHub は値が空文字のジョブ出力を needs から落とす（実 CI で db_suites='' が欠落した: D-102）。それを再現する
  const o = Object.fromEntries(Object.entries(outputs(p)).filter(([, v]) => v !== ""));
  const expected: Record<string, boolean> = {
    build: p.runBuild,
    "release-tools": p.runRelease,
    golden: p.runGolden,
    "db-integration": p.dbMatrix.length > 0,
    images: p.runImages,
    connectorj26: p.runConnectorj26,
    agent: p.runAgent,
    "agent-windows": p.runAgentWindows,
  };
  const needs: Needs = { changes: { result: changesResult, outputs: o } };
  for (const [job, ran] of Object.entries(expected)) needs[job] = { result: ran ? "success" : "skipped", outputs: {} };
  for (const [job, r] of Object.entries(results)) {
    const n = needs[job];
    if (n !== undefined) n.result = r;
  }
  return needs;
}

function changesOutputs(n: Needs): Record<string, string> {
  const c = n["changes"];
  assert.ok(c);
  return c.outputs;
}

const DOCS = classify(["docs/PROGRESS.md"]);
const KOTLIN = classify(["bot/src/main/kotlin/A.kt"]);
const PERSISTENCE = classify(["persistence/src/main/kotlin/A.kt"]);
const FULL = planForEvent("workflow_dispatch");

describe("gate", () => {
  it("docs only passes with everything skipped", () => assert.equal(evaluate(needsFor(DOCS)).ok, true));
  it("docs only never reports skipped as passed", () => {
    const text = evaluate(needsFor(DOCS)).lines.join("\n");
    assert.match(text, /未実施/);
    assert.ok(!text.replaceAll("不要だったが実行して成功", "").includes("成功"));
  });
  it("expected jobs all success passes", () => {
    for (const p of [KOTLIN, PERSISTENCE, FULL]) assert.equal(evaluate(needsFor(p)).ok, true, p.reason);
  });
  it("10 required job failure fails", () => {
    for (const job of ["build", "db-integration", "release-tools", "images"]) {
      const r = evaluate(needsFor(FULL, { [job]: "failure" }));
      assert.equal(r.ok, false, job);
      assert.ok(r.problems.some((p) => p.includes(job)));
    }
  });
  it("required job skipped or cancelled fails", () => {
    for (const r of ["skipped", "cancelled"]) {
      assert.equal(evaluate(needsFor(KOTLIN, { build: r })).ok, false, r);
      assert.equal(evaluate(needsFor(KOTLIN, { "db-integration": r })).ok, false, r);
    }
  });
  it("unexpected job that ran and failed fails", () => {
    assert.equal(evaluate(needsFor(DOCS, { build: "failure" })).ok, false);
    assert.equal(evaluate(needsFor(DOCS, { golden: "cancelled" })).ok, false);
  });
  it("unexpected job that ran and succeeded is fine", () => assert.equal(evaluate(needsFor(DOCS, { build: "success" })).ok, true));
  it("11 non required compat failure does not block", () => {
    assert.equal(evaluate(needsFor(FULL, { connectorj26: "failure" })).ok, true);
    assert.equal(evaluate(needsFor(FULL, { connectorj26: "cancelled" })).ok, true);
    const lines = evaluate(needsFor(FULL, { connectorj26: "failure" })).lines;
    assert.ok(lines.some((l) => l.includes("connectorj26") && l.includes("failure") && l.includes("ゲートに影響しない")));
  });
  it("failed classification fails closed", () => {
    for (const r of ["failure", "cancelled", "skipped"]) assert.equal(evaluate(needsFor(DOCS, {}, r)).ok, false, r);
    assert.equal(evaluate({}).ok, false);
    for (const bad of [null, [], "x", 1]) assert.equal(evaluate(bad).ok, false);
  });
  it("malformed classification output fails", () => {
    let n = needsFor(DOCS);
    delete changesOutputs(n)["run_build"];
    assert.equal(evaluate(n).ok, false);
    n = needsFor(DOCS);
    changesOutputs(n)["mode"] = "whatever";
    assert.equal(evaluate(n).ok, false);
  });
  it("non docs with nothing to run is inconsistent", () => {
    const n = needsFor(DOCS);
    changesOutputs(n)["mode"] = "code";
    assert.equal(evaluate(n).ok, false);
  });
  it("empty outputs are dropped by github and still pass", () => {
    const goldenOnly = classify(["tools/golden/generate.py"]);
    for (const p of [DOCS, goldenOnly]) {
      const n = needsFor(p);
      assert.ok(!("db_suites" in changesOutputs(n)));
      assert.equal(evaluate(n).ok, true, p.reason);
    }
  });
  it("db jobs expected but suites missing fails closed", () => {
    let n = needsFor(PERSISTENCE);
    delete changesOutputs(n)["db_suites"];
    assert.equal(evaluate(n).ok, false);
    n = needsFor(PERSISTENCE);
    changesOutputs(n)["db_suites"] = "";
    assert.equal(evaluate(n).ok, false);
  });
  it("missing db_matrix fails closed", () => {
    const n = needsFor(DOCS);
    delete changesOutputs(n)["db_matrix"];
    assert.equal(evaluate(n).ok, false);
  });
  it("missing job in needs fails", () => {
    const n = needsFor(KOTLIN);
    delete n["images"];
    assert.equal(evaluate(n).ok, false);
  });
  it("cli exit codes and summary", () => {
    const main = join(ROOT, "build/tools/src/main.js");
    const runGate = (env: NodeJS.ProcessEnv): number => spawnSync(process.execPath, [main, "gate"], { env, encoding: "utf8" }).status ?? -1;
    const base: NodeJS.ProcessEnv = { ...process.env, GITHUB_STEP_SUMMARY: "" };
    assert.equal(runGate({ ...base, NEEDS_JSON: JSON.stringify(needsFor(DOCS)) }), 0);
    assert.equal(runGate({ ...base, NEEDS_JSON: JSON.stringify(needsFor(KOTLIN, { build: "failure" })) }), 1);
    const without: NodeJS.ProcessEnv = { ...base };
    delete without["NEEDS_JSON"];
    assert.equal(runGate(without), 1); // 入力が無ければ失敗
    assert.equal(runGate({ ...base, NEEDS_JSON: "not json" }), 1);
  });
  it("13 main merge uses same classification as pr", () => {
    assert.deepEqual(outputs(classify(["persistence/src/main/kotlin/A.kt"])), outputs(classify(["persistence/src/main/kotlin/A.kt"])));
  });
});
