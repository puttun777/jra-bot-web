/**
 * push・PR 作成の前に、ローカルで CI 相当の検査を実行する（GitHub Actions の節約。CI を試行錯誤の実行環境にしない）。旧 tools/ci/preflight.py の移植。
 *
 * 使い方（リポジトリのルートで。`npm ci` 済み）::
 *   npm run preflight                       # origin/main との差分から必要な検査を選んで実行（失敗しても最後まで実行して一覧表示）
 *   npm run preflight -- --list             # 選ばれる検査だけを表示（実行しない）
 *   npm run preflight -- --only kotlin-build,hygiene
 *   npm run preflight -- --full             # 変更範囲に関わらず全部（CI の full 相当）
 *   npm run preflight -- --fail-fast
 *
 * **二重管理を避ける**: 変更範囲 → 検査の選択は CI と同じ classify をそのまま使う。個々の検査のコマンドは `.github/workflows/ci.yml` の
 * 同名ステップの run ブロックを YAML として読み出して実行する（『Verify that …』の件数検査を含む）。CI の手順を変えたら test/preflight.test.ts が落ちる。
 *
 * **環境の違いを偽らない**: JDK・Node・MySQL の版を実行ごとに記録し、CI と違うものは『差異』として結果に出す。
 * Windows 固有（agent-windows）・実 IAP・GitHub 固有の検査はローカルで実行できないので『未実施』と表示する。
 *
 * 記録: `build/preflight/last.json`（検証したコミット・ツリー・版・結果）と `build/preflight/<id>.log`（詳細ログ。Git には入れない）。
 */
import { spawn, spawnSync } from "node:child_process";
import { closeSync, existsSync, mkdirSync, openSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { parseArgs } from "node:util";
import { parse as parseYaml } from "yaml";
import { isRecord } from "../lib/json.js";
import { run } from "../lib/proc.js";
import { repoRoot } from "../release/repo-facts.js";
import { classify, fullPlan, type Plan } from "./classify.js";

export const CI_JDK = "25";
export const CI_NODE = "24";

export interface Step {
  readonly id: string;
  readonly title: string;
  readonly commands: readonly (readonly string[])[];
  readonly env?: Readonly<Record<string, string>>;
  /** 専用の手順（commands の代わり） */
  readonly special?: "agent" | "images";
  readonly needsDocker?: boolean;
  readonly needsJdk25?: boolean;
  readonly caveat?: string;
  /** ci.yml の『Verify that …』ステップ名（実行後に同じ検査を行う）。[job, step] */
  readonly verify?: readonly (readonly [string, string])[];
}

type Status = "ok" | "fail" | "not_run";

interface Result {
  readonly id: string;
  readonly title: string;
  readonly status: Status;
  readonly seconds: number;
  readonly note: string;
}

const UTF8 = { LC_ALL: "C.UTF-8", LANG: "C.UTF-8" } as const;

function sh(root: string, ...args: string[]): string {
  const r = run(args[0] ?? "", args.slice(1), { cwd: root });
  if (r.status !== 0) throw new Error(`${args.join(" ")}: ${r.stderr.slice(0, 200)}`);
  return r.stdout.trim();
}

export function defaultBase(root: string): string {
  for (const ref of ["origin/main", "main"]) {
    if (run("git", ["rev-parse", "--verify", "-q", ref], { cwd: root }).status === 0) return sh(root, "git", "merge-base", "HEAD", ref);
  }
  throw new Error("origin/main が見つからない。git fetch origin main を実行してください");
}

/** base からの差分（コミット済み + 作業ツリー + 未追跡）。CI の pull_request 判定より広い = 安全側。 */
export function changedFiles(root: string, base: string): string[] {
  const names = new Set(
    sh(root, "git", "diff", "--name-only", "--no-renames", base)
      .split("\n")
      .filter((x) => x !== ""),
  );
  for (const n of sh(root, "git", "ls-files", "--others", "--exclude-standard").split("\n")) if (n !== "") names.add(n);
  return [...names].sort();
}

/** ci.yml の jobs.<job>.steps のうち name が一致するステップの run ブロックを返す（件数・必須クラス・スキップ 0 の検査。二重管理しない）。 */
export function ciRunBlock(root: string, job: string, stepName: string): string {
  const doc: unknown = parseYaml(readFileSync(join(root, ".github/workflows/ci.yml"), "utf8"));
  const jobs = isRecord(doc) && isRecord(doc["jobs"]) ? doc["jobs"] : {};
  const j = jobs[job];
  const steps = isRecord(j) && Array.isArray(j["steps"]) ? j["steps"] : [];
  for (const s of steps) {
    if (isRecord(s) && s["name"] === stepName && typeof s["run"] === "string") return s["run"];
  }
  throw new Error(`ci.yml の jobs.${job} に『${stepName}』の run が見つからない`);
}

// ---------------------------------------------------------------------------------------------- 環境の検出
export function findJdk25(): string | undefined {
  const cands = [process.env["PREFLIGHT_JAVA_HOME"] ?? ""];
  for (const base of ["/opt", "/usr/lib/jvm"]) {
    try {
      for (const d of readdirSync(base).sort()) {
        if (/^jdk25|25/.test(d)) {
          cands.push(join(base, d));
          // /opt/jdk25dl/jdk-25.x のような 1 段下も見る
          try {
            for (const s of readdirSync(join(base, d))) if (s.startsWith("jdk-25")) cands.push(join(base, d, s));
          } catch {
            // ディレクトリでない
          }
        }
      }
    } catch {
      // 無い
    }
  }
  return cands.find((c) => c !== "" && existsSync(join(c, "bin", "java")));
}

export function javaVersion(home: string | undefined): string {
  const exe = home !== undefined ? join(home, "bin", "java") : "java";
  const r = run(exe, ["-version"]);
  if (r.status === 127) return "none";
  return /version "([^"]+)"/.exec(r.stderr)?.[1] ?? "?";
}

