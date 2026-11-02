/** JUnit XML の件数検査（旧 verify_test_results.py と ci.yml 内の集計の置換）。0 件・skip・todo・失敗・欠落クラス・壊れた XML を失敗にする。 */
import assert from "node:assert/strict";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { describe, it } from "node:test";
import { parseArgs, parseSuites, verify } from "../../src/ci/junit.js";
import { withTempDir } from "../lib/helpers.js";

/** Gradle 形式（ルートが testsuite・属性つき）。testcase を実際に tests 個並べる */
function suite(name: string, tests: number, skipped = 0, failures = 0, errors = 0): string {
  const cases: string[] = [];
  for (let i = 0; i < tests; i++) {
    let body = "";
    if (i < skipped) body = "<skipped/>";
    else if (i < skipped + failures) body = '<failure message="x">trace</failure>';
    else if (i < skipped + failures + errors) body = "<error/>";
    cases.push(`<testcase name="t${i} &amp; b" classname="${name}" time="0.0">${body}</testcase>`);
  }
  const attrs = `name="${name}" tests="${tests}" skipped="${skipped}" failures="${failures}" errors="${errors}" time="0.1"`;
  const cdata = '<system-out><![CDATA[<testsuite name="Fake" tests="999"><testcase name="fake"/>]]></system-out>';
  return `<?xml version="1.0" encoding="UTF-8"?>\n<testsuite ${attrs}>\n${cases.join("\n")}\n${cdata}\n</testsuite>\n`;
}

function total(xml: string): [number, number, number, number] {
  const t: [number, number, number, number] = [0, 0, 0, 0];
  for (const s of parseSuites(xml, "x.xml")) {
    t[0] += s.tests;
    t[1] += s.skipped;
    t[2] += s.failures;
    t[3] += s.errors;
  }
  return t;
}

function dir(d: string, files: Record<string, string>): string {
  mkdirSync(d, { recursive: true });
  for (const [n, body] of Object.entries(files)) writeFileSync(join(d, n), body);
  return d;
}

