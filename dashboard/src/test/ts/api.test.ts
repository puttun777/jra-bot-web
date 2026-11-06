import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { ApiError, CSRF_HEADER, type FetchFn, getJson, messageOf, post } from "../../main/ts/api.js";
import { FORMAT_MESSAGE, NETWORK_MESSAGE } from "../../main/ts/format.js";
import { Me } from "../../main/ts/schema.js";

interface Call {
  readonly input: string;
  readonly init: RequestInit;
}

function fake(respond: () => Response | Promise<Response>): { fn: FetchFn; calls: Call[] } {
  const calls: Call[] = [];
  return {
    calls,
    fn: async (input, init) => {
      calls.push({ input, init });
      return respond();
    },
  };
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const me = { email: "me@example.com", role: "ADMIN", tenantId: 1 };

async function rejectsWith(p: Promise<unknown>, message: string | RegExp): Promise<void> {
  await assert.rejects(
    p,
    (e: unknown) => e instanceof ApiError && (typeof message === "string" ? e.message === message : message.test(e.message)),
  );
}

describe("api: GET", () => {
  test("同一オリジンの相対パスへ Accept: JSON・no-store で送り、検査した値を返す", async () => {
    const f = fake(() => json({ ...me, extra: "ignored" }));
    assert.deepEqual(await getJson("/api/v1/me", Me, f.fn), me);
    assert.equal(f.calls.length, 1);
    assert.equal(f.calls[0]?.input, "/api/v1/me");
    assert.equal(f.calls[0]?.init.cache, "no-store");
    assert.deepEqual(f.calls[0]?.init.headers, { Accept: "application/json" });
    assert.equal(f.calls[0]?.init.method, undefined);
  });

  test("HTTP の失敗は状態コードの文（本文は表示しない）", async () => {
    await rejectsWith(getJson("/x", Me, fake(() => json({ error: "secret detail" }, 503)).fn), /停止中、または準備中/);
    await rejectsWith(getJson("/x", Me, fake(() => json({}, 403)).fn), /権限がありません/);
    await assert.rejects(getJson("/x", Me, fake(() => json({ error: "secret detail" }, 500)).fn), (e: unknown) => {
      assert.ok(e instanceof ApiError);
      assert.doesNotMatch(e.message, /secret/);
      return true;
    });
  });

  test("通信の失敗・JSON でない応答・形の違う応答を区別する", async () => {
    const offline: FetchFn = async () => {
      throw new TypeError("Failed to fetch");
    };
    await rejectsWith(getJson("/x", Me, offline), NETWORK_MESSAGE);
    await rejectsWith(getJson("/x", Me, fake(() => new Response("<html>", { status: 200 })).fn), FORMAT_MESSAGE);
    await rejectsWith(getJson("/x", Me, fake(() => json({ ...me, tenantId: "1" })).fn), FORMAT_MESSAGE);
    await rejectsWith(getJson("/x", Me, fake(() => json([me])).fn), FORMAT_MESSAGE);
  });
});

describe("api: POST", () => {
  test("CSRF の印・JSON の本文・no-store で送る", async () => {
    const f = fake(() => json(me, 201));
    assert.equal(await post("/api/v1/admin/overrides", { a: 1 }, f.fn), undefined);
    const init = f.calls[0]?.init;
    assert.equal(init?.method, "POST");
    assert.equal(init?.cache, "no-store");
    assert.equal(init?.body, '{"a":1}');
    assert.deepEqual(init?.headers, {
      "Content-Type": "application/json",
      Accept: "application/json",
      [CSRF_HEADER.name]: CSRF_HEADER.value,
    });
    assert.equal(CSRF_HEADER.name, "X-Requested-With");
    assert.equal(CSRF_HEADER.value, "jrabot-dashboard");
  });

  test("本文が undefined なら {} を送る", async () => {
    const f = fake(() => json(me));
    await post("/x", undefined, f.fn);
    assert.equal(f.calls[0]?.init.body, "{}");
  });

  test("2xx なら応答の本文の形に関係なく成功（本文は使わない）", async () => {
    assert.equal(await post("/x", {}, fake(() => new Response("<html>", { status: 201 })).fn), undefined);
    assert.equal(await post("/x", {}, fake(() => new Response(null, { status: 204 })).fn), undefined);
  });

  test("409・404・400・403 と通信の失敗の文", async () => {
    await rejectsWith(post("/x", {}, fake(() => json({}, 409)).fn), /操作できません/);
    await rejectsWith(post("/x", {}, fake(() => json({}, 404)).fn), /見つかりません/);
    await rejectsWith(post("/x", {}, fake(() => json({}, 400)).fn), /入力が正しくありません/);
    await rejectsWith(post("/x", {}, fake(() => json({}, 403)).fn), /権限がありません/);
    const offline: FetchFn = async () => {
      throw new TypeError("Failed to fetch");
    };
    await rejectsWith(post("/x", {}, offline), NETWORK_MESSAGE);
  });
});

describe("api: 画面に出す文", () => {
  test("ApiError は文をそのまま、それ以外の例外は詳細を出さない", () => {
    assert.equal(messageOf(new ApiError("a")), "a");
    assert.doesNotMatch(messageOf(new Error("internal stack detail")), /internal/);
    assert.doesNotMatch(messageOf("x"), /^x$/);
  });
});