function dockerOk(): boolean {
  return run("docker", ["info"], { timeoutMs: 20_000 }).status === 0;
}

async function ensureDocker(out: string): Promise<boolean> {
  if (dockerOk()) return true;
  const isRoot = typeof process.getuid === "function" && process.getuid() === 0;
  if (!isRoot || run("sh", ["-c", "command -v dockerd"]).status !== 0) return false;
  mkdirSync(out, { recursive: true });
  const fd = openSync(join(out, "dockerd.log"), "w");
  spawn("dockerd", [], { stdio: ["ignore", fd, fd], detached: true }).unref();
  closeSync(fd);
  for (let i = 0; i < 30; i++) {
    await new Promise((r) => setTimeout(r, 1000));
    if (dockerOk()) return true;
  }
  return false;
}

/** jraagent-interop（Kotlin の AgentInteropIntegrationTest が起動する C# のドライバ）を Release でビルドし、実行ファイルのパスを返す（CI と同じ）。 */
function interopDriver(root: string, log: number): string {
  const proj = join(root, "agent", "cs", "tools", "JraAgent.Interop", "JraAgent.Interop.csproj");
  if (runLogged(["dotnet", "build", proj, "-c", "Release", "-p:RestoreLockedMode=true"], root, process.env, log) !== 0)
    throw new Error("jraagent-interop をビルドできない");
  return join(root, "agent", "cs", "tools", "JraAgent.Interop", "bin", "Release", "net10.0", "jraagent-interop");
}

