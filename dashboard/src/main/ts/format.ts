/** 表示用の整形と表示名（DOM に依存しない。node:test で試験する）。時刻は Asia/Tokyo で表示する。 */

export const DASH = "—";
const TZ = "Asia/Tokyo";

export function fmtTime(iso: string | null): string {
  return iso ? new Date(iso).toLocaleTimeString("ja-JP", { timeZone: TZ, hour: "2-digit", minute: "2-digit" }) : DASH;
}

export function fmtDateTime(iso: string | null): string {
  return iso
    ? new Date(iso).toLocaleString("ja-JP", { timeZone: TZ, month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" })
    : DASH;
}

/** 0.123 → "12.3%"。 */
export function pct(v: number | null): string {
  return v === null ? DASH : `${(v * 100).toFixed(1)}%`;
}

/** 桁区切り・小数 digits 桁固定。 */
export function num(v: number | null, digits = 0): string {
  return v === null ? DASH : v.toLocaleString("ja-JP", { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

export type Tone = "ok" | "warn" | "ng" | "muted" | "";

export const MODE: Readonly<Record<string, readonly [string, Tone]>> = {
  NORMAL: ["通常運用", "ok"],
  STARTING: ["起動中", "warn"],
  STOPPING: ["停止処理中", "warn"],
  STOPPED: ["停止中", "warn"],
  UNKNOWN: ["状態不明", "ng"],
};
export const DAY: Readonly<Record<string, string>> = {
  PLANNED: "予定",
  UNCONFIRMED: "未確認",
  CONFIRMED: "開催確定",
  RUNNING: "監視中",
  SETTLING: "結果待ち",
  COMPLETED: "完了",
  INCOMPLETE: "結果未了",
  CANCELLED: "中止",
  POSTPONED: "延期",
  UNPLANNED: "未計画",
};
export const RACE: Readonly<Record<string, string>> = {
  ANNOUNCED: "発表",
  CARD: "出馬表",
  MONITORING: "監視中",
  OFF: "発走",
  RESULT_PENDING: "結果待ち",
  RESULT_PARTIAL: "払戻待ち",
  SETTLED: "確定",
  FINALIZED: "確定済み",
  CANCELLED: "中止",
  DELETED: "削除",
};
export const SIGNAL: Readonly<Record<string, string>> = { UNSENT: "未通知", CLAIMED: "送信中", SENT: "通知済み", SEND_UNKNOWN: "送信不明" };
export const RUN: Readonly<Record<string, string>> = {
  STARTING: "開始中",
  RUNNING: "実行中",
  DRAINING: "停止処理中",
  SUCCEEDED: "成功",
  FAILED: "失敗",
  ABORTED: "中断",
  STOPPED_BY_MODE: "モード停止",
  INTERRUPTED: "中断",
};

export const MODEL_STATUS: Readonly<Record<string, string>> = { CANDIDATE: "候補", ACTIVE: "有効", RETIRED: "退役", REJECTED: "不合格" };

/** 表示名。未知の値（サーバ側で追加された状態）は値そのものを出す。継承したプロパティ（"constructor" など）は表示名にしない。 */
export function label(map: Readonly<Record<string, string>>, key: string): string {
  return Object.hasOwn(map, key) ? (map[key] ?? key) : key;
}

export function modeLabel(mode: string): readonly [string, Tone] {
  return Object.hasOwn(MODE, mode) ? (MODE[mode] ?? [mode, "warn"]) : [mode, "warn"];
}

/** 読取りの失敗（HTTP の状態）を利用者向けの文に。サーバの応答本文は表示しない。 */
export function failureMessage(status: number): string {
  if (status === 401 || status === 403) return "権限がありません（許可された利用者でログインしてください）。";
  if (status === 503) return "システムは停止中、または準備中です。起動後に「更新」を押してください。";
  if (status === 400) return "入力が正しくありません。";
  return "取得に失敗しました（しばらくして「更新」を押してください）。";
}

/** 管理操作（POST）の失敗。 */
export function postFailureMessage(status: number): string {
  if (status === 409) return "操作できません（停止中、または既に取消済み／重複しています）。";
  if (status === 404) return "対象が見つかりません。";
  if (status === 400) return "入力が正しくありません。";
  return failureMessage(status);
}

export const NETWORK_MESSAGE = "通信できません。接続を確認してください。";
export const FORMAT_MESSAGE = "応答の形式が想定と違います（画面を再読み込みしてください）。";

/** ?date=... （空なら付けない）。 */
export function dateQuery(date: string): string {
  return date ? `?date=${encodeURIComponent(date)}` : "";
}

export function signalsQuery(date: string, page: number, size: number): string {
  const q = dateQuery(date);
  return `${q ? `${q}&` : "?"}page=${page}&size=${size}`;
}

export function performanceQuery(from: string, to: string): string {
  const parts: string[] = [];
  if (from) parts.push(`from=${encodeURIComponent(from)}`);
  if (to) parts.push(`to=${encodeURIComponent(to)}`);
  return parts.length ? `?${parts.join("&")}` : "";
}

/** "1–50 / 120 件"。 */
export function pageInfo(p: { readonly page: number; readonly size: number; readonly total: number }): string {
  return p.total ? `${p.page * p.size + 1}–${Math.min((p.page + 1) * p.size, p.total)} / ${p.total} 件` : "0 件";
}

export function hasNextPage(p: { readonly page: number; readonly size: number; readonly total: number }): boolean {
  return (p.page + 1) * p.size < p.total;
}
