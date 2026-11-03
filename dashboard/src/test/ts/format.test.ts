import assert from "node:assert/strict";
import { describe, test } from "node:test";
import {
  DASH,
  dateQuery,
  failureMessage,
  fmtDateTime,
  fmtTime,
  hasNextPage,
  label,
  modeLabel,
  num,
  pageInfo,
  pct,
  performanceQuery,
  postFailureMessage,
  RACE,
  signalsQuery,
} from "../../main/ts/format.js";

describe("format: 時刻・数値", () => {
  test("時刻は Asia/Tokyo で表示し、null は —", () => {
    assert.equal(fmtTime("2026-10-24T01:00:00Z"), "10:00");
    assert.equal(fmtTime("2026-10-24T15:05:00Z"), "00:05");
    assert.equal(fmtTime(null), DASH);
    assert.equal(fmtDateTime("2026-10-24T15:05:00Z"), "10/25 00:05");
    assert.equal(fmtDateTime(null), DASH);
  });

  test("百分率は小数 1 桁、数値は桁区切りと固定の小数桁", () => {
    assert.equal(pct(0.1234), "12.3%");
    assert.equal(pct(0), "0.0%");
    assert.equal(pct(1.75), "175.0%");
    assert.equal(pct(null), DASH);
    assert.equal(num(1234.5, 1), "1,234.5");
    assert.equal(num(8, 1), "8.0");
    assert.equal(num(0.6, 2), "0.60");
    assert.equal(num(1234567), "1,234,567");
    assert.equal(num(0), "0");
    assert.equal(num(null, 1), DASH);
  });
});

describe("format: 表示名", () => {
  test("既知の状態は日本語、未知の値はそのまま、継承したプロパティ名は表示名にしない", () => {
    assert.equal(label(RACE, "CARD"), "出馬表");
    assert.equal(label(RACE, "NEW_STATE"), "NEW_STATE");
    assert.equal(label(RACE, "constructor"), "constructor");
    assert.equal(label(RACE, "__proto__"), "__proto__");
  });

  test("運用モードは表示名と色。未知のモードは値と警告色", () => {
    assert.deepEqual(modeLabel("NORMAL"), ["通常運用", "ok"]);
    assert.deepEqual(modeLabel("UNKNOWN"), ["状態不明", "ng"]);
    assert.deepEqual(modeLabel("MAINTENANCE"), ["MAINTENANCE", "warn"]);
    assert.deepEqual(modeLabel("toString"), ["toString", "warn"]);
  });

  test("失敗の文は状態コードで決まり、POST は 409・404・400 を区別する", () => {
    assert.match(failureMessage(401), /権限がありません/);
    assert.match(failureMessage(403), /権限がありません/);
    assert.match(failureMessage(503), /停止中、または準備中/);
    assert.match(failureMessage(400), /入力が正しくありません/);
    assert.match(failureMessage(500), /取得に失敗しました/);
    assert.match(postFailureMessage(409), /操作できません/);
    assert.match(postFailureMessage(404), /見つかりません/);
    assert.match(postFailureMessage(400), /入力が正しくありません/);
    assert.match(postFailureMessage(403), /権限がありません/);
    assert.match(postFailureMessage(503), /停止中/);
  });
});

describe("format: クエリとページ", () => {
  test("日付・ページ・期間のクエリ（空欄は付けない、値はエンコード）", () => {
    assert.equal(dateQuery(""), "");
    assert.equal(dateQuery("2026-10-24"), "?date=2026-10-24");
    assert.equal(dateQuery("a&b=c"), "?date=a%26b%3Dc");
    assert.equal(signalsQuery("", 0, 50), "?page=0&size=50");
    assert.equal(signalsQuery("2026-10-24", 2, 50), "?date=2026-10-24&page=2&size=50");
    assert.equal(performanceQuery("", ""), "");
    assert.equal(performanceQuery("2026-10-01", ""), "?from=2026-10-01");
    assert.equal(performanceQuery("", "2026-10-31"), "?to=2026-10-31");
    assert.equal(performanceQuery("2026-10-01", "2026-10-31"), "?from=2026-10-01&to=2026-10-31");
  });

  test("ページの表示と次のページの有無", () => {
    assert.equal(pageInfo({ page: 0, size: 50, total: 0 }), "0 件");
    assert.equal(pageInfo({ page: 0, size: 50, total: 120 }), "1–50 / 120 件");
    assert.equal(pageInfo({ page: 2, size: 50, total: 120 }), "101–120 / 120 件");
    assert.equal(hasNextPage({ page: 0, size: 50, total: 50 }), false);
    assert.equal(hasNextPage({ page: 0, size: 50, total: 51 }), true);
    assert.equal(hasNextPage({ page: 2, size: 50, total: 120 }), false);
  });
});
