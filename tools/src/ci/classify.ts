/**
 * 変更ファイルから、CI で実行するジョブを決める（GitHub Actions 無料枠の節約）。旧 tools/ci/classify.py の移植（D-254）。
 *
 * 原則: **fail closed**。分類できない・差分を取得できない・予期しないイベントは「全部実行」にする。軽くするのは、
 * docs だけなど、変更が各ジョブの入力に確実に影響しないと分かっているときだけ。
 *
 * ファイルは先頭から順に規則に当てはめてタグを付ける（どれにも当たらないファイルは `unknown` = 全部実行）。タグ → ジョブ:
 *   docs         ドキュメントのみ（hygiene と最終ゲートだけ）
 *   kotlin_app   :bot :ingest :dashboard :cloud-gcp → build、DB 統合（アプリのスイート・MySQL 8.4）
 *   kotlin_core  :domain → build、DB 統合（persistence + アプリ・8.4）
 *   kotlin_db    :persistence :db-migration → build、DB 統合（全スイート・8.4 と 9.7）
 *   build_cfg    Gradle・版数・build-logic・ktlint 設定 → build、DB 統合（全・8.4 + 9.7）、images
 *   ml_tools     tools/ml（Python 参照実装）と合成成果物 → ml-tools
 *   ml_train     ml/（Python の学習 Job）→ ml-train。:bot の結合テストが使うので kotlin_app も
 *   golden       tools/golden（Python）→ golden
 *   kotlin_golden tools/golden（.md 以外）・golden-runner → build（比較は build ジョブの中）
 *   release      リリース補助・スキーマ・契約・マイグレーション・アプリの build 定義 → release-tools（TypeScript 単体）
 *   docker       Dockerfile / .dockerignore → images
 *   connectorj   DB ドライバ関連の依存設定 → connector/j 26.x 互換性チェック（非ゲート）
 *   agent        agent/ → agent（Linux）
 *   agent_windows agent/ の Windows 固有部分 → agent-windows
 *   ci           CI 自体（ワークフロー・tools の共通部・分類ロジック・npm の依存）→ すべて（connector/j 26.x は除く）
 * 実行されなかったジョブは「テストされた」ことにならない（最終ゲートが skipped を成功として数えない）。
 */
import { appendFileSync } from "node:fs";
import { git } from "../lib/proc.js";

export type Tag =
  | "docs"
  | "kotlin_app"
  | "kotlin_core"
  | "kotlin_db"
  | "build_cfg"
  | "ml_tools"
  | "ml_train"
  | "golden"
  | "kotlin_golden"
  | "release"
  | "docker"
  | "connectorj"
  | "agent"
  | "agent_windows"
  | "ci"
  | "unknown";

export const KNOWN_TAGS: ReadonlySet<Tag> = new Set<Tag>([
  "docs",
  "kotlin_app",
  "kotlin_core",
  "kotlin_db",
  "build_cfg",
  "ml_tools",
  "ml_train",
  "golden",
  "kotlin_golden",
  "release",
  "docker",
  "connectorj",
  "agent",
  "agent_windows",
  "ci",
]);

