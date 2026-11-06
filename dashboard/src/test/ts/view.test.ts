import assert from "node:assert/strict";
import { describe, test } from "node:test";
import type { Performance, Status } from "../../main/ts/schema.js";
import {
  OVERRIDE_HEADERS,
  overrideRequest,
  overrideRows,
  PERF_HEADERS,
  performanceIsEmpty,
  performanceNote,
  performanceRows,
  RACE_HEADERS,
  RUN_HEADERS,
  raceRows,
  runRows,
  SIGNAL_HEADERS,
  signalRows,
  statusPills,
} from "../../main/ts/view.js";

const baseStatus: Status = {
  serverTime: "2026-10-24T01:00:00Z",
  today: "2026-10-24",
  mode: "NORMAL",
  days: [],
  lastRun: null,
  nextRaceAt: null,
  scheduleStale: false,
  latestConfirmedAt: null,
};

const basePerf: Performance = {
  from: "2026-10-01",
  to: "2026-10-24",
  winCount: 0,
  winHits: 0,
  winTotalStake: 0,
  winPaperPayout: 0,
  winActualPayout: null,
  winHitRate: null,
  winActualRoi: null,
  combos: [],
  winUnsettled: 0,
  comboUnsettled: 0,
  racesWithoutPostTime: 0,
  unreadableNotices: 0,
};

describe("view: 状態欄", () => {
  test("モードだけ（開催日・確認・実行が無い）", () => {
    assert.deepEqual(statusPills(baseStatus), [{ text: "通常運用", tone: "ok" }]);
  });

  test("確認が新しい・開催日・次の発走・直近の実行", () => {
    const pills = statusPills({
      ...baseStatus,
      latestConfirmedAt: "2026-10-24T00:50:00Z",
      days: [{ venueCode: "05", status: "RUNNING", lastConfirmedAt: null }],
      nextRaceAt: "2026-10-24T01:30:00Z",
      lastRun: {
        runId: "r",
        date: "2026-10-24",
        trigger: "SCHEDULER",
        state: "RUNNING",
        startedAt: "2026-10-24T00:30:00Z",
        finishedAt: null,
        exitReason: null,
      },
    });
    assert.deepEqual(pills, [
      { text: "通常運用", tone: "ok" },
      { text: "予定確認 09:50", tone: "muted" },
      { text: "05 監視中", tone: "" },
      { text: "次の発走 10:30", tone: "" },
      { text: "直近の実行: 実行中", tone: "" },
    ]);
  });

  test("予定が古いときは警告、失敗した実行は理由付きで警告", () => {
    const pills = statusPills({
      ...baseStatus,
      mode: "STOPPED",
      scheduleStale: true,
      latestConfirmedAt: "2026-10-23T22:00:00Z",
      lastRun: {
        runId: "r",
        date: "2026-10-24",
        trigger: "MANUAL",
        state: "FAILED",
        startedAt: "2026-10-24T00:30:00Z",
        finishedAt: "2026-10-24T00:31:00Z",
        exitReason: "DB_UNAVAILABLE",
      },
    });
    assert.deepEqual(pills, [
      { text: "停止中", tone: "warn" },
      { text: "予定が未更新（最終確認 10/24 07:00）", tone: "ng" },
      { text: "直近の実行: 失敗（DB_UNAVAILABLE）", tone: "ng" },
    ]);
  });

  test("予定が古く最終確認も無いときは —", () => {
    assert.deepEqual(statusPills({ ...baseStatus, scheduleStale: true })[1], { text: "予定が未更新（最終確認 —）", tone: "ng" });
  });
});

