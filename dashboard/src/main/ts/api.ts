/**
 * 同一オリジンの API の呼出し。応答は schema.ts の検査に通してから返す（外部入力の実行時検査）。
 * 失敗は ApiError（利用者向けの文だけ。サーバの応答本文・例外の詳細は表示しない）。fetch は試験のために差し替えられる。
 */
import { FORMAT_MESSAGE, failureMessage, NETWORK_MESSAGE, postFailureMessage } from "./format.js";
import { DecodeError, type Decoder } from "./validate.js";

export class ApiError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ApiError";
  }
}

export type FetchFn = (input: string, init: RequestInit) => Promise<Response>;
const browserFetch: FetchFn = (input, init) => fetch(input, init);

/** 管理操作（POST）に付ける印。サーバの CsrfGuardFilter が要求する（CONTRACTS・FRONTEND_DESIGN）。 */
export const CSRF_HEADER = { name: "X-Requested-With", value: "jrabot-dashboard" } as const;

async function decodeBody<T>(res: Response, decoder: Decoder<T>): Promise<T> {
  let body: unknown;
  try {
    body = await res.json();
  } catch {
    throw new ApiError(FORMAT_MESSAGE);
  }
  try {
    return decoder(body, "$");
  } catch (e) {
    if (e instanceof DecodeError) throw new ApiError(FORMAT_MESSAGE);
    throw e;
  }
}

export async function getJson<T>(path: string, decoder: Decoder<T>, fetchFn: FetchFn = browserFetch): Promise<T> {
  let res: Response;
  try {
    res = await fetchFn(path, { headers: { Accept: "application/json" }, cache: "no-store" });
  } catch {
    throw new ApiError(NETWORK_MESSAGE);
  }
  if (!res.ok) throw new ApiError(failureMessage(res.status));
  return decodeBody(res, decoder);
}

/**
 * 管理操作（POST）。2xx なら成功とし、応答の本文は使わない（レビュー 低: 成功した操作の応答の形が違うだけで失敗と表示すると、利用者が再送して
 * 409〔重複〕になる）。画面は成功後に一覧を読み直す（一覧の応答は getJson で検査する）。
 */
export async function post(path: string, body: unknown, fetchFn: FetchFn = browserFetch): Promise<void> {
  let res: Response;
  try {
    res = await fetchFn(path, {
      method: "POST",
      cache: "no-store",
      headers: { "Content-Type": "application/json", Accept: "application/json", [CSRF_HEADER.name]: CSRF_HEADER.value },
      body: JSON.stringify(body ?? {}),
    });
  } catch {
    throw new ApiError(NETWORK_MESSAGE);
  }
  if (!res.ok) throw new ApiError(postFailureMessage(res.status));
  await res.body?.cancel();
}

/** 画面に出す文。ApiError 以外（想定外の例外）は詳細を出さない。 */
export function messageOf(e: unknown): string {
  return e instanceof ApiError ? e.message : "表示に失敗しました（画面を再読み込みしてください）。";
}