/** (正規表現, タグ) を上から順に評価し、最初に当たった規則を使う（1 ファイル 1 規則）。 */
export const RULES: ReadonlyArray<readonly [RegExp, readonly Tag[]]> = [
  // --- CI 自体・分類ロジック・TypeScript の共通設定と依存（安全側: すべて実行）
  [/^\.github\/workflows\/compat-connectorj26\.yml$/, ["ci", "connectorj"]],
  [/^\.github\/(workflows|scripts|actions)\//, ["ci"]],
  [/^tools\/(ci|release)\//, ["ci"]], // 旧 Python 版の置き場所（削除・誤って復活した場合も安全側）
  [/^tools\/(src|test)\/(ci|lib)\//, ["ci"]],
  [/^tools\/(tsconfig\.json|run-tests\.mjs)$/, ["ci"]],
  [/^(package\.json|package-lock\.json|biome\.json)$/, ["ci"]],
  [/^tools\/__init__\.py$/, ["ci"]],
  [/^\.github\/dependabot\.yml$/, ["release"]], // 設定ファイルの整合検査（TS 単体）だけで足りる
  [/^\.github\/[^/]*\.md$|^\.github\/(ISSUE_TEMPLATE|CODEOWNERS|pull_request_template)/, ["docs"]],
  // --- Docker・リリース
  [/^(Dockerfile|\.dockerignore)$/, ["docker", "release"]],
  [/^tools\/(src|test)\/release\//, ["docker", "release"]],
  [/^docs\/(schemas|release)\//, ["release"]],
  [/^docs\/CONTRACTS\.md$/, ["docs", "release"]],
  [/^db-migration\/src\/main\/resources\/db\/migration\//, ["kotlin_db", "release", "docker"]],
  [/^(bot|ingest|dashboard)\/build\.gradle\.kts$/, ["kotlin_app", "release", "docker"]],
  [/^(bot|ingest|dashboard)\/src\/main\/resources\//, ["kotlin_app", "docker"]],
  [/^db-migration\/src\/main\//, ["kotlin_db", "docker"]],
  // --- Connector/J・DB ドライバ関連の依存設定（gradle/libs.versions.toml は build_cfg でもある）
  [/^gradle\/libs\.versions\.toml$/, ["build_cfg", "connectorj"]],
  [/^(persistence|db-migration)\/build\.gradle\.kts$/, ["kotlin_db", "connectorj", "docker"]],
  // --- Windows エージェント。.md は docs。Windows 固有のファイルだけ Windows ランナーでも検証する
  [/^agent\/.*\.md$/, ["docs"]],
  [
    /^agent\/src\/jra_agent\/(winapi|secrets_store|lock)\.py$|^agent\/src\/jra_agent\/jvlink\/(procutil|supervisor|com_adapter|worker_main)\.py$/,
    ["agent", "agent_windows", "kotlin_app"],
  ],
  [/^agent\/(tests\/test_winapi_windows\.py|scripts\/)/, ["agent", "agent_windows"]],
  [/^agent\/(src\/|interop\/|tests\/vectors\/|pyproject\.toml$|requirements)/, ["agent", "kotlin_app"]],
  [/^agent\//, ["agent"]],
  // --- ML ツール。合成成果物は Kotlin のテストも使う
  [/^tools\/ml\/.*\.md$/, ["docs"]],
  [/^tools\/ml\/(canonical|dataset_contract)\.py$/, ["ml_tools", "ml_train", "docker", "kotlin_app"]],
  [/^tools\/ml\//, ["ml_tools"]],
  [/^domain\/src\/test\/resources\/ml\//, ["ml_tools", "ml_train", "kotlin_golden", "release"]], // feature_schema は release-manifest の入力
  // --- ML 学習 Job。ml/ だけの変更でも学習テストが必ず走る。イメージの中身が変わったらイメージも
  [/^ml\/.*\.md$/, ["docs"]],
  [/^ml\/(src\/|requirements[^/]*\.txt$|pyproject\.toml$)/, ["ml_train", "kotlin_app", "docker"]],
  [/^ml\//, ["ml_train", "kotlin_app"]],
  // --- ドキュメント（モジュール内の README なども docs。上の規則に当たらなかったもの）
  [/^(docs\/.*|.*\.md|LICENSE|\.gitignore)$/, ["docs"]],
  // --- Kotlin
  [/^(persistence|db-migration)\//, ["kotlin_db"]],
  [/^domain\//, ["kotlin_core"]],
  [/^(bot|ingest|dashboard|cloud-gcp)\//, ["kotlin_app"]],
  // --- ビルド・lint の設定
  [
    /^(gradle\/|build-logic\/|gradlew(\.bat)?$|settings\.gradle\.kts$|build\.gradle\.kts$|gradle\.properties$|\.editorconfig$)/,
    ["build_cfg", "docker"],
  ],
  // --- ゴールデン。フィクスチャやシナリオが変わったら Kotlin 側の比較も必要
  [/^golden-runner\//, ["kotlin_golden"]],
  [/^tools\/golden\/.*\.md$/, ["docs"]],
  [/^tools\/golden\//, ["golden", "kotlin_golden"]],
];

export const FULL_MATRIX: readonly string[] = ["8.4", "9.7"];
export const ALL_SUITES: readonly string[] = ["migration", "persistence", "apps"];

export type Mode = "docs" | "code" | "full";

export interface Plan {
  mode: Mode;
  reason: string;
  runBuild: boolean;
  runGolden: boolean;
  runMlTools: boolean;
  runMlTrain: boolean;
  runRelease: boolean;
  runImages: boolean;
  runConnectorj26: boolean;
  runAgent: boolean;
  runAgentWindows: boolean;
  dbMatrix: string[];
  dbSuites: string[];
  files: number;
}

function emptyPlan(mode: Mode, reason: string, files: number): Plan {
  return {
    mode,
    reason,
    runBuild: false,
    runGolden: false,
    runMlTools: false,
    runMlTrain: false,
    runRelease: false,
    runImages: false,
    runConnectorj26: false,
    runAgent: false,
    runAgentWindows: false,
    dbMatrix: [],
    dbSuites: [],
    files,
  };
}

/** GITHUB_OUTPUT の値（すべて文字列）。キーは workflow の outputs と一致させる（ci.yml・gate.ts と契約）。 */
export function outputs(p: Plan): Record<string, string> {
  const b = (v: boolean): string => (v ? "true" : "false");
  // GITHUB_OUTPUT を壊す改行などを入れない（ファイル名は PR の作者が決める）
  // biome-ignore lint/suspicious/noControlCharactersInRegex: 制御文字を除去するための正規表現
  const reason = p.reason.replace(/[\x00-\x1f\x7f]/g, " ").slice(0, 300);
  return {
    mode: p.mode,
    reason,
    files: String(p.files),
    run_build: b(p.runBuild),
    run_golden: b(p.runGolden),
    run_ml_tools: b(p.runMlTools),
    run_ml_train: b(p.runMlTrain),
    run_release: b(p.runRelease),
    run_images: b(p.runImages),
    run_connectorj26: b(p.runConnectorj26),
    run_agent: b(p.runAgent),
    run_agent_windows: b(p.runAgentWindows),
    db_matrix: JSON.stringify(p.dbMatrix),
    db_suites: p.dbSuites.join(","),
  };
}

/** すべて実行（Connector/J 26.x は connectorj が true のときだけ。本番採用が未決で、非ゲートの互換性確認なので）。 */
export function fullPlan(reason: string, files = 0, connectorj = false): Plan {
  return {
    ...emptyPlan("full", reason, files),
    runBuild: true,
    runGolden: true,
    runMlTools: true,
    runMlTrain: true,
    runRelease: true,
    runImages: true,
    runConnectorj26: connectorj,
    runAgent: true,
    runAgentWindows: true,
    dbMatrix: [...FULL_MATRIX],
    dbSuites: [...ALL_SUITES],
  };
}

export function tagsFor(path: string): readonly Tag[] {
  for (const [pattern, tags] of RULES) {
    if (pattern.test(path)) return tags;
  }
  return ["unknown"];
}

export function classify(files: readonly string[]): Plan {
  if (files.length === 0) return fullPlan("変更ファイルの一覧が空（取得失敗の可能性）");
  const tags = new Set<Tag>();
  for (const f of files) for (const t of tagsFor(f)) tags.add(t);
  if (tags.has("unknown")) {
    const unknown = files.filter((f) => tagsFor(f)[0] === "unknown").slice(0, 3);
    return fullPlan(`分類できないファイルがある: ${JSON.stringify(unknown)}`, files.length);
  }
  if (tags.has("ci")) return fullPlan("CI 設定が変更された", files.length, tags.has("connectorj"));
  if (tags.size === 1 && tags.has("docs")) return emptyPlan("docs", "ドキュメントのみ", files.length);

  const has = (...ts: Tag[]): boolean => ts.some((t) => tags.has(t));
  const p = emptyPlan("code", "", files.length);
  p.runBuild = has("kotlin_app", "kotlin_core", "kotlin_db", "build_cfg", "kotlin_golden");
  if (has("kotlin_db", "build_cfg")) {
    p.dbMatrix = [...FULL_MATRIX];
    p.dbSuites = [...ALL_SUITES];
  } else if (has("kotlin_core")) {
    p.dbMatrix = ["8.4"];
    p.dbSuites = ["persistence", "apps"];
  } else if (has("kotlin_app")) {
    p.dbMatrix = ["8.4"];
    p.dbSuites = ["apps"];
  }
  p.runGolden = has("golden");
  p.runMlTools = has("ml_tools");
  p.runMlTrain = has("ml_train");
  p.runRelease = has("release");
  p.runImages = has("docker", "build_cfg");
  p.runConnectorj26 = has("connectorj");
  p.runAgent = has("agent");
  p.runAgentWindows = has("agent_windows");
  p.reason = `変更タグ: ${[...tags].sort().join(",")}`;
  return p;
}

// ---------------------------------------------------------------------------------------------------- 差分の取得
/** イベントごとの差分。pull_request は merge コミット（HEAD）とその第 1 親（= テストした時点のベース）の差分。 */
export function changedFiles(event: string, before?: string, baseSha?: string, cwd?: string): string[] {
  let raw: string;
  if (event === "pull_request") {
    const parents = git(["rev-list", "--parents", "-n", "1", "HEAD"], cwd).trim().split(/\s+/);
    const a = parents.length === 3 ? parents[1] : baseSha;
    if (a === undefined || a === "") throw new Error("PR のベースを特定できない");
    raw = git(["diff", "--name-only", "--no-renames", "-z", a, "HEAD"], cwd);
  } else if (event === "push") {
    if (before === undefined || before === "" || /^0+$/.test(before)) throw new Error("push の before が無い（新規ブランチなど）");
    raw = git(["diff", "--name-only", "--no-renames", "-z", before, "HEAD"], cwd);
  } else {
    throw new Error(`差分を取らないイベント: ${event}`);
  }
  return raw.split("\0").filter((f) => f !== "");
}

/** どんな失敗も「全部実行」に倒す。workflow_dispatch・schedule は常にフル検証（Connector/J 26.x を含む）。 */
export function planForEvent(
  event: string,
  opts: { before?: string; baseSha?: string; files?: readonly string[]; cwd?: string } = {},
): Plan {
  if (event === "workflow_dispatch" || event === "schedule") return fullPlan(`${event}: フル検証`, 0, true);
  if (event !== "pull_request" && event !== "push") return fullPlan(`想定外のイベント ${event}`);
  try {
    return classify(opts.files ?? changedFiles(event, opts.before, opts.baseSha, opts.cwd));
  } catch (e) {
    const name = e instanceof Error ? e.name : typeof e;
    const msg = e instanceof Error ? e.message : String(e);
    return fullPlan(`変更判定に失敗（fail closed）: ${name}: ${msg.slice(0, 120)}`);
  }
}

export function main(env: NodeJS.ProcessEnv = process.env): number {
  const opts: { before?: string; baseSha?: string } = {};
  if (env["BEFORE_SHA"] !== undefined) opts.before = env["BEFORE_SHA"];
  if (env["BASE_SHA"] !== undefined) opts.baseSha = env["BASE_SHA"];
  const out = outputs(planForEvent(env["EVENT_NAME"] ?? "", opts));
  const lines = Object.entries(out).map(([k, v]) => `${k}=${v}`);
  for (const l of lines) console.log(l);
  const path = env["GITHUB_OUTPUT"];
  if (path !== undefined && path !== "") appendFileSync(path, `${lines.join("\n")}\n`, "utf8");
  return 0;
}
