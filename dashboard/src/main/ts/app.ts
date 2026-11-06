/**
 * JRA Bot ダッシュボードの画面（index.html から `<script type="module" src="/js/app.js">` で読む）。読取り中心で、管理者だけが手動補正を操作できる。
 * 外部ライブラリ・CDN・インライン script なし。値は textContent だけで描画する。応答は schema.ts で検査してから使う。
 */
import { ApiError, getJson, messageOf, post } from "./api.js";
import { byId, el, emptyBox, errorBox, pill, show, table } from "./dom.js";
import { dateQuery, hasNextPage, pageInfo, performanceQuery, signalsQuery } from "./format.js";
import { type Agent, Agents, Me, MlModels, type Override, Overrides, Performance, Races, Runs, SignalPage, Status } from "./schema.js";
import {
  AGENT_HEADERS,
  agentRows,
  ML_HEADERS,
  ML_NUM_COLS,
  mlModelRows,
  OVERRIDE_HEADERS,
  overrideRequest,
  overrideRows,
  PERF_HEADERS,
  PERF_NUM_COLS,
  performanceIsEmpty,
  performanceNote,
  performanceRows,
  RACE_HEADERS,
  RUN_HEADERS,
  raceRows,
  revokeReason,
  runRows,
  SIGNAL_HEADERS,
  SIGNAL_NUM_COLS,
  signalRows,
  statusPills,
} from "./view.js";

const TABS = ["overview", "signals", "performance", "ops"] as const;
type Tab = (typeof TABS)[number];
function isTab(v: string | undefined): v is Tab {
  return TABS.some((t) => t === v);
}

const PAGE_SIZE = 50;
const STATUS_REFRESH_MS = 60_000;

function ui() {
  return {
    statusbar: byId("statusbar", HTMLDivElement),
    date: byId("date", HTMLInputElement),
    reload: byId("reload", HTMLButtonElement),
    races: byId("races", HTMLDivElement),
    signalTable: byId("signal-table", HTMLDivElement),
    pageinfo: byId("pageinfo", HTMLSpanElement),
    prev: byId("prev", HTMLButtonElement),
    next: byId("next", HTMLButtonElement),
    from: byId("from", HTMLInputElement),
    to: byId("to", HTMLInputElement),
    perf: byId("perf", HTMLDivElement),
    runs: byId("runs", HTMLDivElement),
    admin: byId("admin", HTMLDivElement),
    overrides: byId("overrides", HTMLDivElement),
    form: byId("override-form", HTMLFormElement),
    scope: byId("o-scope", HTMLSelectElement),
    action: byId("o-action", HTMLSelectElement),
    race: byId("o-race", HTMLInputElement),
    postAt: byId("o-postat", HTMLInputElement),
    postponed: byId("o-postponed", HTMLInputElement),
    reason: byId("o-reason", HTMLInputElement),
    msg: byId("o-msg", HTMLSpanElement),
    mlModels: byId("ml-models", HTMLDivElement),
    agents: byId("agents", HTMLDivElement),
    agentMsg: byId("agent-msg", HTMLSpanElement),
  };
}
type Ui = ReturnType<typeof ui>;

