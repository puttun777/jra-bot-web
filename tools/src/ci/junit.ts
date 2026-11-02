/**
 * JUnit の XML レポートを検証する。0 件・スキップ・失敗・エラー・期待したテストクラスの欠落を失敗にする（黙って緑にしない）。
 * 旧 .github/scripts/verify_test_results.py と、ci.yml 内の `python3 - <<PY` の集計（7 か所）を 1 つに統合した。
 *
 * 使い方: verify-junit --label <ラベル> --dir <レポートのディレクトリ | XML ファイル> --min <最小テスト数>
 *         [--classes A,B,C]（単純名）[--max-skipped N（既定 0）]
 */
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { XMLParser, XMLValidator } from "fast-xml-parser";
import { isRecord } from "../lib/json.js";

export interface SuiteCounts {
  readonly name: string;
  readonly tests: number;
  readonly skipped: number;
  readonly failures: number;
  readonly errors: number;
}

const parser = new XMLParser({
  ignoreAttributes: false,
  attributeNamePrefix: "@",
  processEntities: true,
  isArray: (name) => name === "testsuite" || name === "testcase",
});

function intAttr(node: Record<string, unknown>, name: string, file: string): number | undefined {
  const v = node[`@${name}`];
  if (v === undefined) return undefined;
  const n = typeof v === "string" && /^\d+$/.test(v) ? Number(v) : Number.NaN;
  if (!Number.isSafeInteger(n) || n < 0) throw new Error(`${file}: 属性 ${name} が整数ではない (${String(v)})`);
  return n;
}

function arr(v: unknown): Record<string, unknown>[] {
  return Array.isArray(v) ? v.filter(isRecord) : [];
}

/**
 * 1 ファイルの XML から集計を取り出す。**件数は testcase 要素から直接数える**（node:test の junit は describe の外の試験を
 * testsuite に入れず、属性も付けない）。testsuite の属性（Gradle・pytest）がある場合は、その中の testcase の数と一致しなければ失敗にする。
 * 返す name はクラス（Gradle の testsuite 名 / testcase の classname）の一覧の検査に使う。
 */
export function parseSuites(xml: string, file: string): SuiteCounts[] {
  // 閉じタグの欠落など不正な XML は数えずに失敗にする（旧 ElementTree と同じ）
  const valid = XMLValidator.validate(xml);
  if (valid !== true) throw new Error(`${file}: 不正な XML（${valid.err.msg} 行 ${valid.err.line}）`);
  const doc: unknown = parser.parse(xml);
  if (!isRecord(doc)) throw new Error(`${file}: XML を解析できない`);
  const roots: Record<string, unknown>[] = [];
  const top = doc["testsuites"];
  if (isRecord(top)) roots.push(top);
  for (const s of arr(doc["testsuite"])) roots.push({ testsuite: [s] });
  if (roots.length === 0) throw new Error(`${file}: testsuite/testsuites が無い`);
  const out: SuiteCounts[] = [];
  const count = (node: Record<string, unknown>): { tests: number; skipped: number; failures: number; errors: number } => {
    let tests = 0;
    let skipped = 0;
    let failures = 0;
    let errors = 0;
    for (const tc of arr(node["testcase"])) {
      tests++;
      if (tc["skipped"] !== undefined) skipped++;
      if (tc["failure"] !== undefined) failures++;
      if (tc["error"] !== undefined) errors++;
      const cls = tc["@classname"];
      if (typeof cls === "string" && cls !== "") out.push({ name: cls, tests: 0, skipped: 0, failures: 0, errors: 0 });
    }
    for (const s of arr(node["testsuite"])) {
      const name = typeof s["@name"] === "string" ? s["@name"] : "";
      const c = count(s);
      const declared = intAttr(s, "tests", file);
      if (declared !== undefined && declared !== c.tests)
        throw new Error(`${file}: testsuite ${name} の tests=${declared} と testcase の数 ${c.tests} が一致しない`);
      const declaredSkipped = intAttr(s, "skipped", file);
      if (declaredSkipped !== undefined && declaredSkipped !== c.skipped)
        throw new Error(`${file}: testsuite ${name} の skipped=${declaredSkipped} と一致しない`);
      // failures・errors も、属性があれば数えた値と一致しなければ失敗（子要素を出さない形式で失敗を見落とさない）
      for (const [k, counted] of [
        ["failures", c.failures],
        ["errors", c.errors],
      ] as const) {
        const declaredK = intAttr(s, k, file);
        if (declaredK !== undefined && declaredK !== counted)
          throw new Error(`${file}: testsuite ${name} の ${k}=${declaredK} と testcase の数 ${counted} が一致しない`);
      }
      out.push({ name, tests: 0, skipped: 0, failures: 0, errors: 0 });
      tests += c.tests;
      skipped += c.skipped;
      failures += c.failures;
      errors += c.errors;
    }
    return { tests, skipped, failures, errors };
  };
  for (const r of roots) {
    const c = count(r);
    out.push({ name: "", ...c });
  }
  return out;
}

