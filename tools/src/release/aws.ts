/**
 * AWS への公開（D-285）: GitHub Actions の OIDC → STS（AssumeRoleWithWebIdentity）→ ECR の短期パスワードで docker login する。
 * 長期のアクセスキーは使わない。第三者の認証 action も SDK も使わない（依存を増やさない）: STS は署名不要の呼び出し、ECR は SigV4 で署名する。
 * 資格情報・パスワードは標準出力・標準エラー・ファイルに書かない（docker login の標準入力にだけ渡す）。
 */
import { createHash, createHmac } from "node:crypto";
import { type Http, PublishError, type Runner } from "./publish.js";

export const AWS_REGION = "ap-northeast-1";
export const ROLE_ARN_RE = /^arn:aws:iam::[0-9]{12}:role\/[A-Za-z0-9+=,.@_-]{1,64}$/;
export const ACCOUNT_RE = /^[0-9]{12}$/;
export const ECR_REGISTRY_RE = /^[0-9]{12}\.dkr\.ecr\.ap-northeast-1\.amazonaws\.com$/;

export interface AwsCredentials {
  readonly accessKeyId: string;
  readonly secretAccessKey: string;
  readonly sessionToken: string;
}

/** GitHub Actions の OIDC トークン（audience は STS）。 */
export async function githubOidcTokenForAws(env: NodeJS.ProcessEnv, http: Http): Promise<string> {
  const url = env["ACTIONS_ID_TOKEN_REQUEST_URL"];
  const token = env["ACTIONS_ID_TOKEN_REQUEST_TOKEN"];
  if (!url || !token) throw new PublishError("OIDC のトークンを要求できない（ジョブに permissions: id-token: write が必要）");
  const u = new URL(url);
  if (u.protocol !== "https:") throw new PublishError("OIDC の要求先が https ではない");
  u.searchParams.set("audience", "sts.amazonaws.com");
  const r = await http(u.toString(), { method: "GET", headers: { Authorization: `Bearer ${token}`, Accept: "application/json" } });
  const text = await r.text();
  if (r.status !== 200) throw new PublishError(`OIDC のトークンの要求が失敗（HTTP ${r.status}）`);
  let v: unknown;
  try {
    v = JSON.parse(text);
  } catch {
    throw new PublishError("OIDC の応答が JSON ではない");
  }
  const value = typeof v === "object" && v !== null && "value" in v ? (v as { value: unknown }).value : undefined;
  if (typeof value !== "string" || value === "") throw new PublishError("OIDC の応答に value が無い");
  return value;
}

/** XML の要素の値（STS の応答は固定の形。属性・入れ子の同名要素は使わない）。 */
function xmlValue(xml: string, tag: string): string {
  const m = new RegExp(`<${tag}>([^<]{1,4096})</${tag}>`).exec(xml);
  if (m?.[1] === undefined) throw new PublishError(`STS の応答に ${tag} が無い`);
  return m[1];
}

/** STS: OIDC トークン → ロールの短期の資格情報（30 分）。署名は不要（トークンそのものが証明）。 */
export async function assumeRoleWithWebIdentity(roleArn: string, oidc: string, sessionName: string, http: Http): Promise<AwsCredentials> {
  if (!ROLE_ARN_RE.test(roleArn)) throw new PublishError("ロールの ARN の形式が不正（arn:aws:iam::<12 桁>:role/<名前>）");
  const body = new URLSearchParams({
    Action: "AssumeRoleWithWebIdentity",
    Version: "2011-06-15",
    RoleArn: roleArn,
    RoleSessionName: sessionName.replace(/[^A-Za-z0-9+=,.@_-]/g, "-").slice(0, 64),
    WebIdentityToken: oidc,
    DurationSeconds: "1800",
  }).toString();
  const r = await http(`https://sts.${AWS_REGION}.amazonaws.com/`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded; charset=utf-8" },
    body,
  });
  const xml = await r.text();
  // 本文は出さない（資格情報を含み得る）
  if (r.status !== 200) throw new PublishError(`STS の AssumeRoleWithWebIdentity が失敗（HTTP ${r.status}）`);
  return {
    accessKeyId: xmlValue(xml, "AccessKeyId"),
    secretAccessKey: xmlValue(xml, "SecretAccessKey"),
    sessionToken: xmlValue(xml, "SessionToken"),
  };
}

const sha256Hex = (s: string): string => createHash("sha256").update(s, "utf8").digest("hex");
const hmac = (key: Buffer | string, s: string): Buffer => createHmac("sha256", key).update(s, "utf8").digest();

/**
 * AWS Signature Version 4（ヘッダー方式）。対象は path が `/` でクエリの無い要求だけ（ECR・STS の JSON/Query API の範囲）。
 * 返す値は送るべきヘッダー（`authorization`・`x-amz-date`・あれば `x-amz-security-token`）。
 */
