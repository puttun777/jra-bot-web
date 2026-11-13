/** AWS への公開（D-285）: OIDC → STS → ECR の docker login、SigV4、ECR への push の参照。通信と docker は偽物（実 AWS には接続しない）。 */
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { RunResult } from "../../src/lib/proc.js";
import { assumeRoleWithWebIdentity, ecrLogin, ecrPassword, sigv4 } from "../../src/release/aws.js";
import { type Http, PublishError, pushImages, type Runner } from "../../src/release/publish.js";
import { IMAGE_NAMES } from "../../src/release/repo-facts.js";

const ROLE = "arn:aws:iam::123456789012:role/jrabot-gha-web-push";
const REGISTRY = "123456789012.dkr.ecr.ap-northeast-1.amazonaws.com";
const ENV = { ACTIONS_ID_TOKEN_REQUEST_URL: "https://pipelines.actions.example/token?x=1", ACTIONS_ID_TOKEN_REQUEST_TOKEN: "req-token" };
const STS_XML = `<AssumeRoleWithWebIdentityResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/"><AssumeRoleWithWebIdentityResult>
<Credentials><AccessKeyId>ASIASYNTHETIC</AccessKeyId><SecretAccessKey>synthetic/secret</SecretAccessKey><SessionToken>synthetic-session</SessionToken>
<Expiration>2026-11-13T11:00:00Z</Expiration></Credentials></AssumeRoleWithWebIdentityResult></AssumeRoleWithWebIdentityResponse>`;
const ECR_JSON = { authorizationData: [{ authorizationToken: Buffer.from("AWS:synthetic-ecr-password").toString("base64") }] };

interface Call {
  readonly url: string;
  readonly headers: Record<string, string>;
  readonly body?: string;
}

function fakeHttp(responses: Record<string, { status: number; body: unknown }>, calls: Call[]): Http {
  return async (url, init) => {
    calls.push({ url, headers: init.headers, ...(init.body === undefined ? {} : { body: init.body }) });
    const key = Object.keys(responses).find((k) => url.startsWith(k));
    const r = key === undefined ? { status: 404, body: "" } : responses[key];
    assert.ok(r);
    return { status: r.status, text: async () => (typeof r.body === "string" ? r.body : JSON.stringify(r.body)) };
  };
}

const ok = (stdout = ""): RunResult => ({ status: 0, stdout, stderr: "", timedOut: false });

describe("SigV4", () => {
  it("AWS の公開試験ベクトル get-vanilla と同じ署名", () => {
    const h = sigv4({
      method: "GET",
      host: "example.amazonaws.com",
      path: "/",
      headers: {},
      body: "",
      region: "us-east-1",
      service: "service",
      credentials: { accessKeyId: "AKIDEXAMPLE", secretAccessKey: "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY" },
      now: new Date("2015-08-30T12:36:00Z"),
    });
    assert.equal(
      h["authorization"],
      "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, SignedHeaders=host;x-amz-date, Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31",
    );
    assert.equal(h["x-amz-date"], "20150830T123600Z");
  });

  it("一時的な資格情報はセッショントークンも署名に含める", () => {
    const h = sigv4({
      method: "POST",
      host: "api.ecr.ap-northeast-1.amazonaws.com",
      path: "/",
      headers: { "Content-Type": "application/x-amz-json-1.1" },
      body: "{}",
      region: "ap-northeast-1",
      service: "ecr",
      credentials: { accessKeyId: "ASIA", secretAccessKey: "s", sessionToken: "tok" },
      now: new Date("2026-11-13T00:00:00Z"),
    });
    assert.equal(h["x-amz-security-token"], "tok");
    assert.match(h["authorization"] ?? "", /SignedHeaders=content-type;host;x-amz-date;x-amz-security-token,/);
  });
});

