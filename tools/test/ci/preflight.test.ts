/** preflight の検査選択・CI との一致、整合検査（旧 tools/ci/tests/test_preflight.py の移植 + 追加）。 */
import assert from "node:assert/strict";
import { copyFileSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { describe, it } from "node:test";
import { parse as parseYaml } from "yaml";
import { classify, fullPlan, type Plan } from "../../src/ci/classify.js";
import {
  appSchemaVersions,
  check,
  createdTables,
  domaTables,
  flywayLatest,
  grantedTables,
  requiredBotRunEnv,
} from "../../src/ci/consistency.js";
import { CI_JDK, CI_NODE, ciRunBlock, LEGACY_COMMIT, stepsFor } from "../../src/ci/preflight.js";
import { isRecord } from "../../src/lib/json.js";
import { BOT_RUN_ENV } from "../../src/release/image-smoke.js";
import { ROOT, withTempDir } from "../lib/helpers.js";

const CI_YML = readFileSync(join(ROOT, ".github/workflows/ci.yml"), "utf8");
const ids = (p: Plan, files: string[] = []): string[] => stepsFor(p, files, ROOT, "/tmp/preflight-test").map((s) => s.id);

describe("selection", () => {
  it("docs only runs only the cheap checks", () => {
    assert.deepEqual(ids(classify(["docs/PROGRESS.md"])), ["diff-check", "hygiene", "tools-ts", "consistency"]);
  });
  it("app change selects build, golden compare and db apps on 8.4", () => {
    const got = ids(classify(["dashboard/src/main/kotlin/jp/jrabot/dashboard/X.kt"]));
    for (const x of ["kotlin-build", "kotlin-golden", "db-it-8.4"]) assert.ok(got.includes(x), x);
    assert.ok(!got.includes("db-it-9.7"));
  });
  it("db change selects both mysql versions", () => {
    const got = ids(classify(["persistence/src/main/kotlin/jp/jrabot/persistence/X.kt"]));
    assert.ok(got.includes("db-it-8.4") && got.includes("db-it-9.7"));
  });
  it("ci change or unknown file selects everything", () => {
    for (const files of [["tools/src/ci/classify.ts"], ["something/unknown.bin"]]) {
      const got = ids(classify(files));
      for (const x of ["kotlin-build", "db-it-8.4", "db-it-9.7", "agent", "images", "golden-py"])
        assert.ok(got.includes(x), `${x} ${files[0]}`);
    }
  });
  it("app runtime change adds local image smoke even if ci skips it", () => {
    const files = ["dashboard/src/main/kotlin/jp/jrabot/dashboard/X.kt"];
    const p = classify(files);
    assert.equal(p.runImages, false);
    assert.ok(ids(p, files).includes("images"));
  });
  it("python-only change still runs kotlin interop where needed", () => {
    const got = ids(classify(["agent/src/jra_agent/queue.py"]));
    assert.ok(got.includes("agent"));
    assert.ok(got.includes("kotlin-build")); // agent/src は kotlin_app も付く（AgentInteropIntegrationTest）
    const all = ids(classify(["tools/src/ci/classify.ts"]));
    assert.ok(!all.includes("ml-train") && !all.includes("ml-tools"), "Python の ML の検査は無い（D-257）");
  });
});

describe("mirrors ci", () => {
  it("gradle tasks and modules run by preflight exist in ci.yml", () => {
    for (const step of stepsFor(fullPlan("test"), [], ROOT, "/tmp/x")) {
      for (const cmd of step.commands) {
        if (cmd[0] === "./gradlew") {
          for (const task of cmd.slice(1).filter((c) => !c.startsWith("-")))
            assert.ok(CI_YML.includes(task), `${step.id}: ci.yml に Gradle タスク ${task} が無い`);
        }
        if (cmd[0] === "python3" && cmd[1] === "-m")
          assert.ok(CI_YML.includes(cmd[2] ?? "\0"), `${step.id}: ci.yml に ${String(cmd[2])} が無い`);
        if (cmd[0] === "node" && cmd[1] === "build/tools/src/main.js")
          assert.ok(CI_YML.includes(`main.js ${cmd[2] ?? "\0"}`), `${step.id}: ci.yml に ${String(cmd[2])} が無い`);
      }
    }
    assert.ok(CI_YML.includes("check-repo-hygiene.sh"));
    assert.ok(CI_YML.includes("npm run --silent lint") && CI_YML.includes("node tools/run-tests.mjs"));
  });
  it("verify steps exist in ci.yml and are extractable", () => {
    for (const step of stepsFor(fullPlan("test"), [], ROOT, "/tmp/x")) {
      for (const [job, name] of step.verify ?? []) {
        const block = ciRunBlock(ROOT, job, name);
        assert.ok(block.includes("test-results"), name); // テスト結果ディレクトリを検査するブロック
      }
    }
  });
  it("every Verify / Comparator step of ci.yml is covered by preflight", () => {
    // ci.yml 側の検査のうち preflight が意図的に実行しないもの（理由つき）・同じコマンドを直接実行しているもの
    const notRunLocally = new Set(["agent-windows/Verify that the Windows tests actually ran"]); // Windows ランナーでのみ実行できる
    const source = readFileSync(join(ROOT, "tools/src/ci/preflight.ts"), "utf8").replace(/\s+/g, " "); // 整形で改行されても一致させる
    const doc: unknown = parseYaml(CI_YML);
    const jobs = isRecord(doc) && isRecord(doc["jobs"]) ? doc["jobs"] : {};
    const missing: string[] = [];
    for (const [jobName, j] of Object.entries(jobs)) {
      const steps = isRecord(j) && Array.isArray(j["steps"]) ? j["steps"] : [];
      for (const s of steps) {
        if (!isRecord(s) || typeof s["name"] !== "string") continue;
        const n = s["name"];
        if (!/^(Verify|Comparator smoke test)/.test(n)) continue;
        if (n === "Verify committed fixtures (schema, provenance, hashes, synthetic-only)" && source.includes("tools.golden.verify"))
          continue;
        if (notRunLocally.has(`${jobName}/${n}`)) continue;
        if (!source.includes(`"${jobName}", "${n}"`)) missing.push(`${jobName}/${n}`);
      }
    }
    assert.deepEqual(missing, [], `preflight が参照しない CI の検査: ${missing.join(", ")}`);
  });
  it("legacy commit matches golden constants", () => {
    assert.ok(readFileSync(join(ROOT, "tools/golden/constants.py"), "utf8").includes(LEGACY_COMMIT));
  });
  it("ci jdk and node match the workflow", () => {
    assert.match(CI_YML, new RegExp(`java-version: "${CI_JDK}"`));
    assert.match(readFileSync(join(ROOT, ".github/actions/setup-tools/action.yml"), "utf8"), new RegExp(`node-version: "${CI_NODE}\\.`));
  });
});

describe("consistency", () => {
  it("repository is consistent without infra", () => assert.deepEqual(check(ROOT, undefined).errors, []));
  it("bot-run required env is read and covered by the smoke", () => {
    const req = requiredBotRunEnv(ROOT);
    assert.ok(req.size >= 10);
    for (const n of req) assert.ok(n in BOT_RUN_ENV, n);
  });
  it("every DAO table has a grant and a create", () => {
    const tables = domaTables(ROOT);
    assert.ok(tables.size > 15);
    const created = createdTables(ROOT);
    const granted = grantedTables(ROOT);
    for (const t of tables) {
      assert.ok(created.has(t), t);
      assert.ok(granted.has(t), t);
    }
  });
  it("schema versions follow flyway", () => {
    const latest = flywayLatest(ROOT);
    assert.deepEqual(new Set(Object.values(appSchemaVersions(ROOT))), new Set([latest]));
  });
  it("detects a missing smoke variable, a stale schema version, a missing grant and an infra mismatch", () => {
    withTempDir((root) => {
      const mig = join(root, "db-migration/src/main/resources/db/migration");
      for (const rel of [
        "db-migration/src/main/resources/db/migration",
        "bot",
        "ingest",
        "dashboard",
        "persistence/src/main/kotlin/x",
        "bot/src/main/kotlin/jp/jrabot/bot",
        "infra/docs",
      ]) {
        mkdirSync(join(root, rel), { recursive: true });
      }
      copyFileSync(join(ROOT, "db-migration/src/main/resources/db/migration/V1__baseline.sql"), join(mig, "V1__baseline.sql"));
      writeFileSync(join(mig, "V2__x.sql"), "CREATE TABLE `extra_table` (id INT);");
      writeFileSync(join(mig, "R__grants.sql"), "GRANT SELECT ON `${flyway:defaultSchema}`.`race` TO 'app'@'%';");
      writeFileSync(join(root, "bot/build.gradle.kts"), 'extra["requiredSchemaVersion"] = "1"\n');
      for (const app of ["ingest", "dashboard"])
        writeFileSync(join(root, app, "build.gradle.kts"), 'extra["requiredSchemaVersion"] = "2"\n');
      writeFileSync(join(root, "bot/src/main/kotlin/jp/jrabot/bot/BotRunSettings.kt"), 'raw("BOT_RUN_A")\nlong("BOT_RUN_B", 1, 2)\n');
      writeFileSync(join(root, "persistence/src/main/kotlin/x/Dao.kt"), '@Table(name = "race")\n@Table(name = "extra_table")\n');
      writeFileSync(join(root, "infra/docs/CONTRACTS.md"), "現在 **1** の版。BOT_RUN_A だけ\n");
      const { errors } = check(root, join(root, "infra"), new Set(["BOT_RUN_A"]));
      const has = (s: string): boolean => errors.some((e) => e.includes(s));
      assert.ok(has("BOT_RUN_B") && errors.filter((e) => e.includes("BOT_RUN_A")).length === 0, errors.join("\n"));
      assert.ok(has("bot の requiredSchemaVersion=1"), errors.join("\n"));
      assert.ok(has("DAO の表 extra_table が R__grants.sql"), errors.join("\n"));
      assert.ok(has("infra の docs/CONTRACTS.md に BOT_RUN_B が無い") && has("スキーマ版 1 が Flyway の最新版 V2"), errors.join("\n"));
    });
  });
  it("reports infra as not run when the sibling repository is absent", () => {
    assert.ok(check(ROOT, "/nonexistent-infra").notes.some((n) => n.includes("未実施")));
  });
});
