// JRA Bot ダッシュボード（読取り専用）。外部ライブラリ・CDN・インライン script なし。値は textContent だけで描画する（XSS 対策）。
'use strict';
const $ = (id) => document.getElementById(id);
const state = { page: 0, size: 50, tab: 'overview' };
const fmtTime = (iso) => (iso ? new Date(iso).toLocaleTimeString('ja-JP', { timeZone: 'Asia/Tokyo', hour: '2-digit', minute: '2-digit' }) : '—');
const fmtDateTime = (iso) => (iso ? new Date(iso).toLocaleString('ja-JP', { timeZone: 'Asia/Tokyo', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '—');
const pct = (v) => (v == null ? '—' : (v * 100).toFixed(1) + '%');
const num = (v, d = 0) => (v == null ? '—' : Number(v).toLocaleString('ja-JP', { minimumFractionDigits: d, maximumFractionDigits: d }));
const MODE = { NORMAL: ['通常運用', 'ok'], STARTING: ['起動中', 'warn'], STOPPING: ['停止処理中', 'warn'], STOPPED: ['停止中', 'warn'], UNKNOWN: ['状態不明', 'ng'] };
const DAY = { PLANNED: '予定', UNCONFIRMED: '未確認', CONFIRMED: '開催確定', RUNNING: '監視中', SETTLING: '結果待ち', COMPLETED: '完了', INCOMPLETE: '結果未了', CANCELLED: '中止', POSTPONED: '延期', UNPLANNED: '未計画' };
const RACE = { ANNOUNCED: '発表', CARD: '出馬表', MONITORING: '監視中', OFF: '発走', RESULT_PENDING: '結果待ち', RESULT_PARTIAL: '払戻待ち', SETTLED: '確定', FINALIZED: '確定済み', CANCELLED: '中止', DELETED: '削除' };
const SIGNAL = { UNSENT: '未通知', CLAIMED: '送信中', SENT: '通知済み', SEND_UNKNOWN: '送信不明' };
const RUN = { STARTING: '開始中', RUNNING: '実行中', DRAINING: '停止処理中', SUCCEEDED: '成功', FAILED: '失敗', ABORTED: '中断', STOPPED_BY_MODE: 'モード停止', INTERRUPTED: '中断' };

function el(tag, text, cls) { const e = document.createElement(tag); if (text != null) e.textContent = text; if (cls) e.className = cls; return e; }
function table(headers, rows, numCols = []) {
  const t = el('table'); const thead = el('thead'); const tr = el('tr');
  headers.forEach((h, i) => tr.appendChild(el('th', h, numCols.includes(i) ? 'num' : '')));
  thead.appendChild(tr); t.appendChild(thead);
  const tb = el('tbody');
  rows.forEach((r) => { const row = el('tr'); r.forEach((c, i) => row.appendChild(el('td', c, numCols.includes(i) ? 'num' : ''))); tb.appendChild(row); });
  t.appendChild(tb); return t;
}
function show(box, node) { box.replaceChildren(node); }
function empty(msg) { return el('div', msg, 'empty'); }
function failure(res) {
  if (res.status === 401 || res.status === 403) return '権限がありません（許可された利用者でログインしてください）。';
  if (res.status === 503) return 'システムは停止中、または準備中です。起動後に「更新」を押してください。';
  if (res.status === 400) return '入力が正しくありません。';
  return '取得に失敗しました（しばらくして「更新」を押してください）。';
}
async function api(path) {
  let res;
  try { res = await fetch(path, { headers: { Accept: 'application/json' }, cache: 'no-store' }); } catch (e) { throw new Error('通信できません。接続を確認してください。'); }
  if (!res.ok) throw new Error(failure(res));
  return res.json();
}
function q() { const d = $('date').value; return d ? '?date=' + encodeURIComponent(d) : ''; }

async function loadStatus() {
  const bar = $('statusbar');
  try {
    const s = await api('/api/v1/status');
    const [label, cls] = MODE[s.mode] || [s.mode, 'warn'];
    const parts = [el('span', label, 'pill ' + cls)];
    if (s.scheduleStale) parts.push(el('span', '予定が未更新（最終確認 ' + fmtDateTime(s.latestConfirmedAt) + '）', 'pill ng'));
    else if (s.latestConfirmedAt) parts.push(el('span', '予定確認 ' + fmtTime(s.latestConfirmedAt), 'pill muted'));
    s.days.forEach((d) => parts.push(el('span', d.venueCode + ' ' + (DAY[d.status] || d.status), 'pill')));
    if (s.nextRaceAt) parts.push(el('span', '次の発走 ' + fmtTime(s.nextRaceAt), 'pill'));
    if (s.lastRun) {
      const bad = s.lastRun.state === 'FAILED' || s.lastRun.state === 'ABORTED';
      parts.push(el('span', '直近の実行: ' + (RUN[s.lastRun.state] || s.lastRun.state) + (s.lastRun.exitReason ? '（' + s.lastRun.exitReason + '）' : ''), 'pill ' + (bad ? 'ng' : '')));
    }
    bar.replaceChildren(...parts);
    if (!$('date').value) $('date').value = s.today;
  } catch (e) { bar.replaceChildren(el('span', e.message, 'pill ng')); }
}
async function loadRaces() {
  const box = $('races');
  try {
    const rows = await api('/api/v1/races' + q());
    if (!rows.length) return show(box, empty('この日のレース予定はありません。'));
    show(box, table(['発走', 'レース', '名称', '状態'], rows.map((r) => [fmtTime(r.postAt), r.venueCode + ' ' + r.raceNo + 'R', r.name || '—', RACE[r.status] || r.status])));
  } catch (e) { show(box, el('div', e.message, 'error')); }
}
async function loadSignals() {
  const box = $('signal-table');
  try {
    const p = await api('/api/v1/signals' + (q() ? q() + '&' : '?') + 'page=' + state.page + '&size=' + state.size);
    $('pageinfo').textContent = p.total ? (p.page * p.size + 1) + '–' + Math.min((p.page + 1) * p.size, p.total) + ' / ' + p.total + ' 件' : '0 件';
    $('prev').disabled = p.page === 0; $('next').disabled = (p.page + 1) * p.size >= p.total;
    if (!p.items.length) return show(box, empty('この日のシグナルはありません。'));
    show(box, table(['観測', 'レース', '馬番', 'オッズ', '確率', 'EV', '推奨額', '通知', '根拠'],
      p.items.map((s) => [fmtTime(s.observedAt), s.raceId, s.umaban, num(s.odds, 1), pct(s.prob), num(s.ev, 2), num(s.stake, 1), SIGNAL[s.state] || s.state, s.probSource || '—']), [2, 3, 4, 5, 6]));
  } catch (e) { show(box, el('div', e.message, 'error')); }
}
async function loadPerformance() {
  const box = $('perf');
  const from = $('from').value, to = $('to').value;
  const query = (from ? 'from=' + encodeURIComponent(from) : '') + (to ? (from ? '&' : '') + 'to=' + encodeURIComponent(to) : '');
  try {
    const p = await api('/api/v1/performance' + (query ? '?' + query : ''));
    if (!$('from').value) { $('from').value = p.from; $('to').value = p.to; }
    const rows = [['単勝', p.winCount, p.winHits, pct(p.winHitRate), num(p.winTotalStake, 1), num(p.winActualPayout, 1), pct(p.winActualRoi)]];
    p.combos.forEach((c) => rows.push([c.market === 'wide' ? 'ワイド' : '三連複', c.count, c.hits, pct(c.hitRate), num(c.totalStake, 1), num(c.actualPayout, 1), pct(c.actualRoi)]));
    const wrap = el('div');
    wrap.appendChild(table(['賭式', '件数', '的中', '的中率', '推奨額合計', '実払戻', '回収率'], rows, [1, 2, 3, 4, 5, 6]));
    const notes = [];
    if (p.winUnsettled) notes.push('単勝の未確定 ' + p.winUnsettled + ' 件');
    if (p.comboUnsettled) notes.push('買い目の未確定 ' + p.comboUnsettled + ' 件');
    if (p.racesWithoutPostTime) notes.push('発走時刻不明のレース ' + p.racesWithoutPostTime);
    if (p.unreadableNotices) notes.push('読めない通知内容 ' + p.unreadableNotices + ' 件');
    wrap.appendChild(el('p', notes.length ? '集計に含めていないもの: ' + notes.join('、') : '集計に含めていないものはありません。', 'note'));
    if (!p.winCount && !p.combos.length) wrap.prepend(empty('この期間に確定した成績はありません。'));
    show(box, wrap);
  } catch (e) { show(box, el('div', e.message, 'error')); }
}
async function loadRuns() {
  const box = $('runs');
  try {
    const rows = await api('/api/v1/runs' + q());
    if (!rows.length) return show(box, empty('この日の bot の実行記録はありません。'));
    show(box, table(['開始', '終了', '起動', '状態', '理由'], rows.map((r) => [fmtDateTime(r.startedAt), fmtDateTime(r.finishedAt), r.trigger, RUN[r.state] || r.state, r.exitReason || '—'])));
  } catch (e) { show(box, el('div', e.message, 'error')); }
}
async function post(path, body) {
  const res = await fetch(path, { method: 'POST', cache: 'no-store', headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'jrabot-dashboard' }, body: JSON.stringify(body || {}) });
  if (!res.ok) throw new Error(res.status === 409 ? '操作できません（停止中、または既に取消済み／重複しています）。' : res.status === 404 ? '対象が見つかりません。' : res.status === 400 ? '入力が正しくありません。' : failure(res));
  return res.json();
}
async function loadOverrides() {
  const box = $('overrides');
  try {
    const rows = await api('/api/v1/admin/overrides' + q());
    if (!rows.length) return show(box, empty('この日の手動補正はありません。'));
    const t = table(['作成', '対象', '操作', '理由', '作成者', '状態', ''], rows.map((o) => [fmtDateTime(o.createdAt), o.raceId || o.date, o.action, o.reason, o.createdBy, o.revokedAt ? '取消済み' : '有効', '']));
    t.querySelectorAll('tbody tr').forEach((tr, i) => {
      if (rows[i].revokedAt) return;
      const b = el('button', '取消'); b.type = 'button';
      b.addEventListener('click', async () => { if (!confirm('この補正を取り消しますか？')) return; try { await post('/api/v1/admin/overrides/' + rows[i].id + '/revoke'); loadOverrides(); } catch (e) { $('o-msg').textContent = e.message; } });
      tr.lastChild.appendChild(b);
    });
    show(box, t);
  } catch (e) { show(box, el('div', e.message, 'error')); }
}
async function initAdmin() {
  try {
    const me = await api('/api/v1/me');
    if (me.role !== 'ADMIN') return;
    $('admin').hidden = false;
    $('override-form').addEventListener('submit', async (ev) => {
      ev.preventDefault();
      const body = { scope: $('o-scope').value, action: $('o-action').value, date: $('date').value, raceId: $('o-race').value || null, postAt: $('o-postat').value || null, postponedTo: $('o-postponed').value || null, reason: $('o-reason').value };
      try { await post('/api/v1/admin/overrides', body); $('o-msg').textContent = '追加しました。'; $('o-reason').value = ''; loadOverrides(); } catch (e) { $('o-msg').textContent = e.message; }
    });
    loadOverrides();
  } catch (e) { /* 権限が無い・通信できない場合は管理 UI を出さない */ }
}
const LOADERS = { overview: loadRaces, signals: loadSignals, performance: loadPerformance, ops: () => { loadRuns(); if (!$('admin').hidden) loadOverrides(); } };
function refresh() { loadStatus(); LOADERS[state.tab](); }
function select(tab) {
  state.tab = tab;
  document.querySelectorAll('nav [data-tab]').forEach((b) => b.setAttribute('aria-selected', String(b.dataset.tab === tab)));
  document.querySelectorAll('.tab').forEach((s) => { s.hidden = s.id !== tab; });
  LOADERS[tab]();
}
document.addEventListener('DOMContentLoaded', () => {
  document.querySelectorAll('nav [data-tab]').forEach((b) => b.addEventListener('click', () => select(b.dataset.tab)));
  $('reload').addEventListener('click', refresh);
  $('date').addEventListener('change', () => { state.page = 0; LOADERS[state.tab](); });
  $('from').addEventListener('change', loadPerformance); $('to').addEventListener('change', loadPerformance);
  $('prev').addEventListener('click', () => { state.page = Math.max(0, state.page - 1); loadSignals(); });
  $('next').addEventListener('click', () => { state.page += 1; loadSignals(); });
  loadStatus().then(() => { LOADERS.overview(); initAdmin(); });
  setInterval(() => { if (document.visibilityState === 'visible') loadStatus(); }, 60000);
});
