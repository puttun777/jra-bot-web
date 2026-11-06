/**
 * API の応答（外部入力）の実行時検査。ブラウザで動かすため外部ライブラリ（zod など）を使わない小さな組合せ部品。
 * 型と実際の値が食い違ったら DecodeError（画面は「応答の形式が想定と違う」と表示し、壊れた値で描画しない）。
 * 未知のキーは無視する（サーバ側の項目追加で画面を壊さない）。null と欠落は nullable() の項目だけ許す。
 */

export class DecodeError extends Error {
  constructor(
    readonly path: string,
    reason: string,
  ) {
    super(`${path}: ${reason}`);
    this.name = "DecodeError";
  }
}

export type Decoder<T> = (value: unknown, path: string) => T;
export type Infer<D> = D extends Decoder<infer T> ? T : never;

export function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

export const string: Decoder<string> = (v, p) => {
  if (typeof v !== "string") throw new DecodeError(p, "文字列ではない");
  return v;
};

/** 有限の数（NaN・Infinity は JSON に現れないが、念のため拒否する）。 */
export const number: Decoder<number> = (v, p) => {
  if (typeof v !== "number" || !Number.isFinite(v)) throw new DecodeError(p, "数ではない");
  return v;
};

export const integer: Decoder<number> = (v, p) => {
  if (typeof v !== "number" || !Number.isSafeInteger(v)) throw new DecodeError(p, "整数ではない");
  return v;
};

export const boolean: Decoder<boolean> = (v, p) => {
  if (typeof v !== "boolean") throw new DecodeError(p, "真偽値ではない");
  return v;
};

/** 日付（YYYY-MM-DD。サーバの LocalDate）。 */
export const localDate: Decoder<string> = (v, p) => {
  const s = string(v, p);
  if (!/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(s)) throw new DecodeError(p, "日付（YYYY-MM-DD）ではない");
  return s;
};

/** 時刻（ISO 8601。サーバの Instant）。Date で読めないものは拒否する。 */
export const instant: Decoder<string> = (v, p) => {
  const s = string(v, p);
  if (!/^[0-9]{4}-[0-9]{2}-[0-9]{2}T/.test(s) || Number.isNaN(Date.parse(s))) throw new DecodeError(p, "時刻（ISO 8601）ではない");
  return s;
};

/** null・欠落を null に揃える（サーバの nullable な項目）。 */
export function nullable<T>(d: Decoder<T>): Decoder<T | null> {
  return (v, p) => (v === null || v === undefined ? null : d(v, p));
}

export function array<T>(d: Decoder<T>): Decoder<T[]> {
  return (v, p) => {
    if (!Array.isArray(v)) throw new DecodeError(p, "配列ではない");
    return v.map((x, i) => d(x, `${p}[${i}]`));
  };
}

type Shape = Record<string, Decoder<unknown>>;
type FromShape<S extends Shape> = { [K in keyof S]: Infer<S[K]> };

export function object<S extends Shape>(shape: S): Decoder<FromShape<S>> {
  return (v, p) => {
    if (!isRecord(v)) throw new DecodeError(p, "オブジェクトではない");
    const out: Record<string, unknown> = {};
    for (const [k, d] of Object.entries(shape)) out[k] = d(Object.hasOwn(v, k) ? v[k] : undefined, `${p}.${k}`);
    // shape の全キーを、それぞれの Decoder の結果で埋めたので FromShape<S> と一致する（この 1 か所だけの型の付け替え）
    return out as FromShape<S>;
  };
}

/** キーが任意の文字列のオブジェクト（値はすべて [d] で検査。継承したプロパティは見ない）。 */
export function record<T>(d: Decoder<T>): Decoder<Record<string, T>> {
  return (v, p) => {
    if (!isRecord(v)) throw new DecodeError(p, "オブジェクトではない");
    const out: Record<string, T> = {};
    for (const [k, x] of Object.entries(v)) out[k] = d(x, `${p}.${k}`);
    return out;
  };
}
