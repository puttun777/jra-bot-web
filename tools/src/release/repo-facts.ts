/** リポジトリ（CONTRACTS・Gradle・Flyway・特徴量スキーマのファイル）から release-manifest の整合検査に使う事実を取り出す。実行時の値ではなくソースの値。 */
import { readdirSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { featureSchemaHash, loadCanonicalFile, sha256Hex } from "../lib/canonical.js";

/** 実行アプリ（REQUIRED_SCHEMA_VERSION を jar に焼き込む）。db-migration はスキーマ版を要求しない（適用する側）。 */
export const APPS = ["dashboard", "ingest", "bot"] as const;
export type App = (typeof APPS)[number];
/** CONTRACTS §10 の images のキー（5 つ）。すべてビルドできる。 */
export const IMAGE_NAMES = ["dashboard", "ingest", "bot", "db-migration", "ml-train"] as const;
export type ImageName = (typeof IMAGE_NAMES)[number];

/** 特徴量スキーマの定義ファイル（Kotlin の FeatureSchema が書き出す唯一の定義。FeatureSchemaFixtureTest が Kotlin の定義との一致を検査する）。 */
export const FEATURE_SCHEMA_FILES = {
  calibration: "feature_schema.calibration40.json",
  alpha: "feature_schema.alpha35.json",
  market: "feature_schema.market5.json",
} as const;
export type FeatureSchemaKey = keyof typeof FEATURE_SCHEMA_FILES;
export const FEATURE_SCHEMA_DIR = join("domain", "src", "test", "resources", "ml");

/** build/tools/src/release/repo-facts.js から見たリポジトリのルート（ソースから実行しても同じ）。 */
export function repoRoot(): string {
  let d = dirname(fileURLToPath(import.meta.url));
  for (let i = 0; i < 8; i++) {
    try {
      readFileSync(join(d, "settings.gradle.kts"));
      return d;
    } catch {
      d = resolve(d, "..");
    }
  }
  throw new Error("リポジトリのルート（settings.gradle.kts）が見つからない");
}

export function contractVersion(root: string): string {
  const text = readFileSync(join(root, "docs", "CONTRACTS.md"), "utf8");
  const m = /^状態: \*\*(\d+\.\d+\.\d+)/m.exec(text);
  if (m?.[1] === undefined) throw new Error("docs/CONTRACTS.md から contract_version を取り出せない");
  return m[1];
}

/** 特徴量スキーマごとの hash（`sha256:…`）。各ファイルの `hash` が内容から再計算した値と一致することも確認する。 */
export function featureSchemas(root: string): Record<FeatureSchemaKey, string> {
  const read = (key: FeatureSchemaKey): string => {
    const fname = FEATURE_SCHEMA_FILES[key];
    const schema = loadCanonicalFile(readFileSync(join(root, FEATURE_SCHEMA_DIR, fname)), fname);
    const declared = schema["hash"];
    if (typeof declared !== "string" || featureSchemaHash(schema) !== declared) throw new Error(`${fname} の hash が内容と一致しない`);
    return declared;
  };
  return { calibration: read("calibration"), alpha: read("alpha"), market: read("market") };
}

/** release-manifest の `feature_schema_hash`（CONTRACTS §10）: スキーマ別の hash を名前付きで並べた正準 JSON の SHA-256 に `sha256:` を付ける。 */
export function combinedFeatureSchemaHash(hashes: Readonly<Record<string, unknown>>): string {
  const want = Object.keys(FEATURE_SCHEMA_FILES).sort();
  const got = Object.keys(hashes).sort();
  if (JSON.stringify(want) !== JSON.stringify(got)) throw new Error(`feature_schemas のキーは ${JSON.stringify(want)} でなければならない`);
  return `sha256:${sha256Hex(hashes)}`;
}

/** 各アプリの build.gradle.kts の extra["requiredSchemaVersion"]（jar に焼き込まれる値の出どころ）。 */
export function requiredSchemaVersions(root: string): Record<App, string> {
  const read = (app: App): string => {
    const text = readFileSync(join(root, app, "build.gradle.kts"), "utf8");
    const m = /extra\["requiredSchemaVersion"\]\s*=\s*"(\d+(?:\.\d+)*)"/.exec(text);
    if (m?.[1] === undefined) throw new Error(`${app}/build.gradle.kts に requiredSchemaVersion が無い`);
    return m[1];
  };
  return { dashboard: read("dashboard"), ingest: read("ingest"), bot: read("bot") };
}

/** 版は数値の組として比較する（10 > 9）。 */
export function compareVersions(a: string, b: string): number {
  const pa = a.split(".").map(Number);
  const pb = b.split(".").map(Number);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const x = pa[i] ?? 0;
    const y = pb[i] ?? 0;
    if (!Number.isInteger(x) || !Number.isInteger(y)) throw new Error(`版の形式が不正: ${a} / ${b}`);
    if (x !== y) return x - y;
  }
  return 0;
}

/** manifest の required_schema_version は 1 つ。アプリ間で食い違っていれば、最大値を返す。 */
export function commonRequiredSchemaVersion(root: string): string {
  const vs = Object.values(requiredSchemaVersions(root));
  return vs.reduce((m, v) => (compareVersions(v, m) > 0 ? v : m));
}

export const MIGRATION_DIR = join("db-migration", "src", "main", "resources", "db", "migration");

/** db-migration の Versioned マイグレーション（V<版>__<説明>.sql）の版を昇順で返す。 */
export function flywayVersions(root: string): string[] {
  const versions: string[] = [];
  for (const name of readdirSync(join(root, MIGRATION_DIR))) {
    if (!/^V.*__.*\.sql$/.test(name)) continue;
    const m = /^V(\d+(?:[._]\d+)*)__.+\.sql$/.exec(name);
    if (m?.[1] === undefined) throw new Error(`マイグレーションのファイル名が不正: ${name}`);
    versions.push(m[1].replaceAll("_", "."));
  }
  if (versions.length === 0) throw new Error("Versioned マイグレーションが無い");
  return versions.sort(compareVersions);
}
