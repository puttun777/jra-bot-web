/**
 * release-manifest（CONTRACTS §10）の検証と生成。旧 tools/release/manifest.py の移植。
 *
 * - 本番用 `release-manifest.json`（スキーマ `docs/schemas/release-manifest.v3.json`）: registry の digest 付き参照だけを受け付ける。
 *   タグだけ・ローカル image ID・仮値・未生成のイメージ・未検証のロールバック範囲は拒否する。
 * - ドライラン成果物 `manifest-preview.json`（スキーマ `release-manifest-preview.v3.json`）: push していないので digest が無い。
 *   `deployable: false` と `blockers` を必ず持ち、本番スキーマには適合しない。
 * 生成は決定的（同じ入力から同じバイト列）。built_at は入力（コミット時刻）で、実行時刻を使わない。
 *
 * スキーマ検証は Ajv（JSON Schema 2020-12、strict）。未対応・未知のキーワードがスキーマにあればコンパイルで失敗する（旧 jsonschema_lite と同じ fail closed）。
 */
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { Ajv2020, type ErrorObject } from "ajv/dist/2020.js";
import { isRecord } from "../lib/json.js";
import {
  combinedFeatureSchemaHash,
  commonRequiredSchemaVersion,
  compareVersions,
  contractVersion,
  featureSchemas,
  flywayVersions,
  IMAGE_NAMES,
  type ImageName,
  repoRoot,
  requiredSchemaVersions,
} from "./repo-facts.js";

export const SCHEMA_DIR = join("docs", "schemas");
export const PRODUCTION_SCHEMA = "release-manifest.v3.json";
export const PREVIEW_SCHEMA = "release-manifest-preview.v3.json";
export const ROLLBACK_REGISTRY = join("docs", "release", "rollback-verified.json");
export const SCHEMA_VERSION = 3;
export const AGENT_PROTOCOL = 1;
export const MODEL_SCHEMA_VERSIONS: readonly number[] = [1];
export const SUPPORTED_CONTRACT_PREFIX = "0.4.";
export const PREVIEW_NOTES =
  "DRY RUN ONLY. Not deployable: images were built locally and not pushed (no registry digest) " +
  "and the rollback range is unverified. Production manifest generation is forbidden until the Stage 5 gates are met.";

const validators = new Map<string, (v: unknown) => readonly string[]>();

function schemaValidator(root: string, name: string): (v: unknown) => readonly string[] {
  const key = join(root, SCHEMA_DIR, name);
  const cached = validators.get(key);
  if (cached !== undefined) return cached;
  const schema: unknown = JSON.parse(readFileSync(key, "utf8"));
  if (!isRecord(schema)) throw new Error(`${name}: スキーマがオブジェクトではない`);
  rejectIgnoredKeywords(schema, name);
  const ajv = new Ajv2020({ strict: true, allErrors: true, validateFormats: false });
  const fn = ajv.compile(schema);
  const v = (doc: unknown): readonly string[] => (fn(doc) ? [] : (fn.errors ?? []).map(formatError));
  validators.set(key, v);
  return v;
}

/**
 * Ajv が strict でも**黙って検査しない**キーワード（format は validateFormats=false、content* は注釈扱い）がスキーマにあれば拒否する
 * （旧 jsonschema_lite と同じく、検査されない条件を書けないようにする = fail closed）。
 */
const IGNORED_KEYWORDS: ReadonlySet<string> = new Set(["format", "contentEncoding", "contentMediaType", "contentSchema"]);

/** 値がスキーマではない（データ）キーワード。中身は走査しない */
const DATA_KEYWORDS: ReadonlySet<string> = new Set(["const", "enum", "default", "examples"]);
/** 値が『名前 → スキーマ』の対応表のキーワード。キー名は名前で、キーワードではない */
const MAP_KEYWORDS: ReadonlySet<string> = new Set(["properties", "patternProperties", "$defs", "definitions", "dependentSchemas"]);

