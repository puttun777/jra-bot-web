import assert from "node:assert/strict";
import { describe, test } from "node:test";
import {
  array,
  boolean,
  DecodeError,
  instant,
  integer,
  localDate,
  nullable,
  number,
  object,
  record,
  string,
} from "../../main/ts/validate.js";

const rejects = (f: () => unknown, path: string) =>
  assert.throws(f, (e: unknown) => e instanceof DecodeError && e.path === path, `${path} で拒否する`);

describe("validate: 基本の型", () => {
  test("string / number / integer / boolean は型の違う値を拒否する", () => {
    assert.equal(string("a", "$"), "a");
    rejects(() => string(1, "$"), "$");
    assert.equal(number(1.5, "$"), 1.5);
    rejects(() => number("1", "$"), "$");
    rejects(() => number(Number.NaN, "$"), "$");
    rejects(() => number(Number.POSITIVE_INFINITY, "$"), "$");
    assert.equal(integer(3, "$"), 3);
    rejects(() => integer(3.5, "$"), "$");
    rejects(() => integer(2 ** 60, "$"), "$");
    assert.equal(boolean(false, "$"), false);
    rejects(() => boolean("false", "$"), "$");
    rejects(() => boolean(0, "$"), "$");
  });

  test("localDate は YYYY-MM-DD だけ、instant は Date で読める ISO 8601 だけ", () => {
    assert.equal(localDate("2026-10-24", "$"), "2026-10-24");
    rejects(() => localDate("2026/10/24", "$"), "$");
    rejects(() => localDate("2026-10-24T00:00:00Z", "$"), "$");
    assert.equal(instant("2026-10-24T01:00:00Z", "$"), "2026-10-24T01:00:00Z");
    assert.equal(instant("2026-10-24T01:00:00.123456Z", "$"), "2026-10-24T01:00:00.123456Z");
    rejects(() => instant("2026-13-40T00:00:00Z", "$"), "$");
    rejects(() => instant("1700000000", "$"), "$");
    rejects(() => instant(1700000000, "$"), "$");
  });

  test("nullable は null と欠落を null に揃え、それ以外は中の型で検査する", () => {
    const d = nullable(integer);
    assert.equal(d(null, "$"), null);
    assert.equal(d(undefined, "$"), null);
    assert.equal(d(5, "$"), 5);
    rejects(() => d("5", "$"), "$");
  });
});

describe("validate: 配列とオブジェクト", () => {
  const Item = object({ id: integer, name: nullable(string) });

  test("配列の要素の誤りは位置付きで報告する", () => {
    assert.deepEqual(array(integer)([1, 2], "$"), [1, 2]);
    rejects(() => array(integer)([1, "x"], "$"), "$[1]");
    rejects(() => array(integer)({ length: 0 }, "$"), "$");
  });

  test("オブジェクトは定義したキーだけを返し、未知のキーは無視する", () => {
    assert.deepEqual(Item({ id: 1, name: "a", extra: true }, "$"), { id: 1, name: "a" });
    assert.deepEqual(Item({ id: 1 }, "$"), { id: 1, name: null });
  });

  test("必須の項目の欠落・型の誤り・オブジェクト以外を拒否する", () => {
    rejects(() => Item({ name: "a" }, "$"), "$.id");
    rejects(() => Item({ id: "1" }, "$"), "$.id");
    rejects(() => Item(null, "$"), "$");
    rejects(() => Item([], "$"), "$");
    rejects(() => array(Item)([{ id: 1 }, { id: 2, name: 3 }], "$"), "$[1].name");
  });

  test("継承したプロパティを値として読まない（自身のキーだけ）", () => {
    const WithToString = object({ toString: nullable(string) });
    assert.deepEqual(WithToString({}, "$"), { toString: null });
    const parsed: unknown = JSON.parse('{"__proto__": {"id": 1}}');
    rejects(() => Item(parsed, "$"), "$.id");
  });
});

describe("validate: 任意のキーのオブジェクト", () => {
  test("record は全ての値を検査し、配列・null・型の違う値を拒否する", () => {
    assert.deepEqual(record(number)({ a: 1, b: 2.5 }, "$"), { a: 1, b: 2.5 });
    assert.deepEqual(record(number)({}, "$"), {});
    rejects(() => record(number)({ a: "1" }, "$"), "$.a");
    rejects(() => record(number)([1], "$"), "$");
    rejects(() => record(number)(null, "$"), "$");
  });
});
