/**
 * 設定・契約の整合検査（push 前 / CI 共通）。『片方だけ直して食い違う』ことを機械的に検出する。旧 tools/ci/consistency.py の移植。
 *
 * 1. Flyway の最新版（db-migration の `V<n>__*.sql`）= 各アプリの `extra["requiredSchemaVersion"]`（bot / ingest / dashboard）。
 * 2. `bot-run` の必須環境変数（`BotRunSettingsParser` の `raw/long/seconds("BOT_RUN_*")`）をすべて、イメージスモーク（BOT_RUN_ENV）が渡している。
 * 3. 永続化層（Doma の `@Table(name=…)`）が参照する表がすべて Flyway の `CREATE TABLE` に存在し、`R__grants.sql` のどこかの GRANT に現れる。
 * 4. 兄弟リポジトリ `../jra-bot-infra` があれば、infra の `docs/CONTRACTS.md` が 1 の版と 2 の必須環境変数に言及している（無ければ『未実施』と表示する）。
 */
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { BOT_RUN_ENV } from "../release/image-smoke.js";
import { MIGRATION_DIR, repoRoot } from "../release/repo-facts.js";

function flywayFiles(root: string): string[] {
  return readdirSync(join(root, MIGRATION_DIR)).filter((n) => /^V.*__.*\.sql$/.test(n));
}

export function flywayLatest(root: string): number {
  const nums = flywayFiles(root)
    .map((n) => /^V(\d+)__/.exec(n)?.[1])
    .filter((x): x is string => x !== undefined)
    .map(Number);
  if (nums.length === 0) throw new Error("Flyway のマイグレーションが見つからない");
  return Math.max(...nums);
}

export function appSchemaVersions(root: string): Record<string, number> {
  const out: Record<string, number> = {};
  for (const app of ["bot", "ingest", "dashboard"]) {
    const t = readFileSync(join(root, app, "build.gradle.kts"), "utf8");
    const m = /extra\["requiredSchemaVersion"\]\s*=\s*"(\d+)"/.exec(t);
    if (m?.[1] === undefined) throw new Error(`${app}/build.gradle.kts に requiredSchemaVersion が無い`);
    out[app] = Number(m[1]);
  }
  return out;
}

export function requiredBotRunEnv(root: string): Set<string> {
  const t = readFileSync(join(root, "bot/src/main/kotlin/jp/jrabot/bot/BotRunSettings.kt"), "utf8");
  return new Set([...t.matchAll(/(?:raw|long|seconds)\(\s*"(BOT_RUN_[A-Z_]+)"/g)].map((m) => m[1] ?? ""));
}

function walkKt(dir: string, out: string[] = []): string[] {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) walkKt(p, out);
    else if (e.name.endsWith(".kt")) out.push(p);
  }
  return out;
}

export function domaTables(root: string): Set<string> {
  const out = new Set<string>();
  for (const p of walkKt(join(root, "persistence/src/main/kotlin"))) {
    for (const m of readFileSync(p, "utf8").matchAll(/@Table\(name = "([a-z_]+)"\)/g)) out.add(m[1] ?? "");
  }
  return out;
}

export function createdTables(root: string): Set<string> {
  const out = new Set<string>();
  for (const n of flywayFiles(root)) {
    for (const m of readFileSync(join(root, MIGRATION_DIR, n), "utf8").matchAll(/CREATE TABLE\s+`?([a-z_]+)`?/g)) out.add(m[1] ?? "");
  }
  return out;
}

export function grantedTables(root: string): Set<string> {
  const t = readFileSync(join(root, MIGRATION_DIR, "R__grants.sql"), "utf8");
  return new Set([...t.matchAll(/`\$\{flyway:defaultSchema\}`\.`([a-z_]+)`/g)].map((m) => m[1] ?? ""));
}

export interface ConsistencyResult {
  readonly errors: string[];
  readonly notes: string[];
}

export function check(
  root: string,
  infra: string | undefined,
  smokeEnv: ReadonlySet<string> = new Set(Object.keys(BOT_RUN_ENV)),
): ConsistencyResult {
  const errors: string[] = [];
  const notes: string[] = [];
  const latest = flywayLatest(root);
  for (const [app, v] of Object.entries(appSchemaVersions(root))) {
    if (v !== latest) errors.push(`${app} の requiredSchemaVersion=${v} が Flyway の最新版 V${latest} と違う`);
  }
  const required = requiredBotRunEnv(root);
  const missingEnv = [...required].filter((n) => !smokeEnv.has(n)).sort();
  if (missingEnv.length > 0) errors.push(`bot-run の必須環境変数をイメージスモークが渡していない: ${JSON.stringify(missingEnv)}`);
  if (required.size === 0) errors.push("BotRunSettings から必須環境変数を読み取れない（検査が空振りしている）");
  const tables = domaTables(root);
  const created = createdTables(root);
  const granted = grantedTables(root);
  for (const t of [...tables].sort()) if (!created.has(t)) errors.push(`DAO の表 ${t} が Flyway に無い`);
  for (const t of [...tables].sort())
    if (!granted.has(t)) errors.push(`DAO の表 ${t} が R__grants.sql のどの GRANT にも無い（権限が無く実行時に失敗する）`);
  const infraContracts = infra === undefined ? undefined : join(infra, "docs/CONTRACTS.md");
  if (infraContracts !== undefined && existsSync(infraContracts)) {
    const c = readFileSync(infraContracts, "utf8");
    for (const n of [...required].sort())
      if (!c.includes(n)) errors.push(`infra の docs/CONTRACTS.md に ${n} が無い（infra への同期漏れ）`);
    const m = /現在 \*\*(\d+)\*\*/.exec(c);
    if (m?.[1] !== undefined && Number(m[1]) !== latest)
      errors.push(`infra の docs/CONTRACTS.md のスキーマ版 ${m[1]} が Flyway の最新版 V${latest} と違う`);
  } else {
    notes.push("infra リポジトリ（../jra-bot-infra）が無いので infra 契約との整合は未実施");
  }
  return { errors, notes };
}

export function main(): number {
  const root = repoRoot();
  const { errors, notes } = check(root, resolve(root, "..", "jra-bot-infra"));
  for (const n of notes) console.log(`note: ${n}`);
  for (const e of errors) console.log(`::error::${e}`);
  console.log(`consistency: ${errors.length === 0 ? "OK" : "NG"} (errors=${errors.length})`);
  return errors.length > 0 ? 1 : 0;
}