function start(u: Ui): void {
  const state: { page: number; tab: Tab } = { page: 0, tab: "overview" };

  async function loadStatus(): Promise<void> {
    try {
      const s = await getJson("/api/v1/status", Status);
      u.statusbar.replaceChildren(...statusPills(s).map(pill));
      if (!u.date.value) u.date.value = s.today;
    } catch (e) {
      u.statusbar.replaceChildren(el("span", messageOf(e), "pill ng"));
    }
  }

  async function loadRaces(): Promise<void> {
    try {
      const rows = await getJson(`/api/v1/races${dateQuery(u.date.value)}`, Races);
      show(u.races, rows.length ? table(RACE_HEADERS, raceRows(rows)) : emptyBox("この日のレース予定はありません。"));
    } catch (e) {
      show(u.races, errorBox(messageOf(e)));
    }
  }

  async function loadSignals(): Promise<void> {
    try {
      const p = await getJson(`/api/v1/signals${signalsQuery(u.date.value, state.page, PAGE_SIZE)}`, SignalPage);
      u.pageinfo.textContent = pageInfo(p);
      u.prev.disabled = p.page === 0;
      u.next.disabled = !hasNextPage(p);
      show(
        u.signalTable,
        p.items.length ? table(SIGNAL_HEADERS, signalRows(p.items), SIGNAL_NUM_COLS) : emptyBox("この日のシグナルはありません。"),
      );
    } catch (e) {
      show(u.signalTable, errorBox(messageOf(e)));
    }
  }

  async function loadPerformance(): Promise<void> {
    try {
      const p = await getJson(`/api/v1/performance${performanceQuery(u.from.value, u.to.value)}`, Performance);
      if (!u.from.value) {
        u.from.value = p.from;
        u.to.value = p.to;
      }
      const wrap = el("div");
      if (performanceIsEmpty(p)) wrap.appendChild(emptyBox("この期間に確定した成績はありません。"));
      wrap.appendChild(table(PERF_HEADERS, performanceRows(p), PERF_NUM_COLS));
      wrap.appendChild(el("p", performanceNote(p), "note"));
      show(u.perf, wrap);
    } catch (e) {
      show(u.perf, errorBox(messageOf(e)));
    }
  }

  async function loadRuns(): Promise<void> {
    try {
      const rows = await getJson(`/api/v1/runs${dateQuery(u.date.value)}`, Runs);
      show(u.runs, rows.length ? table(RUN_HEADERS, runRows(rows)) : emptyBox("この日の bot の実行記録はありません。"));
    } catch (e) {
      show(u.runs, errorBox(messageOf(e)));
    }
  }

  function revokeButton(o: Override): HTMLButtonElement | string {
    if (o.revokedAt || o.id === null) return "";
    const id = o.id;
    const b = el("button", "取消");
    b.type = "button";
    b.addEventListener("click", async () => {
      if (!confirm("この補正を取り消しますか？")) return;
      try {
        await post(`/api/v1/admin/overrides/${encodeURIComponent(String(id))}/revoke`, {});
        void loadOverrides();
      } catch (e) {
        u.msg.textContent = messageOf(e);
      }
    });
    return b;
  }

  async function loadOverrides(): Promise<void> {
    try {
      const rows = await getJson(`/api/v1/admin/overrides${dateQuery(u.date.value)}`, Overrides);
      if (!rows.length) return show(u.overrides, emptyBox("この日の手動補正はありません。"));
      const cells = overrideRows(rows).map((r, i) => {
        const o = rows[i];
        return o === undefined ? r : [...r, revokeButton(o)];
      });
      show(u.overrides, table(OVERRIDE_HEADERS, cells));
    } catch (e) {
      show(u.overrides, errorBox(messageOf(e)));
    }
  }

  async function loadMlModels(): Promise<void> {
    try {
      const rows = await getJson("/api/v1/admin/ml-models", MlModels);
      show(u.mlModels, rows.length ? table(ML_HEADERS, mlModelRows(rows), ML_NUM_COLS) : emptyBox("登録されたモデルはありません。"));
    } catch (e) {
      show(u.mlModels, errorBox(messageOf(e)));
    }
  }

  function agentRevokeButton(a: Agent): HTMLButtonElement | string {
    if (a.status !== "ACTIVE") return "";
    const b = el("button", "失効");
    b.type = "button";
    b.addEventListener("click", async () => {
      const input = prompt(`エージェント ${a.agentId} を失効します。以後このエージェントの送信は拒否されます。理由（必須・500 文字まで）:`);
      if (input === null) return; // 取消
      const reason = revokeReason(input);
      if (reason === null) {
        u.agentMsg.textContent = "理由が空、または長すぎる・制御文字を含むため失効していません。";
        return;
      }
      if (!confirm(`エージェント ${a.agentId} を失効しますか？（元に戻す操作はありません）`)) return;
      try {
        await post(`/api/v1/admin/agents/${encodeURIComponent(a.agentId)}/revoke`, { reason });
        u.agentMsg.textContent = "失効しました。";
      } catch (e) {
        u.agentMsg.textContent = messageOf(e);
      }
      void loadAgents();
    });
    return b;
  }

  async function loadAgents(): Promise<void> {
    try {
      const rows = await getJson("/api/v1/admin/agents", Agents);
      if (!rows.length) return show(u.agents, emptyBox("登録されたエージェントはありません。"));
      const cells = agentRows(rows).map((r, i) => {
        const a = rows[i];
        return a === undefined ? r : [...r, agentRevokeButton(a)];
      });
      show(u.agents, table(AGENT_HEADERS, cells));
    } catch (e) {
      show(u.agents, errorBox(messageOf(e)));
    }
  }

  async function initAdmin(): Promise<void> {
    let me: Me;
    try {
      me = await getJson("/api/v1/me", Me);
    } catch (e) {
      if (e instanceof ApiError) return; // 権限が無い・通信できない場合は管理 UI を出さない
      throw e;
    }
    if (me.role !== "ADMIN") return;
    u.admin.hidden = false;
    u.form.addEventListener("submit", async (ev) => {
      ev.preventDefault();
      const body = overrideRequest({
        scope: u.scope.value,
        action: u.action.value,
        date: u.date.value,
        raceId: u.race.value,
        postAt: u.postAt.value,
        postponedTo: u.postponed.value,
        reason: u.reason.value,
      });
      try {
        await post("/api/v1/admin/overrides", body);
        u.msg.textContent = "追加しました。";
        u.reason.value = "";
        void loadOverrides();
      } catch (e) {
        u.msg.textContent = messageOf(e);
      }
    });
    void loadOverrides();
    void loadMlModels();
    void loadAgents();
  }

  const loaders: Readonly<Record<Tab, () => void>> = {
    overview: () => void loadRaces(),
    signals: () => void loadSignals(),
    performance: () => void loadPerformance(),
    ops: () => {
      void loadRuns();
      if (!u.admin.hidden) {
        void loadOverrides();
        void loadMlModels();
        void loadAgents();
      }
    },
  };

  function select(tab: Tab): void {
    state.tab = tab;
    for (const b of document.querySelectorAll<HTMLElement>("nav [data-tab]"))
      b.setAttribute("aria-selected", String(b.dataset["tab"] === tab));
    for (const s of document.querySelectorAll<HTMLElement>(".tab")) s.hidden = s.id !== tab;
    loaders[tab]();
  }

  for (const b of document.querySelectorAll<HTMLElement>("nav [data-tab]")) {
    const tab = b.dataset["tab"];
    if (isTab(tab)) b.addEventListener("click", () => select(tab));
  }
  u.reload.addEventListener("click", () => {
    void loadStatus();
    loaders[state.tab]();
  });
  u.date.addEventListener("change", () => {
    state.page = 0;
    loaders[state.tab]();
  });
  u.from.addEventListener("change", () => void loadPerformance());
  u.to.addEventListener("change", () => void loadPerformance());
  u.prev.addEventListener("click", () => {
    state.page = Math.max(0, state.page - 1);
    void loadSignals();
  });
  u.next.addEventListener("click", () => {
    state.page += 1;
    void loadSignals();
  });
  void loadStatus().then(() => {
    loaders.overview();
    // 想定外の例外でも管理 UI は出さない（未処理の Promise の拒否にしない。レビュー 低）
    initAdmin().catch(() => {
      u.admin.hidden = true;
    });
  });
  setInterval(() => {
    if (document.visibilityState === "visible") void loadStatus();
  }, STATUS_REFRESH_MS);
}

function main(): void {
  try {
    start(ui());
  } catch (e) {
    // 画面の要素が欠けている（index.html と食い違い）。描画できないので状態欄に理由を出す
    const bar = document.getElementById("statusbar");
    if (bar) bar.textContent = e instanceof Error ? e.message : "画面を初期化できません。";
  }
}

if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", main);
else main();