export function sigv4(input: {
  readonly method: "GET" | "POST";
  readonly host: string;
  readonly path: string;
  readonly headers: Readonly<Record<string, string>>;
  readonly body: string;
  readonly region: string;
  readonly service: string;
  readonly credentials: { readonly accessKeyId: string; readonly secretAccessKey: string; readonly sessionToken?: string };
  readonly now: Date;
}): Record<string, string> {
  const amzDate = input.now
    .toISOString()
    .replace(/[-:]/g, "")
    .replace(/\.\d{3}/, "");
  const date = amzDate.slice(0, 8);
  const headers: Record<string, string> = { host: input.host, "x-amz-date": amzDate };
  for (const [k, v] of Object.entries(input.headers)) headers[k.toLowerCase()] = v.trim().replace(/\s+/g, " ");
  if (input.credentials.sessionToken !== undefined) headers["x-amz-security-token"] = input.credentials.sessionToken;
  const names = Object.keys(headers).sort();
  const canonicalHeaders = names.map((n) => `${n}:${headers[n]}\n`).join("");
  const signedHeaders = names.join(";");
  const canonicalRequest = [input.method, input.path, "", canonicalHeaders, signedHeaders, sha256Hex(input.body)].join("\n");
  const scope = `${date}/${input.region}/${input.service}/aws4_request`;
  const toSign = ["AWS4-HMAC-SHA256", amzDate, scope, sha256Hex(canonicalRequest)].join("\n");
  const kDate = hmac(`AWS4${input.credentials.secretAccessKey}`, date);
  const kSigning = hmac(hmac(hmac(kDate, input.region), input.service), "aws4_request");
  const signature = createHmac("sha256", kSigning).update(toSign, "utf8").digest("hex");
  const out: Record<string, string> = {
    authorization: `AWS4-HMAC-SHA256 Credential=${input.credentials.accessKeyId}/${scope}, SignedHeaders=${signedHeaders}, Signature=${signature}`,
    "x-amz-date": amzDate,
  };
  if (input.credentials.sessionToken !== undefined) out["x-amz-security-token"] = input.credentials.sessionToken;
  return out;
}

/** ECR: GetAuthorizationToken（12 時間有効の docker のパスワード。`AWS:<password>` の Base64）。 */
export async function ecrPassword(creds: AwsCredentials, http: Http, now: Date = new Date()): Promise<string> {
  const host = `api.ecr.${AWS_REGION}.amazonaws.com`;
  const body = "{}";
  const base = {
    "content-type": "application/x-amz-json-1.1",
    "x-amz-target": "AmazonEC2ContainerRegistry_V20150921.GetAuthorizationToken",
  };
  const signed = sigv4({
    method: "POST",
    host,
    path: "/",
    headers: base,
    body,
    region: AWS_REGION,
    service: "ecr",
    credentials: creds,
    now,
  });
  const r = await http(`https://${host}/`, { method: "POST", headers: { ...base, ...signed }, body });
  const text = await r.text();
  if (r.status !== 200) throw new PublishError(`ECR の GetAuthorizationToken が失敗（HTTP ${r.status}）`);
  let v: unknown;
  try {
    v = JSON.parse(text);
  } catch {
    throw new PublishError("ECR の応答が JSON ではない");
  }
  const data =
    typeof v === "object" && v !== null && "authorizationData" in v ? (v as { authorizationData: unknown }).authorizationData : undefined;
  const first = Array.isArray(data) ? (data[0] as unknown) : undefined;
  const token =
    typeof first === "object" && first !== null && "authorizationToken" in first
      ? (first as { authorizationToken: unknown }).authorizationToken
      : undefined;
  if (typeof token !== "string") throw new PublishError("ECR の応答に authorizationToken が無い");
  const decoded = Buffer.from(token, "base64").toString("utf8");
  if (!decoded.startsWith("AWS:") || decoded.length < 10) throw new PublishError("ECR の authorizationToken の形式が不正");
  return decoded.slice(4);
}

/** GitHub の OIDC → ロール → ECR のパスワード → docker login（パスワードは標準入力だけ）。 */
export async function ecrLogin(
  opts: { roleArn: string; registry: string; sessionName: string },
  env: NodeJS.ProcessEnv,
  http: Http,
  runner: Runner,
): Promise<void> {
  if (!ECR_REGISTRY_RE.test(opts.registry)) throw new PublishError("--registry は <12 桁>.dkr.ecr.ap-northeast-1.amazonaws.com");
  if (opts.registry.split(".")[0] !== opts.roleArn.split(":")[4]) throw new PublishError("ロールとレジストリのアカウントが一致しない");
  const oidc = await githubOidcTokenForAws(env, http);
  const creds = await assumeRoleWithWebIdentity(opts.roleArn, oidc, opts.sessionName, http);
  const password = await ecrPassword(creds, http);
  const r = runner("docker", ["login", "--username", "AWS", "--password-stdin", `https://${opts.registry}`], password);
  if (r.status !== 0) throw new PublishError(`docker login が失敗（status=${r.status}）`);
}
