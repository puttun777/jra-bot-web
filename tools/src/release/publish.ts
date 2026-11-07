/**
 * リリースの公開（L9。D-276）: `release.yml` から呼ぶ。本人が実行し、承認した環境（`release`）でだけ動く。
 *
 *   publish check-ci     --repo owner/name --sha <40桁>                 対象コミットの `ci gate` が成功していること（GITHUB_TOKEN）
 *   publish registry-login --provider <WIF の provider> --service-account <SA> [--registry asia-northeast1-docker.pkg.dev]
 *                                                                       GitHub の OIDC → STS → SA の短期トークンで docker login（長期キーなし）
 *   publish push         --project <GCP プロジェクト> --git-sha <40桁> --out registry-images.json [--registry …]
 *                                                                       ローカルの jra-bot/<name>:<sha> を push し、digest 付き参照を書く
 *
 * トークンは標準出力・標準エラー・ファイルに書かない（docker login の標準入力にだけ渡す）。
 * 終了コード: 0 = 成功 / 1 = 検査 NG（ci gate が成功でない・digest を得られない）/ 2 = 引数・入力・通信の誤り
 */
import { writeFileSync } from "node:fs";
import { parseArgs } from "node:util";
import { isRecord } from "../lib/json.js";
import { type RunResult, run as runProcess } from "../lib/proc.js";
import { IMAGE_NAMES, type ImageName } from "./repo-facts.js";

export const DEFAULT_REGISTRY = "asia-northeast1-docker.pkg.dev";
/** Artifact Registry のリポジトリ名（CONTRACTS §10: `<registry>/<project>/jra-bot/<name>@sha256:…`）。 */
export const REPOSITORY = "jra-bot";
const SCOPE = "https://www.googleapis.com/auth/cloud-platform";

export class PublishError extends Error {
  override readonly name = "PublishError";
  constructor(
    message: string,
    readonly code: 1 | 2 = 2,
  ) {
    super(message);
  }
}

/** HTTP の最小の抽象（試験で差し替える）。 */
export interface HttpResponse {
  readonly status: number;
  text(): Promise<string>;
}
export type Http = (url: string, init: { method: "GET" | "POST"; headers: Record<string, string>; body?: string }) => Promise<HttpResponse>;
export type Runner = (cmd: string, args: readonly string[], input?: string) => RunResult;

const defaultHttp: Http = async (url, init) => {
  const signal = AbortSignal.timeout(60_000);
  const r = await fetch(url, init.body === undefined ? { method: init.method, headers: init.headers, signal } : { ...init, signal });
  return { status: r.status, text: () => r.text() };
};
/** docker の push は大きいので 30 分まで。止まったら失敗にする（タイムアウトの無い待ちを作らない: レビュー 低）。 */
const defaultRunner: Runner = (cmd, args, input) =>
  runProcess(cmd, args, { timeoutMs: 30 * 60_000, ...(input === undefined ? {} : { input }) });

/** docker の失敗の理由（標準エラーの末尾。秘密は含まない: トークンは標準入力にだけ渡す）。 */
const tail = (r: RunResult): string => r.stderr.trim().split("\n").slice(-3).join(" / ").slice(-300);

async function json(r: HttpResponse, what: string): Promise<Record<string, unknown>> {
  const text = await r.text();
  // 応答の本文は出さない（トークンを含み得る）。状態コードだけ
  if (r.status < 200 || r.status >= 300) throw new PublishError(`${what} が失敗（HTTP ${r.status}）`);
  let v: unknown;
  try {
    v = JSON.parse(text);
  } catch {
    throw new PublishError(`${what} の応答が JSON ではない`);
  }
  if (!isRecord(v)) throw new PublishError(`${what} の応答の形式が不正`);
  return v;
}

function field(o: Record<string, unknown>, k: string, what: string): string {
  const v = o[k];
  if (typeof v !== "string" || v.length === 0) throw new PublishError(`${what} の応答に ${k} が無い`);
  return v;
}

export const PROVIDER_RE = /^projects\/[0-9]{6,20}\/locations\/global\/workloadIdentityPools\/[a-z0-9-]{4,32}\/providers\/[a-z0-9-]{4,32}$/;
export const SERVICE_ACCOUNT_RE = /^[a-z][a-z0-9-]{4,28}[a-z0-9]@[a-z][a-z0-9-]{4,28}[a-z0-9]\.iam\.gserviceaccount\.com$/;
export const PROJECT_RE = /^[a-z][a-z0-9-]{4,28}[a-z0-9]$/;
export const REGISTRY_RE = /^[a-z0-9-]+-docker\.pkg\.dev$/;

