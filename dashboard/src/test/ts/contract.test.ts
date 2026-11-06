/**
 * 画面と API の契約の見本（src/test/resources/frontend-contract。合成データ）を schema.ts の検査に通す。
 * 検査の結果のキーが見本のキーと一致することも確かめる（見本が schema のすべての項目を持つ = Kotlin の統合試験の比較が schema 全体を覆う）。
 * あわせて、画面の処理（app.ts）が参照する要素 id が index.html にあること、描画に innerHTML などを使わないことを確かめる。
 */
import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, test } from "node:test";
import { Me, MlModels, Overrides, Performance, Races, Runs, SignalPage, Status } from "../../main/ts/schema.js";
import { type Decoder, isRecord } from "../../main/ts/validate.js";

const ROOT = "dashboard/src";
const CONTRACT = join(ROOT, "test/resources/frontend-contract");
const load = (name: string): unknown => JSON.parse(readFileSync(join(CONTRACT, name), "utf8"));

const CASES: ReadonlyArray<readonly [string, Decoder<unknown>]> = [
  ["status.json", Status],
  ["races.json", Races],
  ["signals.json", SignalPage],
  ["performance.json", Performance],
  ["runs.json", Runs],
  ["overrides.json", Overrides],
  ["me.json", Me],
  ["ml-models.json", MlModels],
];

/** 値の形（オブジェクトのキーを再帰的に。配列は全要素）。 */
function keysOf(v: unknown): unknown {
  if (Array.isArray(v)) return v.map(keysOf);
  if (isRecord(v))
    return Object.fromEntries(
      Object.keys(v)
        .sort()
        .map((k) => [k, keysOf(v[k])]),
    );
  return null;
}

describe("contract: API の応答の見本", () => {
  test("見本のファイルは CASES と一致する（増減の取りこぼしが無い）", () => {
    const files = readdirSync(CONTRACT)
      .filter((f) => f.endsWith(".json"))
      .sort();
    assert.deepEqual(files, CASES.map(([f]) => f).sort());
  });

  for (const [file, decoder] of CASES) {
    test(`${file} は検査を通り、検査の結果と見本のキーが一致する`, () => {
      const raw = load(file);
      const decoded = decoder(raw, "$");
      assert.deepEqual(keysOf(decoded), keysOf(raw));
      if (Array.isArray(raw)) assert.ok(raw.length > 0, "配列の見本は 1 件以上（要素の形を比べるため）");
    });
  }

  test("見本はすべての項目に値がある（null が無い。Kotlin の比較が全項目の JSON の型を確かめられるように）", () => {
    const nulls: string[] = [];
    const walk = (v: unknown, path: string): void => {
      if (v === null) nulls.push(path);
      else if (Array.isArray(v)) {
        for (const [i, x] of v.entries()) walk(x, `${path}[${i}]`);
      } else if (isRecord(v)) for (const [k, x] of Object.entries(v)) walk(x, `${path}.${k}`);
    };
    for (const [file] of CASES) walk(load(file), file);
    assert.deepEqual(nulls, []);
  });

  test("配列を持つ見本は要素を 1 件以上持つ（status.days・signals.items・performance.combos）", () => {
    const status = load("status.json");
    const signals = load("signals.json");
    const perf = load("performance.json");
    assert.ok(isRecord(status) && Array.isArray(status["days"]) && status["days"].length > 0);
    assert.ok(isRecord(status) && isRecord(status["lastRun"]), "lastRun は値のある見本にする");
    assert.ok(isRecord(signals) && Array.isArray(signals["items"]) && signals["items"].length > 0);
    assert.ok(isRecord(perf) && Array.isArray(perf["combos"]) && perf["combos"].length > 0);
  });
});

describe("contract: 静的な画面", () => {
  const html = readFileSync(join(ROOT, "main/resources/static/index.html"), "utf8");
  const sources = readdirSync(join(ROOT, "main/ts"))
    .filter((f) => f.endsWith(".ts"))
    .map((f) => readFileSync(join(ROOT, "main/ts", f), "utf8"));

  test("app.ts が参照する要素 id はすべて index.html にある", () => {
    const app = readFileSync(join(ROOT, "main/ts/app.ts"), "utf8");
    const ids = [...app.matchAll(/byId\("([^"]+)"/g)].map((m) => m[1]);
    assert.ok(ids.length >= 20, `id の参照が少なすぎる（${ids.length}）`);
    for (const id of ids) assert.ok(html.includes(`id="${id}"`), `index.html に id="${id}" が無い`);
  });

  test("app.ts が期待する要素の種類（HTMLxxxElement）と index.html のタグが一致する", () => {
    const app = readFileSync(join(ROOT, "main/ts/app.ts"), "utf8");
    const TAG: Readonly<Record<string, string>> = {
      HTMLDivElement: "div",
      HTMLInputElement: "input",
      HTMLButtonElement: "button",
      HTMLSpanElement: "span",
      HTMLFormElement: "form",
      HTMLSelectElement: "select",
    };
    const refs = [...app.matchAll(/byId\("([^"]+)", (HTML\w+Element)\)/g)];
    assert.ok(refs.length >= 20);
    for (const m of refs) {
      const [, id, type] = m;
      assert.ok(id !== undefined && type !== undefined);
      const tag = TAG[type];
      assert.ok(tag !== undefined, `対応表に無い要素の種類 ${type}`);
      assert.match(html, new RegExp(`<${tag}\\b[^>]*\\bid="${id}"`), `#${id} は <${tag}>`);
    }
  });

  test("index.html はモジュールの script を 1 つだけ読み、インライン script・インラインイベントが無い", () => {
    const scripts = [...html.matchAll(/<script\b[^>]*>/g)].map((m) => m[0]);
    assert.deepEqual(scripts, ['<script type="module" src="/js/app.js">']);
    assert.doesNotMatch(html, /\son[a-z]+=/);
  });

  test("描画は文字列から HTML を作らない（innerHTML・outerHTML・insertAdjacentHTML・document.write・eval・Function）", () => {
    for (const s of sources)
      assert.doesNotMatch(s, /\binnerHTML\b|\bouterHTML\b|insertAdjacentHTML|document\.write|\beval\(|new Function\(/);
  });
});