// ---------------------------------------------------------------------------------------------- 検査の定義
export function stepsFor(plan: Plan, files: readonly string[], root: string, out: string): Step[] {
  const s: Step[] = [];
  s.push({ id: "diff-check", title: "git diff --check（空白・競合マーカー）", commands: [["git", "diff", "--check"]] });
  s.push({
    id: "hygiene",
    title: "秘密・実データ・生成物の混入検査（CI の repo-hygiene と同一）",
    commands: [["bash", ".github/scripts/check-repo-hygiene.sh"]],
  });
  s.push({
    id: "tools-ts",
    title: "TypeScript: lint・型検査・ビルド・単体（tools と画面。CI の release-tools と同一）",
    commands: [
      ["npm", "run", "--silent", "lint"],
      ["npm", "run", "--silent", "build"],
      ["node", "tools/run-tests.mjs"],
      ["npm", "run", "--silent", "test:frontend"],
    ],
  });
  s.push({
    id: "consistency",
    title: "整合検査（Flyway 版・スキーマ版・必須環境変数・スモーク設定・infra 契約）",
    commands: [["node", "build/tools/src/main.js", "consistency"]],
  });
  if (plan.runBuild) {
    s.push({
      id: "kotlin-build",
      title: "Kotlin: ./gradlew build + checkModuleDependencies + ktlintCheck（JDK 25）",
      commands: [
        ["./gradlew", "build", "--stacktrace"],
        ["./gradlew", "checkModuleDependencies", "ktlintCheck"],
      ],
      verify: [
        ["build", "Verify that the cloud-gcp unit tests actually ran"],
        ["build", "Verify that the app and persistence unit tests actually ran"],
      ],
      env: UTF8,
      needsJdk25: true,
    });
    s.push({
      id: "kotlin-golden",
      title: "旧新ゴールデン: フィクスチャの検証と Kotlin ランナーの比較（Kotlin だけ）",
      commands: [
        [
          "./gradlew",
          ":golden-runner:run",
          "--stacktrace",
          `--args=check --fixtures ${root}/tools/golden/fixtures --catalog ${root}/tools/golden/catalog.json --report ${out}/golden-report.json`,
        ],
      ],
      env: UTF8,
      needsJdk25: true,
    });
  }
  const suites = plan.dbSuites;
  for (const mysql of plan.dbMatrix) {
    const tasks: string[][] = [];
    const verify: [string, string][] = [];
    if (suites.includes("migration")) {
      tasks.push(["./gradlew", ":db-migration:integrationTest", "--stacktrace"]);
      verify.push(["db-integration", "Verify that the integration tests actually ran"]);
    }
    if (suites.includes("persistence")) {
      tasks.push(["./gradlew", ":persistence:integrationTest", "--stacktrace"]);
      verify.push(["db-integration", "Verify that the persistence integration tests actually ran"]);
    }
    if (suites.includes("apps")) {
      tasks.push([
        "./gradlew",
        ":bot:integrationTest",
        ":ingest:integrationTest",
        ":dashboard:integrationTest",
        "--continue",
        "--stacktrace",
      ]);
      verify.push(["db-integration", "Verify that the app integration tests actually ran"]);
    }
    if (tasks.length > 0) {
      s.push({
        id: `db-it-${mysql}`,
        title: `MySQL ${mysql} 統合試験（${suites.join(",")}）`,
        commands: tasks,
        env: { ...UTF8, MYSQL_IMAGE: `mysql:${mysql}` },
        needsDocker: true,
        needsJdk25: true,
        verify,
        caveat: "AGENT_INTEROP は preflight が dotnet build して渡す（CI と同じ）",
      });
    }
  }
  if (plan.runAgent)
    s.push({
      id: "agent",
      title: "agent（C#/.NET 10）: restore（lock file どおり）・build（警告はエラー）・xunit",
      commands: [],
      special: "agent",
    });
  const appRuntimeChanged = files.some((f) => /^(bot|ingest|dashboard|cloud-gcp|persistence|domain)\/src\/main\//.test(f));
  if (plan.runImages || appRuntimeChanged) {
    // CI は kotlin のアプリ/ドメイン変更だけでは images を実行しない（節約）。起動設定の食い違いを push 前に捕まえるため、ローカルでは実行する
    s.push({
      id: "images",
      title: "Docker 5 イメージのビルド + 起動スモーク",
      commands: [],
      special: "images",
      needsDocker: true,
      caveat: plan.runImages ? "" : "CI ではこの変更では実行されない。ローカルのみの追加検査",
    });
  }
  return s;
}

// ---------------------------------------------------------------------------------------------- 実行
function runLogged(cmd: readonly string[], cwd: string, env: NodeJS.ProcessEnv, log: number): number {
  writeFileSync(log, `$ ${cmd.join(" ")}\n`);
  const r = spawnSync(cmd[0] ?? "", cmd.slice(1), { cwd, env, stdio: ["ignore", log, log] });
  return r.status ?? 1;
}

function bash(script: string, cwd: string, env: NodeJS.ProcessEnv, log: number, label: string): number {
  writeFileSync(log, `$ verify: ${label}\n`);
  const r = spawnSync("bash", ["-eo", "pipefail", "-c", script], { cwd, env, stdio: ["ignore", log, log] });
  return r.status ?? 1;
}

function runSpecial(step: Step, root: string, out: string, env: NodeJS.ProcessEnv, log: number): number {
  const steps = (cmds: readonly (readonly string[])[], cwd: string, e: NodeJS.ProcessEnv): number => {
    for (const c of cmds) if (runLogged(c, cwd, e, log) !== 0) return 1;
    return 0;
  };
  const junit = (name: string): string => {
    const d = join(out, `junit-${name}`);
    mkdirSync(d, { recursive: true });
    return d;
  };
  switch (step.special) {
    case "agent": {
      const cs = join(root, "agent", "cs");
      const d = junit("agent");
      const exe = join(cs, "tests", "JraAgent.Core.Tests", "bin", "Release", "net10.0", "JraAgent.Core.Tests");
      const rc = steps(
        [
          ["dotnet", "restore", "JraAgent.slnx", "--locked-mode"],
          ["dotnet", "build", "JraAgent.slnx", "--no-restore", "-c", "Release"],
          [exe, "-reporter", "quiet", "-result-junit", join(d, "agent-junit", "agent.xml")],
        ],
        cs,
        env,
      );
      if (rc !== 0) return rc;
      // CI の検査ブロックは $RUNNER_TEMP/agent-junit を読む
      return bash(
        ciRunBlock(root, "agent", "Verify that the tests actually ran"),
        root,
        { ...env, RUNNER_TEMP: d },
        log,
        "agent tests actually ran",
      );
    }
    case "images": {
      const sha = sh(root, "git", "rev-parse", "HEAD");
      for (const t of ["db-migration", "bot", "ingest", "dashboard", "ml-train"]) {
        if (
          runLogged(
            ["docker", "build", "--target", t, "--build-arg", `GIT_SHA=${sha}`, "-t", `jra-bot/${t}:${sha}`, "."],
            root,
            env,
            log,
          ) !== 0
        )
          return 1;
      }
      return runLogged(
        ["node", "build/tools/src/main.js", "image-smoke", "--git-sha", sha, "--report-dir", join(out, "images")],
        root,
        env,
        log,
      );
    }
    case undefined:
      return 1;
  }
  return 1;
}

export async function main(argv: readonly string[]): Promise<number> {
  const { values: a } = parseArgs({
    args: [...argv],
    strict: true,
    options: {
      base: { type: "string" },
      list: { type: "boolean" },
      only: { type: "string" },
      full: { type: "boolean" },
      "fail-fast": { type: "boolean" },
    },
  });
  const root = repoRoot();
  const out = join(root, "build", "preflight");
  const base = a.base ?? defaultBase(root);
  const files = changedFiles(root, base);
  const plan = a.full === true ? fullPlan("--full 指定", files.length) : classify(files);
  let steps = stepsFor(plan, files, root, out);
  if (a.only !== undefined) {
    const wanted = new Set(a.only.split(","));
    steps = steps.filter((x) => wanted.has(x.id));
  }
  const jdk = findJdk25();
  const jv = javaVersion(jdk);
  const dirty = sh(root, "git", "status", "--porcelain") !== "";
  console.log(`preflight: base=${base.slice(0, 10)} files=${files.length} mode=${plan.mode} (${plan.reason})`);
  console.log(
    `  JDK: ${jv} (CI: Temurin ${CI_JDK}) / Node: ${process.versions.node} (CI: ${CI_NODE}) / docker: ${dockerOk() ? "あり" : "停止中（実行時に起動を試みる）"} / 作業ツリー: ${dirty ? "未コミットの変更あり" : "クリーン"}`,
  );
  if (a.list === true) {
    for (const x of steps) console.log(`  - ${x.id}: ${x.title}`);
    return 0;
  }
  mkdirSync(out, { recursive: true });
  const haveDocker = await ensureDocker(out);
  const results: Result[] = [];
  for (const st of steps) {
    if (st.needsDocker === true && !haveDocker) {
      results.push({ id: st.id, title: st.title, status: "not_run", seconds: 0, note: "Docker が使えない" });
      continue;
    }
    if (st.needsJdk25 === true && !(jv === CI_JDK || jv.startsWith(`${CI_JDK}.`))) {
      results.push({
        id: st.id,
        title: st.title,
        status: "not_run",
        seconds: 0,
        note: `JDK ${CI_JDK} が無い（検出: ${jv}）。PREFLIGHT_JAVA_HOME で指定`,
      });
      continue;
    }
    const env: NodeJS.ProcessEnv = { ...process.env, ...(st.env ?? {}) };
    if (jdk !== undefined && st.needsJdk25 === true) env["JAVA_HOME"] = jdk;
    const t0 = Date.now();
    console.log(`  ▶ ${st.id}: ${st.title}`);
    const logPath = join(out, `${st.id}.log`);
    const log = openSync(logPath, "w");
    let rc = 0;
    try {
      if (st.id.startsWith("db-it-")) {
        env["AGENT_INTEROP"] = interopDriver(root, log);
      }
      if (st.special !== undefined) rc = runSpecial(st, root, out, env, log);
      else {
        for (const c of st.commands) {
          rc = runLogged(c, root, env, log);
          if (rc !== 0 && !st.id.startsWith("db-it-")) break;
        }
        // 実行されたテスト数・必須クラス・スキップ 0 の検査（CI と同じ手順。db-it は失敗しても件数検査を行う: CI の always() と同じ）
        if (rc === 0 || st.id.startsWith("db-it-")) {
          for (const [job, name] of st.verify ?? []) {
            const v = bash(ciRunBlock(root, job, name), root, env, log, name);
            rc = rc !== 0 ? rc : v;
          }
        }
      }
    } catch (e) {
      writeFileSync(log, `preflight の内部エラー: ${e instanceof Error ? e.message : String(e)}\n`);
      rc = 1;
    } finally {
      closeSync(log);
    }
    const r: Result = {
      id: st.id,
      title: st.title,
      status: rc === 0 ? "ok" : "fail",
      seconds: (Date.now() - t0) / 1000,
      note: st.caveat ?? "",
    };
    results.push(r);
    console.log(`    ${rc === 0 ? "OK" : "FAIL"} (${r.seconds.toFixed(0)}s) → build/preflight/${st.id}.log`);
    if (rc !== 0 && a["fail-fast"] === true) break;
  }
  const notLocal: string[] = [];
  if (plan.runAgentWindows) notLocal.push("agent-windows（Windows ランナー）");
  if (plan.runConnectorj26) notLocal.push("connectorj26（非ゲートの互換性確認）");
  const sha = sh(root, "git", "rev-parse", "HEAD");
  const record = {
    head: sha,
    tree: sh(root, "git", "rev-parse", "HEAD^{tree}"),
    dirty,
    base,
    mode: plan.mode,
    reason: plan.reason,
    jdk: jv,
    node: process.versions.node,
    results,
    not_run_locally: notLocal,
    finished_at: new Date().toISOString(),
  };
  writeFileSync(join(out, "last.json"), `${JSON.stringify(record, null, 2)}\n`, "utf8");
  console.log("\n== preflight 結果 ==");
  const mark: Record<Status, string> = { ok: "OK  ", fail: "FAIL", not_run: "未実施" };
  for (const r of results) console.log(`  [${mark[r.status]}] ${r.id}: ${r.title}${r.note !== "" ? `  ※${r.note}` : ""}`);
  for (const n of notLocal) console.log(`  [未実施] ${n}: ローカルでは実行できない（GitHub Actions のみ）`);
  if (dirty)
    console.log(
      "  注意: 作業ツリーに未コミットの変更がある。記録（build/preflight/last.json）はコミット済みのツリーとは一致しない。push 前にコミットして再実行すること",
    );
  console.log(`  記録: build/preflight/last.json（head=${sha.slice(0, 10)}）`);
  const bad = results.filter((r) => r.status === "fail");
  const notRun = results.filter((r) => r.status === "not_run");
  if (bad.length > 0) {
    console.log(`\nFAIL ${bad.length} 件 — push しない。ログ: build/preflight/<id>.log`);
    return 1;
  }
  if (notRun.length > 0) console.log(`\n未実施 ${notRun.length} 件あり（上記）。『全検証が成功』とは報告しない。`);
  return 0;
}