/** GitHub Actions の OIDC トークン（audience は WIF の provider）。 */
export async function githubOidcToken(env: NodeJS.ProcessEnv, provider: string, http: Http): Promise<string> {
  const url = env["ACTIONS_ID_TOKEN_REQUEST_URL"];
  const token = env["ACTIONS_ID_TOKEN_REQUEST_TOKEN"];
  if (!url || !token) throw new PublishError("OIDC のトークンを要求できない（ジョブに permissions: id-token: write が必要）");
  const u = new URL(url);
  if (u.protocol !== "https:") throw new PublishError("OIDC の要求先が https ではない");
  u.searchParams.set("audience", `https://iam.googleapis.com/${provider}`);
  const r = await http(u.toString(), { method: "GET", headers: { Authorization: `Bearer ${token}`, Accept: "application/json" } });
  return field(await json(r, "OIDC のトークンの要求"), "value", "OIDC");
}

/** STS: GitHub の OIDC トークン → 連携トークン。 */
export async function stsExchange(provider: string, oidc: string, http: Http): Promise<string> {
  const r = await http("https://sts.googleapis.com/v1/token", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      grantType: "urn:ietf:params:oauth:grant-type:token-exchange",
      audience: `//iam.googleapis.com/${provider}`,
      scope: SCOPE,
      requestedTokenType: "urn:ietf:params:oauth:token-type:access_token",
      subjectToken: oidc,
      subjectTokenType: "urn:ietf:params:oauth:token-type:jwt",
    }),
  });
  return field(await json(r, "STS のトークン交換"), "access_token", "STS");
}

/** 連携トークン → SA の短期アクセストークン（30 分）。 */
export async function impersonate(serviceAccount: string, federated: string, http: Http): Promise<string> {
  const r = await http(
    `https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/${encodeURIComponent(serviceAccount)}:generateAccessToken`,
    {
      method: "POST",
      headers: { Authorization: `Bearer ${federated}`, "Content-Type": "application/json" },
      body: JSON.stringify({ scope: [SCOPE], lifetime: "1800s" }),
    },
  );
  return field(await json(r, "SA のトークンの発行"), "accessToken", "IAM Credentials");
}

export async function registryLogin(
  opts: { provider: string; serviceAccount: string; registry: string },
  env: NodeJS.ProcessEnv,
  http: Http = defaultHttp,
  runner: Runner = defaultRunner,
): Promise<void> {
  if (!PROVIDER_RE.test(opts.provider))
    throw new PublishError("--provider は projects/<番号>/locations/global/workloadIdentityPools/<pool>/providers/<id>");
  if (!SERVICE_ACCOUNT_RE.test(opts.serviceAccount))
    throw new PublishError("--service-account は <名前>@<プロジェクト>.iam.gserviceaccount.com");
  if (!REGISTRY_RE.test(opts.registry)) throw new PublishError("--registry は <地域>-docker.pkg.dev");
  const oidc = await githubOidcToken(env, opts.provider, http);
  const federated = await stsExchange(opts.provider, oidc, http);
  const access = await impersonate(opts.serviceAccount, federated, http);
  // トークンは標準入力だけで渡す（引数・環境変数・ログに載せない）
  const r = runner("docker", ["login", "--username", "oauth2accesstoken", "--password-stdin", `https://${opts.registry}`], access);
  if (r.status !== 0) throw new PublishError(`docker login が失敗（status=${r.status}）`);
}

/** push した参照の RepoDigests から、期待するリポジトリの digest 付き参照を選ぶ。 */
export function digestRef(repoDigests: unknown, repo: string): string {
  if (!Array.isArray(repoDigests)) throw new PublishError("RepoDigests の形式が不正", 1);
  const ref = repoDigests.find((d): d is string => typeof d === "string" && d.startsWith(`${repo}@sha256:`));
  if (ref === undefined || !/^[^@]+@sha256:[0-9a-f]{64}$/.test(ref)) throw new PublishError(`${repo} の digest を得られない`, 1);
  return ref;
}

