/**
 * DOM の小さな部品。値は textContent だけで描画する（文字列から HTML を組み立てる API は使わない: XSS 対策。CSP はインライン script を許さない）。
 * 要素は id と型を実行時に確かめて取り出す（non-null assertion・型の付け替えを使わない）。
 */
import type { Pill } from "./view.js";

export function byId<T extends HTMLElement>(id: string, type: { new (): T; prototype: T }): T {
  const e = document.getElementById(id);
  if (!(e instanceof type)) throw new Error(`画面の要素 #${id} が無い、または種類が違う`);
  return e;
}

export function el<K extends keyof HTMLElementTagNameMap>(tag: K, text?: string, cls?: string): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  if (text !== undefined) e.textContent = text;
  if (cls) e.className = cls;
  return e;
}

export type Cell = string | Node;

export function table(headers: readonly string[], rows: readonly (readonly Cell[])[], numCols: readonly number[] = []): HTMLTableElement {
  const cls = (i: number) => (numCols.includes(i) ? "num" : "");
  const t = el("table");
  const head = el("tr");
  headers.forEach((h, i) => {
    head.appendChild(el("th", h, cls(i)));
  });
  t.appendChild(el("thead")).appendChild(head);
  const body = el("tbody");
  for (const r of rows) {
    const tr = el("tr");
    r.forEach((c, i) => {
      const td = el("td", undefined, cls(i));
      if (typeof c === "string") td.textContent = c;
      else td.appendChild(c);
      tr.appendChild(td);
    });
    body.appendChild(tr);
  }
  t.appendChild(body);
  return t;
}

export function pill(p: Pill): HTMLSpanElement {
  return el("span", p.text, p.tone ? `pill ${p.tone}` : "pill");
}

export function show(box: HTMLElement, node: Node): void {
  box.replaceChildren(node);
}

export function emptyBox(msg: string): HTMLDivElement {
  return el("div", msg, "empty");
}

export function errorBox(msg: string): HTMLDivElement {
  return el("div", msg, "error");
}