export function rejectIgnoredKeywords(schema: unknown, what: string, path = "#"): void {
  if (Array.isArray(schema)) {
    for (const [i, x] of schema.entries()) rejectIgnoredKeywords(x, what, `${path}/${i}`);
    return;
  }
  if (!isRecord(schema)) return;
  for (const [k, v] of Object.entries(schema)) {
    if (IGNORED_KEYWORDS.has(k)) throw new Error(`${what}: ${path} の ${k} は検証されないキーワード（使わない）`);
    if (DATA_KEYWORDS.has(k)) continue;
    if (MAP_KEYWORDS.has(k) && isRecord(v)) {
      for (const [name, sub] of Object.entries(v)) rejectIgnoredKeywords(sub, what, `${path}/${k}/${name}`);
      continue;
    }
    rejectIgnoredKeywords(v, what, `${path}/${k}`);
  }
}

function formatError(e: ErrorObject): string {
  const where = `$${e.instancePath.replaceAll("/", ".")}`;
  if (e.keyword === "additionalProperties" && isRecord(e.params))
    return `${where}.${String(e.params["additionalProperty"])}: 未知のフィールド`;
  if (e.keyword === "required" && isRecord(e.params)) return `${where}.${String(e.params["missingProperty"])}: 必須フィールドが無い`;
  return `${where}: ${e.message ?? e.keyword}`;
}

function isPlaceholderDigest(hex64: string): boolean {
  return new Set(hex64).size <= 2 || hex64 === "deadbeef".repeat(8) || hex64 === "0123456789abcdef".repeat(4);
}

function semanticCommon(doc: Record<string, unknown>, root: string | undefined, expectGitSha: string | undefined): string[] {
  const errors: string[] = [];
  if (expectGitSha !== undefined && doc["git_sha"] !== expectGitSha)
    errors.push(`git_sha が期待値と違う（${String(doc["git_sha"])} != ${expectGitSha}）`);
  if (root === undefined) return errors;
  try {
    const cv = contractVersion(root);
    const docCv = String(doc["contract_version"]);
    if (docCv !== cv) errors.push(`contract_version が docs/CONTRACTS.md と違う（${docCv} != ${cv}）`);
    if (!docCv.startsWith(SUPPORTED_CONTRACT_PREFIX)) errors.push("contract_version が未対応の範囲");
    const apps = requiredSchemaVersions(root);
    if (new Set(Object.values(apps)).size !== 1) errors.push(`アプリ間で requiredSchemaVersion が食い違っている: ${JSON.stringify(apps)}`);
    const expectedRequired = commonRequiredSchemaVersion(root);
    const docRequired = String(doc["required_schema_version"]);
    if (docRequired !== expectedRequired)
      errors.push(`required_schema_version が各アプリに焼き込まれた値と違う（${docRequired} != ${expectedRequired}）`);
    const versions = flywayVersions(root);
    const flyway = isRecord(doc["flyway"]) ? doc["flyway"] : {};
    const latest = String(flyway["latest_version"]);
    if (latest !== versions.at(-1))
      errors.push(`flyway.latest_version が実際のマイグレーションの最新版と違う（${latest} != ${String(versions.at(-1))}）`);
    if (String(flyway["baseline"]) !== versions[0])
      errors.push(`flyway.baseline が最初の版と違う（${String(flyway["baseline"])} != ${String(versions[0])}）`);
    if (compareVersions(docRequired, latest) > 0)
      errors.push("required_schema_version が flyway.latest_version より新しい（適用できない版を要求している）");
    if (JSON.stringify(doc["model_schema_versions"]) !== JSON.stringify(MODEL_SCHEMA_VERSIONS)) {
      errors.push(`model_schema_versions が未対応（${JSON.stringify(doc["model_schema_versions"])}）`);
    }
    const expectedSchemas = featureSchemas(root);
    const docSchemas = isRecord(doc["feature_schemas"]) ? doc["feature_schemas"] : {};
    if (JSON.stringify(sortedEntries(docSchemas)) !== JSON.stringify(sortedEntries(expectedSchemas))) {
      errors.push("feature_schemas が Kotlin の特徴量スキーマ定義（domain/src/test/resources/ml）と違う");
    }
    if (doc["feature_schema_hash"] !== combinedFeatureSchemaHash(docSchemas)) {
      errors.push("feature_schema_hash が feature_schemas から計算した値と違う（スキーマ集合の hash でなければならない）");
    }
  } catch (e) {
    errors.push(`整合検査を完了できない: ${e instanceof Error ? e.message : String(e)}`);
  }
  return errors;
}

function sortedEntries(o: Readonly<Record<string, unknown>>): [string, unknown][] {
  return Object.entries(o).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
}