export interface VerifyInput {
  readonly label: string;
  readonly path: string;
  readonly min: number;
  readonly classes: readonly string[];
  readonly maxSkipped: number;
}

export interface VerifyOutput {
  readonly ok: boolean;
  readonly messages: readonly string[];
}

function xmlFiles(path: string): string[] {
  let st: ReturnType<typeof statSync>;
  try {
    st = statSync(path);
  } catch {
    return [];
  }
  if (st.isFile()) return [path];
  return readdirSync(path)
    .filter((f) => f.endsWith(".xml"))
    .sort()
    .map((f) => join(path, f));
}

export function verify(input: VerifyInput): VerifyOutput {
  const files = xmlFiles(input.path);
  if (files.length === 0) return { ok: false, messages: [`::error::${input.label}: テスト結果が無い (${input.path})`] };
  const seen = new Set<string>();
  let tests = 0;
  let skipped = 0;
  let failures = 0;
  let errors = 0;
  try {
    for (const f of files) {
      for (const s of parseSuites(readFileSync(f, "utf8"), f)) {
        const simple = s.name.split(".").pop() ?? "";
        seen.add(simple);
        tests += s.tests;
        skipped += s.skipped;
        failures += s.failures;
        errors += s.errors;
      }
    }
  } catch (e) {
    return { ok: false, messages: [`::error::${input.label}: ${e instanceof Error ? e.message : String(e)}`] };
  }
  const messages = [`${input.label}: tests=${tests} skipped=${skipped} failures=${failures} errors=${errors} classes=${seen.size}`];
  const missing = input.classes.filter((c) => !seen.has(c)).sort();
  if (missing.length > 0) {
    messages.push(`::error::${input.label}: 期待したテストクラスが結果に無い: ${JSON.stringify(missing)}`);
    return { ok: false, messages };
  }
  if (tests < input.min || skipped > input.maxSkipped || failures > 0 || errors > 0) {
    messages.push(`::error::${input.label}: 件数不足(最小 ${input.min})・スキップ(許容 ${input.maxSkipped})・失敗のいずれか`);
    return { ok: false, messages };
  }
  return { ok: true, messages };
}

export function parseArgs(argv: readonly string[]): VerifyInput {
  const get = (flag: string): string | undefined => {
    const i = argv.indexOf(flag);
    return i >= 0 ? argv[i + 1] : undefined;
  };
  const label = get("--label");
  const path = get("--dir");
  const min = get("--min");
  if (label === undefined || path === undefined || min === undefined || !/^\d+$/.test(min)) {
    throw new Error("使い方: verify-junit --label L --dir D --min N [--classes A,B] [--max-skipped N]");
  }
  const maxSkipped = get("--max-skipped") ?? "0";
  if (!/^\d+$/.test(maxSkipped)) throw new Error("--max-skipped は整数");
  const classes = (get("--classes") ?? "").split(",").filter((c) => c !== "");
  return { label, path, min: Number(min), classes, maxSkipped: Number(maxSkipped) };
}

export function main(argv: readonly string[]): number {
  let input: VerifyInput;
  try {
    input = parseArgs(argv);
  } catch (e) {
    console.error(e instanceof Error ? e.message : String(e));
    return 2;
  }
  const r = verify(input);
  for (const m of r.messages) console.log(m);
  return r.ok ? 0 : 1;
}
