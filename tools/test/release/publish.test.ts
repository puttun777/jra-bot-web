/** リリースの公開（L9。D-276）: WIF の短期トークン・docker login・push・digest・ci gate の検査。通信と docker は偽物で確かめる。 */
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { RunResult } from "../../src/lib/proc.js";
import { checkCi, digestRef, type Http, main, PublishError, pushImages, type Runner, registryLogin } from "../../src/release/publish.js";
import { IMAGE_NAMES } from "../../src/release/repo-facts.js";

const PROVIDER = "projects/123456789/locations/global/workloadIdentityPools/jrabot-github-web/providers/github-web-oidc";
const SA = "jrabot-gha-web-push@jrabot-prod.iam.gserviceaccount.com";
const SHA = "a".repeat(40);
const DIGEST = "b".repeat(64);

interface Call {
  readonly url: string;
  readonly method: string;
  readonly headers: Record<string, string>;
  readonly body?: string;
}

function fakeHttp(responses: Record<string, { status: number; body: unknown }>, calls: Call[]): Http {
  return async (url, init) => {
    calls.push({ url, method: init.method, headers: init.headers, ...(init.body === undefined ? {} : { body: init.body }) });
    const key = Object.keys(responses).find((k) => url.startsWith(k));
    const r = key === undefined ? { status: 404, body: {} } : responses[key];
    assert.ok(r);
    return { status: r.status, text: async () => (typeof r.body === "string" ? r.body : JSON.stringify(r.body)) };
  };
}

const ok = (stdout = ""): RunResult => ({ status: 0, stdout, stderr: "", timedOut: false });

const ENV = { ACTIONS_ID_TOKEN_REQUEST_URL: "https://pipelines.actions.example/token?x=1", ACTIONS_ID_TOKEN_REQUEST_TOKEN: "req-token" };
const TOKENS = {
  "https://pipelines.actions.example/token": { status: 200, body: { value: "oidc-jwt" } },
  "https://sts.googleapis.com/v1/token": { status: 200, body: { access_token: "federated", token_type: "Bearer" } },
  "https://iamcredentials.googleapis.com/": { status: 200, body: { accessToken: "sa-access-token", expireTime: "2026-11-07T07:00:00Z" } },
};