interface RollbackEntry {
  readonly appRelease: string;
  readonly targetFlywayVersion: string;
  readonly evidence: string;
}

function rollbackRegistry(root: string): RollbackEntry[] {
  let text: string;
  try {
    text = readFileSync(join(root, ROLLBACK_REGISTRY), "utf8");
  } catch {
    return [];
  }
  const data: unknown = JSON.parse(text);
  const list = isRecord(data) && Array.isArray(data["verified"]) ? data["verified"] : [];
  const out: RollbackEntry[] = [];
  for (const e of list) {
    if (!isRecord(e)) continue;
    const a = e["app_release"];
    const t = e["target_flyway_version"];
    const ev = e["evidence"];
    if (typeof a === "string" && typeof t === "string" && typeof ev === "string" && ev !== "")
      out.push({ appRelease: a, targetFlywayVersion: t, evidence: ev });
  }
  return out;
}

/** release（例 2026.11.0）の大小比較。 */
export function compareRelease(a: string, b: string): number {
  return compareVersions(a, b);
}

/** 本番用 manifest の違反を返す（空なら適合）。root を渡すとリポジトリの実際の値との整合も検査する。 */
export function validateProduction(doc: unknown, root?: string, expectGitSha?: string): string[] {
  if (!isRecord(doc)) return ["トップレベルがオブジェクトではない"];
  const errors: string[] = [];
  if (doc["kind"] === "release-manifest-preview" || "deployable" in doc || "blockers" in doc) {
    errors.push("プレビュー成果物（manifest-preview.json）は本番用 release-manifest.json として使えない");
  }
  errors.push(...schemaValidator(root ?? repoRoot(), PRODUCTION_SCHEMA)(doc));
  if (errors.length > 0) return errors; // 構造が壊れていれば意味の検査はしない
  const images = isRecord(doc["images"]) ? doc["images"] : {};
  for (const [name, ref] of Object.entries(images)) {
    const digest = String(ref).split("@sha256:").at(-1) ?? "";
    if (isPlaceholderDigest(digest)) errors.push(`images.${name}: digest が仮値に見える（実際のレジストリ digest ではない）`);
  }
  errors.push(...semanticCommon(doc, root, expectGitSha));
  const rollback = isRecord(doc["rollback"]) ? doc["rollback"] : {};
  const minApp = String(rollback["min_app_release"]);
  const release = String(doc["release"]);
  const flyway = isRecord(doc["flyway"]) ? doc["flyway"] : {};
  if (compareRelease(minApp, release) > 0) errors.push("rollback.min_app_release が release より新しい");
  else if (minApp !== release && root !== undefined) {
    const verified = rollbackRegistry(root).filter((e) => e.appRelease === minApp && e.targetFlywayVersion === flyway["latest_version"]);
    if (verified.length === 0) {
      errors.push(`rollback.min_app_release=${minApp} は検証済みの記録（${ROLLBACK_REGISTRY}）が無い。検証済みの値は release 自身だけ`);
    }
  }
  return errors;
}

export function validatePreview(doc: unknown, root?: string, expectGitSha?: string): string[] {
  if (!isRecord(doc)) return ["トップレベルがオブジェクトではない"];
  const errors = [...schemaValidator(root ?? repoRoot(), PREVIEW_SCHEMA)(doc)];
  if (errors.length > 0) return errors;
  if (JSON.stringify(doc).includes("@sha256:"))
    errors.push("プレビューにレジストリ digest 形式の値がある（push していないので digest は存在しない）");
  const images = isRecord(doc["images"]) ? doc["images"] : {};
  const unbuilt: string[] = [];
  for (const [name, img] of Object.entries(images)) {
    if (!isRecord(img)) continue;
    if (img["built"] === true && !img["local_image_id"]) errors.push(`images.${name}: built=true なのに local_image_id が無い`);
    if (img["built"] !== true) {
      unbuilt.push(name);
      if (img["local_image_id"] || !img["reason"])
        errors.push(`images.${name}: 未ビルドなのに local_image_id がある、または reason が無い`);
    }
  }
  const expected = new Set(["IMAGES_NOT_PUSHED", "ROLLBACK_RANGE_UNVERIFIED"]);
  if (unbuilt.length > 0) expected.add("IMAGE_NOT_BUILT");
  const blockers = new Set(Array.isArray(doc["blockers"]) ? doc["blockers"].map(String) : []);
  const missing = [...expected].filter((b) => !blockers.has(b)).sort();
  if (missing.length > 0) errors.push(`blockers に必要な理由が無い: ${JSON.stringify(missing)}`);
  errors.push(...semanticCommon(doc, root, expectGitSha));
  return errors;
}

