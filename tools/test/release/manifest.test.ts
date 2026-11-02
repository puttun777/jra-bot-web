/** release-manifest（旧 tools/release/tests/test_manifest.py の全ケースを移植。jsonschema_lite の試験は Ajv strict の fail closed の試験に置換）。 */
import assert from "node:assert/strict";
import { copyFileSync, cpSync, existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { describe, it } from "node:test";
import { Ajv2020 } from "ajv/dist/2020.js";
import { isRecord } from "../../src/lib/json.js";
import { main as cli } from "../../src/release/cli.js";
import {
  buildPreview,
  buildProduction,
  dumps,
  type LocalImage,
  PREVIEW_SCHEMA,
  PRODUCTION_SCHEMA,
  rejectIgnoredKeywords,
  SCHEMA_DIR,
  validatePreview,
  validateProduction,
} from "../../src/release/manifest.js";
import {
  APPS,
  combinedFeatureSchemaHash,
  commonRequiredSchemaVersion,
  compareVersions,
  contractVersion,
  FEATURE_SCHEMA_DIR,
  FEATURE_SCHEMA_FILES,
  featureSchemas,
  flywayVersions,
  IMAGE_NAMES,
  type ImageName,
  MIGRATION_DIR,
  requiredSchemaVersions,
} from "../../src/release/repo-facts.js";
import { ROOT, withTempDir } from "../lib/helpers.js";

const SHA = "0123456789abcdef0123456789abcdef01234567";
const REG = "asia-northeast1-docker.pkg.dev/synthetic-project/jra-bot";

/** 合成テスト用の「実在しない」digest（仮値判定に掛からないよう文字を散らす）。 */
function digest(n: number): string {
  return `sha256:${`${n.toString(16).padStart(2, "0")}a1b2c3d4e5f60718293a4b5c6d7e8f90`.slice(0, 64).padEnd(64, "7")}`;
}

type Doc = Record<string, unknown>;
const sub = (d: Doc, k: string): Record<string, unknown> => {
  const v = d[k];
  assert.ok(isRecord(v), k);
  return v;
};

/** テスト専用の合成 manifest。digest は実在しない。実際のレジストリ・イメージとは無関係。 */
function syntheticProduction(over: Doc = {}): Doc {
  const images: Record<string, string> = {};
  IMAGE_NAMES.forEach((n, i) => {
    images[n] = `${REG}/${n}@${digest(i + 1)}`;
  });
  const doc = buildProduction(ROOT, SHA, "2026.11.0", "2026-11-01T00:00:00Z", images, "2026.11.0", "3.13.17", "synthetic test manifest");
  return { ...doc, ...over };
}

function localImages(): Partial<Record<ImageName, LocalImage>> {
  const out: Partial<Record<ImageName, LocalImage>> = {};
  IMAGE_NAMES.forEach((n, i) => {
    out[n] = {
      local_tag: `jra-bot/${n}:${SHA}`,
      image_id: `sha256:${`${(i + 1).toString(16)}c3d4e5f6a7b8091a2b3c4d5e6f708192`.slice(0, 32).repeat(2)}`,
    };
  });
  return out;
}

/** 検査に必要なファイルだけを一時ディレクトリへコピーした擬似リポジトリ。 */
function makeRoot(tmp: string, verified: unknown[] = []): string {
  mkdirSync(join(tmp, "docs", "release"), { recursive: true });
  copyFileSync(join(ROOT, "docs", "CONTRACTS.md"), join(tmp, "docs", "CONTRACTS.md"));
  cpSync(join(ROOT, SCHEMA_DIR), join(tmp, SCHEMA_DIR), { recursive: true });
  writeFileSync(join(tmp, "docs", "release", "rollback-verified.json"), JSON.stringify({ schema_version: 1, verified }));
  for (const app of APPS) {
    mkdirSync(join(tmp, app));
    copyFileSync(join(ROOT, app, "build.gradle.kts"), join(tmp, app, "build.gradle.kts"));
  }
  mkdirSync(join(tmp, FEATURE_SCHEMA_DIR), { recursive: true });
  for (const f of Object.values(FEATURE_SCHEMA_FILES)) copyFileSync(join(ROOT, FEATURE_SCHEMA_DIR, f), join(tmp, FEATURE_SCHEMA_DIR, f));
  mkdirSync(join(tmp, MIGRATION_DIR), { recursive: true });
  for (const f of readdirSync(join(ROOT, MIGRATION_DIR)).filter((x) => x.endsWith(".sql")))
    copyFileSync(join(ROOT, MIGRATION_DIR, f), join(tmp, MIGRATION_DIR, f));
  return tmp;
}

const errors = (doc: unknown, root: string = ROOT, sha?: string): string[] => validateProduction(doc, root, sha);
const anyErr = (errs: string[], ...needles: string[]): boolean => errs.some((e) => needles.every((n) => e.includes(n)));

describe("production manifest", () => {
  it("valid manifest passes", () => assert.deepEqual(errors(syntheticProduction(), ROOT, SHA), []));
  it("missing required field", () => {
    for (const field of [
      "git_sha",
      "images",
      "flyway",
      "rollback",
      "feature_schema_hash",
      "feature_schemas",
      "runtime",
      "notes",
      "contract_version",
    ]) {
      const doc = syntheticProduction();
      delete doc[field];
      assert.ok(anyErr(errors(doc), "必須フィールド", field), field);
    }
  });
  it("unknown field rejected", () => assert.ok(anyErr(errors(syntheticProduction({ extra: "x" })), "未知のフィールド")));
  it("bad git sha", () => {
    for (const bad of ["abc123", SHA.toUpperCase(), `${SHA}0`, "g".repeat(40), ""])
      assert.ok(anyErr(errors(syntheticProduction({ git_sha: bad })), "git_sha"), bad);
  });
  it("git sha must match expected", () => assert.ok(anyErr(errors(syntheticProduction(), ROOT, "f".repeat(40)), "期待値")));
  it("bad image references", () => {
    const good = String(sub(syntheticProduction(), "images")["bot"]);
    const badRefs: Record<string, string> = {
      "tag only": `${REG}/bot:${SHA}`,
      "latest tag": `${REG}/bot:latest`,
      "short digest": `${REG}/bot@sha256:abc`,
      "uppercase digest": good.toUpperCase(),
      "no registry (local image id)": `sha256:${"ab12".repeat(16)}`,
      "docker hub": `docker.io/library/bot@${digest(9)}`,
      "other repository name": `${REG}/dashboard@${digest(9)}`,
      sha512: `${REG}/bot@sha512:${"a1".repeat(64)}`,
      empty: "",
    };
    for (const [label, ref] of Object.entries(badRefs)) {
      const doc = syntheticProduction();
      sub(doc, "images")["bot"] = ref;
      assert.ok(anyErr(errors(doc), "images.bot"), label);
    }
  });
  it("placeholder digests rejected", () => {
    for (const fake of ["0".repeat(64), "a".repeat(64), "ab".repeat(32), "deadbeef".repeat(8)]) {
      const doc = syntheticProduction();
      sub(doc, "images")["bot"] = `${REG}/bot@sha256:${fake}`;
      assert.ok(anyErr(errors(doc), "仮値"), fake);
    }
  });
  it("missing image", () => {
    const doc = syntheticProduction();
    delete sub(doc, "images")["ml-train"];
    assert.ok(anyErr(errors(doc), "images.ml-train"));
  });
  it("unsupported schema version", () => {
    for (const bad of [1, 3, 0, "2", true, null])
      assert.ok(anyErr(errors(syntheticProduction({ schema_version: bad })), "schema_version"), JSON.stringify(bad));
  });
  it("unsupported contract version", () => {
    for (const bad of ["1.0.0", "0.5.0", "0.3.9", "0.4", "v0.4.4"])
      assert.ok(anyErr(errors(syntheticProduction({ contract_version: bad })), "contract_version"), bad);
  });
  it("contract version must equal CONTRACTS.md", () => {
    const cur = contractVersion(ROOT);
    const other = `0.4.${Number(cur.split(".")[2]) + 1}`;
    assert.ok(anyErr(errors(syntheticProduction({ contract_version: other })), "CONTRACTS.md"));
  });
  it("required schema version must match baked value", () => {
    const baked = Number(commonRequiredSchemaVersion(ROOT));
    for (const bad of [String(baked + 1), String(baked - 1)])
      assert.ok(anyErr(errors(syntheticProduction({ required_schema_version: bad })), "required_schema_version"), bad);
  });
  it("flyway must match migrations", () => {
    let doc = syntheticProduction();
    sub(doc, "flyway")["latest_version"] = "99";
    assert.ok(anyErr(errors(doc), "flyway.latest_version"));
    doc = syntheticProduction();
    sub(doc, "flyway")["baseline"] = "2";
    assert.ok(anyErr(errors(doc), "flyway.baseline"));
  });
  it("apps disagreeing on required schema version", () => {
    withTempDir((t) => {
      const root = makeRoot(t);
      const f = join(root, "ingest", "build.gradle.kts");
      const current = commonRequiredSchemaVersion(ROOT);
      writeFileSync(f, readFileSync(f, "utf8").replace(`"${current}"`, `"${Number(current) + 1}"`)); // ingest だけ版が違う
      assert.ok(anyErr(validateProduction(syntheticProduction(), root), "食い違"));
    });
  });
  it("rollback range", () => {
    const doc = syntheticProduction();
    sub(doc, "rollback")["min_app_release"] = "2026.12.0"; // release より新しい
    assert.ok(anyErr(errors(doc), "より新しい"));
    sub(doc, "rollback")["min_app_release"] = "latest";
    assert.ok(anyErr(errors(doc), "min_app_release"));
    sub(doc, "rollback")["min_app_release"] = "2026.10.3"; // 古いが検証の記録が無い
    assert.ok(anyErr(errors(doc), "検証済みの記録"));
  });
  it("rollback to an older release needs a verification record for the same schema", () => {
    const latest = flywayVersions(ROOT).at(-1);
    const doc = syntheticProduction();
    sub(doc, "rollback")["min_app_release"] = "2026.10.3";
    withTempDir((t) => {
      const ok = makeRoot(t, [{ app_release: "2026.10.3", target_flyway_version: latest, evidence: "https://example.invalid/run/1" }]);
      assert.deepEqual(validateProduction(doc, ok), []);
    });
    withTempDir((t) => {
      const stale = makeRoot(t, [{ app_release: "2026.10.3", target_flyway_version: "1", evidence: "x" }]); // 別のスキーマ版に対する検証は流用できない
      assert.ok(validateProduction(doc, stale).length > 0);
    });
    withTempDir((t) => {
      const noEvidence = makeRoot(t, [{ app_release: "2026.10.3", target_flyway_version: latest, evidence: "" }]); // 根拠の無い記録は無効
      assert.ok(validateProduction(doc, noEvidence).length > 0);
    });
  });
  it("bad misc fields", () => {
    const cases: [string, unknown][] = [
      ["release", "latest"],
      ["built_at", "2026-11-01"],
      ["agent_protocol", 2],
      ["model_schema_versions", []],
      ["model_schema_versions", [1, 1]],
      ["feature_schema_hash", "sha256:abc"],
      ["feature_schema_hash", null],
      ["runtime", { jdk: "21", python: "3.12.3" }],
      ["feature_schemas", {}],
      ["feature_schemas", { calibration: `sha256:${"1".repeat(64)}` }],
      ["runtime", { jdk: "25", python: null }],
      ["notes", "x".repeat(2001)],
    ];
    for (const [field, bad] of cases)
      assert.ok(errors(syntheticProduction({ [field]: bad })).length > 0, `${field}=${JSON.stringify(bad)}`);
  });
  it("not an object", () => {
    for (const bad of [[], "x", 1, null]) assert.ok(validateProduction(bad, ROOT).length > 0);
  });
  it("generation is reproducible", () => assert.equal(dumps(syntheticProduction()), dumps(syntheticProduction())));
  it("production generation refuses unresolved inputs", () => {
    const images = sub(syntheticProduction(), "images");
    const { "ml-train": _drop, ...withoutMl } = images;
    for (const bad of [withoutMl, { ...images, bot: `${REG}/bot:${SHA}` }, {}]) {
      assert.throws(() => buildProduction(ROOT, SHA, "2026.11.0", "2026-11-01T00:00:00Z", bad, "2026.11.0", "3.13.17"), /生成できない/);
    }
    assert.throws(() => buildProduction(ROOT, SHA, "2026.11.0", "2026-11-01T00:00:00Z", images, "2026.10.0", "3.13.17"), /生成できない/); // 検証されていないロールバック範囲
  });
});

describe("preview manifest", () => {
  const preview = (imgs: Partial<Record<ImageName, LocalImage>> = localImages()): Doc =>
    buildPreview(ROOT, SHA, "preview-0123456789ab", "2026-11-01T00:00:00Z", imgs, null);

  it("preview is valid and not deployable", () => {
    const doc = preview();
    assert.deepEqual(validatePreview(doc, ROOT, SHA), []);
    assert.equal(doc["deployable"], false);
    const blockers = doc["blockers"];
    assert.ok(Array.isArray(blockers));
    assert.ok(blockers.includes("IMAGES_NOT_PUSHED") && blockers.includes("ROLLBACK_RANGE_UNVERIFIED"));
    assert.ok(!blockers.includes("IMAGE_NOT_BUILT") && !blockers.includes("FEATURE_SCHEMA_HASH_UNDEFINED"));
    for (const i of Object.values(sub(doc, "images"))) assert.ok(isRecord(i) && i["built"] === true);
    assert.equal(sub(sub(doc, "images"), "bot")["registry_digest"], null);
    assert.equal(sub(doc, "rollback")["verified"], false);
  });
  it("preview is rejected as production", () => assert.ok(anyErr(validateProduction(preview(), ROOT), "プレビュー成果物")));
  it("preview with production shape tricks is rejected", () => {
    assert.ok(
      validateProduction(syntheticProduction({ kind: "release-manifest-preview", deployable: false, blockers: ["X"] }), ROOT).length > 0,
    );
    assert.ok(validateProduction(syntheticProduction({ deployable: true }), ROOT).length > 0);
  });
  it("production is rejected as preview", () => assert.ok(validatePreview(syntheticProduction(), ROOT).length > 0));
  it("preview cannot carry a registry digest", () => {
    let doc = preview();
    sub(sub(doc, "images"), "bot")["registry_digest"] = `sha256:${"ab12".repeat(16)}`;
    assert.ok(validatePreview(doc, ROOT).length > 0);
    doc = preview();
    doc["notes"] = `${REG}/bot@${digest(3)}`;
    assert.ok(anyErr(validatePreview(doc, ROOT), "digest 形式"));
  });
  it("preview cannot hide blockers", () => {
    let doc = preview();
    doc["blockers"] = ["IMAGES_NOT_PUSHED"];
    assert.ok(anyErr(validatePreview(doc, ROOT), "blockers"));
    doc["blockers"] = [];
    assert.ok(validatePreview(doc, ROOT).length > 0);
    doc = preview();
    doc["deployable"] = true;
    assert.ok(validatePreview(doc, ROOT).length > 0);
  });
  it("preview with an unbuilt image has the blocker", () => {
    const imgs = localImages();
    delete imgs["ml-train"];
    const doc = preview(imgs);
    const blockers = doc["blockers"];
    assert.ok(Array.isArray(blockers) && blockers.includes("IMAGE_NOT_BUILT"));
    assert.equal(sub(sub(doc, "images"), "ml-train")["built"], false);
    assert.deepEqual(validatePreview(doc, ROOT, SHA), []);
    doc["blockers"] = blockers.filter((b) => b !== "IMAGE_NOT_BUILT");
    assert.ok(anyErr(validatePreview(doc, ROOT), "blockers"));
  });
  it("preview cannot claim verified rollback", () => {
    const doc = preview();
    sub(doc, "rollback")["verified"] = true;
    assert.ok(validatePreview(doc, ROOT).length > 0);
  });
  it("feature schema hash is the hash of the named schema set", () => {
    const doc = preview();
    const schemas = featureSchemas(ROOT);
    assert.deepEqual(Object.keys(schemas).sort(), ["alpha", "calibration", "market"]);
    assert.deepEqual(doc["feature_schemas"], schemas);
    assert.equal(doc["feature_schema_hash"], combinedFeatureSchemaHash(schemas));
    const base = combinedFeatureSchemaHash(schemas);
    assert.notEqual(base, combinedFeatureSchemaHash({ ...schemas, calibration: schemas.alpha, alpha: schemas.calibration }));
    assert.notEqual(base, combinedFeatureSchemaHash({ ...schemas, market: `sha256:${"0".repeat(64)}` }));
    const { market: _m, ...withoutMarket } = schemas;
    for (const bad of [withoutMarket, { ...schemas, extra: schemas.market }]) assert.throws(() => combinedFeatureSchemaHash(bad), /キー/);
    assert.equal(new Set(Object.values(schemas)).size, 3);
    let bad = preview();
    bad["feature_schema_hash"] = `sha256:${"ab12".repeat(16)}`;
    assert.ok(anyErr(validatePreview(bad, ROOT), "feature_schema_hash"));
    bad = preview();
    sub(bad, "feature_schemas")["alpha"] = `sha256:${"ab12".repeat(16)}`;
    const errs = validatePreview(bad, ROOT);
    assert.ok(anyErr(errs, "feature_schemas") && anyErr(errs, "feature_schema_hash"), errs.join("\n"));
  });
  it("production feature schema fields are validated", () => {
    let doc = syntheticProduction();
    sub(doc, "feature_schemas")["calibration"] = `sha256:${"ab12".repeat(16)}`;
    assert.ok(anyErr(validateProduction(doc, ROOT), "feature_schemas"));
    doc = syntheticProduction();
    doc["feature_schema_hash"] = `sha256:${"ab12".repeat(16)}`;
    assert.ok(anyErr(validateProduction(doc, ROOT), "feature_schema_hash"));
  });
  it("preview local image id is not a registry reference", () => {
    const doc = preview();
    sub(sub(doc, "images"), "bot")["local_image_id"] = `${REG}/bot@${digest(5)}`;
    assert.ok(validatePreview(doc, ROOT).length > 0);
  });
  it("preview values match repository", () => {
    const doc = preview();
    assert.equal(sub(doc, "flyway")["latest_version"], flywayVersions(ROOT).at(-1));
    assert.equal(doc["required_schema_version"], commonRequiredSchemaVersion(ROOT));
    assert.equal(doc["contract_version"], contractVersion(ROOT));
  });
  it("preview is reproducible regardless of input order", () => {
    const reordered = Object.fromEntries(Object.entries(localImages()).reverse());
    assert.equal(dumps(preview()), dumps(preview()));
    assert.equal(dumps(preview()), dumps(preview(reordered)));
  });
  it("bad git sha in preview", () => {
    const doc = preview();
    doc["git_sha"] = "unknown";
    assert.ok(anyErr(validatePreview(doc, ROOT), "git_sha"));
  });
});

describe("cli", () => {
  const quiet = <T>(fn: () => T): T => {
    const log = console.log;
    const err = console.error;
    console.log = () => {};
    console.error = () => {};
    try {
      return fn();
    } finally {
      console.log = log;
      console.error = err;
    }
  };
  it("preview end to end and reproducible", () => {
    withTempDir((t) => {
      writeFileSync(join(t, "local.json"), JSON.stringify({ git_sha: SHA, images: localImages() }));
      const outs: Buffer[] = [];
      for (const i of [1, 2]) {
        const out = join(t, `p${i}.json`);
        assert.equal(
          quiet(() =>
            cli(["preview", "--git-sha", SHA, "--built-at", "2026-11-01T00:00:00Z", "--local-images", join(t, "local.json"), "--out", out]),
          ),
          0,
        );
        outs.push(readFileSync(out));
      }
      assert.ok(outs[0]?.equals(outs[1] ?? Buffer.alloc(0)));
      assert.equal(
        quiet(() => cli(["validate", "--kind", "preview", "--expect-git-sha", SHA, join(t, "p1.json")])),
        0,
      );
      assert.equal(
        quiet(() => cli(["validate", "--kind", "production", join(t, "p1.json")])),
        1,
      ); // 本番利用は拒否
    });
  });
  it("rejects bad inputs with the documented exit codes", () => {
    withTempDir((t) => {
      writeFileSync(join(t, "local.json"), JSON.stringify({ git_sha: SHA, images: localImages() }));
      const o = join(t, "o.json");
      const pv = (sha: string, builtAt: string): number =>
        quiet(() => cli(["preview", "--git-sha", sha, "--built-at", builtAt, "--local-images", join(t, "local.json"), "--out", o]));
      assert.equal(pv("xyz", "2026-11-01T00:00:00Z"), 2);
      assert.equal(pv("f".repeat(40), "2026-11-01T00:00:00Z"), 2);
      assert.equal(pv(SHA, "yesterday"), 1);
      assert.equal(existsSync(o), false);
      assert.equal(
        quiet(() => cli(["validate", "--kind", "production", join(t, "missing.json")])),
        2,
      );
      assert.equal(
        quiet(() => cli(["nonsense"])),
        2,
      );
      assert.equal(
        quiet(() => cli(["validate", "--kind", "other", join(t, "local.json")])),
        2,
      );
      assert.equal(
        quiet(() => cli(["preview", "--unknown-flag"])),
        2,
      );
    });
  });
  it("production cli refuses without pushed digests", () => {
    withTempDir((t) => {
      writeFileSync(join(t, "reg.json"), JSON.stringify({ bot: `${REG}/bot:${SHA}` }));
      const out = join(t, "release-manifest.json");
      const rc = quiet(() =>
        cli([
          "production",
          "--git-sha",
          SHA,
          "--release",
          "2026.11.0",
          "--built-at",
          "2026-11-01T00:00:00Z",
          "--registry-images",
          join(t, "reg.json"),
          "--min-app-release",
          "2026.11.0",
          "--python-version",
          "3.13.17",
          "--out",
          out,
        ]),
      );
      assert.equal(rc, 1);
      assert.equal(existsSync(out), false);
    });
  });
});

describe("schema files", () => {
  const load = (n: string): Record<string, unknown> => {
    const v: unknown = JSON.parse(readFileSync(join(ROOT, SCHEMA_DIR, n), "utf8"));
    assert.ok(isRecord(v));
    return v;
  };
  it("schemas compile under Ajv strict mode", () => {
    for (const n of [PRODUCTION_SCHEMA, PREVIEW_SCHEMA]) new Ajv2020({ strict: true }).compile(load(n));
  });
  it("an unknown keyword fails closed (compile error, not silently ignored)", () => {
    assert.throws(() => new Ajv2020({ strict: true }).compile({ type: "object", properties: { a: { frobnicate: true } } }));
    withTempDir((t) => {
      const root = makeRoot(t);
      const p = join(root, SCHEMA_DIR, PRODUCTION_SCHEMA);
      const s = load(PRODUCTION_SCHEMA);
      writeFileSync(p, JSON.stringify({ ...s, frobnicate: 1 }));
      assert.throws(() => validateProduction(syntheticProduction(), root));
    });
  });
  it("keywords Ajv would silently ignore (format, content*) are refused", () => {
    withTempDir((t) => {
      const root = makeRoot(t);
      const p = join(root, SCHEMA_DIR, PRODUCTION_SCHEMA);
      const s = load(PRODUCTION_SCHEMA);
      const props = s["properties"];
      assert.ok(isRecord(props) && isRecord(props["built_at"]));
      props["built_at"]["format"] = "date-time";
      writeFileSync(p, JSON.stringify(s));
      assert.throws(() => validateProduction(syntheticProduction(), root), /検証されないキーワード/);
    });
    // properties の中のプロパティ名 format / properties は名前なので許可、その中のスキーマの format キーワードは拒否（文脈で判定）
    assert.doesNotThrow(() =>
      rejectIgnoredKeywords({ type: "object", properties: { format: { type: "string" }, properties: { type: "object" } } }, "t"),
    );
    assert.throws(() => rejectIgnoredKeywords({ properties: { properties: { type: "string", format: "date-time" } } }, "t"), /format/);
    assert.doesNotThrow(() => rejectIgnoredKeywords({ const: { format: 1 }, enum: [{ format: 2 }] }, "t"));
  });
  it("contract example shape: CONTRACTS §10 example keys == production schema required", () => {
    const text = readFileSync(join(ROOT, "docs", "CONTRACTS.md"), "utf8");
    const block = (text.split("## 10.")[1] ?? "").split("```json")[1]?.split("```")[0] ?? "";
    const example: unknown = JSON.parse(
      block.replaceAll("…", "x").replaceAll("<40桁>", "x").replaceAll("<prj>", "p").replaceAll("<64桁>", "0"),
    );
    assert.ok(isRecord(example));
    const required = load(PRODUCTION_SCHEMA)["required"];
    assert.ok(Array.isArray(required));
    assert.deepEqual(Object.keys(example).sort(), required.map(String).sort());
  });
  it("validator basics (bool is not an integer, const is type-strict, additionalProperties false)", () => {
    const v = new Ajv2020({ strict: true }).compile({
      type: "object",
      required: ["a"],
      additionalProperties: false,
      properties: { a: { type: "integer", minimum: 1 }, n: { const: null } },
    });
    assert.equal(v({ a: 1, n: null }), true);
    for (const bad of [{ a: true }, { a: 0 }, { a: 1, n: 0 }, {}, { a: 1, z: 1 }]) assert.equal(v(bad), false, JSON.stringify(bad));
  });
});

describe("repo facts", () => {
  it("facts", () => {
    assert.match(contractVersion(ROOT), /^\d+\.\d+\.\d+$/);
    assert.deepEqual(Object.keys(requiredSchemaVersions(ROOT)).sort(), ["bot", "dashboard", "ingest"]);
    assert.deepEqual(flywayVersions(ROOT), ["1", "2", "3", "4", "5"]);
    assert.ok(compareVersions("10", "9") > 0);
    assert.ok(compareVersions("1.10", "1.9") > 0);
    assert.equal(compareVersions("2026.11.0", "2026.11.0"), 0);
  });
});
