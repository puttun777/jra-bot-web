/** 外部 JSON を unknown として受け、型を絞り込むための小さな道具（any を使わない）。 */

export type JsonValue = null | boolean | number | string | JsonValue[] | { [k: string]: JsonValue };

export function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

export function parseJson(text: string, what: string): unknown {
  try {
    return JSON.parse(text);
  } catch (e) {
    throw new Error(`${what} が JSON として読めない: ${e instanceof Error ? e.message : String(e)}`);
  }
}

export function str(o: Record<string, unknown>, key: string, what: string): string {
  const v = o[key];
  if (typeof v !== "string") throw new Error(`${what}.${key} が文字列ではない`);
  return v;
}

export function obj(o: Record<string, unknown>, key: string, what: string): Record<string, unknown> {
  const v = o[key];
  if (!isRecord(v)) throw new Error(`${what}.${key} がオブジェクトではない`);
  return v;
}

/** JSON.stringify(Python の json.dumps(indent=2, ensure_ascii=False) と同じ見た目) + 末尾改行 1 つ。 */
export function dumpsPretty(v: unknown): string {
  return `${JSON.stringify(v, null, 2)}\n`;
}
