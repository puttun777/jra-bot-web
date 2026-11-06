/**
 * ビルド済みイメージ（ローカルのタグ）のセキュリティ・起動スモーク。旧 tools/release/image_smoke.py の移植（docker CLI だけを使う）。
 *
 * - レジストリ・GCP・インターネットには接続しない。アプリのコンテナは原則 `--network none`。DB が要る検証だけ、使い捨ての MySQL コンテナ
 *   （CI 専用・合成データ・ランダムなパスワード）と同じ一時ネットワークに置く。実データ・本番の秘密は使わない。
 * - 実行できなかった検査は PASS にしない（FAIL）。結果は JSON で保存する（`--report-dir`）。
 * - 出力: `smoke-report.json`、`images-local.json`（release-manifest プレビューの入力。ローカルの image ID であり、レジストリの digest ではない）。
 *
 * 使い方: image-smoke --git-sha <40桁> [--prefix jra-bot] [--tag <git sha>] [--mysql-image mysql:8.4] [--report-dir DIR]
 */
import { spawn } from "node:child_process";
import { randomBytes } from "node:crypto";
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, join } from "node:path";
import { parseArgs } from "node:util";
import { gunzipSync, gzipSync } from "node:zlib";
import { readTar, ZipReader } from "../lib/archive.js";
import { isRecord } from "../lib/json.js";
import { type RunResult, run } from "../lib/proc.js";
import { flywayVersions, IMAGE_NAMES, type ImageName, MIGRATION_DIR, repoRoot, requiredSchemaVersions } from "./repo-facts.js";

export const UID = "10001:10001";
const ENTRYPOINT_JVM = ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError"];
export const EXPECTED_ENTRYPOINT: Readonly<Record<ImageName, readonly string[]>> = {
  dashboard: [...ENTRYPOINT_JVM, "-jar", "/app/app.jar"],
  ingest: [...ENTRYPOINT_JVM, "-jar", "/app/app.jar"],
  bot: [...ENTRYPOINT_JVM, "-jar", "/app/app.jar"],
  "db-migration": [...ENTRYPOINT_JVM, "-cp", "/app/lib/*", "jp.jrabot.dbmigration.DbMigrationMainKt"],
  "ml-train": [...ENTRYPOINT_JVM, "-jar", "/app/app.jar"],
};
export const SECRET_ENV_NAME = /(PASSWORD|SECRET|TOKEN|CREDENTIAL|PRIVATE|API_?KEY|ACCESS_?KEY)/i;
export const FORBIDDEN_FILE =
  /(^|\/)(\.env(\..*)?|id_rsa|id_ed25519|credentials[^/]*\.json|service-account[^/]*\.json|[^/]*\.(pem|key|p12|jks|pfx|sqlite3?|db|pkl|pickle|dump|bak|tfstate|tfvars|pdf))$/i;
