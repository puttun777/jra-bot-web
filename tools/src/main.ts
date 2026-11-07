/**
 * TypeScript ツールの入口（`node build/tools/src/main.js <コマンド> …`）。各コマンドの説明は各モジュールの先頭。
 *   classify | gate | consistency | verify-junit | preflight | release | image-smoke | publish
 */
import * as classify from "./ci/classify.js";
import * as consistency from "./ci/consistency.js";
import * as gate from "./ci/gate.js";
import * as junit from "./ci/junit.js";
import * as preflight from "./ci/preflight.js";
import * as releaseCli from "./release/cli.js";
import * as imageSmoke from "./release/image-smoke.js";
import * as publish from "./release/publish.js";

const COMMANDS: Readonly<Record<string, (args: readonly string[]) => number | Promise<number>>> = {
  classify: () => classify.main(),
  gate: () => gate.main(),
  consistency: () => consistency.main(),
  "verify-junit": (a) => junit.main(a),
  preflight: (a) => preflight.main(a),
  release: (a) => releaseCli.main(a),
  "image-smoke": (a) => imageSmoke.main(a),
  publish: (a) => publish.main(a),
};

async function entry(argv: readonly string[]): Promise<number> {
  const [cmd, ...rest] = argv;
  const fn = cmd === undefined ? undefined : COMMANDS[cmd];
  if (fn === undefined) {
    console.error(`使い方: main.js <${Object.keys(COMMANDS).join("|")}> [引数]`);
    return 2;
  }
  return fn(rest);
}

entry(process.argv.slice(2)).then(
  (code) => {
    process.exitCode = code;
  },
  (e: unknown) => {
    console.error(e instanceof Error ? (e.stack ?? e.message) : String(e));
    process.exitCode = 1;
  },
);
