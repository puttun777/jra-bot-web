/**
 * release-manifest の生成・検証 CLI（旧 tools/release/cli.py）。
 *
 *   release preview    --git-sha <40桁> --built-at 2026-11-01T00:00:00Z --local-images images-local.json --out manifest-preview.json [--release R]
 *   release validate   --kind preview|production FILE [--expect-git-sha <40桁>]
 *   release production --git-sha … --release 2026.11.0 --built-at … --registry-images registry.json --min-app-release 2026.11.0 --out release-manifest.json [--notes N] [--arch arm64|amd64]
 * （schema_version 3 で --python-version は廃止。指定すると引数の誤り = 2）
 *
 * 終了コード: 0=適合 / 1=検証 NG（production の生成拒否を含む）/ 2=引数・入力の誤り
 */
import { readFileSync, writeFileSync } from "node:fs";
import { parseArgs } from "node:util";
import { isRecord } from "../lib/json.js";
import {
  buildPreview,
  buildProduction,
  dumps,
  type LocalImage,
  ManifestError,
  requireGitSha,
  validatePreview,
  validateProduction,
} from "./manifest.js";
import { IMAGE_NAMES, type ImageName, repoRoot } from "./repo-facts.js";

function load(path: string): unknown {
  return JSON.parse(readFileSync(path, "utf8"));
}

class UsageError extends Error {}

function need(v: string | boolean | undefined, flag: string): string {
  if (typeof v !== "string" || v === "") throw new UsageError(`${flag} が必要`);
  return v;
}

function parseLocalImages(raw: unknown, sha: string): Partial<Record<ImageName, LocalImage>> {
  if (!isRecord(raw)) throw new UsageError("images-local.json がオブジェクトではない");
  if (raw["git_sha"] !== sha) throw new UsageError("images-local.json の git_sha が一致しない");
  const imgs = raw["images"];
  if (!isRecord(imgs)) throw new UsageError("images-local.json に images が無い");
  const out: Partial<Record<ImageName, LocalImage>> = {};
  for (const name of IMAGE_NAMES) {
    const i = imgs[name];
    if (i === undefined) continue;
    if (!isRecord(i) || typeof i["local_tag"] !== "string" || typeof i["image_id"] !== "string")
      throw new UsageError(`images-local.json の ${name} が不正`);
    out[name] = { local_tag: i["local_tag"], image_id: i["image_id"] };
  }
  return out;
}

export function main(argv: readonly string[]): number {
  const [cmd, ...rest] = argv;
  try {
    const { values: a, positionals } = parseArgs({
      args: [...rest],
      allowPositionals: true,
      strict: true,
      options: {
        "git-sha": { type: "string" },
        release: { type: "string" },
        "built-at": { type: "string" },
        "local-images": { type: "string" },
        "registry-images": { type: "string" },
        "min-app-release": { type: "string" },
        notes: { type: "string" },
        out: { type: "string" },
        kind: { type: "string" },
        "expect-git-sha": { type: "string" },
        arch: { type: "string" },
      },
    });
    const root = repoRoot();
    if (cmd === "preview") {
      const sha = requireGitSha(need(a["git-sha"], "--git-sha"));
      const local = parseLocalImages(load(need(a["local-images"], "--local-images")), sha);
      const doc = buildPreview(root, sha, a.release ?? `preview-${sha.slice(0, 12)}`, need(a["built-at"], "--built-at"), local);
      const errors = validatePreview(doc, root, sha);
      if (errors.length > 0) {
        console.error(`プレビューの生成結果が検証に通らない:\n- ${errors.join("\n- ")}`);
        return 1;
      }
      writeFileSync(need(a.out, "--out"), dumps(doc), "utf8");
      console.log(`wrote ${a.out} (deployable=false, blockers=${JSON.stringify(doc["blockers"])})`);
      return 0;
    }
    if (cmd === "validate") {
      const kind = need(a.kind, "--kind");
      if (kind !== "preview" && kind !== "production") throw new UsageError("--kind は preview か production");
      const file = positionals[0];
      if (file === undefined || positionals.length !== 1) throw new UsageError("検証するファイルを 1 つ指定");
      const doc = load(file);
      const errors =
        kind === "production" ? validateProduction(doc, root, a["expect-git-sha"]) : validatePreview(doc, root, a["expect-git-sha"]);
      if (errors.length > 0) {
        console.error(`${file} は ${kind} として不適合:\n- ${errors.join("\n- ")}`);
        return 1;
      }
      console.log(`${file}: ${kind} として適合`);
      return 0;
    }
    if (cmd === "production") {
      const sha = requireGitSha(need(a["git-sha"], "--git-sha"));
      const reg = load(need(a["registry-images"], "--registry-images"));
      if (!isRecord(reg)) throw new UsageError("registry-images がオブジェクトではない");
      const doc = buildProduction(
        root,
        sha,
        need(a.release, "--release"),
        need(a["built-at"], "--built-at"),
        reg,
        need(a["min-app-release"], "--min-app-release"),
        a.notes ?? "",
        parseArch(a.arch),
      );
      writeFileSync(need(a.out, "--out"), dumps(doc), "utf8");
      console.log(`wrote ${a.out}`);
      return 0;
    }
    throw new UsageError(`未知のサブコマンド: ${String(cmd)}（preview | validate | production）`);
  } catch (e) {
    console.error(`error: ${e instanceof Error ? e.message : String(e)}`);
    if (e instanceof ManifestError && cmd === "production") return 1;
    return 2;
  }
}

/** `--arch`（任意。D-284）。 */
function parseArch(v: string | undefined): "arm64" | "amd64" | undefined {
  if (v === undefined) return undefined;
  if (v === "arm64" || v === "amd64") return v;
  throw new UsageError("--arch は arm64 か amd64");
}