export const PRIVATE_KEY = /-----BEGIN [A-Z ]*PRIVATE KEY-----/;
export const SECRET_ASSIGNMENT = /^\s*[\w.-]*(password|secret|token|api[_-]?key)[\w.-]*\s*[=:]\s*(?!\$\{)(?!\s*$)(\S+)/im;
const TEXT_RESOURCE = /\.(properties|ya?ml|xml|json|sql|txt|md|conf|toml)$/;

/** ingest の追加の必須設定（本番相当の起動には署名鍵の KEK が要る。合成の値。秘密ではない）。 */
export const INGEST_EXTRA: Readonly<Record<string, string>> = {
  INTERNAL_ID_TOKEN_AUDIENCE: "https://ingest.example.invalid",
  OPS_SA_EMAIL: "ops@example.invalid",
  AGENT_KEK_KEYS: "k1:AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
  AGENT_KEK_ACTIVE_KID: "k1",
};
/** calendar-check は監視ウィンドウ・鮮度の設定が必須（既定値なし。CONTRACTS §2）。合成のテスト値で、運用の既定値ではない。 */
export const CALENDAR_ENV: Readonly<Record<string, string>> = {
  CALENDAR_WATCH_START_MINUTES: "120",
  CALENDAR_PRESTART_MINUTES: "10",
  CALENDAR_SETTLE_DEADLINE_MINUTES: "90",
  CALENDAR_STALE_AFTER_MINUTES: "120",
};
/**
 * bot-run の環境変数（運用値の既定は無い: CONTRACTS §2 / BotRunSettingsParser）。スモーク用の**合成値**（運用値ではない）。
 * tools/src/ci/consistency.ts が、BotRunSettings の必須変数がすべてここにあることを検査する。
 */
export const BOT_RUN_ENV: Readonly<Record<string, string>> = {
  BOT_RUN_LEASE_SECONDS: "300",
  BOT_RUN_HEARTBEAT_SECONDS: "60",
  BOT_RUN_POLL_FAR_SECONDS: "300",
  BOT_RUN_POLL_MID_SECONDS: "60",
  BOT_RUN_POLL_NEAR_SECONDS: "30",
  BOT_RUN_MAX_ODDS_AGE_SECONDS: "180",
  BOT_RUN_MAX_RUNTIME_MINUTES: "120",
  BOT_RUN_STALE_CLAIM_SECONDS: "600",
  BOT_RUN_MAX_CYCLE_FAILURES: "5",
  BOT_RUN_SNAPSHOT_MINUTES: "30,5",
  BOT_RUN_TIGHTEN_NEAR: "false",
  BOT_RUN_SNAPSHOT_HISTORY_LIMIT: "3",
  BOT_RUN_RESULT_AFTER_MINUTES: "30",
  BOT_RUN_RESULT_POLL_SECONDS: "300",
  BOT_RUN_ML_MODEL_TYPE: "off",
  BOT_RUN_DECISION_PROFILE: "legacy_function_defaults",
};
const DASHBOARD_EXTRA: Readonly<Record<string, string>> = {
  IAP_AUDIENCE: "/projects/0/global/backendServices/0",
  OPS_SA_EMAIL: "ops@example.invalid",
};

interface CheckResult {
  readonly name: string;
  readonly ok: boolean;
  readonly detail: string;
}

class Report {
  readonly results: CheckResult[] = [];
  check(name: string, ok: boolean, detail = ""): boolean {
    this.results.push({ name, ok, detail });
    console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail !== "" && !ok ? `  -- ${detail}` : ""}`);
    return ok;
  }
  get failed(): CheckResult[] {
    return this.results.filter((r) => !r.ok);
  }
}

function docker(args: readonly string[], timeoutSec = 180): RunResult {
  return run("docker", args, { timeoutMs: timeoutSec * 1000 });
}

function dockerChecked(args: readonly string[], timeoutSec = 180): string {
  const r = docker(args, timeoutSec);
  if (r.status !== 0) throw new Error(`docker ${args[0] ?? ""}: ${r.stderr.slice(0, 200)}`);
  return r.stdout;
}

function hex(n: number): string {
  return randomBytes(n).toString("hex");
}

export function jsonLine(out: string): Record<string, unknown> {
  const lines = out.trim().split("\n");
  const last = lines.at(-1);
  if (last === undefined) return {};
  try {
    const v: unknown = JSON.parse(last);
    return isRecord(v) ? v : {};
  } catch {
    return {};
  }
}

async function httpStatus(url: string): Promise<number> {
  const r = await fetch(url, { signal: AbortSignal.timeout(5000), redirect: "manual" });
  await r.body?.cancel();
  return r.status;
}

/** 静的な画面の検査用（本文は先頭 64 KiB まで）。 */
async function httpGet(url: string): Promise<{ status: number; type: string; csp: string; body: string }> {
  const r = await fetch(url, { signal: AbortSignal.timeout(5000), redirect: "manual" });
  const body = (await r.text()).slice(0, 65536);
  return { status: r.status, type: r.headers.get("content-type") ?? "", csp: r.headers.get("content-security-policy") ?? "", body };
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

interface RunOpts {
  env?: Readonly<Record<string, string>>;
  args?: readonly string[];
  network?: string;
  mounts?: readonly string[];
  desc?: string;
  timeout?: number;
  entrypoint?: string;
}

interface ImageInfo {
  readonly image_id: string;
  readonly size_bytes: number;
  readonly local_tag: string;
}

export class Smoke {
  readonly r = new Report();
  private readonly canaries: string[];
  private readonly captured: [string, string][] = [];
  private readonly network = `jrasmoke-${hex(4)}`;
  private readonly mysql = `jrasmoke-mysql-${hex(4)}`;
  private readonly tmp = mkdtempSync(join(tmpdir(), "jrasmoke-"));
  private readonly facts = repoRoot();
  private readonly passwords: Readonly<Record<string, string>>;
  private readonly created: string[] = [];
  private mlKeyValue: string | undefined;

  constructor(
    private readonly prefix: string,
    private readonly tag: string,
    private readonly gitSha: string,
    private readonly mysqlImage: string,
    private readonly reportDir: string | undefined,
  ) {
    const pw: Record<string, string> = {};
    for (const u of ["root", "migrate", "app", "ingest", "bot", "mltrain"]) pw[u] = `ci-only-${hex(8)}`;
    this.passwords = pw;
    this.canaries = Object.values(pw);
  }

  private pw(user: string): string {
    const p = this.passwords[user];
    if (p === undefined) throw new Error(`未知のユーザー ${user}`);
    return p;
  }

  image(name: string): string {
    return `${this.prefix}/${name}:${this.tag}`;
  }

  // ------------------------------------------------------------------ 実行ヘルパ
  /** 使い捨てコンテナで実行（読み取り専用の rootfs、/tmp は tmpfs、権限を落とす）。network 未指定は none。 */
  runIn(name: string, o: RunOpts = {}): [number, string, string] {
    const cmd = [
      "run",
      "--rm",
      "--read-only",
      "--tmpfs",
      "/tmp",
      "--cap-drop",
      "ALL",
      "--security-opt",
      "no-new-privileges",
      "--network",
      o.network ?? "none",
    ];
    for (const [k, v] of Object.entries(o.env ?? {})) cmd.push("-e", `${k}=${v}`);
    for (const m of o.mounts ?? []) cmd.push("-v", m);
    if (o.entrypoint !== undefined) cmd.push("--entrypoint", o.entrypoint);
    cmd.push(this.image(name), ...(o.args ?? []));
    const timeout = o.timeout ?? 90;
    const p = docker(cmd, timeout);
    const desc = o.desc ?? `${name} ${JSON.stringify(o.args ?? [])}`;
    if (p.timedOut) {
      this.r.check(`timeout: ${desc}`, false, `${timeout}s`);
      return [124, "", "timeout"];
    }
    this.captured.push([desc, `${p.stdout}\n${p.stderr}`]);
    return [p.status, p.stdout, p.stderr];
  }

  mysqlExec(sql: string, user = "root", password?: string, database = ""): string {
    const p = docker(
      ["exec", "-e", `MYSQL_PWD=${password ?? this.pw("root")}`, this.mysql, "mysql", `-u${user}`, "-N", "-B", database, "-e", sql],
      60,
    );
    if (p.status !== 0) throw new Error(`mysql: ${p.stderr.slice(0, 200)}`);
    return p.stdout.trim();
  }

  connections(): number {
    const n = Number(this.mysqlExec("SHOW GLOBAL STATUS LIKE 'Connections'").split(/\s+/).at(-1));
    if (!Number.isInteger(n)) throw new Error("Connections を読めない");
    return n;
  }

  // ------------------------------------------------------------------ 静的検査
  staticChecks(name: ImageName): ImageInfo | undefined {
    const img = this.image(name);
    const p = docker(["image", "inspect", img]);
    if (!this.r.check(`[${name}] image exists`, p.status === 0, p.stderr.slice(0, 200))) return undefined;
    const parsed: unknown = JSON.parse(p.stdout);
    const info = Array.isArray(parsed) && isRecord(parsed[0]) ? parsed[0] : {};
    const cfg = isRecord(info["Config"]) ? info["Config"] : {};
    const ep = cfg["Entrypoint"];
    this.r.check(
      `[${name}] ENTRYPOINT is exec form and exactly as designed`,
      JSON.stringify(ep) === JSON.stringify(EXPECTED_ENTRYPOINT[name]),
      JSON.stringify(ep),
    );
    const cmdv = cfg["Cmd"];
    this.r.check(
      `[${name}] no CMD (args from Cloud Run are appended to ENTRYPOINT)`,
      cmdv === null || cmdv === undefined || (Array.isArray(cmdv) && cmdv.length === 0),
      JSON.stringify(cmdv),
    );
    this.r.check(`[${name}] runs as numeric non-root user ${UID}`, cfg["User"] === UID, String(cfg["User"]));
    const labels = isRecord(cfg["Labels"]) ? cfg["Labels"] : {};
    this.r.check(
      `[${name}] OCI label revision == git sha`,
      labels["org.opencontainers.image.revision"] === this.gitSha,
      String(labels["org.opencontainers.image.revision"]),
    );
    this.r.check(
      `[${name}] OCI label version == git sha (not the base image's)`,
      labels["org.opencontainers.image.version"] === this.gitSha,
      String(labels["org.opencontainers.image.version"]),
    );
    this.r.check(
      `[${name}] OCI label source present`,
      String(labels["org.opencontainers.image.source"] ?? "").startsWith("https://github.com/"),
    );
    const envList = Array.isArray(cfg["Env"]) ? cfg["Env"].map(String) : [];
    const envNames = envList.map((e) => e.split("=")[0] ?? "");
    this.r.check(`[${name}] image env has no secret-like names`, !envNames.some((n) => SECRET_ENV_NAME.test(n)), JSON.stringify(envNames));
    const env = new Map(envList.map((e) => [e.slice(0, Math.max(0, e.indexOf("="))), e.slice(e.indexOf("=") + 1)]));
    this.r.check(`[${name}] TZ=Asia/Tokyo baked (CONTRACTS §2)`, env.get("TZ") === "Asia/Tokyo");
    const hist = docker(["history", "--no-trunc", "--format", "{{.CreatedBy}}", img]).stdout;
    this.r.check(`[${name}] build history has no secret assignments / ARG secrets`, !/(password|secret|token|api_?key)\s*=/i.test(hist));
    this.r.check(`[${name}] no HEALTHCHECK (Cloud Run uses service probes)`, !cfg["Healthcheck"]);

    {
      const [rc, out, err] = this.runIn(name, { args: ["-version"], entrypoint: "java", desc: `${name} java -version` });
      this.r.check(`[${name}] runs on JDK 25`, rc === 0 && /version "25\./.test(out + err), (out + err).slice(0, 120));
    }
    {
      const [, out] = this.runIn(name, { args: ["-u"], entrypoint: "id", desc: `${name} id` });
      this.r.check(`[${name}] effective uid is 10001`, out.trim() === "10001", out.trim());
    }
    {
      const [, out] = this.runIn(name, {
        args: ["-c", "for c in javac jlink gradle mvn git curl wget gcc cc make unzip; do command -v $c; done"],
        entrypoint: "sh",
        desc: `${name} tools`,
      });
      this.r.check(`[${name}] no build tools / downloaders in the runtime image`, out.trim() === "", out.trim());
    }
    {
      const [rc] = this.runIn(name, { args: ["-c", "touch /app/x"], entrypoint: "sh", desc: `${name} write /app` });
      this.r.check(`[${name}] /app is not writable by the app user`, rc !== 0);
    }
    const id = info["Id"];
    const size = info["Size"];
    return { image_id: typeof id === "string" ? id : "", size_bytes: typeof size === "number" ? size : 0, local_tag: img };
  }

  /** コンテナのファイルシステム全体の名前を走査する（秘密・実データっぽいファイルが無いこと）。 */
  async filesystemChecks(name: ImageName): Promise<void> {
    const c = dockerChecked(["create", this.image(name)]).trim();
    this.created.push(c);
    const proc = spawn("docker", ["export", c], { stdio: ["ignore", "pipe", "ignore"] });
    const exited = new Promise<number>((resolve) => proc.on("close", (code) => resolve(code ?? 1)));
    const names: string[] = [];
    const modes = new Map<string, number>();
    for await (const m of readTar(proc.stdout)) {
      if (m.name.startsWith("app")) modes.set(m.name, m.mode);
      if (m.name.startsWith("app/") && m.isDir) continue; // ディレクトリは「ファイルの一覧」に含めない
      names.push(m.name);
    }
    if (!this.r.check(`[${name}] docker export succeeded (filesystem fully scanned)`, (await exited) === 0)) return;
    const userAreas = names.filter((n) => /^(app|home|root|tmp|srv|opt\/(?!java))/.test(n) || /^etc\/[^/]*\.env/.test(n));
    const bad = userAreas.filter((n) => FORBIDDEN_FILE.test(n));
    this.r.check(
      `[${name}] no secret/real-data file names in app areas (/app /home /root /tmp /srv /opt)`,
      bad.length === 0,
      JSON.stringify(bad.slice(0, 5)),
    );
    const appFiles = names.filter((n) => n.startsWith("app/")).sort();
    const writable = [...modes.entries()].filter(([, mode]) => (mode & 0o022) !== 0).map(([n]) => n);
    const libMode = modes.get("app/lib");
    const traversable = libMode === undefined || (libMode & 0o111) !== 0;
    this.r.check(
      `[${name}] nothing under /app is writable, directories are traversable`,
      writable.length === 0 && traversable,
      JSON.stringify(writable.slice(0, 5)),
    );
    let ok: boolean;
    if (name === "db-migration") {
      ok =
        appFiles.every((n) => /^app\/lib\/[\w.-]+\.jar$/.test(n)) &&
        appFiles.some((n) => n.includes("flyway-core")) &&
        appFiles.some((n) => n.includes("mysql-connector-j"));
    } else {
      ok = JSON.stringify(appFiles) === JSON.stringify(["app/app.jar"]);
    }
    this.r.check(`[${name}] /app contains only the expected artifacts`, ok, JSON.stringify(appFiles.slice(0, 8)));
    docker(["rm", c]);
  }

  jarChecks(name: ImageName): void {
    const c = dockerChecked(["create", this.image(name)]).trim();
    this.created.push(c);
    const required = requiredSchemaVersions(this.facts);
    try {
      if (name === "db-migration") {
        const dir = join(this.tmp, "dbm-lib");
        const p = docker(["cp", `${c}:/app/lib/.`, dir]);
        const jars = p.status === 0 ? readdirSync(dir).filter((f) => /^db-migration-.*\.jar$/.test(f)) : [];
        this.r.check("[db-migration] app jar found", p.status === 0 && jars.length === 1);
        const first = jars[0];
        const zipNames = first !== undefined ? new ZipReader(readFileSync(join(dir, first))).names : [];
        const sql = zipNames
          .filter((n) => n.startsWith("db/migration/") && n.endsWith(".sql"))
          .map((n) => basename(n))
          .sort();
        const expected = readdirSync(join(this.facts, MIGRATION_DIR))
          .filter((f) => f.endsWith(".sql"))
          .sort();
        this.r.check(
          "[db-migration] image embeds exactly the repository's migrations",
          JSON.stringify(sql) === JSON.stringify(expected),
          JSON.stringify(sql),
        );
        return;
      }
      const jarPath = join(this.tmp, `${name}.jar`);
      const p = docker(["cp", `${c}:/app/app.jar`, jarPath]);
      this.r.check(`[${name}] app.jar extractable`, p.status === 0);
      const z = new ZipReader(readFileSync(jarPath));
      const zipNames = z.names;
      const props = new Map<string, string>();
      for (const line of z.read("BOOT-INF/classes/jrabot-app.properties").toString("utf8").split("\n")) {
        if (line.startsWith("#") || !line.includes("=")) continue;
        const i = line.indexOf("=");
        props.set(line.slice(0, i), line.slice(i + 1).replace(/\r$/, ""));
      }
      const req = name === "bot" || name === "ingest" || name === "dashboard" || name === "ml-train" ? required[name] : undefined;
      this.r.check(
        `[${name}] baked requiredSchemaVersion == build.gradle.kts (${String(req)})`,
        props.get("requiredSchemaVersion") === req,
        JSON.stringify([...props]),
      );
      this.r.check(`[${name}] baked releaseId == git sha`, props.get("releaseId") === this.gitSha, props.get("releaseId") ?? "");
      this.r.check(`[${name}] no Flyway in the app image (CONTRACTS §4.1)`, !zipNames.some((n) => n.toLowerCase().includes("flyway")));
      this.r.check(
        `[${name}] MySQL driver present`,
        zipNames.some((n) => n.includes("mysql-connector-j")),
      );
      const forbidden = zipNames.filter((n) => FORBIDDEN_FILE.test(n));
      this.r.check(
        `[${name}] no secret/real-data file names inside the jar`,
        forbidden.length === 0,
        JSON.stringify(forbidden.slice(0, 5)),
      );
      const leaks: string[] = [];
      for (const n of zipNames) {
        if (n.startsWith("BOOT-INF/classes/") && TEXT_RESOURCE.test(n)) {
          const body = z.read(n).toString("utf8");
          if (PRIVATE_KEY.test(body) || SECRET_ASSIGNMENT.test(body)) leaks.push(n);
        }
      }
      this.r.check(`[${name}] no secret-like assignments in own resources`, leaks.length === 0, JSON.stringify(leaks));
      if (name === "ml-train") {
        const testOnly = zipNames.filter((n) => /(legacy-pkl|python-reference|expected\.json|Test\.class$)/.test(n));
        this.r.check(
          "[ml-train] no test-only resources (synthetic pickles, reference fixtures, test classes) in the jar",
          testOnly.length === 0,
          JSON.stringify(testOnly.slice(0, 5)),
        );
        this.r.check(
          "[ml-train] the Hipparchus linear algebra library is bundled",
          zipNames.some((n) => /hipparchus-core-[\d.]+\.jar$/.test(n)),
        );
      }
    } finally {
      docker(["rm", c]);
    }
  }

  // ------------------------------------------------------------------ 動作検査（DB なし）
  botChecksNoDb(): void {
    const pw = this.pw("bot");
    let [rc] = this.runIn("bot", { desc: "bot no args" });
    this.r.check("[bot] no args -> exit 2", rc === 2, `rc=${rc}`);
    [rc] = this.runIn("bot", { args: ["--task=nonsense", "--tenant-id=1"], desc: "bot invalid task" });
    this.r.check("[bot] invalid --task -> exit 2", rc === 2, `rc=${rc}`);
    [rc] = this.runIn("bot", { args: ["--task=calendar-check", "--tenant-id=1"], desc: "bot no env" });
    this.r.check("[bot] valid task but no required settings -> exit 2 (fail safe)", rc === 2, `rc=${rc}`);
    [rc] = this.runIn("bot", { args: ["--task=calendar-check"], env: { APP_ENV: "local" }, desc: "bot no tenant" });
    this.r.check("[bot] missing --tenant-id -> exit 2", rc === 2, `rc=${rc}`);
    const badDb = { DB_URL: "jdbc:mysql://127.0.0.1:1/jrabot", DB_USER: "bot", DB_PASSWORD: pw };
    [rc] = this.runIn("bot", {
      args: ["--task=calendar-check", "--tenant-id=1"],
      env: { APP_ENV: "local", OPS_MODE_SOURCE: "/nonexistent/ops-state.json", ...CALENDAR_ENV, ...badDb },
      desc: "bot UNKNOWN",
    });
    this.r.check("[bot] ModeProvider UNKNOWN (unreadable source) -> exit 75", rc === 75, `rc=${rc}`);
    [rc] = this.runIn("bot", {
      args: ["--task=calendar-check", "--tenant-id=1"],
      env: {
        APP_ENV: "prod",
        OPS_MODE_SOURCE: "/nonexistent/ops-state.json",
        RUN_JOBS_PROJECT_ID: "synthetic-proj1",
        RUN_JOBS_REGION: "asia-northeast1",
        ...CALENDAR_ENV,
        ...badDb,
      },
      desc: "bot prod local file",
    });
    this.r.check("[bot] prod refuses a local-file mode source -> exit 2 (never 0)", rc === 2, `rc=${rc}`);
    const gcs = { APP_ENV: "prod", OPS_MODE_SOURCE: "gs://synthetic-bucket/state/ops-state.json", ...CALENDAR_ENV, ...BOT_RUN_ENV };
    [rc] = this.runIn("bot", { args: ["--task=bot-run", "--tenant-id=1"], env: { ...gcs, ...badDb }, desc: "bot prod gcs unreachable" });
    this.r.check("[bot] prod, mode source unreachable (no network) -> exit 75, never 0", rc === 75, `rc=${rc}`);
    [rc] = this.runIn("bot", {
      args: ["--task=bot-run", "--tenant-id=1"],
      env: { ...gcs, ...badDb, DB_USER: "migrate" },
      desc: "bot prod migrate user",
    });
    this.r.check("[bot] prod with a non-bot DB user -> exit 2", rc === 2, `rc=${rc}`);
  }

  dbmChecksNoDb(): void {
    let [rc, out] = this.runIn("db-migration", { desc: "dbm no args" });
    this.r.check("[db-migration] no args -> exit 2 and empty stdout", rc === 2 && out.trim() === "", `rc=${rc}`);
    [rc] = this.runIn("db-migration", { args: ["--mode=bogus"], desc: "dbm bogus mode" });
    this.r.check("[db-migration] invalid --mode -> exit 2", rc === 2, `rc=${rc}`);
    [rc, out] = this.runIn("db-migration", { args: ["--mode=info"], desc: "dbm no env" });
    this.r.check("[db-migration] --mode=info without DB_URL/DB_USER/DB_PASSWORD -> exit 2", rc === 2 && out.trim() === "", `rc=${rc}`);
    const env = { DB_URL: "jdbc:mysql://127.0.0.1:1/jrabot", DB_USER: "migrate", DB_PASSWORD: this.pw("migrate") };
    [rc, out] = this.runIn("db-migration", { args: ["--mode=info"], env, desc: "dbm unreachable" });
    this.r.check(
      "[db-migration] CLI starts; DB unreachable -> exit 75 with one JSON line on stdout",
      rc === 75 && out.trim().split("\n").length === 1 && out.includes("unavailable"),
      `rc=${rc} out=${out.slice(0, 80)}`,
    );
  }

  /** アプリが起動に失敗した（非 0）ことの確認。docker 自体の失敗（125〜127）・タイムアウト（124）は該当しない */
  private appFailed(rc: number): boolean {
    return rc !== 0 && rc !== 124 && !(rc >= 125 && rc <= 127);
  }

  webChecksNoDb(name: "ingest" | "dashboard", extra: Readonly<Record<string, string>>): void {
    let [rc] = this.runIn(name, { env: { APP_ENV: "prod" }, desc: `${name} prod no settings`, timeout: 120 });
    this.r.check(`[${name}] APP_ENV=prod without required settings fails to start (nonzero, no hang)`, this.appFailed(rc), `rc=${rc}`);
    [rc] = this.runIn(name, { desc: `${name} no env`, timeout: 120 });
    this.r.check(`[${name}] no environment at all fails to start (nonzero)`, this.appFailed(rc), `rc=${rc}`);
    const env = {
      APP_ENV: "prod",
      OPS_MODE_SOURCE: "/nonexistent/ops-state.json",
      DB_URL: "jdbc:mysql://127.0.0.1:1/jrabot",
      DB_USER: "x",
      DB_PASSWORD: this.pw("app"),
      ...extra,
    };
    [rc] = this.runIn(name, { env, desc: `${name} prod with local mode file`, timeout: 120 });
    this.r.check(`[${name}] prod with a local-file mode source fails to start (nonzero)`, this.appFailed(rc), `rc=${rc}`);
  }

  // ------------------------------------------------------------------ 動作検査（CI 専用 MySQL）
  async startMysql(): Promise<boolean> {
    dockerChecked(["network", "create", this.network]);
    const p = docker(
      [
        "run",
        "-d",
        "--name",
        this.mysql,
        "--network",
        this.network,
        "-e",
        `MYSQL_ROOT_PASSWORD=${this.pw("root")}`,
        "-e",
        "MYSQL_DATABASE=jrabot",
        this.mysqlImage,
      ],
      300,
    );
    if (!this.r.check("CI MySQL container starts", p.status === 0, p.stderr.slice(0, 200))) return false;
    let ready = false;
    for (let i = 0; i < 90 && !ready; i++) {
      try {
        // 初期化中の一時サーバと区別するため、データベースが作られていることまで確認する
        ready = this.mysqlExec("SELECT 1") === "1" && this.mysqlExec("SHOW DATABASES").includes("jrabot");
      } catch {
        // 起動待ち
      }
      if (!ready) await sleep(2000);
    }
    if (!ready) return this.r.check("CI MySQL becomes ready", false, "timeout");
    const statements = ["migrate", "app", "ingest", "bot", "mltrain"].map((u) => `CREATE USER '${u}'@'%' IDENTIFIED BY '${this.pw(u)}'`);
    statements.push("GRANT ALL PRIVILEGES ON jrabot.* TO 'migrate'@'%' WITH GRANT OPTION");
    this.mysqlExec(statements.join("; "));
    return this.r.check("CI MySQL ready with synthetic users", true);
  }

  jdbc(): string {
    return `jdbc:mysql://${this.mysql}:3306/jrabot`;
  }

  dbmChecksWithDb(): void {
    const env = { DB_URL: this.jdbc(), DB_USER: "migrate", DB_PASSWORD: this.pw("migrate") };
    const latest = flywayVersions(this.facts).at(-1);
    const net = this.network;
    let [rc, out] = this.runIn("db-migration", { args: ["--mode=info"], env, network: net, desc: "dbm info empty" });
    let j = jsonLine(out);
    this.r.check(
      "[db-migration] info on an empty DB -> exit 0, current=null, latest=repo latest",
      rc === 0 && j["current"] === null && j["latest"] === latest,
      `rc=${rc} ${out.slice(0, 120)}`,
    );
    [rc, out] = this.runIn("db-migration", { args: ["--mode=validate"], env, network: net, desc: "dbm validate pending" });
    this.r.check("[db-migration] validate with pending migrations -> exit 10", rc === 10, `rc=${rc} ${out.slice(0, 120)}`);
    [rc, out] = this.runIn("db-migration", { args: ["--mode=migrate"], env, network: net, desc: "dbm migrate", timeout: 180 });
    j = jsonLine(out);
    const pending = j["pending"];
    this.r.check(
      "[db-migration] migrate against MySQL -> exit 0, success, current=latest",
      rc === 0 &&
        j["success"] === true &&
        j["current"] === latest &&
        (pending === undefined ||
          pending === null ||
          pending === 0 ||
          (Array.isArray(pending) && pending.length === 0) ||
          pending === false),
      `rc=${rc} ${out.slice(0, 160)}`,
    );
    [rc, out] = this.runIn("db-migration", { args: ["--mode=validate"], env, network: net, desc: "dbm validate" });
    this.r.check(
      "[db-migration] validate after migrate -> exit 0",
      rc === 0 && jsonLine(out)["valid"] === true,
      `rc=${rc} ${out.slice(0, 120)}`,
    );
    [rc, out] = this.runIn("db-migration", { args: ["--mode=info"], env, network: net, desc: "dbm info" });
    j = jsonLine(out);
    this.r.check(
      "[db-migration] info after migrate: current == latest == repo latest",
      rc === 0 && j["current"] === latest && j["latest"] === latest,
      out.slice(0, 120),
    );
    [rc, out] = this.runIn("db-migration", {
      args: ["--mode=info"],
      env: { ...env, DB_PASSWORD: `wrong-${hex(4)}` },
      network: net,
      desc: "dbm wrong password",
    });
    this.r.check(
      "[db-migration] wrong password -> exit 1 (not 75), stdout is one JSON line without the password",
      rc === 1 && out.trim().split("\n").length === 1,
      `rc=${rc} ${out.slice(0, 100)}`,
    );
  }

  private writeOpsFiles(full: boolean): string {
    const d = join(this.tmp, "ops");
    mkdirSync(d, { recursive: true });
    chmodSync(d, 0o755);
    for (const mode of ["NORMAL", "STOPPED"] as const) {
      const f = join(d, `${mode}.json`);
      if (!full && existsSync(f)) continue;
      const body = full
        ? {
            schema_version: 1,
            seq: 1,
            mode,
            desired: { mode, set_by: "github:synthetic", set_at: "2026-11-01T10:00:00Z", reason: "smoke" },
            operation: { id: "op-1", type: "stop", status: "SUCCEEDED", force: false },
          }
        : { schema_version: 1, seq: 1, mode, desired: { mode } };
      writeFileSync(f, JSON.stringify(body));
      chmodSync(f, 0o644);
    }
    return d;
  }

  botChecksWithDb(): void {
    const d = this.writeOpsFiles(true);
    const base = {
      APP_ENV: "local",
      DB_URL: this.jdbc(),
      DB_USER: "bot",
      DB_PASSWORD: this.pw("bot"),
      MODEL_STORE_URI: "file:///tmp/store",
      ...CALENDAR_ENV,
      ...BOT_RUN_ENV,
    };
    const mounts = [`${d}:/ops:ro`];
    const net = this.network;
    const tasks = ["bot-run", "calendar-check", "reconcile", "retention", "export-dataset"];
    const extraArgs: Readonly<Record<string, readonly string[]>> = {
      "export-dataset": ["--dataset-id=smoke", "--from=2026-01-01", "--to=2026-01-31", "--feature-schema=calibration"],
    };
    let before = this.connections();
    const codes: Record<string, number> = {};
    for (const t of tasks) {
      codes[t] = this.runIn("bot", {
        args: [`--task=${t}`, "--tenant-id=1", ...(extraArgs[t] ?? [])],
        env: { ...base, OPS_MODE_SOURCE: "/ops/STOPPED.json" },
        network: net,
        mounts,
        desc: `bot STOPPED ${t}`,
      })[0];
    }
    let after = this.connections();
    this.r.check(
      "[bot] STOPPED -> exit 0 for all 5 tasks",
      Object.values(codes).every((c) => c === 0),
      JSON.stringify(codes),
    );
    this.r.check(
      "[bot] STOPPED never opens a DB connection (server Connections delta == 1: the probe itself)",
      after - before === 1,
      `delta=${after - before}`,
    );
    before = this.connections();
    let [rc, out, err] = this.runIn("bot", {
      args: ["--task=retention", "--tenant-id=1"],
      env: { ...base, OPS_MODE_SOURCE: "/ops/NORMAL.json" },
      network: net,
      mounts,
      desc: "bot NORMAL retention dry-run",
      timeout: 120,
    });
    after = this.connections();
    const tail = (s: string): string => s.slice(-300);
    this.r.check(
      "[bot] NORMAL connects to the DB and runs retention as a dry-run -> exit 0",
      rc === 0 && after - before >= 2,
      `rc=${rc} delta=${after - before}\n${tail(out + err)}`,
    );
    this.r.check(
      "[bot] retention is not a stub and deletes nothing by default (RETENTION_DRY_RUN)",
      (out + err).includes("RETENTION_DRY_RUN") && !(out + err).includes("STUB_LOCAL_OK") && !(out + err).includes("TASK_NOT_IMPLEMENTED"),
      tail(out + err),
    );
    [rc, out, err] = this.runIn("bot", {
      args: ["--task=reconcile", "--tenant-id=1"],
      env: { ...base, OPS_MODE_SOURCE: "/ops/NORMAL.json" },
      network: net,
      mounts,
      desc: "bot reconcile empty",
      timeout: 120,
    });
    this.r.check(
      "[bot] reconcile on an empty DB -> exit 0 RECONCILED (implemented, not a stub)",
      rc === 0 && (out + err).includes("RECONCILED") && !(out + err).includes("STUB_LOCAL_OK"),
      `rc=${rc}\n${tail(out + err)}`,
    );
    [rc, out, err] = this.runIn("bot", {
      args: ["--task=calendar-check", "--tenant-id=1"],
      env: { ...base, OPS_MODE_SOURCE: "/ops/NORMAL.json" },
      network: net,
      mounts,
      desc: "bot calendar-check unknown",
      timeout: 120,
    });
    this.r.check(
      "[bot] calendar-check on an empty schedule -> exit 75 SCHEDULE_UNKNOWN (fail closed, no launch)",
      rc === 75 && (out + err).includes("SCHEDULE_UNKNOWN"),
      `rc=${rc}\n${tail(out + err)}`,
    );
    [rc] = this.runIn("bot", {
      args: ["--task=calendar-check", "--tenant-id=1"],
      env: { ...base, OPS_MODE_SOURCE: "/ops/NORMAL.json", DB_PASSWORD: `wrong-${hex(4)}` },
      network: net,
      mounts,
      desc: "bot wrong pw",
      timeout: 120,
    });
    this.r.check("[bot] DB authentication failure -> exit 1 (not 75, not 0)", rc === 1, `rc=${rc}`);
    // スキーマ版不足: 履歴の表の無い別 DB
    this.mysqlExec("CREATE DATABASE IF NOT EXISTS emptydb; GRANT SELECT ON emptydb.* TO 'bot'@'%'");
    [rc] = this.runIn("bot", {
      args: ["--task=calendar-check", "--tenant-id=1"],
      env: { ...base, DB_URL: `jdbc:mysql://${this.mysql}:3306/emptydb`, OPS_MODE_SOURCE: "/ops/NORMAL.json" },
      network: net,
      mounts,
      desc: "bot schema missing",
      timeout: 120,
    });
    this.r.check("[bot] schema version missing -> exit 1 (does not run the task, does not migrate)", rc === 1, `rc=${rc}`);
    this.r.check("[bot] the app did not create tables in the empty DB", this.mysqlExec("SHOW TABLES", "root", undefined, "emptydb") === "");
  }

  async webChecksWithDb(name: "ingest" | "dashboard", user: string, extra: Readonly<Record<string, string>>): Promise<void> {
    const d = join(this.tmp, "ops");
    const env: Record<string, string> = {
      APP_ENV: "local",
      OPS_MODE_SOURCE: "/ops/NORMAL.json",
      DB_URL: this.jdbc(),
      DB_USER: user,
      DB_PASSWORD: this.pw(user),
      PORT: "8080",
      ...extra,
    };
    const cname = `jrasmoke-${name}-${hex(3)}`;
    const cmd = [
      "run",
      "-d",
      "--name",
      cname,
      "--read-only",
      "--tmpfs",
      "/tmp",
      "--cap-drop",
      "ALL",
      "--security-opt",
      "no-new-privileges",
    ];
    cmd.push("--network", this.network, "-p", "127.0.0.1::8080", "-v", `${d}:/ops:ro`);
    for (const [k, v] of Object.entries(env)) cmd.push("-e", `${k}=${v}`);
    cmd.push(this.image(name));
    const p = docker(cmd);
    this.created.push(cname);
    if (!this.r.check(`[${name}] container starts`, p.status === 0, p.stderr.slice(0, 200))) return;
    const port = docker(["port", cname, "8080/tcp"]).stdout.trim().split("\n")[0]?.split(":").at(-1) ?? "";
    const status: Record<string, number> = {};
    const deadline = Date.now() + 120_000;
    while (Date.now() < deadline) {
      try {
        for (const path of ["/internal/health", "/internal/ready"]) status[path] = await httpStatus(`http://127.0.0.1:${port}${path}`);
        break;
      } catch {
        if (docker(["inspect", "-f", "{{.State.Running}}", cname]).stdout.trim() !== "true") break;
        await sleep(2000);
      }
    }
    this.r.check(
      `[${name}] Tomcat is up on $PORT and /internal/* requires a signed token (401 without one)`,
      status["/internal/health"] === 401 && status["/internal/ready"] === 401 && Object.keys(status).length === 2,
      JSON.stringify(status),
    );
    if (name === "dashboard") await this.dashboardStaticChecks(port);
    const lr = docker(["logs", cname]);
    const logs = lr.stdout + lr.stderr;
    this.captured.push([`${name} server logs`, logs]);
    this.r.check(`[${name}] logs go to stdout/stderr`, logs.trim().length > 0);
    const t0 = Date.now();
    docker(["stop", "-t", "10", cname], 60);
    const took = (Date.now() - t0) / 1000;
    const st: unknown = JSON.parse(docker(["inspect", cname]).stdout);
    const state = Array.isArray(st) && isRecord(st[0]) && isRecord(st[0]["State"]) ? st[0]["State"] : {};
    const code = state["ExitCode"];
    this.r.check(
      `[${name}] SIGTERM stops the JVM gracefully within the grace period (exit 0/143, not 137/OOM)`,
      (code === 0 || code === 143) && state["OOMKilled"] === false && took < 9.5,
      `exit=${String(code)} took=${took.toFixed(1)}s`,
    );
  }

  /**
   * 画面（TypeScript を変換した ES モジュール。D-263）が jar に入り、同一オリジンから配信されること。index.html はモジュールを読み、
   * 旧 /app.js は無い。静的な画面はアプリの認証の外（本番は IAP の内側）なので、トークン無しで取得できる。
   */
  async dashboardStaticChecks(port: string): Promise<void> {
    try {
      const index = await httpGet(`http://127.0.0.1:${port}/`);
      const app = await httpGet(`http://127.0.0.1:${port}/js/app.js`);
      const api = await httpGet(`http://127.0.0.1:${port}/js/api.js`);
      const old = await httpGet(`http://127.0.0.1:${port}/app.js`);
      this.r.check(
        "[dashboard] index.html loads the compiled module /js/app.js (CSP header present)",
        index.status === 200 &&
          index.body.includes('<script type="module" src="/js/app.js"></script>') &&
          index.csp.includes("script-src 'self'"),
        `status=${index.status}`,
      );
      this.r.check(
        "[dashboard] /js/app.js and /js/api.js are served as JavaScript (compiled from dashboard/src/main/ts)",
        app.status === 200 &&
          /javascript/.test(app.type) &&
          app.body.includes('from "./api.js"') &&
          api.status === 200 &&
          /javascript/.test(api.type),
        `app=${app.status} ${app.type} api=${api.status}`,
      );
      this.r.check("[dashboard] the legacy /app.js is gone (404)", old.status === 404, `status=${old.status}`);
    } catch (e) {
      this.r.check("[dashboard] static UI is reachable", false, e instanceof Error ? e.message.slice(0, 200) : "error");
    }
  }

  // ------------------------------------------------------------------ ml-train（Kotlin。D-257）
  get mlKey(): string {
    if (this.mlKeyValue === undefined) {
      this.mlKeyValue = randomBytes(32).toString("base64"); // 実行時に生成するテスト鍵（秘密でも本番鍵でもない）
      this.canaries.push(this.mlKeyValue);
    }
    return this.mlKeyValue;
  }

  mlEnv(over: Readonly<Record<string, string>> = {}): Record<string, string> {
    return {
      APP_ENV: "local",
      OPS_MODE_SOURCE: "file:///ops/NORMAL.json",
      MODEL_STORE_URI: "file:///store",
      MODEL_SIGNING_KEY: this.mlKey,
      ...over,
    };
  }

  mlChecksNoDb(): void {
    let [rc, out] = this.runIn("ml-train", { desc: "ml no args" });
    this.r.check("[ml-train] no args -> exit 2", rc === 2, `rc=${rc}`);
    [rc] = this.runIn("ml-train", { args: ["nonsense"], desc: "ml bad command" });
    this.r.check("[ml-train] unknown command -> exit 2", rc === 2);
    [rc, out] = this.runIn("ml-train", {
      args: ["train", "--tenant-id", "1", "--dataset-id", "x", "--config", "/nonexistent.json"],
      env: { APP_ENV: "local" },
      desc: "ml no env",
    });
    this.r.check(
      "[ml-train] valid args but missing configuration -> exit 2 (touches neither DB nor store)",
      rc === 2,
      `rc=${rc} ${out.slice(-200)}`,
    );
    [rc, out] = this.runIn("ml-train", {
      args: ["convert-pkl", "--pkl", "/x.pkl", "--out-dir", "/tmp/o", "--tenant-id", "1", "--model-id", "m", "--confirm-own-local-file"],
      env: { APP_ENV: "prod", MODEL_SIGNING_KEY: this.mlKey },
      desc: "ml convert refuses cloud",
    });
    this.r.check(
      "[ml-train] convert-pkl refuses to run in a cloud-like environment (exit 2)",
      rc === 2 && out.includes("LOCAL_ONLY"),
      `rc=${rc} ${out.slice(-200)}`,
    );
  }

  private mlResult(out: string): Record<string, unknown> {
    const lines = out.trim().split("\n").reverse();
    const line = lines.find((l) => l.includes("JOB_RESULT"));
    return line === undefined ? {} : jsonLine(line);
  }

  /** 合成データセット（Kotlin の DatasetExporter が書いたコミット済みの契約ファイル）で、実際に学習・署名・登録までを行う。 */
  mlChecksWithDb(): void {
    const ops = this.writeOpsFiles(false);
    const res = join(this.facts, "domain", "src", "test", "resources", "ml", "dataset");
    const store = join(this.tmp, "ml-store");
    const dsDir = join(store, "1", "datasets");
    mkdirSync(dsDir, { recursive: true });
    const csvGz = join(dsDir, "synthetic-calibration.csv.gz");
    writeFileSync(csvGz, gzipSync(readFileSync(join(res, "synthetic-calibration.csv"))));
    writeFileSync(join(dsDir, "synthetic-calibration.manifest.json"), readFileSync(join(res, "synthetic-calibration.manifest.json")));
    for (const dd of [store, join(store, "1"), dsDir]) chmodSync(dd, 0o777);
    for (const f of readdirSync(dsDir)) chmodSync(join(dsDir, f), 0o666);
    const cfg = join(this.tmp, "ml-cfg");
    mkdirSync(cfg, { recursive: true });
    chmodSync(cfg, 0o755);
    const files: Record<string, unknown> = {
      "train.json": { kind: "calibration", seed: 42, l2_c: 1.0, recency_halflife_days: 90 },
      "train2.json": { kind: "calibration", seed: 7, l2_c: 0.5 },
      "gate-pass.json": { purpose: "synthetic_test", checks: { min_valid_rows: 5, max_valid_logloss: 5.0 } },
      "gate-strict.json": { purpose: "synthetic_test", checks: { max_valid_logloss: 0.0001 } },
    };
    for (const [n, body] of Object.entries(files)) {
      writeFileSync(join(cfg, n), JSON.stringify(body));
      chmodSync(join(cfg, n), 0o644);
    }
    const mounts = [`${ops}:/ops:ro`, `${store}:/store`, `${cfg}:/cfg:ro`];
    const db = { DB_URL: this.jdbc(), DB_USER: "mltrain", DB_PASSWORD: this.pw("mltrain") };
    const train = (
      config: string,
      gate: string | undefined,
      env: Readonly<Record<string, string>> = {},
      desc = "ml train",
    ): [number, string, Record<string, unknown>] => {
      const args = [
        "train",
        "--tenant-id",
        "1",
        "--dataset-id",
        "synthetic-calibration",
        "--config",
        `/cfg/${config}`,
        ...(gate !== undefined ? ["--gate", `/cfg/${gate}`] : []),
      ];
      const [rc, out, err] = this.runIn("ml-train", {
        args,
        env: this.mlEnv({ ...db, ...env }),
        network: this.network,
        mounts,
        desc,
        timeout: 180,
      });
      return [rc, out + err, this.mlResult(out)];
    };
    const modelsDir = join(store, "1", "models");
    const models = (): string[] => (existsSync(modelsDir) ? readdirSync(modelsDir).sort() : []);
    // V1 は tenant に行を入れない（本番の行を持たない）。ml_model の複合 FK のため、CI 専用の合成 DB にだけテナント 1 を入れる
    this.mysqlExec(
      "INSERT IGNORE INTO tenant (tenant_id, name, created_at) VALUES (1, 'ci-synthetic', NOW(6))",
      "root",
      undefined,
      "jrabot",
    );
    const count = (where = "1=1"): number =>
      Number(this.mysqlExec(`SELECT COUNT(*) FROM ml_model WHERE ${where}`, "root", undefined, "jrabot"));

    let [rc, log, r] = train("train.json", "gate-pass.json", {}, "ml train pass");
    this.r.check(
      "[ml-train] trains on the synthetic dataset and registers a CANDIDATE -> exit 0",
      rc === 0 && r["reason"] === "CANDIDATE_REGISTERED",
      `rc=${rc}\n${log.slice(-600)}`,
    );
    const mid = typeof r["model_id"] === "string" ? r["model_id"] : "";
    const ms = models();
    this.r.check(
      "[ml-train] the registry row is CANDIDATE (never ACTIVE) and the signed model + report are in the store",
      mid !== "" &&
        this.mysqlExec(`SELECT status FROM ml_model WHERE tenant_id = 1 AND model_id = '${mid}'`, "root", undefined, "jrabot") ===
          "CANDIDATE" &&
        ms.includes(`${mid}.json`) &&
        ms.includes(`${mid}.sig`) &&
        existsSync(join(store, "1", "reports", `${mid}.report.json`)),
      `${mid} ${JSON.stringify(ms)}`,
    );
    // 成果物はコンテナの uid 10001 が所有する（0600 など）。検査側が読めるよう、同じイメージの中で読取り権限だけを足す
    this.runIn("ml-train", {
      entrypoint: "/bin/chmod",
      args: ["-R", "a+rX", "/store"],
      mounts: [`${store}:/store`],
      desc: "ml make artifacts readable",
    });
    const sig = mid !== "" && existsSync(join(modelsDir, `${mid}.sig`)) ? readFileSync(join(modelsDir, `${mid}.sig`)) : Buffer.alloc(0);
    this.r.check("[ml-train] .sig is a raw 64-byte Ed25519 signature", sig.length === 64, String(sig.length));
    this.r.check("[ml-train] no ACTIVE row and no active_flag after training", count("status = 'ACTIVE' OR active_flag IS NOT NULL") === 0);
    [rc, log, r] = train("train.json", "gate-pass.json", {}, "ml train rerun");
    this.r.check(
      "[ml-train] re-running the same job is idempotent (ALREADY_REGISTERED, no second row)",
      rc === 0 && r["reason"] === "ALREADY_REGISTERED" && count() === 1,
      `rc=${rc} ${JSON.stringify(r)}`,
    );
    [rc, log, r] = train("train2.json", "gate-strict.json", {}, "ml train reject");
    this.r.check(
      "[ml-train] a model failing the (synthetic) quality gate is registered as REJECTED -> exit 0",
      rc === 0 && r["reason"] === "MODEL_REJECTED" && count("status = 'REJECTED'") === 1,
      `rc=${rc} ${log.slice(-300)}`,
    );
    [rc, log, r] = train("train2.json", undefined, {}, "ml train no gate");
    this.r.check(
      "[ml-train] without a quality gate the model is REJECTED (fail closed)",
      rc === 0 && r["reason"] === "MODEL_REJECTED" && count("status = 'REJECTED'") === 2,
      `rc=${rc} ${log.slice(-300)}`,
    );
    const beforeModels = JSON.stringify(models());
    const beforeRows = count();
    const beforeConn = this.connections();
    [rc, log, r] = train("train.json", "gate-pass.json", { OPS_MODE_SOURCE: "file:///ops/STOPPED.json" }, "ml STOPPED");
    const afterConn = this.connections();
    this.r.check(
      "[ml-train] STOPPED -> exit 0 (MODE_STOPPED) without any DB connection",
      rc === 0 && r["reason"] === "MODE_STOPPED" && afterConn - beforeConn === 1,
      `rc=${rc} delta=${afterConn - beforeConn}`,
    );
    [rc, log, r] = train("train.json", "gate-pass.json", { OPS_MODE_SOURCE: "file:///ops/missing.json" }, "ml UNKNOWN");
    this.r.check("[ml-train] mode UNKNOWN -> exit 75 (never treated as NORMAL)", rc === 75 && r["reason"] === "MODE_UNKNOWN", `rc=${rc}`);
    [rc, log, r] = train("train2.json", "gate-pass.json", { DB_PASSWORD: `wrong-${hex(4)}` }, "ml wrong db password");
    this.r.check(
      "[ml-train] DB authentication failure -> exit 1 (not 75) and no artifacts are left behind",
      rc === 1 && r["reason"] === "DB_AUTH_FAILED" && JSON.stringify(models()) === beforeModels && count() === beforeRows,
      `rc=${rc} ${JSON.stringify(r)} ${JSON.stringify(models())}`,
    );
    [rc] = train("train.json", "gate-pass.json", { MODEL_SIGNING_KEY: "" }, "ml no key");
    this.r.check("[ml-train] missing MODEL_SIGNING_KEY -> exit 2", rc === 2, `rc=${rc}`);
    // データセットの改ざん（CSV を 1 バイト書き換え）→ 学習しない・登録しない
    const original = readFileSync(csvGz);
    const raw = Buffer.from(gunzipSync(original));
    const mid8 = Math.floor(raw.length / 2);
    raw[mid8] = (raw[mid8] ?? 0) ^ 1;
    writeFileSync(csvGz, gzipSync(raw));
    [rc, log, r] = train("train2.json", "gate-pass.json", {}, "ml tampered dataset");
    writeFileSync(csvGz, original);
    this.r.check(
      "[ml-train] a tampered dataset is rejected (exit 1 DATASET_INVALID), nothing is stored or registered",
      rc === 1 && r["reason"] === "DATASET_INVALID" && JSON.stringify(models()) === beforeModels && count() === beforeRows,
      `rc=${rc} ${JSON.stringify(r)}`,
    );
    // mltrain は昇格できない
    let promoted: boolean;
    try {
      this.mysqlExec(
        "UPDATE ml_model SET status = 'ACTIVE', active_flag = 1, activated_at = NOW(6)",
        "mltrain",
        this.pw("mltrain"),
        "jrabot",
      );
      promoted = true;
    } catch {
      promoted = false;
    }
    this.r.check("[ml-train] the mltrain DB user cannot promote a model (UPDATE denied)", !promoted && count("status = 'ACTIVE'") === 0);
  }

  // ------------------------------------------------------------------ 全体
  secretScanOutputs(): void {
    const leaked = this.captured.filter(([, o]) => this.canaries.some((c) => o.includes(c))).map(([d]) => d);
    this.r.check("test passwords never appear in any container stdout/stderr/log", leaked.length === 0, JSON.stringify(leaked.slice(0, 5)));
  }

  cleanup(): void {
    for (const c of this.created) docker(["rm", "-f", c]);
    docker(["rm", "-f", this.mysql]);
    docker(["network", "rm", this.network]);
  }

  async runAll(): Promise<number> {
    const infos: Partial<Record<ImageName, ImageInfo>> = {};
    try {
      for (const name of IMAGE_NAMES) {
        const info = this.staticChecks(name);
        if (info !== undefined) {
          infos[name] = info;
          await this.filesystemChecks(name);
          this.jarChecks(name);
        }
      }
      const all = IMAGE_NAMES.every((n) => infos[n] !== undefined);
      this.r.check("all 5 buildable images exist (dashboard, ingest, bot, db-migration, ml-train)", all);
      if (all) {
        this.botChecksNoDb();
        this.dbmChecksNoDb();
        this.mlChecksNoDb();
        this.webChecksNoDb("ingest", INGEST_EXTRA);
        this.webChecksNoDb("dashboard", DASHBOARD_EXTRA);
        if (await this.startMysql()) {
          this.dbmChecksWithDb();
          this.botChecksWithDb();
          this.mlChecksWithDb();
          await this.webChecksWithDb("ingest", "ingest", INGEST_EXTRA);
          await this.webChecksWithDb("dashboard", "app", DASHBOARD_EXTRA);
        }
      }
      this.secretScanOutputs();
    } catch (e) {
      // 実行できなかった検査を成功にしない
      this.r.check(
        `smoke run aborted: ${e instanceof Error ? e.name : typeof e}`,
        false,
        (e instanceof Error ? e.message : String(e)).slice(0, 300),
      );
    } finally {
      this.cleanup();
    }
    const failed = this.r.failed;
    if (this.reportDir !== undefined) {
      mkdirSync(this.reportDir, { recursive: true });
      const report = {
        git_sha: this.gitSha,
        passed: this.r.results.length - failed.length,
        failed: failed.length,
        results: this.r.results,
      };
      writeFileSync(join(this.reportDir, "smoke-report.json"), `${JSON.stringify(report, null, 2)}\n`);
      const images: Record<string, { local_tag: string; image_id: string }> = {};
      for (const [n, i] of Object.entries(infos)) images[n] = { local_tag: i.local_tag, image_id: i.image_id };
      writeFileSync(join(this.reportDir, "images-local.json"), `${JSON.stringify({ git_sha: this.gitSha, images }, null, 2)}\n`);
    }
    console.log(`\nsmoke: ${this.r.results.length - failed.length} passed, ${failed.length} failed`);
    for (const f of failed) console.log(`  FAILED: ${f.name} -- ${f.detail}`);
    return failed.length > 0 ? 1 : 0;
  }
}

export async function main(argv: readonly string[]): Promise<number> {
  let a: { "git-sha"?: string; prefix?: string; tag?: string; "mysql-image"?: string; "report-dir"?: string };
  try {
    a = parseArgs({
      args: [...argv],
      strict: true,
      options: {
        "git-sha": { type: "string" },
        prefix: { type: "string" },
        tag: { type: "string" },
        "mysql-image": { type: "string" },
        "report-dir": { type: "string" },
      },
    }).values;
  } catch (e) {
    console.error(e instanceof Error ? e.message : String(e));
    return 2;
  }
  const sha = a["git-sha"];
  if (sha === undefined || !/^[0-9a-f]{40}$/.test(sha)) {
    console.error("git_sha は 40 桁の小文字 16 進");
    return 2;
  }
  return new Smoke(a.prefix ?? "jra-bot", a.tag ?? sha, sha, a["mysql-image"] ?? "mysql:8.4", a["report-dir"]).runAll();
}