export function pushImages(
  opts: { project: string; gitSha: string; registry: string },
  runner: Runner = defaultRunner,
): Record<ImageName, string> {
  if (!PROJECT_RE.test(opts.project)) throw new PublishError("--project は GCP のプロジェクト ID");
  if (!/^[0-9a-f]{40}$/.test(opts.gitSha)) throw new PublishError("--git-sha は 40 桁の小文字 16 進");
  if (!REGISTRY_RE.test(opts.registry)) throw new PublishError("--registry は <地域>-docker.pkg.dev");
  const out: Partial<Record<ImageName, string>> = {};
  for (const name of IMAGE_NAMES) {
    const local = `jra-bot/${name}:${opts.gitSha}`;
    const repo = `${opts.registry}/${opts.project}/${REPOSITORY}/${name}`;
    // タグは Git SHA（不変）。デプロイは digest で指定する（CONTRACTS §1）
    const remote = `${repo}:${opts.gitSha}`;
    const tagged = runner("docker", ["tag", local, remote]);
    if (tagged.status !== 0) throw new PublishError(`docker tag ${name} が失敗（status=${tagged.status}）: ${tail(tagged)}`);
    const pushed = runner("docker", ["push", remote]);
    if (pushed.status !== 0) throw new PublishError(`docker push ${name} が失敗（status=${pushed.status}）: ${tail(pushed)}`);
    // push が報告した digest（この push のもの）。inspect の RepoDigests と一致しなければ失敗（古い・別の digest を選ばない: レビュー 低）
    const reported = [...pushed.stdout.matchAll(/digest: (sha256:[0-9a-f]{64})/g)].map((m) => m[1]);
    if (reported.length !== 1) throw new PublishError(`docker push ${name} の出力に digest が 1 つだけ無い`, 1);
    const inspect = runner("docker", ["image", "inspect", remote, "--format", "{{json .RepoDigests}}"]);
    if (inspect.status !== 0) throw new PublishError(`docker image inspect ${name} が失敗（status=${inspect.status}）: ${tail(inspect)}`);
    let digests: unknown;
    try {
      digests = JSON.parse(inspect.stdout);
    } catch {
      throw new PublishError(`${name} の RepoDigests が JSON ではない`, 1);
    }
    const ref = digestRef(digests, repo);
    if (ref !== `${repo}@${String(reported[0])}`) throw new PublishError(`${name} の push の digest と RepoDigests が一致しない`, 1);
    out[name] = ref;
  }
  const done = IMAGE_NAMES.every((n) => out[n] !== undefined);
  if (!done) throw new PublishError("digest の揃わないイメージがある", 1);
  return out as Record<ImageName, string>;
}

/** このコミットの ci の実行で成功していなければならないジョブ（ci.yml の名前。gate だけでは足りない: 分類で build・images が動かないコミットがある）。 */
export const REQUIRED_JOBS = ["ci gate", "build (Temurin 25)"] as const;
const IMAGES_JOB = /^images \/ /;
const DB_JOB = /^db integration \(MySQL [0-9.]+\)$/;
/** 失敗しても ci の結果に影響しない参考のジョブ（continue-on-error）。 */
const INFORMATIONAL_JOB = /^connectorj26( \/ |$)/;
const FAILED = ["failure", "cancelled", "timed_out", "action_required", "startup_failure"];

/**
 * 対象コミットで CI が**コードを実際に検査した**こと（レビュー 中）。そのコミットの **ci.yml の最新の完了した実行**だけを見る
 * （他の実行・他のワークフロー・前回の release の失敗を混ぜない）: 実行が成功、ジョブ ci gate・build・images（1 つ以上）・
 * db integration（1 つ以上）がすべて成功、参考のジョブ（connectorj26）以外に失敗・取消が無い。
 * 通常の push の CI は分類で images・db を省くので、リリースの前に ci.yml を手動で実行する（全部の検査: USER_ACTIONS B4b）。
 */