/** 決定的な出力（キー順は生成順のまま、インデント 2、末尾に改行 1 つ）。 */
export function dumps(doc: unknown): string {
  return `${JSON.stringify(doc, null, 2)}\n`;
}

export interface LocalImage {
  readonly local_tag: string;
  readonly image_id: string;
}

/** ドライラン用 manifest。localImages は image-smoke の出力（images-local.json の images）。 */
export function buildPreview(
  root: string,
  gitSha: string,
  release: string,
  builtAt: string,
  localImages: Readonly<Partial<Record<ImageName, LocalImage>>>,
): Record<string, unknown> {
  const versions = flywayVersions(root);
  const schemas = featureSchemas(root);
  const images: Record<string, unknown> = {};
  for (const name of IMAGE_NAMES) {
    const local = localImages[name];
    images[name] =
      local !== undefined
        ? { built: true, local_tag: local.local_tag, local_image_id: local.image_id, registry_digest: null, reason: null }
        : { built: false, local_tag: null, local_image_id: null, registry_digest: null, reason: "image was not built in this run" };
  }
  const blockers = ["IMAGES_NOT_PUSHED", "ROLLBACK_RANGE_UNVERIFIED"];
  if (IMAGE_NAMES.some((n) => localImages[n] === undefined)) blockers.push("IMAGE_NOT_BUILT");
  return {
    schema_version: SCHEMA_VERSION,
    kind: "release-manifest-preview",
    deployable: false,
    blockers: blockers.sort(),
    release,
    git_sha: gitSha,
    built_at: builtAt,
    contract_version: contractVersion(root),
    agent_protocol: AGENT_PROTOCOL,
    images,
    flyway: { latest_version: versions.at(-1), baseline: versions[0] },
    required_schema_version: commonRequiredSchemaVersion(root),
    rollback: { min_app_release: null, verified: false },
    model_schema_versions: [...MODEL_SCHEMA_VERSIONS],
    feature_schemas: schemas,
    feature_schema_hash: combinedFeatureSchemaHash(schemas),
    runtime: { jdk: "25" },
    notes: PREVIEW_NOTES,
  };
}

/** 本番用 manifest。入力は実際にレジストリへ push した後に得た digest 付き参照だけ。検証に通らなければ例外（出力しない）。 */
export function buildProduction(
  root: string,
  gitSha: string,
  release: string,
  builtAt: string,
  registryImages: Readonly<Record<string, unknown>>,
  minAppRelease: string,
  notes = "",
): Record<string, unknown> {
  const versions = flywayVersions(root);
  const schemas = featureSchemas(root);
  const images: Record<string, unknown> = {};
  for (const name of IMAGE_NAMES) images[name] = registryImages[name] ?? "";
  const doc: Record<string, unknown> = {
    schema_version: SCHEMA_VERSION,
    release,
    git_sha: gitSha,
    built_at: builtAt,
    contract_version: contractVersion(root),
    agent_protocol: AGENT_PROTOCOL,
    images,
    flyway: { latest_version: versions.at(-1), baseline: versions[0] },
    required_schema_version: commonRequiredSchemaVersion(root),
    rollback: { min_app_release: minAppRelease },
    model_schema_versions: [...MODEL_SCHEMA_VERSIONS],
    feature_schemas: schemas,
    feature_schema_hash: combinedFeatureSchemaHash(schemas),
    runtime: { jdk: "25" },
    notes,
  };
  const errors = validateProduction(doc, root, gitSha);
  if (errors.length > 0) throw new ManifestError(`本番用 manifest を生成できない:\n- ${errors.join("\n- ")}`);
  return doc;
}

export class ManifestError extends Error {
  override readonly name = "ManifestError";
}

export function requireGitSha(value: string): string {
  if (!/^[0-9a-f]{40}$/.test(value)) throw new ManifestError("git_sha は 40 桁の小文字 16 進のコミット SHA");
  return value;
}
