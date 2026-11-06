// TypeScript ツールの単体試験を実行し、JUnit XML で件数・スキップ・失敗を検証する（0 件・skip・todo を成功にしない）。
// 使い方: node tools/run-tests.mjs [--min N]（既定は下の MIN。試験を削って下げない）
import { spawnSync } from "node:child_process";
import { mkdirSync, readdirSync } from "node:fs";
import { join } from "node:path";

const MIN = 166;
const i = process.argv.indexOf("--min");
const min = i >= 0 ? Number(process.argv[i + 1]) : MIN;
const outDir = "build/tools-test";
mkdirSync(outDir, { recursive: true });
const files = readdirSync("build/tools/test", { recursive: true })
  .map(String)
  .filter((f) => f.endsWith(".test.js"))
  .sort()
  .map((f) => join("build/tools/test", f));
if (files.length === 0) {
  console.error("::error::試験ファイルが無い（npm run build を先に実行）");
  process.exit(1);
}
const r = spawnSync(
  process.execPath,
  [
    "--test",
    "--test-reporter=spec",
    "--test-reporter-destination=stdout",
    "--test-reporter=junit",
    `--test-reporter-destination=${outDir}/junit.xml`,
    ...files,
  ],
  { stdio: "inherit", env: { ...process.env, LC_ALL: "C.UTF-8" } },
);
const v = spawnSync(
  process.execPath,
  ["build/tools/src/main.js", "verify-junit", "--label", "tools (TypeScript)", "--dir", `${outDir}/junit.xml`, "--min", String(min)],
  { stdio: "inherit" },
);
process.exit(r.status !== 0 ? (r.status ?? 1) : (v.status ?? 1));
