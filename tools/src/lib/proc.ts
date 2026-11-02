/** 子プロセスの実行（シェルを介さない。引数は配列で渡す）。 */
import { type SpawnSyncOptions, spawnSync } from "node:child_process";

export interface RunResult {
  readonly status: number;
  readonly stdout: string;
  readonly stderr: string;
  readonly timedOut: boolean;
}

export interface RunOptions {
  readonly cwd?: string;
  readonly env?: NodeJS.ProcessEnv;
  readonly input?: string | Buffer;
  /** ミリ秒。超えたら SIGKILL して timedOut=true（status は 124）。 */
  readonly timeoutMs?: number;
  readonly maxBuffer?: number;
}

export function run(cmd: string, args: readonly string[], opts: RunOptions = {}): RunResult {
  const o: SpawnSyncOptions = {
    encoding: "utf8",
    maxBuffer: opts.maxBuffer ?? 256 * 1024 * 1024,
    killSignal: "SIGKILL",
  };
  if (opts.cwd !== undefined) o.cwd = opts.cwd;
  if (opts.env !== undefined) o.env = opts.env;
  if (opts.input !== undefined) o.input = opts.input;
  if (opts.timeoutMs !== undefined) o.timeout = opts.timeoutMs;
  const r = spawnSync(cmd, [...args], o);
  const timedOut = r.error !== undefined && "code" in r.error && r.error.code === "ETIMEDOUT";
  if (r.error !== undefined && !timedOut) {
    return { status: 127, stdout: "", stderr: `${cmd}: ${r.error.message}`, timedOut: false };
  }
  return {
    status: timedOut ? 124 : (r.status ?? 128),
    stdout: typeof r.stdout === "string" ? r.stdout : (r.stdout?.toString("utf8") ?? ""),
    stderr: typeof r.stderr === "string" ? r.stderr : (r.stderr?.toString("utf8") ?? ""),
    timedOut,
  };
}

/** 失敗したら例外にする（呼出側で fail closed に倒す用途）。 */
export function runChecked(cmd: string, args: readonly string[], opts: RunOptions = {}): string {
  const r = run(cmd, args, opts);
  if (r.status !== 0) {
    throw new Error(`${cmd} ${args.join(" ")} が失敗 (status=${r.status}): ${r.stderr.slice(0, 300)}`);
  }
  return r.stdout;
}

export function git(args: readonly string[], cwd?: string): string {
  return runChecked("git", args, cwd === undefined ? {} : { cwd });
}