describe("registry-login (WIF → 短期トークン → docker login)", () => {
  it("OIDC の audience は provider、STS・IAM の順に交換し、トークンは docker login の標準入力にだけ渡す", async () => {
    const calls: Call[] = [];
    const runs: { cmd: string; args: readonly string[]; input?: string }[] = [];
    const runner: Runner = (cmd, args, input) => {
      runs.push({ cmd, args, ...(input === undefined ? {} : { input }) });
      return ok();
    };
    await registryLogin(
      { provider: PROVIDER, serviceAccount: SA, registry: "asia-northeast1-docker.pkg.dev" },
      ENV,
      fakeHttp(TOKENS, calls),
      runner,
    );
    assert.equal(calls.length, 3);
    const oidcUrl = new URL(calls[0]?.url ?? "");
    assert.equal(oidcUrl.searchParams.get("audience"), `https://iam.googleapis.com/${PROVIDER}`);
    assert.equal(oidcUrl.searchParams.get("x"), "1", "元のクエリを保つ");
    assert.equal(calls[0]?.headers["Authorization"], "Bearer req-token");
    const sts = JSON.parse(calls[1]?.body ?? "{}") as Record<string, unknown>;
    assert.equal(sts["audience"], `//iam.googleapis.com/${PROVIDER}`);
    assert.equal(sts["subjectToken"], "oidc-jwt");
    assert.equal(sts["subjectTokenType"], "urn:ietf:params:oauth:token-type:jwt");
    assert.match(calls[2]?.url ?? "", /serviceAccounts\/jrabot-gha-web-push%40jrabot-prod\.iam\.gserviceaccount\.com:generateAccessToken$/);
    assert.equal(calls[2]?.headers["Authorization"], "Bearer federated");
    assert.deepEqual(runs, [
      {
        cmd: "docker",
        args: ["login", "--username", "oauth2accesstoken", "--password-stdin", "https://asia-northeast1-docker.pkg.dev"],
        input: "sa-access-token",
      },
    ]);
    assert.ok(!runs.some((r) => r.args.some((a) => a.includes("sa-access-token") || a.includes("federated"))), "トークンを引数に載せない");
  });

  it("id-token の権限が無い・HTTP の失敗・形式の誤り・docker login の失敗はすべて例外（応答の本文は出さない）", async () => {
    const runner: Runner = () => ok();
    await assert.rejects(
      registryLogin(
        { provider: PROVIDER, serviceAccount: SA, registry: "asia-northeast1-docker.pkg.dev" },
        {},
        fakeHttp(TOKENS, []),
        runner,
      ),
      /id-token/,
    );
    await assert.rejects(
      registryLogin(
        { provider: PROVIDER, serviceAccount: SA, registry: "asia-northeast1-docker.pkg.dev" },
        ENV,
        fakeHttp({ ...TOKENS, "https://sts.googleapis.com/v1/token": { status: 403, body: "secret-ish body" } }, []),
        runner,
      ),
      (e: unknown) => e instanceof PublishError && /HTTP 403/.test(e.message) && !e.message.includes("secret-ish"),
    );
    await assert.rejects(
      registryLogin(
        { provider: PROVIDER, serviceAccount: SA, registry: "asia-northeast1-docker.pkg.dev" },
        ENV,
        fakeHttp({ ...TOKENS, "https://iamcredentials.googleapis.com/": { status: 200, body: {} } }, []),
        runner,
      ),
      /accessToken が無い/,
    );
    await assert.rejects(
      registryLogin(
        { provider: PROVIDER, serviceAccount: SA, registry: "asia-northeast1-docker.pkg.dev" },
        ENV,
        fakeHttp(TOKENS, []),
        () => ({ status: 1, stdout: "", stderr: "denied", timedOut: false }),
      ),
      /docker login が失敗/,
    );
    await assert.rejects(
      registryLogin(
        { provider: PROVIDER, serviceAccount: SA, registry: "asia-northeast1-docker.pkg.dev" },
        { ...ENV, ACTIONS_ID_TOKEN_REQUEST_URL: "http://plain.example/" },
        fakeHttp(TOKENS, []),
        runner,
      ),
      /https/,
    );
  });

  it("provider・SA・レジストリの形を検査する（通信の前に止める）", async () => {
    const calls: Call[] = [];
    for (const [provider, sa, registry] of [
      ["projects/x/locations/global/workloadIdentityPools/p/providers/q", SA, "asia-northeast1-docker.pkg.dev"],
      [PROVIDER, "someone@gmail.com", "asia-northeast1-docker.pkg.dev"],
      [PROVIDER, SA, "ghcr.io"],
    ] as const) {
      await assert.rejects(
        registryLogin({ provider, serviceAccount: sa, registry }, ENV, fakeHttp(TOKENS, calls), () => ok()),
        PublishError,
      );
    }
    assert.equal(calls.length, 0);
  });
});

describe("push (Git SHA のタグで push し、digest 付き参照を得る)", () => {
  const pushOut = (d = DIGEST): RunResult => ok(`The push refers to repository [x]\n${SHA}: digest: sha256:${d} size: 1234\n`);
  const fake =
    (inspectDigest = DIGEST, pushDigest = DIGEST): Runner =>
    (_cmd, args) => {
      if (args[0] === "push") return pushOut(pushDigest);
      if (args[0] === "image") {
        const ref = String(args[2]).split(":")[0] ?? "";
        return ok(JSON.stringify([`other.example/x@sha256:${"c".repeat(64)}`, `${ref}@sha256:${inspectDigest}`]));
      }
      return ok();
    };
  it("5 つのイメージを tag → push → inspect し、push が報告した digest と一致する期待のリポジトリの参照を選ぶ", () => {
    const runs: string[] = [];
    const inner = fake();
    const refs = pushImages({ project: "jrabot-prod", gitSha: SHA, registry: "asia-northeast1-docker.pkg.dev" }, (c, args, i) => {
      runs.push(args.join(" "));
      return inner(c, args, i);
    });
    assert.deepEqual(Object.keys(refs).sort(), [...IMAGE_NAMES].sort());
    assert.equal(refs.bot, `asia-northeast1-docker.pkg.dev/jrabot-prod/jra-bot/bot@sha256:${DIGEST}`);
    assert.ok(runs.includes(`tag jra-bot/bot:${SHA} asia-northeast1-docker.pkg.dev/jrabot-prod/jra-bot/bot:${SHA}`));
    assert.ok(runs.includes(`push asia-northeast1-docker.pkg.dev/jrabot-prod/jra-bot/bot:${SHA}`));
    assert.equal(runs.length, IMAGE_NAMES.length * 3);
  });

  it("push の失敗（理由の末尾を出す）・push の digest が無い・inspect と食い違う・形の違う digest は失敗", () => {
    const opts = { project: "jrabot-prod", gitSha: SHA, registry: "asia-northeast1-docker.pkg.dev" };
    assert.throws(
      () =>
        pushImages(opts, (_c, a) =>
          a[0] === "push" ? { status: 1, stdout: "", stderr: "denied: permission\n", timedOut: false } : ok("[]"),
        ),
      /docker push .*denied: permission/,
    );
    assert.throws(() => pushImages(opts, (_c, a) => (a[0] === "push" ? ok("no digest here") : ok("[]"))), /digest が 1 つだけ無い/);
    assert.throws(() => pushImages(opts, fake(DIGEST, "d".repeat(64))), /一致しない/);
    assert.throws(() => digestRef([], "r/x"), /digest を得られない/);
    assert.throws(() => digestRef([`r/x@sha256:${"g".repeat(64)}`], "r/x"), /digest を得られない/);
    assert.throws(() => digestRef([`r/xy@sha256:${DIGEST}`], "r/x"), /digest を得られない/);
    assert.throws(() => digestRef("nope", "r/x"), /形式が不正/);
    assert.equal(digestRef([`r/x@sha256:${DIGEST}`], "r/x"), `r/x@sha256:${DIGEST}`);
    assert.throws(() => pushImages({ ...opts, project: "Bad_Project" }, () => ok()), /--project/);
    assert.throws(() => pushImages({ ...opts, gitSha: "abc" }, () => ok()), /--git-sha/);
  });
});