describe("view: 表の行", () => {
  test("レース: 発走時刻・名称が無ければ —、状態は表示名", () => {
    assert.equal(RACE_HEADERS.length, 4);
    assert.deepEqual(
      raceRows([
        { raceId: "202610240501", venueCode: "05", raceNo: 1, name: "テスト1R", postAt: "2026-10-24T01:00:00Z", status: "CARD" },
        { raceId: "202610240502", venueCode: "05", raceNo: 2, name: null, postAt: null, status: "NEW" },
      ]),
      [
        ["10:00", "05 1R", "テスト1R", "出馬表"],
        ["—", "05 2R", "—", "NEW"],
      ],
    );
  });

  test("シグナル: オッズ 1 桁・確率 %・EV 2 桁・推奨額 1 桁、根拠が無ければ —", () => {
    const rows = signalRows([
      {
        signalId: 1,
        raceId: "202610240501",
        market: "win",
        umaban: 2,
        observedAt: "2026-10-24T00:55:00Z",
        odds: 8,
        prob: 0.2,
        ev: 0.6,
        stake: 3,
        probSource: null,
        state: "SEND_UNKNOWN",
      },
    ]);
    assert.deepEqual(rows, [["09:55", "202610240501", "2", "8.0", "20.0%", "0.60", "3.0", "送信不明", "—"]]);
    assert.equal(rows[0]?.length, SIGNAL_HEADERS.length);
  });

  test("成績: 単勝と買い目（ワイド・三連複）、実払戻が無ければ —", () => {
    const rows = performanceRows({
      ...basePerf,
      winCount: 3,
      winHits: 1,
      winTotalStake: 1234.5,
      winHitRate: 1 / 3,
      combos: [
        { market: "wide", count: 2, hits: 1, totalStake: 2, actualPayout: 3.5, hitRate: 0.5, actualRoi: 1.75 },
        { market: "trio", count: 1, hits: 0, totalStake: 1, actualPayout: 0, hitRate: 0, actualRoi: 0 },
      ],
    });
    assert.deepEqual(rows, [
      ["単勝", "3", "1", "33.3%", "1,234.5", "—", "—"],
      ["ワイド", "2", "1", "50.0%", "2.0", "3.5", "175.0%"],
      ["三連複", "1", "0", "0.0%", "1.0", "0.0", "0.0%"],
    ]);
    for (const r of rows) assert.equal(r.length, PERF_HEADERS.length);
  });

  test("成績の注記と空の判定", () => {
    assert.equal(performanceNote(basePerf), "集計に含めていないものはありません。");
    assert.equal(
      performanceNote({ ...basePerf, winUnsettled: 2, comboUnsettled: 1, racesWithoutPostTime: 3, unreadableNotices: 4 }),
      "集計に含めていないもの: 単勝の未確定 2 件、買い目の未確定 1 件、発走時刻不明のレース 3、読めない通知内容 4 件",
    );
    assert.equal(performanceIsEmpty(basePerf), true);
    assert.equal(performanceIsEmpty({ ...basePerf, winCount: 1 }), false);
    assert.equal(
      performanceIsEmpty({
        ...basePerf,
        combos: [{ market: "wide", count: 1, hits: 0, totalStake: 1, actualPayout: 0, hitRate: 0, actualRoi: 0 }],
      }),
      false,
    );
  });

  test("実行記録: 終了・理由が無ければ —", () => {
    const rows = runRows([
      {
        runId: "r",
        date: "2026-10-24",
        trigger: "SCHEDULER",
        state: "STOPPED_BY_MODE",
        startedAt: "2026-10-24T00:30:00Z",
        finishedAt: null,
        exitReason: null,
      },
    ]);
    assert.deepEqual(rows, [["10/24 09:30", "—", "SCHEDULER", "モード停止", "—"]]);
    assert.equal(rows[0]?.length, RUN_HEADERS.length);
  });

  test("手動補正: 対象はレース ID（無ければ日付）、取消済みを区別、末尾の列は画面が足す", () => {
    const base = {
      id: 1,
      scope: "RACE",
      date: "2026-10-24",
      raceId: "202610240501",
      action: "CANCEL",
      value: null,
      reason: "天候",
      createdBy: "me@example.com",
      createdAt: "2026-10-24T00:10:00Z",
      validUntil: null,
      revokedAt: null,
      revokedBy: null,
    };
    const rows = overrideRows([base, { ...base, id: 2, scope: "DAY", raceId: null, revokedAt: "2026-10-24T00:20:00Z" }]);
    assert.deepEqual(rows, [
      ["10/24 09:10", "202610240501", "CANCEL", "天候", "me@example.com", "有効"],
      ["10/24 09:10", "2026-10-24", "CANCEL", "天候", "me@example.com", "取消済み"],
    ]);
    assert.equal(rows[0]?.length, OVERRIDE_HEADERS.length - 1);
  });

  test("補正の要求本文: 空欄は null、理由と日付はそのまま", () => {
    assert.deepEqual(
      overrideRequest({
        scope: "DAY",
        action: "MARK_NO_RACE",
        date: "2026-10-24",
        raceId: "",
        postAt: "",
        postponedTo: "",
        reason: "台風",
      }),
      { scope: "DAY", action: "MARK_NO_RACE", date: "2026-10-24", raceId: null, postAt: null, postponedTo: null, reason: "台風" },
    );
    assert.deepEqual(
      overrideRequest({
        scope: "RACE",
        action: "SET_POST_TIME",
        date: "2026-10-24",
        raceId: "202610240511",
        postAt: "2026-10-24T06:30:00Z",
        postponedTo: "",
        reason: "変更",
      }),
      {
        scope: "RACE",
        action: "SET_POST_TIME",
        date: "2026-10-24",
        raceId: "202610240511",
        postAt: "2026-10-24T06:30:00Z",
        postponedTo: null,
        reason: "変更",
      },
    );
  });
});