export async function checkCi(opts: { repo: string; sha: string; token: string }, http: Http = defaultHttp): Promise<void> {
  if (!/^[A-Za-z0-9-]+\/[A-Za-z0-9._-]+$/.test(opts.repo)) throw new PublishError("--repo は owner/name");
  if (!/^[0-9a-f]{40}$/.test(opts.sha)) throw new PublishError("--sha は 40 桁の小文字 16 進");
  const headers = { Authorization: `Bearer ${opts.token}`, Accept: "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28" };
  const runsBody = await json(
    await http(
      `https://api.github.com/repos/${opts.repo}/actions/workflows/ci.yml/runs?head_sha=${opts.sha}&status=completed&per_page=100`,
      { method: "GET", headers },
    ),
    "ci の実行の取得",
  );
  const runs = (Array.isArray(runsBody["workflow_runs"]) ? runsBody["workflow_runs"].filter(isRecord) : []).filter(
    (r) => r["head_sha"] === opts.sha && r["status"] === "completed" && typeof r["id"] === "number" && typeof r["created_at"] === "string",
  );
  const latest = runs.sort(
    (a, b) => String(b["created_at"]).localeCompare(String(a["created_at"])) || Number(b["id"]) - Number(a["id"]),
  )[0];
  if (latest === undefined)
    throw new PublishError("このコミットの ci の完了した実行が無い（Actions → ci → Run workflow で main を検査してから）", 1);
  if (latest["conclusion"] !== "success")
    throw new PublishError(`このコミットの最新の ci の実行が成功していない（${String(latest["conclusion"])}）`, 1);
  const jobsBody = await json(
    await http(`https://api.github.com/repos/${opts.repo}/actions/runs/${String(latest["id"])}/jobs?filter=latest&per_page=100`, {
      method: "GET",
      headers,
    }),
    "ci のジョブの取得",
  );
  const jobs = Array.isArray(jobsBody["jobs"]) ? jobsBody["jobs"].filter(isRecord) : [];
  const name = (jb: Record<string, unknown>): string => (typeof jb["name"] === "string" ? jb["name"] : "");
  const ok = (jb: Record<string, unknown>): boolean => jb["status"] === "completed" && jb["conclusion"] === "success";
  const bad = jobs.filter(
    (jb) => !INFORMATIONAL_JOB.test(name(jb)) && (jb["status"] !== "completed" || FAILED.includes(String(jb["conclusion"]))),
  );
  if (bad.length > 0) throw new PublishError(`ci の実行に失敗・取消・未完了のジョブがある: ${bad.map(name).slice(0, 5).join(", ")}`, 1);
  const missing: string[] = REQUIRED_JOBS.filter((n) => !jobs.some((jb) => name(jb) === n && ok(jb)));
  for (const [re, label] of [
    [IMAGES_JOB, "images"],
    [DB_JOB, "db integration"],
  ] as const) {
    const matched = jobs.filter((jb) => re.test(name(jb)));
    if (matched.length === 0 || !matched.every(ok)) missing.push(label);
  }
  if (missing.length > 0) {
    throw new PublishError(
      `このコミットの ci の実行で成功していない・実行されていないジョブ: ${missing.join(", ")}（Actions → ci → Run workflow で全部の検査を実行してから）`,
      1,
    );
  }
}

export async function main(argv: readonly string[], env: NodeJS.ProcessEnv = process.env): Promise<number> {
  const [cmd, ...rest] = argv;
  try {
    const { values: a } = parseArgs({
      args: [...rest],
      strict: true,
      options: {
        repo: { type: "string" },
        sha: { type: "string" },
        provider: { type: "string" },
        "service-account": { type: "string" },
        registry: { type: "string" },
        project: { type: "string" },
        "git-sha": { type: "string" },
        out: { type: "string" },
      },
    });
    const need = (v: string | undefined, name: string): string => {
      if (v === undefined || v === "") throw new PublishError(`${name} が必要`);
      return v;
    };
    if (cmd === "check-ci") {
      await checkCi({ repo: need(a.repo, "--repo"), sha: need(a.sha, "--sha"), token: need(env["GITHUB_TOKEN"], "環境変数 GITHUB_TOKEN") });
      console.error("ci gate: success");
      return 0;
    }
    if (cmd === "registry-login") {
      await registryLogin(
        {
          provider: need(a.provider, "--provider"),
          serviceAccount: need(a["service-account"], "--service-account"),
          registry: a.registry ?? DEFAULT_REGISTRY,
        },
        env,
      );
      console.error("registry login: ok");
      return 0;
    }
    if (cmd === "push") {
      const refs = pushImages({
        project: need(a.project, "--project"),
        gitSha: need(a["git-sha"], "--git-sha"),
        registry: a.registry ?? DEFAULT_REGISTRY,
      });
      writeFileSync(need(a.out, "--out"), `${JSON.stringify(refs, null, 2)}\n`, "utf8");
      console.error(`pushed: ${Object.keys(refs).length} images`);
      return 0;
    }
    throw new PublishError(`未知のサブコマンド: ${String(cmd)}（check-ci | registry-login | push）`);
  } catch (e) {
    if (e instanceof PublishError) {
      console.error(e.message);
      return e.code;
    }
    if (e instanceof TypeError && "code" in e) {
      console.error(`引数の誤り: ${e.message}`);
      return 2;
    }
    console.error(`通信・実行の失敗: ${e instanceof Error ? e.name : "unknown"}`);
    return 2;
  }
}