describe("check-ci (このコミットで CI がコードを実際に検査したこと)", () => {
  const url = `https://api.github.com/repos/puttun777/jra-bot-web/commits/${SHA}/check-runs`;
  const cr = (name: string, conclusion: string | null, extra: Record<string, unknown> = {}) => ({
    name,
    head_sha: SHA,
    status: conclusion === null ? "in_progress" : "completed",
    conclusion,
    app: { slug: "github-actions" },
    ...extra,
  });
  const green = [
    cr("changes and repo hygiene", "success"),
    cr("build (Temurin 25)", "success"),
    cr("images / images (build + smoke, no push)", "success"),
    cr("db integration (MySQL 8.4)", "success"),
    cr("connectorj26", "skipped"),
    cr("ci gate", "success"),
  ];
  const check = (runs: unknown[], calls: Call[] = []) =>
    checkCi(
      { repo: "puttun777/jra-bot-web", sha: SHA, token: "t" },
      fakeHttp({ [url]: { status: 200, body: { total_count: runs.length, check_runs: runs } } }, calls),
    );
  it("gate・build・images・db integration が成功なら通る（skipped の任意のジョブは問わない）", async () => {
    const calls: Call[] = [];
    await check(green, calls);
    assert.match(calls[0]?.url ?? "", /filter=latest&per_page=100&page=1$/);
  });
  it("ドキュメントだけのコミット（build・images が skipped）・失敗・取消・進行中・別 App の同名・別のコミットは 1", async () => {
    const replace = (name: string, v: unknown) => green.map((c) => (c.name === name ? v : c));
    for (const runs of [
      replace("build (Temurin 25)", cr("build (Temurin 25)", "skipped")),
      replace("images / images (build + smoke, no push)", cr("images / images (build + smoke, no push)", "skipped")),
      green.filter((c) => !c.name.startsWith("db integration")),
      [...green, cr("db integration (MySQL 9.7)", "failure")],
      [...green, cr("agent windows", "cancelled")],
      replace("ci gate", cr("ci gate", null)),
      replace("ci gate", cr("ci gate", "success", { app: { slug: "someone-else" } })),
      replace("build (Temurin 25)", cr("build (Temurin 25)", "success", { head_sha: "f".repeat(40) })),
      [],
    ]) {
      await assert.rejects(check(runs), (e: unknown) => e instanceof PublishError && e.code === 1);
    }
    await assert.rejects(checkCi({ repo: "puttun777/jra-bot-web", sha: SHA, token: "t" }, fakeHttp({}, [])), /HTTP 404/);
  });
});

describe("CLI", () => {
  it("未知のサブコマンド・引数の欠落・未知の引数は 2、GITHUB_TOKEN が無ければ 2", async () => {
    assert.equal(await main(["nope"], {}), 2);
    assert.equal(await main(["push", "--project", "jrabot-prod"], {}), 2);
    assert.equal(await main(["push", "--bogus", "x"], {}), 2);
    assert.equal(await main(["check-ci", "--repo", "a/b", "--sha", SHA], {}), 2);
    assert.equal(await main(["registry-login", "--provider", PROVIDER, "--service-account", SA], {}), 2);
  });
});