describe("ecr-login (OIDC → STS → ECR → docker login)", () => {
  it("OIDC の audience は sts.amazonaws.com、STS は署名なし、ECR は署名つき。パスワードは docker login の標準入力にだけ渡す", async () => {
    const calls: Call[] = [];
    const runs: { args: readonly string[]; input?: string }[] = [];
    const runner: Runner = (_c, args, input) => {
      runs.push({ args, ...(input === undefined ? {} : { input }) });
      return ok();
    };
    const http = fakeHttp(
      {
        "https://pipelines.actions.example/token": { status: 200, body: { value: "oidc-jwt" } },
        "https://sts.ap-northeast-1.amazonaws.com/": { status: 200, body: STS_XML },
        "https://api.ecr.ap-northeast-1.amazonaws.com/": { status: 200, body: ECR_JSON },
      },
      calls,
    );
    await ecrLogin({ roleArn: ROLE, registry: REGISTRY, sessionName: "release-1" }, ENV, http, runner);
    assert.equal(new URL(calls[0]?.url ?? "").searchParams.get("audience"), "sts.amazonaws.com");
    const sts = new URLSearchParams(calls[1]?.body ?? "");
    assert.equal(sts.get("Action"), "AssumeRoleWithWebIdentity");
    assert.equal(sts.get("RoleArn"), ROLE);
    assert.equal(sts.get("WebIdentityToken"), "oidc-jwt");
    assert.equal(calls[1]?.headers["authorization"], undefined, "STS の呼び出しは署名しない");
    assert.match(
      calls[2]?.headers["authorization"] ?? "",
      /^AWS4-HMAC-SHA256 Credential=ASIASYNTHETIC\/\d{8}\/ap-northeast-1\/ecr\/aws4_request/,
    );
    assert.equal(calls[2]?.headers["x-amz-security-token"], "synthetic-session");
    assert.deepEqual(runs, [
      { args: ["login", "--username", "AWS", "--password-stdin", `https://${REGISTRY}`], input: "synthetic-ecr-password" },
    ]);
  });

  it("ロール・レジストリの形式違い、アカウントの不一致、STS・ECR の失敗は通信の前か後で拒否（応答の本文は出さない）", async () => {
    const runner: Runner = () => ok();
    const http = fakeHttp({}, []);
    await assert.rejects(ecrLogin({ roleArn: ROLE, registry: "example.com", sessionName: "x" }, ENV, http, runner), PublishError);
    await assert.rejects(
      ecrLogin({ roleArn: ROLE, registry: "999999999999.dkr.ecr.ap-northeast-1.amazonaws.com", sessionName: "x" }, ENV, http, runner),
      /アカウントが一致しない/,
    );
    await assert.rejects(assumeRoleWithWebIdentity("arn:aws:iam::1:role/x", "t", "s", http), PublishError);
    const denied = fakeHttp({ "https://sts.": { status: 403, body: "<Error><Message>secret-ish</Message></Error>" } }, []);
    await assert.rejects(
      assumeRoleWithWebIdentity(ROLE, "t", "s", denied),
      (e: Error) => e instanceof PublishError && !e.message.includes("secret-ish"),
    );
    const bad = fakeHttp(
      { "https://api.ecr.": { status: 200, body: { authorizationData: [{ authorizationToken: Buffer.from("x").toString("base64") }] } } },
      [],
    );
    await assert.rejects(ecrPassword({ accessKeyId: "a", secretAccessKey: "b", sessionToken: "c" }, bad), PublishError);
    await assert.rejects(ecrLogin({ roleArn: ROLE, registry: REGISTRY, sessionName: "x" }, {}, http, runner), /id-token: write/);
  });
});

describe("push to ECR", () => {
  it("ECR は <アカウント>.dkr.ecr…/jra-bot/<name>（--project 不要）で、digest 付きの参照を返す", () => {
    const sha = "a".repeat(40);
    const digest = "c".repeat(64);
    const runner: Runner = (_c, args) => {
      if (args[0] === "push") return ok(`latest: digest: sha256:${digest} size: 1234`);
      if (args[0] === "image") {
        const name = String(args[2]).split("/jra-bot/")[1]?.split(":")[0];
        return ok(JSON.stringify([`${REGISTRY}/jra-bot/${String(name)}@sha256:${digest}`]));
      }
      return ok();
    };
    const refs = pushImages({ gitSha: sha, registry: REGISTRY }, runner);
    for (const n of IMAGE_NAMES) assert.equal(refs[n], `${REGISTRY}/jra-bot/${n}@sha256:${digest}`);
    assert.throws(() => pushImages({ gitSha: sha, registry: "asia-northeast1-docker.pkg.dev" }, runner), /--project/);
    assert.throws(() => pushImages({ gitSha: sha, registry: "evil.example.com" }, runner), PublishError);
  });
});
