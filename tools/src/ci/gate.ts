/**
 * 最終集約チェック（`ci gate`）。分岐保護の required check はこのジョブだけにする。旧 tools/ci/gate.py の移植。
 *
 * - 実行が必要と判定されたジョブは **success** でなければ失敗（skipped・cancelled・failure はすべて失敗）。
 * - 不要と判定されたジョブは skipped（または実行して success）でなければ失敗。実行して失敗したものは失敗。
 * - 変更判定ジョブ（changes）が成功していなければ、他の skipped を信用できないので失敗（fail closed）。
 * - skipped を「テスト成功」として表示しない: 要約には『未実施（変更内容から不要と判定）』と書く。
 * - connector/j 26.x（非ゲートの互換性確認）は結果を表示するだけで、ゲートの成否に影響しない。
 */
import { appendFileSync } from "node:fs";
import { isRecord } from "../lib/json.js";

type Outputs = Readonly<Record<string, string>>;

/** ジョブ名 → 実行が必要か（changes の outputs から）。ここに無いジョブは ci.yml の needs に入れない（テストで検査）。 */
export const REQUIRED: Readonly<Record<string, (o: Outputs) => boolean>> = {
  build: (o) => o["run_build"] === "true",
  "release-tools": (o) => o["run_release"] === "true",
  golden: (o) => o["run_golden"] === "true",
  "db-integration": (o) => o["db_matrix"] !== "[]" && o["db_matrix"] !== "",
  images: (o) => o["run_images"] === "true",
  agent: (o) => o["run_agent"] === "true",
  "agent-windows": (o) => o["run_agent_windows"] === "true",
};
export const INFORMATIONAL: readonly string[] = ["connectorj26"];
/** 常に値がある出力。db_suites は DB ジョブが無いとき空文字で、GitHub は空文字の出力を needs から落とす（D-102）ので別扱い。 */
export const OUTPUT_KEYS: readonly string[] = [
  "mode",
  "reason",
  "run_build",
  "run_golden",
  "run_release",
  "run_images",
  "run_connectorj26",
  "run_agent",
  "run_agent_windows",
  "db_matrix",
];

export interface GateResult {
  readonly ok: boolean;
  readonly problems: readonly string[];
  readonly lines: readonly string[];
}

function stringOutputs(v: unknown): Record<string, string> {
  const out: Record<string, string> = {};
  if (!isRecord(v)) return out;
  for (const [k, x] of Object.entries(v)) if (typeof x === "string") out[k] = x;
  return out;
}

function resultOf(node: unknown): string | undefined {
  return isRecord(node) && typeof node["result"] === "string" ? node["result"] : undefined;
}

/** needs は GitHub の `toJSON(needs)`（外部入力。unknown として受けて検査する）。 */
export function evaluate(needs: unknown): GateResult {
  const problems: string[] = [];
  const lines: string[] = [];
  const n = isRecord(needs) ? needs : {};
  const changes = n["changes"];
  if (resultOf(changes) !== "success") {
    return {
      ok: false,
      problems: ["変更判定ジョブ(changes)が成功していない。スキップされたジョブを信用できない"],
      lines: [`| changes | - | ${String(resultOf(changes))} | NG |`],
    };
  }
  const outputs = stringOutputs(isRecord(changes) ? changes["outputs"] : undefined);
  const missing = OUTPUT_KEYS.filter((k) => !(k in outputs));
  const dbm = outputs["db_matrix"];
  if (dbm !== undefined && dbm !== "[]" && dbm !== "" && !outputs["db_suites"]) {
    missing.push("db_suites"); // DB ジョブを走らせるのにスイートが分からない → 安全側で失敗
  }
  const mode = outputs["mode"];
  if (missing.length > 0 || (mode !== "docs" && mode !== "code" && mode !== "full")) {
    return { ok: false, problems: [`変更判定の出力が不正: 欠落=${JSON.stringify(missing)} mode=${JSON.stringify(mode)}`], lines: [] };
  }
  let expectedAny = false;
  for (const [job, needed] of Object.entries(REQUIRED)) {
    const result = resultOf(n[job]);
    if (result === undefined) {
      problems.push(`${job}: needs に結果が無い（ゲートの設定漏れ）`);
      lines.push(`| ${job} | - | (結果なし) | NG |`);
      continue;
    }
    const expected = needed(outputs);
    expectedAny ||= expected;
    let verdict: string;
    if (expected && result === "success") verdict = "実行して成功";
    else if (expected) {
      verdict = "NG";
      problems.push(`${job}: 実行が必要だが結果が ${result}`);
    } else if (result === "skipped") verdict = "未実施（変更内容から不要と判定。テストされていない）";
    else if (result === "success") verdict = "不要だったが実行して成功";
    else {
      verdict = "NG";
      problems.push(`${job}: 実行されて結果が ${result}`);
    }
    lines.push(`| ${job} | ${expected ? "必要" : "不要"} | ${result} | ${verdict} |`);
  }
  if (mode !== "docs" && !expectedAny) problems.push("docs 以外の変更なのに実行するジョブが無い（判定の不整合。fail closed）");
  for (const job of INFORMATIONAL) {
    const needed = outputs["run_connectorj26"] === "true" ? "必要" : "不要";
    lines.push(`| ${job} (非ゲート) | ${needed} | ${resultOf(n[job]) ?? "-"} | 参考（ゲートに影響しない） |`);
  }
  return { ok: problems.length === 0, problems, lines };
}

export function main(env: NodeJS.ProcessEnv = process.env): number {
  let outputs: Record<string, string> = {};
  let res: GateResult;
  try {
    const raw = env["NEEDS_JSON"];
    if (raw === undefined) throw new Error("NEEDS_JSON が無い");
    const needs: unknown = JSON.parse(raw);
    if (isRecord(needs) && isRecord(needs["changes"])) outputs = stringOutputs(needs["changes"]["outputs"]);
    res = evaluate(needs);
  } catch (e) {
    res = { ok: false, problems: [`ゲートの評価に失敗: ${e instanceof Error ? `${e.name}: ${e.message}` : String(e)}`], lines: [] };
  }
  const summary = [
    "## CI gate",
    "",
    `判定: **${res.ok ? "成功" : "失敗"}**  mode=\`${String(outputs["mode"])}\`  理由: ${String(outputs["reason"])}`,
    "",
    "| ジョブ | 判定 | 結果 | 扱い |",
    "|---|---|---|---|",
    ...res.lines,
    "",
    ...res.problems.map((p) => `- ❌ ${p}`),
  ];
  const text = summary.join("\n");
  console.log(text);
  const path = env["GITHUB_STEP_SUMMARY"];
  if (path !== undefined && path !== "") appendFileSync(path, `${text}\n`, "utf8");
  for (const p of res.problems) console.log(`::error::${p}`);
  return res.ok ? 0 : 1;
}
