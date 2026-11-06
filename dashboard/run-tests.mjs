// ダッシュボードの画面（TypeScript）の単体試験を実行し、JUnit XML で件数・スキップ・失敗を検証する（0 件・skip・todo を成功にしない）。
// 使い方: npm run test:frontend（tsc -p dashboard/tsconfig.test.json の後に実行）。リポジトリの最上位で実行する
import { spawnSync } from "node:child_process";
import { mkdirSync, readdirSync } from "node:fs";
import { join } from "node:path";

const MIN = 54;
const testDir = "dashboard/build/frontend-test/test/ts";
const outDir = "dashboard/build/frontend-test-results";
mkdirSync(outDir, { recursive: true });
const files = readdirSync(testDir)
  .filter((f) => f.endsWith(".test.js"))
  .sort()
  .map((f) => join(testDir, f));
// 試験ファイルの取りこぼし（削除・tsconfig の include 漏れ）を件数だけで見逃さない（レビュー 低）
const EXPECTED = ["api.test.js", "contract.test.js", "format.test.js", "validate.test.js", "view.test.js"];
const missing = EXPECTED.filter((f) => !files.includes(join(testDir, f)));
if (files.length === 0 || missing.length > 0) {
  console.error(`::error::試験ファイルが無い: ${missing.join(", ") || "すべて"}（tsc -p dashboard/tsconfig.test.json を先に実行）`);
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
  { stdio: "inherit", env: { ...process.env, LC_ALL: "C.UTF-8", TZ: "UTC" } },
);
const v = spawnSync(
  process.execPath,
  [
    "build/tools/src/main.js",
    "verify-junit",
    "--label",
    "dashboard frontend (TypeScript)",
    "--dir",
    `${outDir}/junit.xml`,
    "--min",
    String(MIN),
  ],
  { stdio: "inherit" },
);
process.exit(r.status !== 0 ? (r.status ?? 1) : (v.status ?? 1));