describe("junit", () => {
  it("counts testcase elements of gradle (testsuite root), pytest and node:test (bare testcases)", () => {
    assert.deepEqual(total(suite("jp.x.ATest", 3, 1)), [3, 1, 0, 0]);
    assert.ok(parseSuites(suite("jp.x.ATest", 3), "a.xml").some((s) => s.name === "jp.x.ATest"));
    const pytest =
      '<testsuites><testsuite name="pytest" tests="2" skipped="1" failures="0" errors="0"><testcase classname="t.A" name="a"/><testcase classname="t.A" name="b"><skipped/></testcase></testsuite></testsuites>';
    assert.deepEqual(total(pytest), [2, 1, 0, 0]);
    // node:test: describe の外の試験は testsuite に入らず属性も無い。skip と todo は <skipped type=…>
    const node =
      '<testsuites><testcase name="a" classname="test"/><testcase name="b" classname="test"><skipped type="skipped" message="true"/></testcase>' +
      '<testcase name="c" classname="test"><skipped type="todo" message="true"/></testcase>' +
      '<testsuite name="d" time="1"><testcase name="e" classname="test"><failure type="testCodeFailure" message="x"/></testcase></testsuite></testsuites>';
    assert.deepEqual(total(node), [4, 2, 1, 0]);
  });
  it("rejects malformed numbers, attribute/testcase mismatches and missing suites", () => {
    assert.throws(() => parseSuites('<testsuite name="x" tests="many"/>', "c.xml"), /整数ではない/);
    assert.throws(() => parseSuites('<testsuite name="x" tests="3"><testcase name="a"/></testsuite>', "c.xml"), /一致しない/);
    assert.throws(() => parseSuites('<testsuite name="x" tests="1" skipped="1"><testcase name="a"/></testsuite>', "c.xml"), /一致しない/);
    assert.throws(() => parseSuites("<other/>", "d.xml"), /testsuite/);
    // 子要素を出さずに属性だけで失敗を報告する形式も失敗にする（レビュー指摘: 属性との照合）
    assert.throws(
      () => parseSuites('<testsuite name="x" tests="2" failures="1"><testcase name="a"/><testcase name="b"/></testsuite>', "c.xml"),
      /failures/,
    );
    assert.throws(() => parseSuites('<testsuite name="x" tests="1" errors="1"><testcase name="a"/></testsuite>', "c.xml"), /errors/);
    // 不正な XML（閉じタグ欠落）は数えない
    assert.throws(() => parseSuites('<testsuite name="x" tests="1"><testcase name="a">', "c.xml"), /不正な XML/);
  });
  it("cdata content that looks like a testsuite is not counted", () => assert.deepEqual(total(suite("A", 1)), [1, 0, 0, 0]));
  it("passes only when counts, classes, skips and failures are all satisfied", () => {
    withTempDir((t) => {
      const ok = dir(join(t, "ok"), { "TEST-a.xml": suite("jp.x.ATest", 5), "TEST-b.xml": suite("jp.x.BTest", 5) });
      assert.equal(verify({ label: "l", path: ok, min: 10, classes: ["ATest", "BTest"], maxSkipped: 0 }).ok, true);
      assert.equal(verify({ label: "l", path: ok, min: 11, classes: [], maxSkipped: 0 }).ok, false); // 件数不足
      const missing = verify({ label: "l", path: ok, min: 1, classes: ["CTest"], maxSkipped: 0 });
      assert.equal(missing.ok, false);
      assert.ok(missing.messages.some((m) => m.includes("CTest")));
      const skip = dir(join(t, "skip"), { "TEST-a.xml": suite("A", 5, 1) });
      assert.equal(verify({ label: "l", path: skip, min: 1, classes: [], maxSkipped: 0 }).ok, false);
      assert.equal(verify({ label: "l", path: skip, min: 1, classes: [], maxSkipped: 1 }).ok, true);
      const fail = dir(join(t, "fail"), { "TEST-a.xml": suite("A", 5, 0, 1) });
      assert.equal(verify({ label: "l", path: fail, min: 1, classes: [], maxSkipped: 9 }).ok, false);
      const err = dir(join(t, "err"), { "TEST-a.xml": suite("A", 5, 0, 0, 1) });
      assert.equal(verify({ label: "l", path: err, min: 1, classes: [], maxSkipped: 9 }).ok, false);
      assert.equal(verify({ label: "l", path: join(t, "nope"), min: 0, classes: [], maxSkipped: 0 }).ok, false); // 結果が無い
      assert.equal(verify({ label: "l", path: dir(join(t, "empty"), {}), min: 0, classes: [], maxSkipped: 0 }).ok, false);
      const broken = dir(join(t, "broken"), { "TEST-a.xml": '<testsuite name="A" tests="x"/>' });
      assert.equal(verify({ label: "l", path: broken, min: 0, classes: [], maxSkipped: 0 }).ok, false);
      // ファイルを直接指定（pytest / node:test の 1 ファイル）
      assert.equal(verify({ label: "l", path: join(ok, "TEST-a.xml"), min: 5, classes: ["ATest"], maxSkipped: 0 }).ok, true);
    });
  });
  it("parses cli arguments strictly", () => {
    assert.deepEqual(parseArgs(["--label", "L", "--dir", "d", "--min", "3", "--classes", "A,B,", "--max-skipped", "2"]), {
      label: "L",
      path: "d",
      min: 3,
      classes: ["A", "B"],
      maxSkipped: 2,
    });
    assert.throws(() => parseArgs(["--label", "L", "--dir", "d"]));
    assert.throws(() => parseArgs(["--label", "L", "--dir", "d", "--min", "-1"]));
    assert.throws(() => parseArgs(["--label", "L", "--dir", "d", "--min", "1", "--max-skipped", "x"]));
  });
});
