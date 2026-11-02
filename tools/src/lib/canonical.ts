/**
 * 正準 JSON（CONTRACTS §5.1）の TypeScript 版。正は Kotlin の `jp.jrabot.domain.ml.json.CanonicalJson`。
 * 規則: 空白なし・キーはコードポイント順・非 ASCII はそのまま（UTF-8）・NaN/Infinity 禁止・整数と浮動小数は別物。
 *
 * JavaScript の number は整数と浮動小数（`1` と `1.0`）を区別できず、浮動小数の文字列表現も Kotlin/旧 Python と一致する保証がない。
 * そのため **整数（安全な範囲）以外の数値は拒否する**（fail closed）。この TS 版の用途は特徴量スキーマ（整数・文字列・配列のみ）の
 * hash 再計算だけで、浮動小数を含むモデル JSON の正準化・署名は Kotlin が行う。
 */
import { createHash } from "node:crypto";
import { isRecord } from "./json.js";

function compareCodePoints(a: string, b: string): number {
  const ia = a[Symbol.iterator]();
  const ib = b[Symbol.iterator]();
  for (;;) {
    const x = ia.next();
    const y = ib.next();
    if (x.done === true) return y.done === true ? 0 : -1;
    if (y.done === true) return 1;
    const cx = x.value.codePointAt(0) ?? 0;
    const cy = y.value.codePointAt(0) ?? 0;
    if (cx !== cy) return cx - cy;
  }
}

export function canonicalString(v: unknown, path = "$"): string {
  if (v === null) return "null";
  if (typeof v === "boolean") return v ? "true" : "false";
  if (typeof v === "string") return JSON.stringify(v);
  if (typeof v === "number") {
    if (!Number.isSafeInteger(v)) throw new Error(`${path}: 整数以外の数値は TS の正準化の対象外（Kotlin で扱う）: ${v}`);
    return String(v);
  }
  if (Array.isArray(v)) return `[${v.map((x, i) => canonicalString(x, `${path}[${i}]`)).join(",")}]`;
  if (isRecord(v)) {
    const keys = Object.keys(v).sort(compareCodePoints);
    return `{${keys.map((k) => `${JSON.stringify(k)}:${canonicalString(v[k], `${path}.${k}`)}`).join(",")}}`;
  }
  throw new Error(`${path}: JSON の値ではない (${typeof v})`);
}

export function canonicalBytes(v: unknown): Buffer {
  return Buffer.from(canonicalString(v), "utf8");
}

export function sha256Hex(v: unknown): string {
  return createHash("sha256").update(canonicalBytes(v)).digest("hex");
}

/**
 * 正準形で保存されたファイルを厳格に読む: BOM 禁止、**読んだ結果の正準形がファイルのバイト列と完全一致**すること。
 * これにより重複キー（JSON.parse は後勝ちで黙って受け入れる）・空白・キー順の違い・浮動小数を検出する。
 */
export function loadCanonicalFile(raw: Buffer, what: string): Record<string, unknown> {
  if (raw.length >= 3 && raw[0] === 0xef && raw[1] === 0xbb && raw[2] === 0xbf) throw new Error(`${what}: BOM は許可しない`);
  const text = raw.toString("utf8");
  let v: unknown;
  try {
    v = JSON.parse(text);
  } catch (e) {
    throw new Error(`${what}: JSON として読めない: ${e instanceof Error ? e.message : String(e)}`);
  }
  if (!isRecord(v)) throw new Error(`${what}: トップレベルがオブジェクトではない`);
  if (!canonicalBytes(v).equals(raw)) throw new Error(`${what}: 正準形ではない（重複キー・空白・キー順・数値表現のいずれか）`);
  return v;
}

/** `feature_schema.hash`: hash を除いたオブジェクトの正準 JSON の SHA-256 に `sha256:` を付ける。 */
export function featureSchemaHash(schema: Record<string, unknown>): string {
  const body: Record<string, unknown> = {};
  for (const [k, x] of Object.entries(schema)) if (k !== "hash") body[k] = x;
  return `sha256:${sha256Hex(body)}`;
}
