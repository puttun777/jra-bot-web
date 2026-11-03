/**
 * ダッシュボードの API（/api/v1。dashboard の ReadApi・AdminApi・MeController の DTO）の応答の型と検査。
 * サーバの DTO と項目名・型を揃える。見本（src/test/resources/frontend-contract/*.json）を、TypeScript の試験はこの検査に通し、
 * Kotlin の統合試験は実際の応答と形（キーと JSON の型）を比べる。サーバ側の変更で食い違ったら、どちらかの試験が失敗する。
 */
import { array, boolean, type Infer, instant, integer, localDate, nullable, number, object, string } from "./validate.js";

export const Run = object({
  runId: string,
  date: localDate,
  trigger: string,
  state: string,
  startedAt: instant,
  finishedAt: nullable(instant),
  exitReason: nullable(string),
});
export type Run = Infer<typeof Run>;

export const Day = object({ venueCode: string, status: string, lastConfirmedAt: nullable(instant) });
export type Day = Infer<typeof Day>;

export const Status = object({
  serverTime: instant,
  today: localDate,
  mode: string,
  days: array(Day),
  lastRun: nullable(Run),
  nextRaceAt: nullable(instant),
  scheduleStale: boolean,
  latestConfirmedAt: nullable(instant),
});
export type Status = Infer<typeof Status>;

export const Race = object({
  raceId: string,
  venueCode: string,
  raceNo: integer,
  name: nullable(string),
  postAt: nullable(instant),
  status: string,
});
export type Race = Infer<typeof Race>;
export const Races = array(Race);

export const Signal = object({
  signalId: nullable(integer),
  raceId: string,
  market: string,
  umaban: integer,
  observedAt: instant,
  odds: number,
  prob: number,
  ev: number,
  stake: number,
  probSource: nullable(string),
  state: string,
});
export type Signal = Infer<typeof Signal>;

export const SignalPage = object({ items: array(Signal), page: integer, size: integer, total: integer });
export type SignalPage = Infer<typeof SignalPage>;

export const ComboSummary = object({
  market: string,
  count: integer,
  hits: integer,
  totalStake: number,
  actualPayout: number,
  hitRate: nullable(number),
  actualRoi: nullable(number),
});
export type ComboSummary = Infer<typeof ComboSummary>;

export const Performance = object({
  from: localDate,
  to: localDate,
  winCount: integer,
  winHits: integer,
  winTotalStake: number,
  winPaperPayout: number,
  winActualPayout: nullable(number),
  winHitRate: nullable(number),
  winActualRoi: nullable(number),
  combos: array(ComboSummary),
  winUnsettled: integer,
  comboUnsettled: integer,
  racesWithoutPostTime: integer,
  unreadableNotices: integer,
});
export type Performance = Infer<typeof Performance>;

export const Runs = array(Run);

export const Override = object({
  id: nullable(integer),
  scope: string,
  date: localDate,
  raceId: nullable(string),
  action: string,
  value: nullable(string),
  reason: string,
  createdBy: string,
  createdAt: instant,
  validUntil: nullable(instant),
  revokedAt: nullable(instant),
  revokedBy: nullable(string),
});
export type Override = Infer<typeof Override>;
export const Overrides = array(Override);

export const Me = object({ email: string, role: string, tenantId: integer });
export type Me = Infer<typeof Me>;
