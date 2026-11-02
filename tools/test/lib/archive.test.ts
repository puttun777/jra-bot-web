/** 検査用の ZIP・tar 読取り（実物の jar / tar を作って読む）。 */
import assert from "node:assert/strict";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { Readable } from "node:stream";
import { describe, it } from "node:test";
import { readTar, ZipReader } from "../../src/lib/archive.js";
import { run } from "../../src/lib/proc.js";
import { withTempDir } from "./helpers.js";

describe("archive", () => {
  it("reads names and stored/deflated entries of a jar made by the JDK or zip", () => {
    withTempDir((t) => {
      mkdirSync(join(t, "in", "BOOT-INF", "classes"), { recursive: true });
      writeFileSync(join(t, "in", "BOOT-INF", "classes", "jrabot-app.properties"), "requiredSchemaVersion=5\nreleaseId=abc\n".repeat(20));
      writeFileSync(join(t, "in", "small.txt"), "x");
      const jar = join(t, "a.jar");
      const r = run("jar", ["cf", jar, "-C", join(t, "in"), "."]);
      const z = r.status === 0 ? jar : join(t, "a.zip");
      if (r.status !== 0) assert.equal(run("sh", ["-c", `cd "${join(t, "in")}" && zip -qr "${z}" .`]).status, 0, "jar も zip も無い");
      const reader = new ZipReader(readFileSync(z));
      assert.ok(reader.names.includes("BOOT-INF/classes/jrabot-app.properties"), JSON.stringify(reader.names));
      assert.match(reader.read("BOOT-INF/classes/jrabot-app.properties").toString("utf8"), /^requiredSchemaVersion=5\nreleaseId=abc\n/);
      assert.equal(reader.read("small.txt").toString("utf8"), "x");
      assert.throws(() => reader.read("missing"), /無い/);
      assert.throws(() => new ZipReader(Buffer.from("not a zip at all, definitely not")), /終端レコード/);
    });
  });
  it("lists tar members with modes, directories and long (PAX/GNU) names, in chunks", async () => {
    await (async () => {
      const t = join("/tmp", `jratar-${process.pid}`);
      const long = `app/${"d".repeat(120)}/file.jar`;
      mkdirSync(join(t, "src", "app", "lib"), { recursive: true });
      mkdirSync(join(t, "src", long.split("/").slice(0, -1).join("/")), { recursive: true });
      writeFileSync(join(t, "src", "app", "app.jar"), "j".repeat(1500));
      writeFileSync(join(t, "src", long), "x");
      for (const fmt of ["pax", "gnu"]) {
        const tarPath = join(t, `a-${fmt}.tar`);
        assert.equal(run("tar", [`--format=${fmt}`, "-cf", tarPath, "-C", join(t, "src"), "app"]).status, 0);
        const buf = readFileSync(tarPath);
        const chunks: Buffer[] = [];
        for (let i = 0; i < buf.length; i += 700) chunks.push(buf.subarray(i, i + 700)); // 見出しをまたぐ分割
        const members = [];
        for await (const m of readTar(Readable.from(chunks))) members.push(m);
        const names = members.map((m) => m.name);
        assert.ok(names.includes("app/app.jar"), `${fmt}: ${JSON.stringify(names)}`);
        assert.ok(names.includes(long), `${fmt}: long name`);
        const lib = members.find((m) => m.name === "app/lib");
        assert.ok(lib?.isDir === true, `${fmt}: ディレクトリ名の末尾の / を落とす`);
      }
      // 途中で切れた tar は例外（旧 tarfile と同じ）
      const whole = readFileSync(join(t, "a-pax.tar"));
      await assert.rejects(async () => {
        for await (const _ of readTar(Readable.from([whole.subarray(0, 1300)]))) {
          // 読み捨て
        }
      }, /途中で切れている/);
      run("rm", ["-rf", t]);
    })();
  });
});
